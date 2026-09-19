package com.aura.assistant.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import android.util.Log
import com.aura.assistant.audio.AuraVadDetector
import com.aura.assistant.config.Secrets
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.sqrt

data class LiveVoiceTurn(
    val userText: String,
    val assistantText: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class AuraLiveState {
    IDLE,
    CONNECTING,
    CONNECTED,
    LISTENING,
    THINKING,
    SPEAKING,
    ERROR
}

/**
 * Ultra-low latency Native Gemini Live Client.
 *
 * Implements bidirectional real-time audio streaming (16kHz PCM input -> 24kHz PCM output)
 * over the official Google GenerativeService WebSocket (BidiGenerateContent) using strict camelCase schema.
 */
class GeminiLiveClient(private val context: Context) {

    companion object {
        private const val TAG = "GeminiLiveClient"
        private const val BASE_WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        // Primary low-latency Live Audio API model (verified from Google v1beta GenerativeService)
        private const val PRIMARY_LIVE_MODEL = "models/gemini-2.5-flash-native-audio-latest"
        // Fallback model
        private const val FALLBACK_LIVE_MODEL = "models/gemini-2.5-flash-native-audio-latest"
    }

    private var activeModel = PRIMARY_LIVE_MODEL
    private var isFallbackAttempt = false

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Mute State ────────────────────────────────────────────────────────────
    @Volatile
    private var isMuted = false

    fun setMuted(muted: Boolean) {
        isMuted = muted
        if (muted) {
            _amplitudeFlow.value = 0.0f
        }
    }

    fun isMuted(): Boolean = isMuted

    // ── State Flows for UI / ViewModels ───────────────────────────────────────
    private val _liveState = MutableStateFlow(AuraLiveState.IDLE)
    val liveState: StateFlow<AuraLiveState> = _liveState

    private val _transcriptFlow = MutableStateFlow("")
    val transcriptFlow: StateFlow<String> = _transcriptFlow

    private val _amplitudeFlow = MutableStateFlow(0.0f)
    val amplitudeFlow: StateFlow<Float> = _amplitudeFlow

    private val _turnCompletedFlow = MutableSharedFlow<LiveVoiceTurn>(extraBufferCapacity = 10)
    val turnCompletedFlow: SharedFlow<LiveVoiceTurn> = _turnCompletedFlow.asSharedFlow()

    private val currentUserTurnAccumulator = StringBuilder()
    private val currentAssistantTurnAccumulator = StringBuilder()

    private val vadDetector = AuraVadDetector()
    private var micJob: Job? = null
    @Volatile private var isMicRecordingActive = false

    // Adaptive noise floor for speaker barge-in
    private var noiseFloor = 0.02
    @Volatile private var playbackEndTime = 0L
    @Volatile private var bargeInCooldownUntil = 0L
    private var bargeInConsecutiveFrames = 0

    // Progressive word streaming (prevents spoiling assistant transcript ahead of spoken audio)
    private val pendingAssistantWords = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private var wordStreamerJob: Job? = null
    private val displayedAssistantText = StringBuilder()

    // ── Pending text queue (for messages sent before LISTENING state) ──────────
    private val pendingTextMessages = ArrayDeque<String>()

    // ── Native AudioTrack for Instant 24kHz PCM Playback ──────────────────────
    private var audioTrack: AudioTrack? = null
    private val audioBufferSize = AudioTrack.getMinBufferSize(
        24000,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    private val audioPlaybackChannel = kotlinx.coroutines.channels.Channel<ByteArray>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private var playbackJob: Job? = null

    init {
        initAudioTrack()
        startAudioPlaybackWorker()
    }

    private fun initAudioTrack() {
        try {
            audioTrack?.release()
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(24000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(audioBufferSize * 2, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize native AudioTrack", e)
        }
    }

    private fun startAudioPlaybackWorker() {
        playbackJob?.cancel()
        playbackJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val chunk = audioPlaybackChannel.receiveCatching().getOrNull() ?: break
                try {
                    val track = audioTrack ?: continue
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        track.play()
                    }
                    track.write(chunk, 0, chunk.size)
                } catch (e: Exception) {
                    Log.e(TAG, "AudioTrack playback error", e)
                }
            }
        }
    }

    private fun flushAudioPlayback() {
        while (audioPlaybackChannel.tryReceive().isSuccess) {}
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (_: Exception) {}
        clearAssistantStreamer()
    }

    private fun enqueueAssistantText(text: String) {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        for (tok in tokens) {
            pendingAssistantWords.add(tok)
        }
        startWordStreamerIfNeeded()
    }

    private fun startWordStreamerIfNeeded() {
        if (wordStreamerJob?.isActive == true) return
        wordStreamerJob = scope.launch(Dispatchers.Default) {
            while (isActive && pendingAssistantWords.isNotEmpty()) {
                val nextWord = pendingAssistantWords.poll() ?: break
                if (displayedAssistantText.isNotEmpty()) {
                    displayedAssistantText.append(" ")
                }
                displayedAssistantText.append(nextWord)
                _transcriptFlow.value = displayedAssistantText.toString()
                delay(175) // Natural human speaking cadence (~3.5 words per second)
            }
            wordStreamerJob = null
        }
    }

    private fun flushPendingAssistantText() {
        while (pendingAssistantWords.isNotEmpty()) {
            val nextWord = pendingAssistantWords.poll() ?: break
            if (displayedAssistantText.isNotEmpty()) {
                displayedAssistantText.append(" ")
            }
            displayedAssistantText.append(nextWord)
        }
        if (displayedAssistantText.isNotEmpty()) {
            _transcriptFlow.value = displayedAssistantText.toString()
        }
    }

    private fun clearAssistantStreamer() {
        wordStreamerJob?.cancel()
        wordStreamerJob = null
        pendingAssistantWords.clear()
        displayedAssistantText.clear()
    }

    private fun ensureAudioPlaybackReady() {
        if (audioTrack == null || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            initAudioTrack()
        }
        if (playbackJob == null || playbackJob?.isActive == false) {
            startAudioPlaybackWorker()
        }
    }

    /**
     * Connects to Gemini Live using the primary verified API key.
     */
    fun connect() {
        if (_liveState.value == AuraLiveState.CONNECTED ||
            _liveState.value == AuraLiveState.CONNECTING ||
            _liveState.value == AuraLiveState.LISTENING ||
            _liveState.value == AuraLiveState.SPEAKING) return

        ensureAudioPlaybackReady()
        _liveState.value = AuraLiveState.CONNECTING
        val key = Secrets.getActiveGeminiKey()
        val url = "$BASE_WS_URL?key=$key"
        val request = Request.Builder().url(url).build()

        Log.i(TAG, "Connecting to Gemini Live WS with model: $activeModel")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected successfully to Gemini Live ($activeModel)")
                _liveState.value = AuraLiveState.CONNECTED
                sendSetupMessage()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "WS text message received: ${text.take(80)}")
                handleServerMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val text = bytes.utf8()
                Log.d(TAG, "WS binary message received (${bytes.size} bytes): ${text.take(80)}")
                handleServerMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code / $reason")
                _liveState.value = AuraLiveState.IDLE
                stopMicRecording()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                Log.e(TAG, "WebSocket failure on $activeModel: ${t.message}, HTTP=$code")
                stopMicRecording()
                _liveState.value = AuraLiveState.ERROR
            }
        })
    }

    private fun sendSetupMessage() {
        val setupObj = JsonObject().apply {
            val setup = JsonObject().apply {
                addProperty("model", activeModel)

                val genConfig = JsonObject().apply {
                    val modalities = JsonArray().apply { add("AUDIO") }
                    add("responseModalities", modalities)

                    val speechConfig = JsonObject().apply {
                        val voiceConfig = JsonObject().apply {
                            val prebuilt = JsonObject().apply {
                                addProperty("voiceName", "Aoede")
                            }
                            add("prebuiltVoiceConfig", prebuilt)
                        }
                        add("voiceConfig", voiceConfig)
                    }
                    add("speechConfig", speechConfig)
                }
                add("generationConfig", genConfig)

                // System Instruction with permanent memories
                val sysInstruction = JsonObject().apply {
                    val parts = JsonArray().apply {
                        val part = JsonObject().apply {
                            addProperty(
                                "text",
                                AuraMemoryManager.buildSystemPrompt(context, isVoiceMode = true)
                            )
                        }
                        add(part)
                    }
                    add("parts", parts)
                }
                add("systemInstruction", sysInstruction)

                // Tools in official Bidi camelCase format
                val toolsArray = JsonArray().apply {
                    val toolHolder = JsonObject()
                    toolHolder.add("functionDeclarations", JarvisToolRegistry.getGeminiToolDeclarations())
                    add(toolHolder)
                }
                add("tools", toolsArray)

                // Explicitly enable input and output audio transcriptions.
                // Per Gemini Live API spec: AudioTranscriptionConfig = empty object = enabled.
                add("inputAudioTranscription", JsonObject())
                add("outputAudioTranscription", JsonObject())
            }
            add("setup", setup)
        }

        webSocket?.send(setupObj.toString())
        Log.i(TAG, "Setup message sent to Gemini Live")
    }

    private fun startMicRecording() {
        stopMicRecording()
        micJob = scope.launch(Dispatchers.IO) {
            // Give audio hardware 200ms to settle after WakeWordService released its AudioRecord.
            // The ViewModel already waits 500ms before calling connect(), so by the time
            // setupComplete arrives and this runs, total elapsed time is well over 500ms.
            // The extra 200ms here is a safety margin for the hardware pipeline.
            delay(200)

            val hasPerm = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasPerm) {
                Log.e(TAG, "RECORD_AUDIO permission missing! Cannot open microphone.")
                _liveState.value = AuraLiveState.ERROR
                return@launch
            }

            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBuf <= 0) {
                Log.e(TAG, "AudioRecord.getMinBufferSize returned $minBuf — hardware not ready")
                _liveState.value = AuraLiveState.ERROR
                return@launch
            }
            val bufferSize = maxOf(minBuf * 4, 16384)
            Log.i(TAG, "AudioRecord params: sampleRate=$sampleRate minBuf=$minBuf actualBuf=$bufferSize")

            val audioSources = listOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, // Far-field speech recognition (Google Assistant tuning)
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.DEFAULT
            )

            // CRITICAL FIX: Retry loop — if another component (e.g. WakeWordService) held the
            // AudioRecord just before us, give the hardware up to 3 attempts with 200ms backoff.
            var record: AudioRecord? = null
            for (attempt in 1..3) {
                for (source in audioSources) {
                    try {
                        val r = AudioRecord(source, sampleRate, channelConfig, audioFormat, bufferSize)
                        if (r.state == AudioRecord.STATE_INITIALIZED) {
                            record = r
                            Log.i(TAG, "AudioRecord initialized (attempt $attempt, source=$source, sessionId=${r.audioSessionId})")
                            break
                        } else {
                            Log.w(TAG, "AudioRecord STATE not initialized (attempt $attempt, source=$source, state=${r.state})")
                            r.release()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "AudioRecord init exception (attempt $attempt, source=$source): ${e.message}")
                    }
                }
                if (record != null) break
                if (attempt < 3) {
                    Log.w(TAG, "AudioRecord not ready yet — retrying in 200ms (attempt $attempt/3)")
                    delay(200)
                }
            }

            if (record == null) {
                Log.e(TAG, "FATAL: AudioRecord failed to initialize after 3 attempts. Mic unavailable.")
                _liveState.value = AuraLiveState.ERROR
                return@launch
            }

            // CRITICAL FIX: Only set isMicRecordingActive = true AFTER AudioRecord is confirmed initialized.
            isMicRecordingActive = true

            try {
                // Hardware AcousticEchoCanceler & AutomaticGainControl
                try {
                    if (AcousticEchoCanceler.isAvailable()) {
                        AcousticEchoCanceler.create(record.audioSessionId)?.apply {
                            enabled = true
                            Log.i(TAG, "Hardware AcousticEchoCanceler enabled")
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "AcousticEchoCanceler attach skipped: ${e.message}")
                }
                try {
                    if (AutomaticGainControl.isAvailable()) {
                        AutomaticGainControl.create(record.audioSessionId)?.apply {
                            enabled = true
                            Log.i(TAG, "Hardware AutomaticGainControl enabled")
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "AutomaticGainControl attach skipped: ${e.message}")
                }

                record.startRecording()
                Log.i(TAG, "✅ Microphone recording started. recordingState=${record.recordingState}")

                // Official Google Live API best practice: Stream audio in 40ms chunks (640 samples @ 16kHz)
                val shortBuf = ShortArray(640)
                val byteBuf = ByteArray(1280)

                while (isActive && isMicRecordingActive && webSocket != null) {
                    val readShorts = record.read(shortBuf, 0, shortBuf.size)
                    if (readShorts > 0) {
                        if (isMuted) {
                            _amplitudeFlow.value = 0.0f
                            continue
                        }

                        // Compute local energy (RMS)
                        var sum = 0.0
                        for (i in 0 until readShorts) {
                            sum += (shortBuf[i].toLong() * shortBuf[i].toLong())
                        }
                        val rms = sqrt(sum / readShorts) / 32768.0

                        // Process VAD for human speech classification and UI visualizer
                        val vadResult = vadDetector.process(shortBuf, readShorts)

                        // ── 1. Acoustic Echo Gate & Intentional Barge-In ─────────────────
                        if (_liveState.value == AuraLiveState.SPEAKING) {
                            if ((vadResult.isSpeech && rms > 0.06) || rms > 0.12) {
                                bargeInConsecutiveFrames++
                                if (bargeInConsecutiveFrames >= 2) {
                                    Log.i(TAG, "Real user barge-in detected (RMS=$rms). Flushing playback.")
                                    flushAudioPlayback()
                                    bargeInConsecutiveFrames = 0
                                    _liveState.value = AuraLiveState.LISTENING
                                }
                            } else {
                                bargeInConsecutiveFrames = 0
                            }
                            // CRITICAL: NEVER stream speaker audio back into mic to prevent echo loop
                            continue
                        }

                        // ── 2. Adaptive Far-Field Audio Gain (30-100 cm natural distance) ──
                        // Dynamically boost quiet/distant speech by up to 3.5x so user never needs
                        // to put lips to the microphone hole, while gently tapering loud speech to prevent clipping.
                        val adaptiveGain = when {
                            rms < 0.020 -> 3.5f  // Distant or whisper level (desk / arm distance)
                            rms < 0.050 -> 2.6f  // Normal conversational speech
                            rms < 0.100 -> 1.8f  // Close / elevated speech
                            else -> 1.3f         // Very loud / near mic
                        }
                        for (i in 0 until readShorts) {
                            val sample = (shortBuf[i] * adaptiveGain).toInt().coerceIn(-32768, 32767)
                            byteBuf[i * 2] = (sample and 0xFF).toByte()
                            byteBuf[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                        }

                        // Update UI orb with normalized amplitude
                        _amplitudeFlow.value = if (vadResult.isSpeech) {
                            vadResult.normalizedEnergy.coerceIn(0.15f, 1.0f)
                        } else {
                            (rms * 4.0).toFloat().coerceIn(0.02f, 0.15f)
                        }

                        // Stream directly to Gemini Live — no destructive zero-filling during listening
                        sendAudioChunk(byteBuf, readShorts * 2)
                    } else if (readShorts < 0) {
                        Log.e(TAG, "AudioRecord.read() returned error code: $readShorts — stopping mic")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in mic recording loop", e)
            } finally {
                isMicRecordingActive = false
                try { record.stop(); record.release() } catch (_: Exception) {}
                Log.i(TAG, "Microphone recording stopped and released")
            }
        }
    }

    private fun stopMicRecording() {
        isMicRecordingActive = false
        micJob?.cancel()
        micJob = null
    }

    /**
     * Sends a user text prompt to Gemini Live.
     * If not yet connected, queues the message and connects.
     */
    fun sendTextMessage(text: String) {
        if (text.isBlank()) return
        currentUserTurnAccumulator.append(text).append(" ")
        if (_liveState.value == AuraLiveState.LISTENING || _liveState.value == AuraLiveState.CONNECTED) {
            sendTextMessageInternal(text)
        } else {
            pendingTextMessages.addLast(text)
            if (_liveState.value == AuraLiveState.IDLE || _liveState.value == AuraLiveState.ERROR) {
                connect()
            }
        }
    }

    private fun sendTextMessageInternal(text: String) {
        val inputObj = JsonObject().apply {
            val clientContent = JsonObject().apply {
                val turns = JsonArray().apply {
                    val turn = JsonObject().apply {
                        addProperty("role", "user")
                        val parts = JsonArray().apply {
                            val part = JsonObject().apply {
                                addProperty("text", text)
                            }
                            add(part)
                        }
                        add("parts", parts)
                    }
                    add(turn)
                }
                add("turns", turns)
                addProperty("turnComplete", true)
            }
            add("clientContent", clientContent)
        }
        webSocket?.send(inputObj.toString())
        Log.d(TAG, "Sent text via clientContent: $text")
    }

    /**
     * Streams real-time camera or screen capture JPEG frames to Gemini Live.
     */
    fun sendImageChunk(jpegBytes: ByteArray) {
        if (_liveState.value == AuraLiveState.IDLE || _liveState.value == AuraLiveState.ERROR) return

        val base64Data = Base64.encodeToString(jpegBytes, 0, jpegBytes.size, Base64.NO_WRAP)
        val inputObj = JsonObject().apply {
            val realtime = JsonObject().apply {
                val chunks = JsonArray().apply {
                    val chunk = JsonObject().apply {
                        addProperty("mimeType", "image/jpeg")
                        addProperty("data", base64Data)
                    }
                    add(chunk)
                }
                add("mediaChunks", chunks)
            }
            add("realtimeInput", realtime)
        }
        webSocket?.send(inputObj.toString())
        Log.i(TAG, "Sent real-time image frame (${jpegBytes.size} bytes) to Gemini Live")
    }

    /**
     * Streams raw 16kHz mono PCM audio to Gemini Live.
     */
    fun sendAudioChunk(pcmBytes: ByteArray, length: Int = pcmBytes.size) {
        if (_liveState.value == AuraLiveState.IDLE || _liveState.value == AuraLiveState.ERROR) return

        val base64Data = Base64.encodeToString(pcmBytes, 0, length, Base64.NO_WRAP)
        val inputObj = JsonObject().apply {
            val realtime = JsonObject().apply {
                val audioObj = JsonObject().apply {
                    addProperty("data", base64Data)
                    addProperty("mimeType", "audio/pcm;rate=16000")
                }
                add("audio", audioObj)
            }
            add("realtimeInput", realtime)
        }
        webSocket?.send(inputObj.toString())
    }

    private fun handleServerMessage(jsonText: String) {
        try {
            val root = JsonParser.parseString(jsonText).asJsonObject

            // 0. Setup Complete
            if (root.has("setupComplete")) {
                Log.i(TAG, "Gemini Live setupComplete received! Starting mic.")
                _liveState.value = AuraLiveState.LISTENING
                startMicRecording()

                while (pendingTextMessages.isNotEmpty()) {
                    val pending = pendingTextMessages.removeFirst()
                    sendTextMessageInternal(pending)
                }
                return
            }

            // 1. Server Content (Audio, Transcripts, Interruption)
            if (root.has("serverContent")) {
                val serverContent = root.getAsJsonObject("serverContent")

                // Server detected user interruption
                if (serverContent.has("interrupted") && serverContent.get("interrupted").asBoolean) {
                    Log.i(TAG, "Server acknowledged user interruption")
                    flushAudioPlayback()
                    val partialUser = currentUserTurnAccumulator.toString().trim()
                    val partialAssistant = currentAssistantTurnAccumulator.toString().trim()
                    if (partialUser.isNotBlank() || partialAssistant.isNotBlank()) {
                        _turnCompletedFlow.tryEmit(
                            LiveVoiceTurn(
                                userText = if (partialUser.isNotBlank()) partialUser else "Spoken query",
                                assistantText = if (partialAssistant.isNotBlank()) "$partialAssistant..." else ""
                            )
                        )
                        currentUserTurnAccumulator.clear()
                        currentAssistantTurnAccumulator.clear()
                    }
                    _liveState.value = AuraLiveState.LISTENING
                    return
                }

                // Spoken transcriptions
                if (serverContent.has("inputTranscription")) {
                    val inTx = serverContent.getAsJsonObject("inputTranscription")
                    val tx = inTx.get("text")?.asString ?: ""
                    if (tx.isNotBlank()) {
                        clearAssistantStreamer()
                        _transcriptFlow.value = tx
                        currentUserTurnAccumulator.append(tx).append(" ")
                        if (_liveState.value != AuraLiveState.SPEAKING) {
                            _liveState.value = AuraLiveState.THINKING
                        }
                    }
                }

                if (serverContent.has("outputTranscription")) {
                    val outTx = serverContent.getAsJsonObject("outputTranscription")
                    val tx = outTx.get("text")?.asString ?: ""
                    if (tx.isNotBlank()) {
                        enqueueAssistantText(tx)
                        currentAssistantTurnAccumulator.append(tx).append(" ")
                    }
                }

                // Audio chunks from model turn
                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getAsJsonObject("modelTurn")
                    val parts = modelTurn.getAsJsonArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.size()) {
                            val part = parts.get(i).asJsonObject

                            if (part.has("inlineData")) {
                                val inline = part.getAsJsonObject("inlineData")
                                val mime = inline.get("mimeType")?.asString ?: ""
                                if (mime.startsWith("audio/pcm")) {
                                    val b64 = inline.get("data").asString
                                    val audioBytes = Base64.decode(b64, Base64.NO_WRAP)
                                    playAudioChunk(audioBytes)
                                }
                            }
                        }
                    }
                }

                if (serverContent.has("turnComplete") &&
                    serverContent.get("turnComplete").asBoolean) {
                    flushPendingAssistantText()
                    val userTurn = currentUserTurnAccumulator.toString().trim()
                    val assistantTurn = currentAssistantTurnAccumulator.toString().trim()
                    if (userTurn.isNotBlank() || assistantTurn.isNotBlank()) {
                        _turnCompletedFlow.tryEmit(
                            LiveVoiceTurn(
                                userText = if (userTurn.isNotBlank()) userTurn else "Spoken query",
                                assistantText = assistantTurn
                            )
                        )
                    }
                    currentUserTurnAccumulator.clear()
                    currentAssistantTurnAccumulator.clear()
                    _amplitudeFlow.value = 0.05f
                    _liveState.value = AuraLiveState.LISTENING
                    Log.i(TAG, "Turn complete → back to LISTENING")
                }
            }

            // 2. Tool Calls
            if (root.has("toolCall")) {
                val toolCall = root.getAsJsonObject("toolCall")
                val fnCalls = toolCall.getAsJsonArray("functionCalls") ?: return
                _liveState.value = AuraLiveState.THINKING

                scope.launch {
                    val responseArray = JsonArray()
                    for (j in 0 until fnCalls.size()) {
                        val call = fnCalls.get(j).asJsonObject
                        val name = call.get("name").asString
                        val id = call.get("id").asString
                        val args = call.getAsJsonObject("args") ?: JsonObject()
                        val result = JarvisToolRegistry.executeTool(context, name, args)
                        val fnResp = JsonObject().apply {
                            addProperty("id", id)
                            addProperty("name", name)
                            val resp = JsonObject().apply {
                                add("output", result)
                            }
                            add("response", resp)
                        }
                        responseArray.add(fnResp)
                    }

                    val toolResponseObj = JsonObject().apply {
                        val toolResponse = JsonObject().apply {
                            add("functionResponses", responseArray)
                        }
                        add("toolResponse", toolResponse)
                    }
                    webSocket?.send(toolResponseObj.toString())
                    Log.i(TAG, "Sent toolResponse to Gemini Live")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling server message: ${e.message}")
        }
    }

    private fun playAudioChunk(pcmBytes: ByteArray) {
        _liveState.value = AuraLiveState.SPEAKING
        try {
            // 24kHz mono 16-bit PCM = 24000 samples/sec * 2 bytes = 48000 bytes/sec = 48 bytes/ms
            val chunkDurationMs = (pcmBytes.size.toLong() * 1000L) / 48000L
            val now = System.currentTimeMillis()
            playbackEndTime = maxOf(playbackEndTime, now) + chunkDurationMs

            var sum = 0.0
            val numShorts = pcmBytes.size / 2
            for (i in 0 until numShorts) {
                val lo = pcmBytes[i * 2].toInt() and 0xFF
                val hi = pcmBytes[i * 2 + 1].toInt()
                val sample = (hi shl 8) or lo
                sum += (sample * sample)
            }
            if (numShorts > 0) {
                val rms = sqrt(sum / numShorts)
                _amplitudeFlow.value = (rms / 12000.0).toFloat().coerceIn(0.15f, 1.0f)
            }

            audioPlaybackChannel.trySend(pcmBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Error enqueuing audio chunk", e)
        }
    }

    fun disconnect() {
        try {
            stopMicRecording()
            flushAudioPlayback()
            pendingTextMessages.clear()
            webSocket?.close(1000, "User disconnected")
            webSocket = null
            _liveState.value = AuraLiveState.IDLE
            _amplitudeFlow.value = 0.0f
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        }
    }

    fun release() {
        disconnect()
        playbackJob?.cancel()
        playbackJob = null
        micJob?.cancel()
        micJob = null
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }
}
