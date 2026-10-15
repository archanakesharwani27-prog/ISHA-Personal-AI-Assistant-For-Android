package com.aura.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.util.Log
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.aura.assistant.R

/**
 * Apple Dynamic Island style minimal top bubble for ISHA.
 *
 * - Appears as a compact pill at the top center of screen.
 * - Shows ONLY state (Listening/Speaking/Thinking) + animated audio wave.
 * - No cards, no chips, no large text, no screen preview.
 * - Instant barge-in: if user speaks while ISHA is talking, stop speech and listen immediately.
 * - Execute commands directly in background (no full app jump unless explicitly requested).
 * - Clean, minimal, professional assistant feel like Gemini.
 */
object IshaDynamicIsland {

    private const val TAG = "IshaDynamicIsland"
    private const val IDLE_DISMISS_MS = 12_000L // 12 seconds idle before auto-dismiss

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { hide() }

    // Audio wave visualizer
    private var waveView: WaveformView? = null
    private var micVolume: Float = 0f
    private var speakerVolume: Float = 0f

    // State indicators
    private var stateText: TextView? = null
    private var lotusIcon: ImageView? = null
    private var lotusSpinAnimator: ValueAnimator? = null

    // Background colors for states — Always AMOLED dark glass, with glowing accents
    private val DARK_BG_COLOR = Color.parseColor("#F508090C") // Pitch Black Glass
    private val STROKE_IDLE = Color.parseColor("#3300E5FF")
    private val STROKE_LISTENING = Color.parseColor("#9900E5FF") // Electric Cyan Glow
    private val STROKE_THINKING = Color.parseColor("#99A855F7")  // Neural Violet Glow
    private val STROKE_SPEAKING = Color.parseColor("#9910B981")  // Emerald Glow

    @Volatile private var currentReason: String = "wake"
    @Volatile var isAppInForeground = false

    /** Show the Dynamic Island bubble. Safe to call from any thread. */
    fun show(context: Context, reason: String = "wake") {
        if (isAppInForeground && reason != "shake") return
        handler.post { _show(context.applicationContext, reason) }
    }

    /** Real-time live state update from Gemini Live (Listening, Thinking, Speaking) */
    fun updateLiveState(ctx: Context? = null, state: com.aura.assistant.ai.AuraLiveState, amplitude: Float = 0f) {
        if (state == com.aura.assistant.ai.AuraLiveState.IDLE || state == com.aura.assistant.ai.AuraLiveState.ERROR) {
            handler.post { _hide() }
            return
        }
        if (isAppInForeground || overlayView == null) return

        handler.post {
            if (isAppInForeground || overlayView == null) return@post

            when (state) {
                com.aura.assistant.ai.AuraLiveState.LISTENING -> {
                    handler.removeCallbacks(dismissRunnable)
                    stateText?.text = "Listening..."
                    updateBackgroundColor("Listening")
                    startLotusSpin(1500L)
                    waveView?.setListening(true)
                    waveView?.updateAmplitude(amplitude)
                }
                com.aura.assistant.ai.AuraLiveState.THINKING -> {
                    handler.removeCallbacks(dismissRunnable)
                    stateText?.text = "Thinking..."
                    updateBackgroundColor("Thinking")
                    startLotusSpin(1000L)
                    waveView?.setThinking(true)
                }
                com.aura.assistant.ai.AuraLiveState.SPEAKING -> {
                    handler.removeCallbacks(dismissRunnable)
                    stateText?.text = "Speaking..."
                    updateBackgroundColor("Speaking")
                    startLotusSpin(2200L)
                    waveView?.setSpeaking(true)
                    waveView?.updateAmplitude(amplitude)
                }
                com.aura.assistant.ai.AuraLiveState.IDLE,
                com.aura.assistant.ai.AuraLiveState.ERROR -> {
                    stateText?.text = "ISHA 2.0"
                    updateBackgroundColor("Idle")
                    startLotusSpin(3500L)
                    waveView?.setListening(false)
                    resetDismissTimer()
                }
                else -> {
                    stateText?.text = "ISHA 2.0"
                    updateBackgroundColor("Idle")
                    startLotusSpin(3500L)
                    waveView?.setListening(false)
                }
            }
        }
    }

    /** Hide and remove from WindowManager. */
    fun hide() {
        handler.post { _hide() }
    }

    private fun _show(ctx: Context, reason: String) {
        // Always map 'wake' to 'voice_mode' so it functions purely as visual HUD for Gemini Live
        val effectiveReason = if (reason == "wake") "voice_mode" else reason
        currentReason = effectiveReason
        if (overlayView != null) {
            // Already showing - update reason
            updateStateFromReason(effectiveReason)
            if (effectiveReason != "screen_share" && effectiveReason != "voice_mode") {
                resetDismissTimer()
            } else {
                handler.removeCallbacks(dismissRunnable)
            }
            return
        }

        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        val overlay = buildDynamicIslandView(ctx, reason)
        overlayView = overlay

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val displayMetrics = ctx.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val pillWidth = if (reason == "screen_share") (screenWidth * 0.62).toInt() else (screenWidth * 0.48).toInt()
        val pillHeight = 28.dp(ctx) // Ultra-slim 28dp height

        val params = WindowManager.LayoutParams(
            pillWidth,
            pillHeight,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - pillWidth) / 2
            y = 12.dp(ctx) // Sleek position hugging top punch-hole
        }

        // Enable dragging so user can move pill anywhere on screen
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        overlay.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.hypot(dx.toDouble(), dy.toDouble()) > 10) {
                        isDragging = true
                        params.x = (initialX + dx).toInt().coerceIn(0, screenWidth - pillWidth)
                        params.y = (initialY + dy).toInt().coerceIn(0, displayMetrics.heightPixels - pillHeight)
                        try {
                            wm.updateViewLayout(overlay, params)
                        } catch (_: Exception) {}
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        true
                    } else {
                        view.performClick()
                        false
                    }
                }
                else -> false
            }
        }

        try {
            wm.addView(overlay, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add Dynamic Island overlay", e)
            overlayView = null
            return
        }

        // Fade-in animation
        overlay.alpha = 0f
        overlay.animate()
                .alpha(1f)
                .setDuration(180)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()

        // Set initial state based on reason
        updateStateFromReason(reason)

        // Waveform state based on reason
        if (reason == "voice_mode" || reason == "screen_share") {
            waveView?.setListening(true)
        } else {
            waveView?.setListening(false)
        }
        resetDismissTimer()
    }

    private fun _hide() {
        handler.removeCallbacks(dismissRunnable)
        lotusSpinAnimator?.cancel()
        lotusSpinAnimator = null
        lotusIcon?.clearAnimation()
        val ov = overlayView ?: return
        val wm = windowManager ?: return

        // Fade-out animation
        ov.animate()
                .alpha(0f)
                .setDuration(120)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    val appContext = ov.context.applicationContext
                    try { wm.removeView(ov) } catch (_: Exception) {}
                    overlayView = null
                    windowManager = null
                    resetState()
                    try {
                        val isVoiceActive = MainActivity.instance?.viewModel?.isVoiceModeActive?.value == true
                        if (!isVoiceActive) {
                            val resumeIntent = Intent(appContext, WakeWordService::class.java).apply {
                                action = WakeWordService.ACTION_RESUME
                            }
                            appContext.startService(resumeIntent)
                        } else {
                            Log.d(TAG, "Gemini Live voice active — skipping WakeWordService resume")
                        }
                    } catch (_: Exception) {}
                }.start()
    }

    private fun buildDynamicIslandView(ctx: Context, reason: String): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(10.dp(ctx), 1.dp(ctx), 10.dp(ctx), 1.dp(ctx))

            // Dynamic background with rounded corners
            setBackground(createDynamicBackground(ctx))
        }

        // Handle outside tap to dismiss + swipe-down to dismiss
        val gestureDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 != null && (e2.rawY - e1.rawY > 40.dp(ctx)) && velocityY > 150) {
                    hide()
                    return true
                }
                return false
            }
        })

        root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                hide()
                return@setOnTouchListener true
            }
            if (event.action == MotionEvent.ACTION_UP && event.y > root.height / 2f) {
                // Swipe down gesture
                hide()
                return@setOnTouchListener true
            }
            gestureDetector.onTouchEvent(event)
            false
        }

        // Tap: Bring MainActivity to foreground directly in Gemini Live voice mode
        root.setOnClickListener {
            try {
                val intent = Intent(ctx, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("open_voice_mode", true)
                }
                ctx.startActivity(intent)
                MainActivity.instance?.startVoiceModeDirectly()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch voice mode on tap", e)
            }
            hide()
        }

        // Miniature Neural Lotus brand icon with continuous rotation spin
        lotusIcon = ImageView(ctx).apply {
            setImageResource(R.mipmap.ic_launcher_round)
            layoutParams = LinearLayout.LayoutParams(18.dp(ctx), 18.dp(ctx))
        }
        startLotusSpin(3000L)

        // State indicator text (ultra-clean typography)
        stateText = TextView(ctx).apply {
            text = "ISHA 2.0"
            textSize = 10.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E2E8F0"))
        }

        // Slim Waveform visualizer
        waveView = WaveformView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(26.dp(ctx), 14.dp(ctx))
        }

        // Add views to root - horizontal layout: [lotus] [state] [waveform]
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun View.addMargin(h: Int, v: Int = 0) {
            val p = layoutParams as? ViewGroup.MarginLayoutParams
                ?: ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            p.setMargins(h, v, h, v)
            layoutParams = p
        }

        lotusIcon!!.addMargin(0.dp(ctx))
        stateText!!.addMargin(6.dp(ctx))
        waveView!!.addMargin(6.dp(ctx))

        content.addView(lotusIcon!!)
        content.addView(stateText!!)
        content.addView(waveView!!)

        if (reason == "screen_share") {
            val stopBtn = TextView(ctx).apply {
                text = "Stop"
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#EF4444"))
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 10.dp(ctx).toFloat()
                    setColor(Color.parseColor("#33EF4444"))
                    setStroke(1.dp(ctx), Color.parseColor("#80EF4444"))
                }
                background = bg
                setPadding(6.dp(ctx), 2.dp(ctx), 6.dp(ctx), 2.dp(ctx))
                setOnClickListener {
                    try {
                        val stopIntent = Intent("com.aura.screenshare.STOP")
                        ctx.sendBroadcast(stopIntent)
                    } catch (_: Exception) {}
                    hide()
                }
            }
            stopBtn.addMargin(4.dp(ctx))
            content.addView(stopBtn)
        }

        root.addView(content)

        return root
    }

    private fun createDynamicBackground(ctx: Context): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14.dp(ctx).toFloat()
            setColor(DARK_BG_COLOR)
            setStroke(1.dp(ctx), STROKE_IDLE)
        }
    }

    private fun updateBackgroundColor(state: String) {
        val strokeColor = when (state) {
            "Listening" -> STROKE_LISTENING
            "Thinking" -> STROKE_THINKING
            "Speaking" -> STROKE_SPEAKING
            else -> STROKE_IDLE
        }
        val background = overlayView?.background as? GradientDrawable
        background?.setColor(DARK_BG_COLOR)
        val ctx = overlayView?.context
        if (ctx != null) {
            background?.setStroke(1.dp(ctx), strokeColor)
        }
        background?.invalidateSelf()
    }

    private fun startLotusSpin(durationMs: Long = 3000L) {
        val icon = lotusIcon ?: return
        handler.post {
            lotusSpinAnimator?.cancel()
            lotusSpinAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = durationMs
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    val angle = anim.animatedValue as? Float ?: return@addUpdateListener
                    lotusIcon?.rotation = angle
                }
                start()
            }
        }
    }

    private fun updateStateFromReason(reason: String) {
        val stateText = when (reason) {
            "screen_share" -> "ISHA Viewing 👁️"
            "shake" -> "Listening..."
            "morning_reminder" -> "Good morning"
            "voice_mode" -> "ISHA Live 🎙️"
            "auto_overlay" -> "ISHA ✦"
            else -> "Listening..."
        }
        this.stateText?.text = stateText
        if (reason == "auto_overlay") {
            updateBackgroundColor("Idle")
            waveView?.setListening(false)
        } else {
            updateBackgroundColor("Listening")
        }
        if (reason == "screen_share" || reason == "voice_mode") {
            waveView?.setListening(true)
        }
    }

    private fun resetState() {
        lotusSpinAnimator?.cancel()
        lotusSpinAnimator = null
        lotusIcon?.clearAnimation()
        lotusIcon?.rotation = 0f
        stateText?.text = "ISHA"
        updateBackgroundColor("Idle")
        waveView?.reset()
    }


    private fun resetDismissTimer(durationMs: Long = IDLE_DISMISS_MS) {
        handler.removeCallbacks(dismissRunnable)
        val timeout = if (currentReason == "screen_share") 120_000L else durationMs
        handler.postDelayed(dismissRunnable, timeout)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Waveform Visualizer
    // ─────────────────────────────────────────────────────────────────────────────

    private class WaveformView(context: Context) : View(context) {
        private val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
        }
        private val barCount = 5
        private val barHeights = FloatArray(barCount) { 0.2f }
        private var isListening = false
        private var isThinking = false
        private var isSpeaking = false
        private val rng = java.util.Random()
        private val idleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val v = animator.animatedValue as Float
                // Gentle breathing for all bars
                val base = 0.15f + v * 0.15f
                for (i in barHeights.indices) {
                    barHeights[i] = base + (rng.nextFloat() - 0.5f) * 0.08f
                }
                invalidate()
            }
        }

        init {
            setBackgroundColor(Color.TRANSPARENT)
        }

        fun setListening(listening: Boolean) {
            isListening = listening
            if (!listening && !isThinking && !isSpeaking) {
                idleAnimator.start()
            } else {
                idleAnimator.end()
            }
        }

        fun setThinking(thinking: Boolean) {
            isThinking = thinking
            if (!isListening && !thinking && !isSpeaking) {
                idleAnimator.start()
            } else {
                idleAnimator.end()
            }
        }

        fun setSpeaking(speaking: Boolean) {
            isSpeaking = speaking
            if (!isListening && !isThinking && !speaking) {
                idleAnimator.start()
            } else {
                idleAnimator.end()
            }
        }

        fun updateAmplitude(amplitude: Float) {
            val clamped = amplitude.coerceIn(0f, 1f)
            for (i in barHeights.indices) {
                val jitter = (rng.nextFloat() - 0.5f) * 0.12f
                barHeights[i] = (clamped * 0.85f + 0.15f + jitter).coerceIn(0.15f, 1.0f)
            }
            invalidate()
        }

        fun reset() {
            isListening = false
            isThinking = false
            isSpeaking = false
            for (i in barHeights.indices) {
                barHeights[i] = 0.2f
            }
            idleAnimator.end()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val width = measuredWidth.toFloat()
            val height = measuredHeight.toFloat()
            val barWidth = width / (barCount * 1.6f)
            val spacing = (width - barWidth * barCount) / (barCount + 1)
            val startX = spacing

            for (i in barHeights.indices) {
                val heightRatio = barHeights[i]
                val barHeight = (height * heightRatio * 0.85f).coerceAtLeast(3f)
                val y = (height - barHeight) / 2f
                val x = startX + i * (barWidth + spacing)

                // Color based on state
                val color = when {
                    isListening -> Color.parseColor("#00E5FF") // Electric Cyan
                    isThinking -> Color.parseColor("#A855F7")  // Neural Violet
                    isSpeaking -> Color.parseColor("#10B981")  // Emerald Green
                    else -> Color.parseColor("#38BDF8")        // Sky Blue
                }
                paint.color = color

                val rect = RectF(x, y, x + barWidth, y + barHeight)
                canvas.drawRoundRect(rect, 2f, 2f, paint)
            }
        }

        // Update mic volume from audio callback
        fun setMicVolume(volume: Float) {
            micVolume = volume
            // Animate bars based on volume
            for (i in barHeights.indices) {
                val target = 0.1f + (volume * 0.9f)
                val noise = (rng.nextFloat() - 0.5f) * 0.2f
                barHeights[i] = (target + noise).coerceIn(0.05f, 1.0f)
            }
            invalidate()
        }

        // Update speaker volume from audio callback
        fun setSpeakerVolume(volume: Float) {
            speakerVolume = volume
            // For speaker, we could show different visualization
            // For now, just use same bars but maybe different color handling
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Utility extensions
    // ─────────────────────────────────────────────────────────────────────────────

    private fun Int.dp(context: Context): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}

/** Backward compatibility alias for AuraDynamicIsland */
val AuraDynamicIsland = IshaDynamicIsland