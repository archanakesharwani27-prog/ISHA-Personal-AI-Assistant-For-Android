package com.aura.assistant.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlin.math.sqrt

/**
 * Encapsulates a high-performance 16kHz mono PCM microphone capture session.
 *
 * Features:
 * - Coordinates mic access with [MicOwnershipManager]
 * - Attaches hardware AEC and AGC when available
 * - Evaluates VAD via [AuraVadDetector]
 * - Provides adaptive far-field gain (30-100cm distance)
 * - Safe retry initialization loop
 */
class AudioCaptureSession(
    private val context: Context,
    private val onChunk: (pcmBytes: ByteArray, length: Int) -> Unit,
    private val onAmplitude: (amplitude: Float) -> Unit,
    private val onBargeInDetected: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "AudioCaptureSession"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val CHUNK_SIZE_SHORTS = 640  // 40ms @ 16kHz
        const val CHUNK_SIZE_BYTES = CHUNK_SIZE_SHORTS * 2
    }

    private val vadDetector = AuraVadDetector()
    private var captureJob: Job? = null
    @Volatile private var isRecording = false

    @Volatile private var isMuted = false
    @Volatile private var isSpeakerActive = false
    private var bargeInConsecutiveFrames = 0

    val isActive: Boolean get() = isRecording

    fun setMuted(muted: Boolean) {
        isMuted = muted
        if (muted) {
            onAmplitude(0.0f)
        }
    }

    fun setSpeakerActive(active: Boolean) {
        isSpeakerActive = active
        if (!active) {
            bargeInConsecutiveFrames = 0
        }
    }

    fun start(scope: CoroutineScope): Boolean {
        if (isRecording) return true

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission missing")
            return false
        }

        if (!MicOwnershipManager.requestOwnership(MicOwner.GEMINI_LIVE)) {
            Log.w(TAG, "Mic ownership denied by MicOwnershipManager")
            return false
        }

        captureJob = scope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minBuf <= 0) {
                Log.e(TAG, "Invalid minBufferSize: $minBuf")
                MicOwnershipManager.releaseOwnership(MicOwner.GEMINI_LIVE)
                return@launch
            }
            val bufferSize = maxOf(minBuf * 4, 16384)

            val audioSources = listOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.DEFAULT
            )

            var record: AudioRecord? = null
            for (attempt in 1..3) {
                for (source in audioSources) {
                    try {
                        val r = AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
                        if (r.state == AudioRecord.STATE_INITIALIZED) {
                            record = r
                            Log.i(TAG, "AudioRecord initialized (attempt $attempt, source=$source, sessionId=${r.audioSessionId})")
                            break
                        } else {
                            r.release()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "AudioRecord init failed (attempt $attempt, source=$source): ${e.message}")
                    }
                }
                if (record != null) break
                delay(200)
            }

            if (record == null) {
                Log.e(TAG, "Failed to initialize AudioRecord after 3 attempts")
                MicOwnershipManager.releaseOwnership(MicOwner.GEMINI_LIVE)
                return@launch
            }

            isRecording = true

            try {
                // Attach AEC & AGC
                runCatching {
                    if (AcousticEchoCanceler.isAvailable()) {
                        AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
                    }
                }
                runCatching {
                    if (AutomaticGainControl.isAvailable()) {
                        AutomaticGainControl.create(record.audioSessionId)?.apply { enabled = true }
                    }
                }

                record.startRecording()
                Log.i(TAG, "AudioCaptureSession recording started")

                val shortBuf = ShortArray(CHUNK_SIZE_SHORTS)
                val byteBuf = ByteArray(CHUNK_SIZE_BYTES)

                while (isActive && isRecording) {
                    val readShorts = record.read(shortBuf, 0, shortBuf.size)
                    if (readShorts > 0) {
                        if (isMuted) {
                            onAmplitude(0.0f)
                            continue
                        }

                        // Calculate RMS energy
                        var sum = 0.0
                        for (i in 0 until readShorts) {
                            sum += (shortBuf[i].toLong() * shortBuf[i].toLong())
                        }
                        val rms = sqrt(sum / readShorts) / 32768.0

                        val vadResult = vadDetector.process(shortBuf, readShorts)

                        // Barge-in check when speaker is active
                        if (isSpeakerActive) {
                            if ((vadResult.isSpeech && rms > 0.06) || rms > 0.12) {
                                bargeInConsecutiveFrames++
                                if (bargeInConsecutiveFrames >= 2) {
                                    Log.i(TAG, "Barge-in speech confirmed (RMS=$rms)")
                                    bargeInConsecutiveFrames = 0
                                    onBargeInDetected?.invoke()
                                }
                            } else {
                                bargeInConsecutiveFrames = 0
                            }
                            continue
                        }

                        // Far-field adaptive gain
                        val adaptiveGain = when {
                            rms < 0.020 -> 3.5f
                            rms < 0.050 -> 2.6f
                            rms < 0.100 -> 1.8f
                            else -> 1.3f
                        }

                        for (i in 0 until readShorts) {
                            val sample = (shortBuf[i] * adaptiveGain).toInt().coerceIn(-32768, 32767)
                            byteBuf[i * 2] = (sample and 0xFF).toByte()
                            byteBuf[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                        }

                        val amplitude = if (vadResult.isSpeech) {
                            vadResult.normalizedEnergy.coerceIn(0.15f, 1.0f)
                        } else {
                            (rms * 4.0).toFloat().coerceIn(0.02f, 0.15f)
                        }
                        onAmplitude(amplitude)

                        onChunk(byteBuf, readShorts * 2)
                    } else if (readShorts < 0) {
                        Log.e(TAG, "AudioRecord read error: $readShorts")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in AudioCaptureSession loop", e)
            } finally {
                isRecording = false
                runCatching { record.stop(); record.release() }
                MicOwnershipManager.releaseOwnership(MicOwner.GEMINI_LIVE)
                Log.i(TAG, "AudioCaptureSession stopped and mic released")
            }
        }
        return true
    }

    fun stop() {
        isRecording = false
        captureJob?.cancel()
        captureJob = null
        MicOwnershipManager.releaseOwnership(MicOwner.GEMINI_LIVE)
    }
}
