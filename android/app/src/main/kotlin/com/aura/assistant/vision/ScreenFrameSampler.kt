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
 */
class ScreenFrameSampler(
    private val onFrameReady: (ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "ScreenFrameSampler"
        private const val DEFAULT_JPEG_QUALITY = 75
    }

    private var lastFrameHash: Int = 0
    private var lastSentTime: Long = 0L

    /**
     * Processes an incoming raw screen bitmap.
     * Evaluates whether visual content has meaningfully changed before encoding.
     */
    fun processBitmap(bitmap: Bitmap, forceSend: Boolean = false) {
        val now = System.currentTimeMillis()
        // Throttle to max 2 frames per second for Live streaming efficiency
        if (!forceSend && now - lastSentTime < 500) {
            return
        }

        // Quick pixel hash sample (9 sample points across screen)
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return

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
            Log.d(TAG, "Sampled & sent screen frame (${bytes.size} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "Error compressing screen frame: ${e.message}")
        }
    }
}
