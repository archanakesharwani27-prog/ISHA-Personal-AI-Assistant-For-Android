package com.aura.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import java.util.Locale
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.LinearLayout
import android.widget.TextView
import com.aura.assistant.ai.GeminiChatService
import com.aura.assistant.data.ChatMessage

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
    private const val LISTENING_ANIM_MS = 600L  // Wave animation duration
    private const val EXPAND_COLLAPSE_MS = 200L // Expand/collapse animation duration

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { hide() }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningSpeech = false
    private var isSpeaking = false
    private var isThinking = false
    private var isExpanded = false

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    // Audio wave visualizer
    private var waveView: WaveformView? = null
    private var micVolume: Float = 0f
    private var speakerVolume: Float = 0f

    // State indicators
    private var stateText: TextView? = null
    private var sparkleIcon: TextView? = null

    // Background colors for states — Always AMOLED dark glass, with glowing accents
    private val DARK_BG_COLOR = Color.parseColor("#F50B0B0E") // Pitch Black Glass
    private val STROKE_IDLE = Color.parseColor("#33FFFFFF")
    private val STROKE_LISTENING = Color.parseColor("#8038BDF8") // Subtle Sky Blue Glow
    private val STROKE_THINKING = Color.parseColor("#80A855F7")  // Subtle Violet Glow
    private val STROKE_SPEAKING = Color.parseColor("#8010B981")  // Subtle Emerald Glow

    private var islandTts: android.speech.tts.TextToSpeech? = null
    private var isIslandTtsReady = false

    private fun initTts(ctx: Context) {
        // Disabled: User strictly uses Gemini Live Bidirectional Voice duplex ("Aoede")
    }

    private fun speakText(text: String) {
        // Suppressed: All assistant voice output is handled via 24kHz PCM duplex by GeminiLiveClient
        Log.d(TAG, "Legacy TTS speakText suppressed in favor of Gemini Live: $text")
    }

    @Volatile private var currentReason: String = "wake"
    @Volatile var isAppInForeground = false

    /** Show the Dynamic Island bubble. Safe to call from any thread. */
    fun show(context: Context, reason: String = "wake") {
        if (isAppInForeground && reason != "shake") return
        handler.post { _show(context.applicationContext, reason) }
    }

    /** Real-time live state update from Gemini Live (Listening, Thinking, Speaking) */
    fun updateLiveState(ctx: Context? = null, state: com.aura.assistant.ai.AuraLiveState, amplitude: Float = 0f) {
        handler.post {
            if (isAppInForeground) return@post
            val targetCtx = ctx ?: overlayView?.context
            // If Aura is engaged in background and island is not visible, spawn it immediately
            if (overlayView == null && targetCtx != null && state in listOf(
                    com.aura.assistant.ai.AuraLiveState.LISTENING,
                    com.aura.assistant.ai.AuraLiveState.THINKING,
                    com.aura.assistant.ai.AuraLiveState.SPEAKING,
                    com.aura.assistant.ai.AuraLiveState.CONNECTED
                )) {
                if (android.provider.Settings.canDrawOverlays(targetCtx)) {
                    _show(targetCtx, "voice_mode")
                }
            }
            if (overlayView == null) return@post

            when (state) {
                com.aura.assistant.ai.AuraLiveState.LISTENING -> {
                    handler.removeCallbacks(dismissRunnable)
                    stateText?.text = "Listening... 🎙️"
                    updateBackgroundColor("Listening")
                    sparkleIcon?.setTextColor(Color.parseColor("#38BDF8"))
                    waveView?.setListening(true)
                    waveView?.updateAmplitude(amplitude)
                }
                com.aura.assistant.ai.AuraLiveState.THINKING -> {
                    handler.removeCallbacks(dismissRunnable)
                    stateText?.text = "Thinking... 🧠"
                    updateBackgroundColor("Thinking")
                    sparkleIcon?.setTextColor(Color.parseColor("#A855F7"))
                    waveView?.setListening(false)
                }
                com.aura.assistant.ai.AuraLiveState.SPEAKING -> {
                    handler.removeCallbacks(dismissRunnable)
                    stateText?.text = "Speaking... 💬"
                    updateBackgroundColor("Speaking")
                    sparkleIcon?.setTextColor(Color.parseColor("#10B981"))
                    waveView?.setListening(true)
                    waveView?.updateAmplitude(amplitude)
                }
                com.aura.assistant.ai.AuraLiveState.IDLE,
                com.aura.assistant.ai.AuraLiveState.ERROR -> {
                    stateText?.text = "ISHA ✦"
                    updateBackgroundColor("Idle")
                    waveView?.setListening(false)
                    resetDismissTimer()
                }
                else -> {
                    stateText?.text = "ISHA Live ✦"
                    updateBackgroundColor("Idle")
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
        val pillWidth = if (reason == "screen_share") (screenWidth * 0.70).toInt() else (screenWidth * 0.45).toInt()
        val pillHeight = 48.dp(ctx) // Compact height

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
            y = 52.dp(ctx) // Positioned comfortably below camera cutout
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
        stopSpeechListening()
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
            setPadding(16.dp(ctx), 8.dp(ctx), 16.dp(ctx), 8.dp(ctx))

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

        // State indicator text (small)
        stateText = TextView(ctx).apply {
            text = "ISHA"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E2E8F0"))
        }

        // Sparkle icon for visual interest
        sparkleIcon = TextView(ctx).apply {
            text = "✦"
            textSize = 14f
            setTextColor(Color.parseColor("#38BDF8"))
            // Gentle pulse animation
            val pulse = AlphaAnimation(0.6f, 1.0f).apply {
                duration = 1200
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
            }
            startAnimation(pulse)
        }

        // Waveform visualizer
        waveView = WaveformView(ctx).apply {
            // Layout params set later
        }

        // Add views to root - horizontal layout: [sparkle] [state] [waveform]
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

        sparkleIcon!!.addMargin(0.dp(ctx))
        stateText!!.addMargin(8.dp(ctx))
        waveView!!.addMargin(8.dp(ctx))

        content.addView(sparkleIcon!!)
        content.addView(stateText!!)
        content.addView(waveView!!)

        if (reason == "screen_share") {
            val stopBtn = TextView(ctx).apply {
                text = "Stop"
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#EF4444"))
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12.dp(ctx).toFloat()
                    setColor(Color.parseColor("#33EF4444"))
                    setStroke(1.dp(ctx), Color.parseColor("#80EF4444"))
                }
                background = bg
                setPadding(8.dp(ctx), 4.dp(ctx), 8.dp(ctx), 4.dp(ctx))
                setOnClickListener {
                    try {
                        val stopIntent = Intent("com.aura.screenshare.STOP")
                        ctx.sendBroadcast(stopIntent)
                    } catch (_: Exception) {}
                    hide()
                }
            }
            stopBtn.addMargin(6.dp(ctx))
            content.addView(stopBtn)
        }

        root.addView(content)

        return root
    }

    private fun createDynamicBackground(ctx: Context): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24.dp(ctx).toFloat()
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
        isListeningSpeech = false
        isSpeaking = false
        isThinking = false
        isExpanded = false
        stateText?.text = "ISHA"
        updateBackgroundColor("Idle")
        waveView?.reset()
    }

    private fun toggleExpandedState() {
        isExpanded = !isExpanded
        // Animate height change using WindowManager.LayoutParams (NOT ViewGroup.MarginLayoutParams!)
        // The overlay view is managed by WindowManager, so we must use updateViewLayout().
        val ov = overlayView ?: return
        val wm = windowManager ?: return
        val wmParams = ov.layoutParams as? WindowManager.LayoutParams ?: return

        val startHeight = if (isExpanded) 48.dp(ov.context) else 80.dp(ov.context)
        val endHeight   = if (isExpanded) 80.dp(ov.context) else 48.dp(ov.context)

        val anim = ValueAnimator.ofInt(startHeight, endHeight).apply {
            duration = EXPAND_COLLAPSE_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val height = animator.animatedValue as Int
                try {
                    wmParams.height = height
                    wm.updateViewLayout(ov, wmParams)
                } catch (e: Exception) {
                    Log.w(TAG, "updateViewLayout failed during expand animation: ${e.message}")
                    cancel()
                }
            }
        }
        anim.start()
    }

    private fun startSpeechListening(ctx: Context, root: View) {
        // Disabled: User strictly uses Gemini Live Bidirectional Voice duplex ("Aoede") via MainActivity
        Log.d(TAG, "Dynamic Island SpeechRecognizer disabled in favor of Gemini Live Voice")
    }

    private fun stopSpeechListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
        isListeningSpeech = false
        abandonDucking()
    }

    private fun updateStateUI(state: String) {
        handler.post {
            stateText?.text = when (state) {
                "Listening" -> "Listening..."
                "Thinking" -> "Thinking..."
                "Speaking" -> "Speaking..."
                "Error" -> "Mic error"
                else -> "ISHA"
            }
            updateBackgroundColor(state)
            waveView?.apply {
                setListening(state == "Listening")
                setThinking(state == "Thinking")
                setSpeaking(state == "Speaking")
            }
        }
    }

    private fun handleSpokenCommand(ctx: Context, command: String) {
        val lower = command.lowercase()
        Log.d(TAG, "Command received: $command")

        // Show we heard the command
        stateText?.text = "\"$command\""
        updateBackgroundColor("Listening")
        waveView?.setListening(true)

        // If currently speaking, INTERRUPT immediately (barge-in)
        if (isSpeaking) {
            isSpeaking = false
            // Signal Gemini Live service to interrupt generation
            // This is done via method channel in MainActivity - we'll broadcast an interrupt intent
            val interruptIntent = Intent("com.aura.voice.INTERRUPT")
                    .putExtra("reason", "barge_in")
            ctx.sendBroadcast(interruptIntent)
        }

        // Process command via accessibility service (A11y) for instant execution
        val a11y = AuraAccessibilityService.instance
        when {
            lower.contains("screen") || lower.contains("photo") || lower.contains("dekho") || lower.contains("analyze") -> {
                // Background screen analysis via Gemini AI
                stateText?.text = "Screen analyzing..."
                updateBackgroundColor("Thinking")
                waveView?.setThinking(true)

                a11y?.takeOptimizedScreenCapture(maxDim = 1024, quality = 75) { bytes ->
                    handler.post {
                        if (bytes != null && bytes.isNotEmpty()) {
                            val chatService = GeminiChatService(ctx)
                            val prompt = if (command.length < 15) "Boss ne kaha hai screen dekho aur batao kya dikh raha hai. Screen ko analyze karke short me batao." else command
                            chatService.sendChatStream(
                                history = emptyList(),
                                newPrompt = prompt,
                                imageBytes = bytes,
                                callback = object : com.aura.assistant.ai.ChatStreamCallback {
                                    val responseBuf = StringBuilder()
                                    override fun onToken(chunk: String) {
                                        responseBuf.append(chunk)
                                    }
                                    override fun onToolExecution(toolName: String, args: String, result: String) {
                                        handler.post { stateText?.text = "$toolName ✓" }
                                    }
                                    override fun onComplete(fullText: String) {
                                        handler.post {
                                            val shortResp = fullText.lines().firstOrNull { it.isNotBlank() } ?: "Done ✓"
                                            val display = if (shortResp.length > 28) shortResp.take(25) + "..." else shortResp
                                            stateText?.text = display
                                            updateBackgroundColor("Speaking")
                                            waveView?.setThinking(false)
                                            waveView?.setSpeaking(true)
                                            speakText(fullText)
                                            handler.postDelayed({ hide() }, 4500L)
                                        }
                                    }
                                    override fun onError(errorMessage: String) {
                                        handler.post {
                                            stateText?.text = "Analysis error"
                                            updateBackgroundColor("Idle")
                                            waveView?.reset()
                                            handler.postDelayed({ hide() }, 1500L)
                                        }
                                    }
                                }
                            )
                        } else {
                            stateText?.text = "Screen capture failed"
                            handler.postDelayed({ hide() }, 1500L)
                        }
                    }
                }
            }
            lower.contains("like") || lower.contains("pasand") -> {
                val ok = a11y?.performYoutubeLike() ?: false
                stateText?.text = if (ok) "Liked! 👍" else "No like button"
                handler.postDelayed({ hide() }, 1200L)
            }
            lower.contains("skip") || lower.contains("hatao") -> {
                val ok = a11y?.performYoutubeSkipAd() ?: false
                stateText?.text = if (ok) "Ad skipped! ⏭️" else "No skip"
                handler.postDelayed({ hide() }, 1200L)
            }
            lower.contains("torch on") || lower.contains("flashlight on") -> {
                toggleFlashlight(ctx, true)
                stateText?.text = "Torch on"
                handler.postDelayed({ hide() }, 1000L)
            }
            lower.contains("torch off") || lower.contains("flashlight off") -> {
                toggleFlashlight(ctx, false)
                stateText?.text = "Torch off"
                handler.postDelayed({ hide() }, 1000L)
            }
            lower.contains("volume up") -> {
                adjustVolume(ctx, AudioManager.ADJUST_RAISE)
                stateText?.text = "Volume up"
                handler.postDelayed({ hide() }, 800L)
            }
            lower.contains("volume down") -> {
                adjustVolume(ctx, AudioManager.ADJUST_LOWER)
                stateText?.text = "Volume down"
                handler.postDelayed({ hide() }, 800L)
            }
            lower.contains("mute") -> {
                adjustVolume(ctx, AudioManager.ADJUST_MUTE)
                stateText?.text = "Muted"
                handler.postDelayed({ hide() }, 1000L)
            }
            lower.contains("unmute") -> {
                adjustVolume(ctx, AudioManager.ADJUST_UNMUTE)
                stateText?.text = "Unmuted"
                handler.postDelayed({ hide() }, 1000L)
            }
            lower.contains("open") || lower.contains("kholo") -> {
                // Open app request
                val appName = extractAppName(command)
                if (appName.isNotEmpty()) {
                    launchApp(ctx, appName)
                    stateText?.text = "Opening $appName"
                    handler.postDelayed({ hide() }, 1500L)
                } else {
                    stateText?.text = "Which app?"
                    handler.postDelayed({ hide() }, 1200L)
                }
            }
            else -> {
                stateText?.text = "Thinking..."
                updateBackgroundColor("Thinking")
                waveView?.setListening(false)
                waveView?.setThinking(true)

                val chatService = GeminiChatService(ctx)
                chatService.sendChatStream(
                    history = emptyList<ChatMessage>(),
                    newPrompt = command,
                    callback = object : com.aura.assistant.ai.ChatStreamCallback {
                        val responseBuf = StringBuilder()
                        override fun onToken(chunk: String) {
                            responseBuf.append(chunk)
                        }
                        override fun onToolExecution(toolName: String, args: String, result: String) {
                            handler.post {
                                stateText?.text = "$toolName ✓"
                            }
                        }
                        override fun onComplete(fullText: String) {
                            handler.post {
                                val shortResp = fullText.lines().firstOrNull { it.isNotBlank() } ?: "Done ✓"
                                val display = if (shortResp.length > 28) shortResp.take(25) + "..." else shortResp
                                stateText?.text = display
                                updateBackgroundColor("Speaking")
                                waveView?.setThinking(false)
                                waveView?.setSpeaking(true)
                                speakText(fullText)

                                // Persist user voice prompt and Aura response to Chat History
                                try {
                                    val repo = com.aura.assistant.data.AuraChatRepository(ctx)
                                    val sessions = repo.getAllSessions()
                                    val sessId = sessions.firstOrNull()?.id ?: repo.createSession(if (command.length > 28) command.take(25) + "..." else command).id
                                    val msgs = repo.getMessages(sessId)
                                    msgs.add(ChatMessage(sessionId = sessId, role = com.aura.assistant.data.MessageRole.USER, text = command))
                                    if (fullText.isNotBlank()) {
                                        msgs.add(ChatMessage(sessionId = sessId, role = com.aura.assistant.data.MessageRole.ASSISTANT, text = fullText))
                                    }
                                    repo.saveMessages(sessId, msgs)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to save voice chat history: $e")
                                }

                                handler.postDelayed({ hide() }, 4000L)
                            }
                        }
                        override fun onError(errorMessage: String) {
                            handler.post {
                                stateText?.text = "Done ✓"
                                updateBackgroundColor("Idle")
                                waveView?.reset()
                                handler.postDelayed({ hide() }, 1200L)
                            }
                        }
                    }
                )
            }
        }
    }

    private fun toggleFlashlight(ctx: Context, enable: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val camManager = ctx.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
                val camId = camManager?.cameraIdList?.firstOrNull()
                if (camId != null) {
                    camManager.setTorchMode(camId, enable)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Flashlight error", e)
        }
    }

    private fun adjustVolume(ctx: Context, direction: Int) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        } catch (e: Exception) {
            Log.w(TAG, "Volume error", e)
        }
    }

    private fun launchApp(ctx: Context, appName: String) {
        try {
            val intent = com.aura.assistant.ai.IshaToolRegistry.resolveAppIntent(ctx, appName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            } else {
                // If package is unresolvable directly, use Accessibility service's intelligent home search fallback
                val a11y = com.aura.assistant.AuraAccessibilityService.instance
                if (a11y != null) {
                    a11y.armAppSearchAndLaunch(appName)
                } else {
                    Log.w(TAG, "Cannot launch app '$appName': not found and AccessibilityService not active")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Open app error for $appName", e)
        }
    }

    private fun extractAppName(command: String): String {
        // Simple extraction - in reality would use NLP
        val apps = listOf("whatsapp", "gmail", "youtube", "chrome", "settings", "spotify", "instagram", "telegram")
        val lower = command.lowercase()
        for (app in apps) {
            if (lower.contains(app)) return app.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
        return ""
    }

    private fun requestDucking(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            audioManager = am
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                        )
                        .setOnAudioFocusChangeListener { /* no-op */ }
                        .build()
                audioFocusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
        } catch (_: Exception) {}
    }

    private fun abandonDucking() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(null)
            }
            audioFocusRequest = null
            audioManager = null
        } catch (_: Exception) {}
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
        private val barCount = 20
        private val barHeights = FloatArray(barCount) { 0.1f }
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
                val base = 0.1f + v * 0.1f
                for (i in barHeights.indices) {
                    barHeights[i] = base + (rng.nextFloat() - 0.5f) * 0.05f
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
                barHeights[i] = (clamped * 0.85f + 0.15f + jitter).coerceIn(0.1f, 1.0f)
            }
            invalidate()
        }

        fun reset() {
            isListening = false
            isThinking = false
            isSpeaking = false
            for (i in barHeights.indices) {
                barHeights[i] = 0.1f
            }
            idleAnimator.end()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val width = measuredWidth.toFloat()
            val height = measuredHeight.toFloat()
            val barWidth = width / barCount * 0.6f
            val spacing = (width - barWidth * barCount) / (barCount + 1)
            val startX = spacing

            for (i in barHeights.indices) {
                val heightRatio = barHeights[i]
                val barHeight = height * heightRatio * 0.8f
                val y = height - barHeight
                val x = startX + i * (barWidth + spacing)

                // Color based on state
                val color = when {
                    isListening -> Color.parseColor("#FF0EA5E9") // Sky blue
                    isThinking -> Color.parseColor("#FF7C3AED") // Violet
                    isSpeaking -> Color.parseColor("#FF0EA5E9") // Sky blue
                    else -> Color.parseColor("#FF94A3B8") // Gray
                }
                paint.color = color

                val rect = RectF(x, y, x + barWidth, y + barHeight)
                canvas.drawRoundRect(rect, 4f, 4f, paint)
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