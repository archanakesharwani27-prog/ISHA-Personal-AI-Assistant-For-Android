package com.aura.assistant.vision

import android.graphics.Bitmap
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Adaptive Screen Frame Sampler for AURA 2.0.
 * Sections 18 & 19 of 2.0.md.
 *
 * Implements change detection and adaptive compression to minimize bandwidth,
 * latency, and token consumption when streaming screen or camera to Gemini Live.
 *
 * Supports both raw [Bitmap] input (processsBitmap) and pre-compressed JPEG bytes
 * (processJpeg) so it can be used at every screen-capture callsite regardless of
 * which format the capture API produces.
 */
class ScreenFrameSampler(
    private val onFrameReady: (ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "ScreenFrameSampler"
        private const val DEFAULT_JPEG_QUALITY = 75
        /** Min interval between sent frames (500ms = max 2fps). */
        private const val MIN_INTERVAL_MS = 500L
    }

    @Volatile private var lastFrameHash: Int = 0
    @Volatile private var lastSentTime: Long = 0L

    // ─── Bitmap path ────────────────────────────────────────────────────────

    /**
     * Processes an incoming raw screen bitmap.
     * Drops frames that are visually identical or too frequent.
     *
     * @param bitmap  The screen/camera frame. Must not be recycled before return.
     * @param forceSend  Bypass change-detection; always encode & send.
     */
    fun processBitmap(bitmap: Bitmap, forceSend: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!forceSend && now - lastSentTime < MIN_INTERVAL_MS) return

        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return

        // Quick 3-point pixel hash to detect visual changes
        val sampleHash = (
            bitmap.getPixel(w / 4, h / 4) xor
            bitmap.getPixel(w / 2, h / 2) xor
            bitmap.getPixel((3 * w) / 4, (3 * h) / 4)
        )

        if (!forceSend && sampleHash == lastFrameHash) {
            // Visuals haven't changed — drop frame to save tokens & battery
            return
        }

        lastFrameHash = sampleHash
        lastSentTime = now

        try {
            val bos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, DEFAULT_JPEG_QUALITY, bos)
            val bytes = bos.toByteArray()
            onFrameReady(bytes)
            Log.d(TAG, "processBitmap: sent ${bytes.size} bytes")
        } catch (e: Exception) {
            Log.w(TAG, "Error compressing bitmap frame: ${e.message}")
        }
    }

    // ─── Pre-compressed JPEG path ────────────────────────────────────────────

    /**
     * Processes pre-compressed JPEG bytes (e.g. from [AuraAccessibilityService.takeOptimizedScreenCapture]).
     * Uses a fast content-hash of 8 evenly-spaced byte samples for change detection.
     *
     * @param jpegBytes  The JPEG-compressed screen frame.
     * @param forceSend  Bypass change-detection; always forward the bytes.
     */
    fun processJpeg(jpegBytes: ByteArray, forceSend: Boolean = false) {
        if (jpegBytes.isEmpty()) return
        val now = System.currentTimeMillis()
        if (!forceSend && now - lastSentTime < MIN_INTERVAL_MS) return

        // Lightweight content fingerprint: sample 8 bytes spread across the payload
        val step = maxOf(1, jpegBytes.size / 8)
        var hash = jpegBytes.size
        for (i in 0 until 8) {
            val idx = (i * step).coerceAtMost(jpegBytes.size - 1)
            hash = hash * 31 + jpegBytes[idx].toInt()
        }

        if (!forceSend && hash == lastFrameHash) {
            // Content unchanged — skip to save tokens
            return
        }

        lastFrameHash = hash
        lastSentTime = now

        onFrameReady(jpegBytes)
        Log.d(TAG, "processJpeg: sent ${jpegBytes.size} bytes")
    }
}
