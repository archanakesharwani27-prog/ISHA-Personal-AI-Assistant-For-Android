package com.aura.assistant.wakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import android.util.Log
import kotlin.math.*

/**
 * HeyIshaDetector — Standalone "Hey Isha" wake-word detector.
 *
 * Runs the custom 12.7 KB WakeWordNet ONNX model directly via ONNX Runtime.
 * Bypasses the openWakeWord/rementia library pipeline entirely.
 *
 * Pipeline:
 *   Raw PCM (16 kHz mono) → Mel Filterbank (128 bins) → hey_isha.onnx → score [0..1]
 *
 * Model architecture (WakeWordNet):
 *   Input:  float[1][128]  — 128-dim mel features
 *   Hidden: 128 → 128 → 64 → 32
 *   Output: float[1][1]    — sigmoid activation score
 *
 * Thread safety: Call [process] from a single background thread (or synchronise externally).
 */
class HeyIshaDetector(
    private val assetManager: AssetManager,
    modelPath: String = "wakeword/hey_isha.onnx"
) : AutoCloseable {

    companion object {
        private const val TAG = "HeyIshaDetector"

        // ── Audio config (must match training) ──────────────────────────────────
        const val SAMPLE_RATE   = 16_000
        const val FRAME_SIZE    = 512           // ~32 ms @ 16 kHz — same as WakeWordService

        // ── Mel-filterbank config (must match training) ─────────────────────────
        private const val N_MELS      = 128     // FEATURE_DIM in Colab notebook
        private const val FFT_SIZE    = 512     // FFT window
        private const val HOP_SIZE    = 160     // 10 ms hop
        private const val F_MIN       = 0f      // Minimum frequency (Hz)
        private const val F_MAX       = 8_000f  // Nyquist for 16 kHz

        // ── Detection thresholds (calibrated) ───────────────────────────────────
        const val MIN_SCORE             = 0.70f  // Minimum per-frame score to start counting
        const val HIGH_CONFIDENCE_SCORE = 0.88f  // High-confidence threshold (requires at least 2 frames)
        const val CONFIRM_FRAMES        = 4      // Consecutive frames needed at MIN_SCORE (~320ms)
        const val HIGH_CONF_FRAMES      = 2      // Consecutive frames needed even for high confidence
    }

    // ── ONNX Runtime session ─────────────────────────────────────────────────────

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    // ── Ring buffer — accumulates PCM frames for mel computation ─────────────────
    // We compute one mel vector per FRAME_SIZE samples (analogous to a single hop).
    private val ringBuffer = ShortArray(FFT_SIZE)
    private var ringWritePos = 0
    private var ringFilled = false

    // ── Pre-computed mel filterbank matrix [N_MELS × (FFT_SIZE/2 + 1)] ───────────
    private val melFilterbank: Array<FloatArray>

    init {
        // Load model from assets
        val modelBytes = assetManager.open(modelPath).use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)  // Single thread — minimal CPU for background service
        }
        session = env.createSession(modelBytes, opts)
        Log.i(TAG, "hey_isha.onnx loaded — inputs=${session.inputNames}, outputs=${session.outputNames}")

        // Build mel filterbank
        melFilterbank = buildMelFilterbank(N_MELS, FFT_SIZE, SAMPLE_RATE, F_MIN, F_MAX)
        Log.i(TAG, "Mel filterbank built: ${N_MELS} bands × ${FFT_SIZE / 2 + 1} FFT bins")
    }

    // ── Public API ────────────────────────────────────────────────────────────────

    /**
     * Feed a chunk of raw PCM samples (16-bit signed, 16 kHz, mono) into the detector.
     *
     * Returns the model confidence score in [0.0, 1.0].
     * Returns -1f if we do not have enough samples yet to run inference.
     */
    fun process(pcm: ShortArray): Float {
        // Write incoming samples into the ring buffer
        for (sample in pcm) {
            ringBuffer[ringWritePos] = sample
            ringWritePos = (ringWritePos + 1) % FFT_SIZE
            if (ringWritePos == 0) ringFilled = true
        }
        if (!ringFilled && ringWritePos < FFT_SIZE) return -1f

        // Read full frame in order
        val frame = FloatArray(FFT_SIZE) { i ->
            val idx = (ringWritePos + i) % FFT_SIZE
            ringBuffer[idx] / 32768f   // Normalise to [-1, 1]
        }

        // Compute mel features for this frame
        val melFeatures = computeMelFeatures(frame)   // FloatArray(N_MELS)

        // Run ONNX inference
        return runInference(melFeatures)
    }

    /**
     * Reset internal ring buffer (call after a wake trigger or engine restart).
     */
    fun reset() {
        ringBuffer.fill(0)
        ringWritePos = 0
        ringFilled = false
    }

    override fun close() {
        runCatching { session.close() }
        runCatching { env.close() }
    }

    // ── Signal Processing ─────────────────────────────────────────────────────────

    /**
     * Compute 128-dim log mel filterbank energy for one FFT_SIZE-sample frame.
     * Uses Hann window + magnitude spectrum + mel filterbank + log compression.
     */
    private fun computeMelFeatures(frame: FloatArray): FloatArray {
        val n = FFT_SIZE

        // 1. Apply Hann window
        val windowed = FloatArray(n) { i ->
            frame[i] * (0.5f - 0.5f * cos(2.0 * PI * i / (n - 1))).toFloat()
        }

        // 2. Compute FFT magnitude spectrum (using Cooley-Tukey radix-2)
        val re = windowed.copyOf()
        val im = FloatArray(n)
        fftInPlace(re, im, n)

        // 3. Power spectrum — only positive frequencies (n/2 + 1 bins)
        val nBins = n / 2 + 1
        val powerSpec = FloatArray(nBins) { i -> re[i] * re[i] + im[i] * im[i] }

        // 4. Apply mel filterbank → sum energy in each mel band
        val melEnergy = FloatArray(N_MELS) { m ->
            var energy = 0f
            val filter = melFilterbank[m]
            for (bin in filter.indices) {
                energy += filter[bin] * powerSpec[bin]
            }
            energy
        }

        // 5. Log compression (same as librosa default)
        return FloatArray(N_MELS) { m ->
            ln((melEnergy[m] + 1e-9f).toDouble()).toFloat()
        }
    }

    /**
     * Radix-2 Cooley-Tukey FFT (in-place, power-of-2 length).
     * Re and Im are modified in-place.
     */
    private fun fftInPlace(re: FloatArray, im: FloatArray, n: Int) {
        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        // Butterfly stages
        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val ang = -2.0 * PI / len
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f; var curIm = 0f
                for (k in 0 until halfLen) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + halfLen] * curRe - im[i + k + halfLen] * curIm
                    val vIm = re[i + k + halfLen] * curIm + im[i + k + halfLen] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + halfLen] = uRe - vRe
                    im[i + k + halfLen] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * Build mel filterbank matrix.
     * Returns float[N_MELS][nBins] where each row is a triangular filter weight vector.
     */
    private fun buildMelFilterbank(
        nMels: Int,
        fftSize: Int,
        sampleRate: Int,
        fMin: Float,
        fMax: Float
    ): Array<FloatArray> {
        val nBins = fftSize / 2 + 1

        fun hzToMel(hz: Float): Float = 2595f * log10(1f + hz / 700f)
        fun melToHz(mel: Float): Float = 700f * (10f.pow(mel / 2595f) - 1f)

        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)

        // nMels + 2 evenly spaced mel points (includes endpoints)
        val melPoints = FloatArray(nMels + 2) { i ->
            melMin + i * (melMax - melMin) / (nMels + 1)
        }
        // Convert to FFT bin indices
        val binPoints = FloatArray(nMels + 2) { i ->
            floor((fftSize + 1) * melToHz(melPoints[i]) / sampleRate).toFloat()
        }

        return Array(nMels) { m ->
            val filter = FloatArray(nBins)
            val start = binPoints[m].toInt()
            val center = binPoints[m + 1].toInt()
            val end = binPoints[m + 2].toInt()
            for (bin in start until center) {
                if (bin < nBins) {
                    val denom = (binPoints[m + 1] - binPoints[m]).coerceAtLeast(1f)
                    filter[bin] = (bin - binPoints[m]) / denom
                }
            }
            for (bin in center until end) {
                if (bin < nBins) {
                    val denom = (binPoints[m + 2] - binPoints[m + 1]).coerceAtLeast(1f)
                    filter[bin] = (binPoints[m + 2] - bin) / denom
                }
            }
            filter
        }
    }

    // ── ONNX Inference ────────────────────────────────────────────────────────────

    private fun runInference(melFeatures: FloatArray): Float {
        return try {
            val inputData = Array(1) { melFeatures }
            val tensor = OnnxTensor.createTensor(env, inputData)
            val inputName = session.inputNames.iterator().next()
            val results = session.run(mapOf(inputName to tensor))
            val outputTensor = results[0].value

            // Output shape: [1, 1] or [1] — extract float score
            val score = when (outputTensor) {
                is Array<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    (outputTensor as Array<FloatArray>)[0][0]
                }
                is FloatArray -> outputTensor[0]
                else -> {
                    Log.w(TAG, "Unexpected output type: ${outputTensor?.javaClass?.name}")
                    0f
                }
            }

            tensor.close()
            results.close()
            score
        } catch (e: Exception) {
            Log.e(TAG, "ONNX inference error: ${e.message}", e)
            0f
        }
    }
}
