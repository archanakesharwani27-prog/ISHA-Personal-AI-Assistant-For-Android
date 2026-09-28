package com.aura.assistant.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * Dedicated 24kHz mono 16-bit PCM audio playback session for Gemini Live audio responses.
 *
 * Provides ultra-low latency streaming, non-blocking queueing, instant flush on barge-in,
 * and amplitude calculation for visualizers.
 */
class AudioPlaybackSession(
    private val onAmplitude: ((Float) -> Unit)? = null
) {
    companion object {
        private const val TAG = "AudioPlaybackSession"
        const val SAMPLE_RATE = 24000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioTrack: AudioTrack? = null
    private val audioBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    private val playbackChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private var workerJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    @Volatile
    private var playbackEndTime = 0L

    init {
        initAudioTrack()
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
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(audioBufferSize * 2, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack", e)
        }
    }

    fun startWorker(scope: CoroutineScope) {
        workerJob?.cancel()
        workerJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val chunk = playbackChannel.receiveCatching().getOrNull() ?: break
                try {
                    val track = audioTrack ?: continue
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        track.play()
                    }
                    _isPlaying.value = true
                    track.write(chunk, 0, chunk.size)

                    // Compute RMS amplitude for visualizer
                    if (onAmplitude != null && chunk.isNotEmpty()) {
                        var sum = 0.0
                        val numShorts = chunk.size / 2
                        for (i in 0 until numShorts) {
                            val lo = chunk[i * 2].toInt() and 0xFF
                            val hi = chunk[i * 2 + 1].toInt()
                            val sample = (hi shl 8) or lo
                            sum += (sample * sample)
                        }
                        if (numShorts > 0) {
                            val rms = sqrt(sum / numShorts)
                            val amp = (rms / 12000.0).toFloat().coerceIn(0.15f, 1.0f)
                            onAmplitude.invoke(amp)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing audio chunk to AudioTrack", e)
                } finally {
                    if (playbackChannel.isEmpty) {
                        _isPlaying.value = false
                    }
                }
            }
        }
    }

    /**
     * Enqueues a chunk of 24kHz PCM bytes for playback.
     */
    fun enqueue(pcmBytes: ByteArray) {
        val chunkDurationMs = (pcmBytes.size.toLong() * 1000L) / (SAMPLE_RATE * 2L)
        val now = System.currentTimeMillis()
        playbackEndTime = maxOf(playbackEndTime, now) + chunkDurationMs
        playbackChannel.trySend(pcmBytes)
    }

    /**
     * Instantly flushes pending and currently playing audio.
     * Crucial for ChatGPT-style barge-in when user starts speaking.
     */
    fun flush() {
        while (playbackChannel.tryReceive().isSuccess) {}
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (_: Exception) {}
        _isPlaying.value = false
        onAmplitude?.invoke(0.0f)
    }

    fun release() {
        workerJob?.cancel()
        workerJob = null
        flush()
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }
}
