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
import com.aura.assistant.audio.AuraDiagnostics
import com.aura.assistant.audio.AuraVadDetector
import com.aura.assistant.audio.MicOwnershipManager
import com.aura.assistant.audio.MicOwner
import com.aura.assistant.config.Secrets
import com.aura.assistant.config.IshaGatewayConfig
import com.aura.assistant.vision.ScreenFrameSampler
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

/**
 * Holds the context of an interrupted turn so it can be merged with the
 * next user utterance — ChatGPT-style context-preserving barge-in.
 */
data class InterruptedContext(
    val userSaid: String,           // What user said before interrupting
    val assistantWasSaying: String, // What AURA was saying when interrupted
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Rolling buffer of recent conversation turns for in-session context.
 * Keeps up to [maxTurns] turns; oldest are dropped automatically.
 */
class ConversationTurnBuffer(private val maxTurns: Int = 6) {
    private val turns = ArrayDeque<LiveVoiceTurn>()

    fun add(turn: LiveVoiceTurn) {
        turns.addLast(turn)
        if (turns.size > maxTurns) turns.removeFirst()
    }

    fun recentTurns(): List<LiveVoiceTurn> = turns.toList()

    fun isEmpty(): Boolean = turns.isEmpty()

    fun clear() = turns.clear()
}

enum class AuraLiveState {
    IDLE,
    WAKE_LISTENING,
    WAKE_DETECTED,
    CONNECTING,
    CONNECTED,
    LISTENING,
    THINKING,
    SPEAKING,
    INTERRUPTED,
    RECOVERY,
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
        // ╔══════════════════════════════════════════════════════════════════════════════════╗
        // ║ 🔒 STRICTLY FROZEN - DO NOT MODIFY, REVERT, OR RENAME THESE LIVE MODELS          ║
        // ║ Verified & tested on real hardware via live Google Gemini Live WebSocket API.    ║
        // ╚══════════════════════════════════════════════════════════════════════════════════╝
        private const val PRIMARY_LIVE_MODEL = "models/gemini-2.5-flash-native-audio-latest"
        private const val FALLBACK_LIVE_MODEL = "models/gemini-2.5-flash-native-audio-latest"
        const val FROZEN_VOICE_NAME = "Aoede" // 🔒 FROZEN: Sweet natural female persona
    }

    private var activeModel = PRIMARY_LIVE_MODEL
    private var isFallbackAttempt = false
    private var activeVoiceName: String = com.aura.assistant.ui.components.IshaSettingsManager.getSavedVoice(context)

    fun setActiveVoice(voice: String) {
        if (voice.isNotBlank()) {
            activeVoiceName = voice
            Log.i(TAG, "Gemini Live voice updated to: $voice")
        }
    }

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

    // ── Context-Preserving Barge-In (ChatGPT style) ───────────────────────────
    // When user interrupts AURA mid-speech, the partial context is NOT discarded.
    // It is stored here and silently injected into the next user turn so AURA
    // always responds with full conversational awareness.
    @Volatile
    private var interruptedContext: InterruptedContext? = null

    // Rolling buffer of completed turns for in-session context continuity
    private val turnBuffer = ConversationTurnBuffer(maxTurns = 6)

    private val vadDetector = AuraVadDetector()
    private var micJob: Job? = null
    @Volatile private var isMicRecordingActive = false

    // ScreenFrameSampler: adaptive change-detection gating for screen/camera frames.
    // Drops visually identical or too-frequent frames to save Gemini Live token cost.
    private val screenFrameSampler = ScreenFrameSampler { jpegBytes ->
        sendImageChunk(jpegBytes)
    }

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

    // ── Active Voice Language ("hinglish", "hindi", "english") ──────────────
    @Volatile private var activeLanguage: String = "hinglish"
    var onLanguageChanged: ((String) -> Unit)? = null

    fun setVoiceLanguage(lang: String) {
        val normalized = when (lang.trim().lowercase()) {
            "hindi", "hi", "हिंदी" -> "hindi"
            "english", "en", "अंग्रेजी" -> "english"
            else -> "hinglish"
        }
        val previous = activeLanguage
        activeLanguage = normalized
        Log.i(TAG, "Voice language switched from $previous to $normalized")
        onLanguageChanged?.invoke(normalized)

        if (_liveState.value == AuraLiveState.LISTENING || _liveState.value == AuraLiveState.CONNECTED) {
            val directive = when (normalized) {
                "hindi" -> "[SYSTEM INSTRUCTION: Switch immediately to speaking in fluent, warm Hindi, and provide output transcription in Hindi (Devanagari script). User requested: Hindi.]"
                "english" -> "[SYSTEM INSTRUCTION: Switch immediately to speaking in fluent, friendly English, and provide output transcription in English. User requested: English.]"
                else -> "[SYSTEM INSTRUCTION: Switch immediately to speaking in warm, natural Hinglish (Hindi in Roman script). User requested: Hinglish.]"
            }
            sendTextMessage(directive)
        }
    }

    fun getVoiceLanguage(): String = activeLanguage

    /**
     * Sends an immediate audioStreamEnd event to Gemini Live WebSocket.
     * Triggers instant server turn finalization without waiting for server silence timeout.
     */
    fun sendAudioStreamEnd() {
        if (_liveState.value == AuraLiveState.IDLE || _liveState.value == AuraLiveState.ERROR) return
        val inputObj = JsonObject().apply {
            val realtime = JsonObject().apply {
                addProperty("audioStreamEnd", true)
            }
            add("realtimeInput", realtime)
        }
        webSocket?.send(inputObj.toString())
        Log.d(TAG, "Sent audioStreamEnd (Hybrid VAD fast turn finalization)")
    }

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
        scope.launch {
            _liveState.collect { state ->
                try {
                    com.aura.assistant.AuraDynamicIsland.updateLiveState(context, state, _amplitudeFlow.value)
                } catch (_: Exception) {}
            }
        }
        scope.launch {
            _amplitudeFlow.collect { amp ->
                try {
                    val st = _liveState.value
                    if (st == AuraLiveState.LISTENING || st == AuraLiveState.SPEAKING) {
                        com.aura.assistant.AuraDynamicIsland.updateLiveState(context, st, amp)
                    }
                } catch (_: Exception) {}
            }
        }
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
        AuraDiagnostics.updateLive { it.copy(sessionState = AuraLiveState.CONNECTING, lastError = null) }
        val key = Secrets.getActiveGeminiKey(context)
        val (url, extraHeaders) = IshaGatewayConfig.getLiveWsEndpoint(context, key)
        val reqBuilder = Request.Builder().url(url)
        for ((k, v) in extraHeaders) {
            reqBuilder.addHeader(k, v)
        }
        val request = reqBuilder.build()

        Log.i(TAG, "Connecting to Gemini Live WS with model: $activeModel")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected successfully to Gemini Live ($activeModel)")
                _liveState.value = AuraLiveState.CONNECTED
                AuraDiagnostics.updateLive { it.copy(wsConnected = true, sessionState = AuraLiveState.CONNECTED) }
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
                AuraDiagnostics.updateLive { it.copy(wsConnected = false, sessionState = AuraLiveState.IDLE) }
                stopMicRecording()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                Log.e(TAG, "WebSocket failure on $activeModel: ${t.message}, HTTP=$code")
                stopMicRecording()
                _liveState.value = AuraLiveState.ERROR
                AuraDiagnostics.updateLive { it.copy(wsConnected = false, lastError = t.message, sessionState = AuraLiveState.ERROR) }
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
                                addProperty("voiceName", activeVoiceName)
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
                                AuraMemoryManager.buildSystemPrompt(context, isVoiceMode = true, language = activeLanguage)
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
                    toolHolder.add("functionDeclarations", IshaToolRegistry.getGeminiToolDeclarations())
                    add(toolHolder)
                }
                add("tools", toolsArray)

                // Explicitly enable input and output audio transcriptions with active language hints.
                val inputTxConfig = JsonObject().apply {
                    val langCodes = JsonArray()
                    when (activeLanguage) {
                        "hindi" -> langCodes.add("hi-IN")
                        "english" -> {
                            langCodes.add("en-IN")
                            langCodes.add("en-US")
                        }
                    }
                    add("languageCodes", langCodes)
                }
                add("inputAudioTranscription", inputTxConfig)
                add("outputAudioTranscription", JsonObject())
            }
            add("setup", setup)
        }

        webSocket?.send(setupObj.toString())
        Log.i(TAG, "Setup message sent to Gemini Live (language=$activeLanguage)")
    }

    private fun startMicRecording() {
        stopMicRecording()
        micJob = scope.launch(Dispatchers.IO) {
            // Give audio hardware 50ms to settle after WakeWordService released its AudioRecord.
            delay(50)

            // Acquire exclusive mic ownership from MicOwnershipManager
            if (!MicOwnershipManager.requestOwnership(MicOwner.GEMINI_LIVE)) {
                Log.w(TAG, "Mic not immediately available, waiting 300ms for release...")
                delay(300)
                if (!MicOwnershipManager.requestOwnership(MicOwner.GEMINI_LIVE)) {
                    Log.e(TAG, "Mic ownership denied to GEMINI_LIVE (held by ${MicOwnershipManager.currentOwner.value})")
                    _liveState.value = AuraLiveState.ERROR
                    AuraDiagnostics.updateLive { it.copy(lastError = "Mic ownership denied", sessionState = AuraLiveState.ERROR) }
                    return@launch
                }
            }
            AuraDiagnostics.updateLive { it.copy(micOwned = true, sessionState = _liveState.value) }

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
                try {
                    if (NoiseSuppressor.isAvailable()) {
                        NoiseSuppressor.create(record.audioSessionId)?.apply {
                            enabled = true
                            Log.i(TAG, "Hardware NoiseSuppressor enabled on session ${record.audioSessionId}")
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "NoiseSuppressor attach skipped: ${e.message}")
                }

                record.startRecording()
                Log.i(TAG, "✅ Microphone recording started. recordingState=${record.recordingState}")

                // Official Google Live API best practice: Stream audio in 40ms chunks (640 samples @ 16kHz)
                val shortBuf = ShortArray(640)
                val byteBuf = ByteArray(1280)

                // Hybrid VAD Turn Tracking for Instant End-of-Speech Finalization
                var hasSpokenInTurn = false
                var consecutiveSilenceFrames = 0

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
                        val isActivelyPlayingAudio = _liveState.value == AuraLiveState.SPEAKING || System.currentTimeMillis() < (playbackEndTime + 350L)
                        if (isActivelyPlayingAudio) {
                            hasSpokenInTurn = false
                            consecutiveSilenceFrames = 0
                            // Phone speaker output easily reaches RMS 0.08 - 0.25 at the mic.
                            // To prevent speaker echo from falsely interrupting Aura, require deliberate user speech (RMS > 0.38)
                            // and sustained energy over at least 8 frames (~320ms).
                            if ((vadResult.isSpeech && rms > 0.38) || rms > 0.48) {
                                bargeInConsecutiveFrames++
                                if (bargeInConsecutiveFrames >= 8) {
                                    Log.i(TAG, "Real user barge-in detected (RMS=$rms). Flushing playback & preserving context.")
                                    flushAudioPlayback()
                                    playbackEndTime = 0L
                                    val partialUser = currentUserTurnAccumulator.toString().trim()
                                    val partialAssistant = currentAssistantTurnAccumulator.toString().trim()
                                    if (partialUser.isNotBlank() && partialAssistant.length >= 25) {
                                        interruptedContext = InterruptedContext(
                                            userSaid = partialUser,
                                            assistantWasSaying = partialAssistant
                                        )
                                        Log.i(TAG, "Client barge-in saved interrupted context: user='${partialUser.take(40)}', aura='${partialAssistant.take(40)}'")
                                        _turnCompletedFlow.tryEmit(
                                            LiveVoiceTurn(
                                                userText = partialUser,
                                                assistantText = "$partialAssistant…"
                                            )
                                        )
                                    }
                                    currentUserTurnAccumulator.clear()
                                    currentAssistantTurnAccumulator.clear()
                                    bargeInConsecutiveFrames = 0
                                    _liveState.value = AuraLiveState.INTERRUPTED
                                    AuraDiagnostics.updateLive {
                                        it.copy(
                                            interruptionCount = it.interruptionCount + 1,
                                            sessionState = AuraLiveState.INTERRUPTED
                                        )
                                    }
                                    _liveState.value = AuraLiveState.LISTENING
                                }
                            } else {
                                bargeInConsecutiveFrames = 0
                            }
                            // CRITICAL: NEVER stream speaker audio back into mic to prevent echo loop
                            continue
                        }

                        // ── Hybrid VAD: Fast Turn Finalization (Bypasses server silence timeout) ──
                        val isUserSpeaking = vadResult.isSpeech || rms > 0.035
                        if (isUserSpeaking) {
                            hasSpokenInTurn = true
                            consecutiveSilenceFrames = 0
                        } else if (hasSpokenInTurn) {
                            consecutiveSilenceFrames++
                            if (consecutiveSilenceFrames == 12) { // 12 * ~40ms = ~480ms silence after speech
                                sendAudioStreamEnd()
                                hasSpokenInTurn = false
                            }
                        }

                        // ── 2. Adaptive Far-Field Audio Gain with Clean Silence Gating ──
                        // Does NOT amplify low ambient noise floors (fans, horns, background radio),
                        // while intelligently boosting conversational speech at natural desk/arm distance.
                        val adaptiveGain = when {
                            rms < 0.005 -> 0.2f  // Silence floor - attenuate so server VAD detects silence immediately
                            rms < 0.020 -> if (vadResult.isSpeech) 3.2f else 0.2f  // Soft speech boosted, ambient noise floor attenuated!
                            rms < 0.050 -> 2.5f  // Normal conversational speech
                            rms < 0.100 -> 1.8f  // Close / elevated speech
                            else -> 1.2f         // Very loud / near mic
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
        MicOwnershipManager.releaseOwnership(MicOwner.GEMINI_LIVE)
        AuraDiagnostics.updateLive { it.copy(micOwned = false) }
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

    /**
     * Sends a user text message to Gemini Live.
     *
     * If an [interruptedContext] is present (user interrupted AURA mid-speech),
     * the prior partial exchange is injected as multi-turn context BEFORE the
     * new user utterance. This gives Gemini full conversation awareness across
     * interruptions — ChatGPT-style seamless context preservation.
     *
     * Context injection format:
     *   [user: what they said before] → [model: what AURA was saying] → [user: NEW message]
     */
    private fun sendTextMessageInternal(text: String) {
        val ctx = interruptedContext

        val inputObj = JsonObject().apply {
            val clientContent = JsonObject().apply {
                val turns = JsonArray().apply {

                    // ── Inject interrupted context as prior turns ──────────────────────
                    // When user barge-in happened, we replay the partial exchange first
                    // so Gemini's next response is contextually continuous.
                    if (ctx != null) {
                        // Prior user utterance (what user said before interrupting)
                        if (ctx.userSaid.isNotBlank()) {
                            val priorUserTurn = JsonObject().apply {
                                addProperty("role", "user")
                                val parts = JsonArray().apply {
                                    add(JsonObject().apply {
                                        addProperty("text", ctx.userSaid)
                                    })
                                }
                                add("parts", parts)
                            }
                            add(priorUserTurn)
                        }

                        // Prior model response (what AURA was saying when interrupted)
                        if (ctx.assistantWasSaying.isNotBlank()) {
                            val priorModelTurn = JsonObject().apply {
                                addProperty("role", "model")
                                val parts = JsonArray().apply {
                                    add(JsonObject().apply {
                                        // Mark as interrupted so model knows it wasn't finished
                                        addProperty("text", "${ctx.assistantWasSaying} [interrupted]")
                                    })
                                }
                                add("parts", parts)
                            }
                            add(priorModelTurn)
                        }

                        Log.i(TAG, "Context-preserving barge-in: injecting prior turn into clientContent " +
                                "(userSaid='${ctx.userSaid.take(40)}', auraWasSaying='${ctx.assistantWasSaying.take(40)}')")
                    }

                    // ── Current new user utterance ─────────────────────────────────────
                    val newUserTurn = JsonObject().apply {
                        addProperty("role", "user")
                        val parts = JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("text", text)
                            })
                        }
                        add("parts", parts)
                    }
                    add(newUserTurn)
                }
                add("turns", turns)
                addProperty("turnComplete", true)
            }
            add("clientContent", clientContent)
        }

        webSocket?.send(inputObj.toString())
        Log.d(TAG, "Sent text via clientContent (withContext=${ctx != null}): $text")
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
     * Streams a raw [Bitmap] screen/camera frame through [ScreenFrameSampler] before sending
     * to Gemini Live. The sampler drops identical frames and throttles to ≤2fps automatically.
     *
     * @param bitmap The current screen or camera frame.
     * @param forceSend If true, bypasses change-detection (use on session start or scene changes).
     */
    fun sendScreenBitmap(bitmap: android.graphics.Bitmap, forceSend: Boolean = false) {
        if (_liveState.value == AuraLiveState.IDLE || _liveState.value == AuraLiveState.ERROR) return
        screenFrameSampler.processBitmap(bitmap, forceSend)
    }

    /**
     * Streams pre-compressed JPEG screen bytes through [ScreenFrameSampler] before sending
     * to Gemini Live. Use this when the frame is already JPEG-encoded (e.g. from
     * [AuraAccessibilityService.takeOptimizedScreenCapture]) to avoid redundant decode+re-encode.
     *
     * The sampler hashes frame content to drop visually identical frames and throttles to ≤2fps.
     *
     * @param jpegBytes  JPEG-encoded screen frame.
     * @param forceSend  Bypass change-detection (use on session start or after major UI change).
     */
    fun sendScreenJpeg(jpegBytes: ByteArray, forceSend: Boolean = false) {
        if (_liveState.value == AuraLiveState.IDLE || _liveState.value == AuraLiveState.ERROR) return
        screenFrameSampler.processJpeg(jpegBytes, forceSend)
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
        AuraDiagnostics.updateLive { it.copy(audioPacketsSent = it.audioPacketsSent + 1) }
    }

    private fun handleServerMessage(jsonText: String) {
        try {
            AuraDiagnostics.updateLive { it.copy(serverEventsReceived = it.serverEventsReceived + 1) }
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
                    Log.i(TAG, "Server acknowledged user interruption — preserving context (ChatGPT style)")
                    flushAudioPlayback()
                    val partialUser = currentUserTurnAccumulator.toString().trim()
                    val partialAssistant = currentAssistantTurnAccumulator.toString().trim()

                    // ── CONTEXT-PRESERVING BARGE-IN ──────────────────────────────────────
                    // Save interrupted context instead of discarding it. When user speaks
                    // next, we inject this as background context so AURA's response is
                    // continuous and aware — exactly like ChatGPT's behaviour.
                    if (partialUser.isNotBlank() || partialAssistant.isNotBlank()) {
                        interruptedContext = InterruptedContext(
                            userSaid = partialUser,
                            assistantWasSaying = partialAssistant
                        )
                        Log.i(TAG, "Interrupted context saved — user='${partialUser.take(60)}' " +
                                "assistant='${partialAssistant.take(60)}'")

                        // Emit partial turn for chat history UI ONLY if meaningful text exists (avoids single-word fragments)
                        if (partialUser.isNotBlank() && partialAssistant.length >= 25) {
                            _turnCompletedFlow.tryEmit(
                                LiveVoiceTurn(
                                    userText = partialUser,
                                    assistantText = "$partialAssistant…"
                                )
                            )
                        }
                    }
                    // Reset accumulators for the NEXT turn (context is in interruptedContext)
                    currentUserTurnAccumulator.clear()
                    currentAssistantTurnAccumulator.clear()
                    _liveState.value = AuraLiveState.LISTENING
                    AuraDiagnostics.updateLive {
                        it.copy(
                            interruptionCount = it.interruptionCount + 1,
                            sessionState = AuraLiveState.LISTENING
                        )
                    }
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
                        val lower = tx.lowercase()
                        if (lower.contains("hindi me baat") || lower.contains("hindi mein baat") || lower.contains("hindi me bolo") || lower.contains("hindi mein bolo") || lower.contains("shuddh hindi") || lower.contains("speak in hindi")) {
                            setVoiceLanguage("hindi")
                        } else if (lower.contains("english me bolo") || lower.contains("english mein bolo") || lower.contains("speak in english") || lower.contains("talk in english")) {
                            setVoiceLanguage("english")
                        } else if (lower.contains("hinglish me") || lower.contains("hinglish mein")) {
                            setVoiceLanguage("hinglish")
                        }
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
                        val completedTurn = LiveVoiceTurn(
                            userText = if (userTurn.isNotBlank()) userTurn else "Spoken query",
                            assistantText = assistantTurn
                        )
                        _turnCompletedFlow.tryEmit(completedTurn)
                        // Add to rolling context buffer for future turns
                        turnBuffer.add(completedTurn)
                        // Successful turn completion clears any pending interrupted context
                        interruptedContext = null
                        Log.d(TAG, "Turn complete — context buffer size=${turnBuffer.recentTurns().size}, interrupted context cleared")
                    }
                    currentUserTurnAccumulator.clear()
                    currentAssistantTurnAccumulator.clear()

                    val remainingPlayback = playbackEndTime - System.currentTimeMillis()
                    if (remainingPlayback > 0) {
                        _liveState.value = AuraLiveState.SPEAKING
                        scope.launch {
                            delay(remainingPlayback + 200L)
                            if (_liveState.value == AuraLiveState.SPEAKING) {
                                _amplitudeFlow.value = 0.05f
                                _liveState.value = AuraLiveState.LISTENING
                                Log.i(TAG, "Audio playback finished → back to LISTENING")
                            }
                        }
                    } else {
                        _amplitudeFlow.value = 0.05f
                        _liveState.value = AuraLiveState.LISTENING
                        Log.i(TAG, "Turn complete → back to LISTENING")
                    }
                }
            }

            // 2. Tool Calls
            if (root.has("toolCall")) {
                val toolCall = root.getAsJsonObject("toolCall")
                val fnCalls = toolCall.getAsJsonArray("functionCalls") ?: return
                _liveState.value = AuraLiveState.THINKING
                AuraDiagnostics.updateLive {
                    it.copy(
                        toolCallsHandled = it.toolCallsHandled + fnCalls.size(),
                        sessionState = AuraLiveState.THINKING
                    )
                }

                scope.launch {
                    val responseArray = JsonArray()
                    for (j in 0 until fnCalls.size()) {
                        val call = fnCalls.get(j).asJsonObject
                        val name = call.get("name").asString
                        val id = call.get("id").asString
                        val args = call.getAsJsonObject("args") ?: JsonObject()
                        val result = com.aura.assistant.execution.ExecutionEngine.executeTool(context, name, args, scope)
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
            // Clear context buffers — stale context should not bleed across sessions
            interruptedContext = null
            turnBuffer.clear()
            currentUserTurnAccumulator.clear()
            currentAssistantTurnAccumulator.clear()
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
