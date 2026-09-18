package com.aura.assistant.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
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
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.sqrt

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
        // Official low-latency Live Audio API model (verified working)
        private const val DEFAULT_LIVE_MODEL = "models/gemini-3.1-flash-live-preview"
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

    private val _turnCompletedFlow = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val turnCompletedFlow: SharedFlow<String> = _turnCompletedFlow.asSharedFlow()

    private val currentTurnTextAccumulator = StringBuilder()

    private val vadDetector = AuraVadDetector()
    private var micJob: Job? = null
    @Volatile private var isMicRecordingActive = false

    // Adaptive noise floor for speaker barge-in
    private var noiseFloor = 0.02

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
    }

    /**
     * Connects to Gemini Live using the primary verified API key.
     */
    fun connect() {
        if (_liveState.value == AuraLiveState.CONNECTED ||
            _liveState.value == AuraLiveState.CONNECTING ||
            _liveState.value == AuraLiveState.LISTENING ||
            _liveState.value == AuraLiveState.SPEAKING) return

        _liveState.value = AuraLiveState.CONNECTING
        val key = Secrets.getActiveGeminiKey()
        val url = "$BASE_WS_URL?key=$key"
        val request = Request.Builder().url(url).build()

        Log.i(TAG, "Connecting to Gemini Live WS with model: $DEFAULT_LIVE_MODEL")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected successfully to Gemini Live")
                _liveState.value = AuraLiveState.CONNECTED
                sendSetupMessage()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code / $reason")
                _liveState.value = AuraLiveState.IDLE
                stopMicRecording()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                Log.e(TAG, "WebSocket failure: ${t.message}, HTTP=$code")
                _liveState.value = AuraLiveState.ERROR
                stopMicRecording()
            }
        })
    }

    private fun sendSetupMessage() {
        val setupObj = JsonObject().apply {
            val setup = JsonObject().apply {
                addProperty("model", DEFAULT_LIVE_MODEL)

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

                // Explicitly enable input and output audio transcriptions
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
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = maxOf(minBuf, 2048)

            var record: AudioRecord? = null
            try {
                isMicRecordingActive = true
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate, channelConfig, audioFormat, bufferSize
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate, channelConfig, audioFormat, bufferSize
                    )
                }
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "Failed to initialize AudioRecord")
                    return@launch
                }

                record.startRecording()
                val shortBuf = ShortArray(512)
                val byteBuf = ByteArray(1024)

                while (isActive && isMicRecordingActive && webSocket != null) {
                    val readShorts = record.read(shortBuf, 0, shortBuf.size)
                    if (readShorts > 0) {
                        if (isMuted) {
                            _amplitudeFlow.value = 0.0f
                            continue
                        }

                        // Compute local energy for visualizer
                        var sum = 0.0
                        for (i in 0 until readShorts) {
                            sum += (shortBuf[i] * shortBuf[i])
                        }
                        val rms = sqrt(sum / readShorts) / 32768.0

                        val isBotSpeaking = _liveState.value == AuraLiveState.SPEAKING
                        if (!isBotSpeaking) {
                            _amplitudeFlow.value = (rms * 3.0).toFloat().coerceIn(0.05f, 1.0f)
                        }

                        // Barge-in check: if user speaks loudly while bot is talking, interrupt instantly
                        if (isBotSpeaking) {
                            val bargeThreshold = max(0.04, noiseFloor * 1.8)
                            if (rms > bargeThreshold) {
                                Log.i(TAG, "User barge-in detected! Stopping audio playback.")
                                flushAudioPlayback()
                                _liveState.value = AuraLiveState.LISTENING
                            } else {
                                // Track noise floor and suppress sending chunk while bot is producing sound to prevent echo
                                noiseFloor = noiseFloor * 0.95 + rms * 0.05
                                continue
                            }
                        }

                        // Convert short buffer to byte buffer
                        for (i in 0 until readShorts) {
                            val s = shortBuf[i].toInt()
                            byteBuf[i * 2] = (s and 0xFF).toByte()
                            byteBuf[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                        }

                        // Continuously stream 16kHz audio to Gemini Live
                        sendAudioChunk(byteBuf, readShorts * 2)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in mic recording loop", e)
            } finally {
                try { record?.stop(); record?.release() } catch (_: Exception) {}
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
                            val part = JsonObject().apply { addProperty("text", text) }
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
    }

    /**
     * Streams raw 16kHz mono PCM audio to Gemini Live in official camelCase schema.
     */
    fun sendAudioChunk(pcmBytes: ByteArray, length: Int = pcmBytes.size) {
        if (_liveState.value != AuraLiveState.LISTENING &&
            _liveState.value != AuraLiveState.CONNECTED) return

        val base64Data = Base64.encodeToString(pcmBytes, 0, length, Base64.NO_WRAP)
        val inputObj = JsonObject().apply {
            val realtime = JsonObject().apply {
                val chunks = JsonArray().apply {
                    val chunk = JsonObject().apply {
                        addProperty("mimeType", "audio/pcm;rate=16000")
                        addProperty("data", base64Data)
                    }
                    add(chunk)
                }
                add("mediaChunks", chunks)
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
                    _liveState.value = AuraLiveState.LISTENING
                    return
                }

                // Spoken transcriptions
                if (serverContent.has("inputTranscription")) {
                    val inTx = serverContent.getAsJsonObject("inputTranscription")
                    val tx = inTx.get("text")?.asString ?: ""
                    if (tx.isNotBlank()) {
                        _transcriptFlow.value = tx
                        if (_liveState.value != AuraLiveState.SPEAKING) {
                            _liveState.value = AuraLiveState.THINKING
                        }
                    }
                }

                if (serverContent.has("outputTranscription")) {
                    val outTx = serverContent.getAsJsonObject("outputTranscription")
                    val tx = outTx.get("text")?.asString ?: ""
                    if (tx.isNotBlank()) {
                        _transcriptFlow.value = tx
                        currentTurnTextAccumulator.append(tx).append(" ")
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
                    val fullTurn = currentTurnTextAccumulator.toString().trim()
                    if (fullTurn.isNotBlank()) {
                        _turnCompletedFlow.tryEmit(fullTurn)
                    }
                    currentTurnTextAccumulator.clear()
                    _liveState.value = AuraLiveState.LISTENING
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
        scope.cancel()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }
}
