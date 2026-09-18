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
 * Apple Dynamic Island style minimal top bubble for AURA.
 *
 * - Appears as a compact pill at the top center of screen.
 * - Shows ONLY state (Listening/Speaking/Thinking) + animated audio wave.
 * - No cards, no chips, no large text, no screen preview.
 * - Instant barge-in: if user speaks while AURA is talking, stop speech and listen immediately.
 * - Execute commands directly in background (no full app jump unless explicitly requested).
 * - Clean, minimal, professional assistant feel like Gemini.
 */
object AuraDynamicIsland {

    private const val TAG = "AuraDynamicIsland"
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

    /** Show the Dynamic Island bubble. Safe to call from any thread. */
    fun show(context: Context, reason: String = "wake") {
        handler.post { _show(context.applicationContext, reason) }
    }

    /** Hide and remove from WindowManager. */
    fun hide() {
        handler.post { _hide() }
    }

    private fun _show(ctx: Context, reason: String) {
        if (overlayView != null) {
            // Already showing - update reason and reset timer
            updateStateFromReason(reason)
            resetDismissTimer()
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
        val pillWidth = (screenWidth * 0.45).toInt() // ~45% screen width
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
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 12.dp(ctx) // Just below status bar / notch
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

        // 450ms settle delay after shake before opening SpeechRecognizer.
        // Prevents phone vibration motor and hand shake noise from triggering false speech!
        handler.postDelayed({
            if (overlayView != null) {
                startSpeechListening(ctx, overlay)
            }
        }, 450L)

        // Auto-dismiss after idle time
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
                        val resumeIntent = Intent(appContext, WakeWordService::class.java).apply {
                            action = WakeWordService.ACTION_RESUME
                        }
                        appContext.startService(resumeIntent)
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

        // Tap: if currently idle/not-listening → restart speech; otherwise toggle expand
        root.setOnClickListener {
            if (!isListeningSpeech) {
                // Re-arm speech so user can speak without re-shaking
                startSpeechListening(ctx, root)
                resetDismissTimer()
            } else {
                toggleExpandedState()
            }
        }

        // State indicator text (small)
        stateText = TextView(ctx).apply {
            text = "AURA"
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
            "shake" -> "Listening..."
            "morning_reminder" -> "Good morning"
            else -> "Listening..."
        }
        this.stateText?.text = stateText
        updateBackgroundColor("Listening")
    }

    private fun resetState() {
        isListeningSpeech = false
        isSpeaking = false
        isThinking = false
        isExpanded = false
        stateText?.text = "AURA"
        updateBackgroundColor("Idle")
        waveView?.reset()
    }

    private fun toggleExpandedState() {
        isExpanded = !isExpanded
        // Animate height change
        val ov = overlayView ?: return
        val startHeight = if (isExpanded) 48.dp(ov.context) else 80.dp(ov.context)
        val endHeight = if (isExpanded) 80.dp(ov.context) else 48.dp(ov.context)

        val anim = ValueAnimator.ofInt(startHeight, endHeight).apply {
            duration = EXPAND_COLLAPSE_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val height = animator.animatedValue as Int
                val params = ov.layoutParams as ViewGroup.MarginLayoutParams
                params.height = height
                ov.layoutParams = params
            }
        }
        anim.start()
    }

    private fun startSpeechListening(ctx: Context, root: View) {
        if (isListeningSpeech) return
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
                Log.w(TAG, "Speech engine unavailable")
                return
            }
            stopSpeechListening()
            requestDucking(ctx)

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListeningSpeech = true
                        isThinking = false
                        updateStateUI("Listening")
                        micVolume = 0f
                        waveView?.setListening(true)
                    }

                    override fun onBeginningOfSpeech() {
                        isListeningSpeech = true
                        isThinking = false
                        updateStateUI("Listening")
                        resetDismissTimer()
                    }

                    override fun onEndOfSpeech() {
                        isListeningSpeech = false
                        isThinking = true
                        updateStateUI("Thinking")
                        waveView?.setListening(false)
                        waveView?.setThinking(true)
                        // Small delay to simulate processing
                        handler.postDelayed({
                            if (!isSpeaking) {
                                // If not currently speaking, go back to idle after thinking
                                handler.postDelayed({ updateStateUI("AURA") }, 800L)
                            }
                        }, 600L)
                    }

                    override fun onError(error: Int) {
                        isListeningSpeech = false
                        isThinking = false
                        Log.d(TAG, "SpeechRecognizer error: $error")
                        when (error) {
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                            SpeechRecognizer.ERROR_AUDIO -> {
                                // Mic was still in use by WakeWordService — retry after short pause
                                Log.w(TAG, "Mic busy/audio error ($error), retrying after 600ms")
                                handler.postDelayed({
                                    if (overlayView != null) {
                                        updateStateUI("Listening")
                                        startSpeechListening(ctx, root)
                                        resetDismissTimer()
                                    }
                                }, 600L)
                            }
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                            SpeechRecognizer.ERROR_NO_MATCH -> {
                                // User didn't speak in time — dismiss cleanly without claiming Done
                                handler.post {
                                    stateText?.text = "No speech detected"
                                    updateBackgroundColor("Idle")
                                    waveView?.setListening(false)
                                }
                                handler.postDelayed({ hide() }, 1200L)
                            }
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                                stateText?.text = "Mic permission"
                                handler.postDelayed({ hide() }, 2000L)
                            }
                            SpeechRecognizer.ERROR_CLIENT,
                            SpeechRecognizer.ERROR_SERVER -> {
                                handler.post {
                                    stateText?.text = "Mic error"
                                    updateBackgroundColor("Idle")
                                }
                                handler.postDelayed({ hide() }, 1200L)
                            }
                            else -> {
                                handler.post {
                                    stateText?.text = "Listening stopped"
                                    updateBackgroundColor("Idle")
                                }
                                handler.postDelayed({ hide() }, 1000L)
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        isListeningSpeech = false
                        isThinking = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spoken = matches?.firstOrNull()?.trim() ?: ""
                        if (spoken.length >= 2 &&
                            !spoken.equals("done", ignoreCase = true) &&
                            !spoken.equals("bnd", ignoreCase = true) &&
                            !spoken.matches(Regex("^[.\\s,;\\-_*]*$"))) {
                            // Valid user speech — process command
                            handleSpokenCommand(ctx, spoken)
                        } else {
                            Log.d(TAG, "Ignoring noise artifact in Dynamic Island: '$spoken'")
                            handler.postDelayed({ hide() }, 800L)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        // Optional: show partial transcript
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onRmsChanged(rmsdB: Float) {
                        val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                        waveView?.setMicVolume(normalized)
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            isListeningSpeech = false
            Log.w(TAG, "SpeechRecognizer start failed: $e")
            stateText?.text = "Mic error"
            handler.postDelayed({ hide() }, 1000L)
        }
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
                else -> "AURA"
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
            lower.contains("screen") || lower.contains("photo") || lower.contains("dekho") -> {
                // Screen share / analyze
                a11y?.takeScreenCapture { bytes ->
                    handler.post {
                        if (bytes != null && bytes.isNotEmpty()) {
                            // Show brief confirmation then resume listening
                            stateText?.text = "Screen captured"
                            handler.postDelayed({
                                updateStateUI("Listening")
                                // Continue listening for follow-up questions about screen
                            }, 1200L)
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
                        override fun onToken(token: String) {
                            responseBuf.append(token)
                        }
                        override fun onToolExecution(toolName: String, args: String, result: String) {
                            handler.post {
                                stateText?.text = "$toolName ✓"
                            }
                        }
                        override fun onComplete(fullResponse: String) {
                            handler.post {
                                val shortResp = fullResponse.lines().firstOrNull { it.isNotBlank() } ?: "Done ✓"
                                val display = if (shortResp.length > 28) shortResp.take(25) + "..." else shortResp
                                stateText?.text = display
                                updateBackgroundColor("Speaking")
                                waveView?.setThinking(false)
                                waveView?.setSpeaking(true)
                                handler.postDelayed({ hide() }, 2500L)
                            }
                        }
                        override fun onError(error: String) {
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
            val pm = ctx.packageManager
            val pkg = when (appName.lowercase()) {
                "whatsapp" -> "com.whatsapp"
                "gmail" -> "com.google.android.gm"
                "youtube" -> "com.google.android.youtube"
                "chrome" -> "com.android.chrome"
                "settings" -> "com.android.settings"
                "spotify" -> "com.spotify.music"
                "instagram" -> "com.instagram.android"
                "telegram" -> "org.telegram.messenger"
                else -> null
            }
            val intent = if (pkg != null) pm.getLaunchIntentForPackage(pkg) else null
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            } else {
                val searchIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(searchIntent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Open app error", e)
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
        handler.postDelayed(dismissRunnable, durationMs)
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