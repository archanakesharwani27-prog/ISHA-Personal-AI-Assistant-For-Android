package com.aura.assistant.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * High-performance, zero-allocation Voice Activity Detector (VAD) for ISHA.
 *
 * Implements a dynamic noise-floor tracking energy detector with
 * zero-crossing rate (ZCR) feature gating.
 *
 * Purpose:
 *   - Drops CPU usage to ~0% when the room is silent or has static background noise.
 *   - Prevents openWakeWord neural network from running inference on empty air.
 *   - Provides real-time normalized audio amplitudes (0.0 to 1.0) for the UI visualizer orb.
 */
class IshaVadDetector(
    private val sampleRate: Int = 16000,
    private val minSpeechEnergyDb: Float = -62.0f,   // Far-field sensitivity (picks up natural speech at 30-70 cm)
    private val speechMarginDb: Float = 2.5f,        // 2.5 dB above ambient room noise
    private val hangoverFrames: Int = 15             // Keeps gate open for ~450ms after speech ends
) {
    // Dynamic noise floor estimate (slow moving average)
    private var noiseFloorDb: Float = -62.0f
    private val noiseFloorAlpha = 0.04f // Slower adaptation so speech isn't absorbed into floor

    // Hangover counter to prevent cutting off words
    private var hangoverCounter = 0

    data class VadResult(
        val isSpeech: Boolean,
        val rmsDb: Float,
        val normalizedEnergy: Float // 0.0f to 1.0f for Orb visualizer
    )

    /**
     * Process 16-bit PCM samples in ShortArray.
     */
    fun process(samples: ShortArray, length: Int = samples.size): VadResult {
        if (length <= 0) {
            return VadResult(isSpeech = false, rmsDb = -90f, normalizedEnergy = 0f)
        }

        var sumSquares = 0.0
        var zeroCrossings = 0
        var prevSign = samples[0] >= 0

        for (i in 0 until length) {
            val sample = samples[i]
            sumSquares += (sample.toLong() * sample.toLong())
            val currentSign = sample >= 0
            if (currentSign != prevSign) {
                zeroCrossings++
                prevSign = currentSign
            }
        }

        val rms = sqrt(sumSquares / length)
        // Convert to dBFS (0 dBFS is maximum possible 16-bit audio)
        val rmsDb = if (rms > 0.0) {
            (20.0 * log10(rms / 32768.0)).toFloat().coerceIn(-90.0f, 0.0f)
        } else {
            -90.0f
        }

        val zcr = zeroCrossings.toFloat() / length

        // Check if raw energy is above dynamic noise floor + margin AND above absolute minimum
        val energyAboveFloor = rmsDb - noiseFloorDb
        val rawSpeechDetected = (rmsDb > minSpeechEnergyDb) && 
                                (energyAboveFloor > speechMarginDb) && 
                                (zcr in 0.01f..0.75f)

        if (rawSpeechDetected) {
            hangoverCounter = hangoverFrames
        } else {
            if (hangoverCounter > 0) {
                hangoverCounter--
            }
            // Adapt noise floor only during silence
            noiseFloorDb = (1.0f - noiseFloorAlpha) * noiseFloorDb + noiseFloorAlpha * rmsDb
            noiseFloorDb = noiseFloorDb.coerceIn(-75.0f, -30.0f)
        }

        val isSpeech = hangoverCounter > 0

        // Normalized energy: map [-65 dB, -10 dB] to [0.0, 1.0] for glowing orb pulsing
        val normalizedEnergy = ((rmsDb + 65.0f) / 55.0f).coerceIn(0.0f, 1.0f)

        return VadResult(
            isSpeech = isSpeech,
            rmsDb = rmsDb,
            normalizedEnergy = normalizedEnergy
        )
    }

    fun reset() {
        noiseFloorDb = -62.0f
        hangoverCounter = 0
    }
}

/** Backward compatibility alias for AuraVadDetector */
typealias AuraVadDetector = IshaVadDetector

