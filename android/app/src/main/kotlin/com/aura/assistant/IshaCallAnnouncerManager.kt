package com.aura.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * ISHA Comprehensive Call Announcer & Hand / Voice Accept/Decline Manager.
 *
 * Mechanisms supported:
 *  1. Hand Wave Gesture: Waving hand over phone's proximity sensor accepts call immediately.
 *  2. Hand Hover / Cover: Covering proximity sensor for > 2s declines call.
 *  3. Interactive Heads-Up Touch HUD: Floating overlay with large Accept (green) and Decline (red) buttons for direct hand tap.
 *  4. Hands-Free Voice Response: Listens for "Accept" / "Decline" / "Haan" / "Kaat do" right after caller name announcement.
 *  5. Bulletproof Multi-tier Execution: TelecomManager + Notification Action + Accessibility click & swipe + MediaButton.
 */
object IshaCallAnnouncerManager {

    private const val TAG = "IshaCallAnnouncer"
    private const val UTTERANCE_CALL_ANNOUNCE = "ISHA_CALL_ANNOUNCE"

    @Volatile var isRinging = false
        private set
    @Volatile var currentCallerName = ""
        private set
    @Volatile var currentCallerNumber = ""
        private set

    private var nativeTts: TextToSpeech? = null
    private var isTtsReady = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // Proximity Sensor state (Hand Wave & Hand Hover)
    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var proximityListener: SensorEventListener? = null
    private var handNearTimestamp: Long = 0L

    // Voice Response Recognition
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningVoiceResponse = false

    // Interactive Floating Call HUD Overlay
    private var windowManager: WindowManager? = null
    private var floatingCallView: View? = null

    fun init(context: Context) {
        if (nativeTts == null) {
            try {
                nativeTts = TextToSpeech(context.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        nativeTts?.language = Locale("en", "IN")
                        nativeTts?.setSpeechRate(0.92f)
                        isTtsReady = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TTS initialization error", e)
            }
        }
    }

    /**
     * Triggered when an incoming cellular or VoIP call starts ringing.
     */
    fun onCallRinging(context: Context, callerName: String, number: String) {
        val cleanNumber = number.trim()
        val memoryMatch = com.aura.assistant.ai.IshaContactMemoryManager.resolveIncomingNumber(context, cleanNumber)
        val cleanName = when {
            memoryMatch != null -> memoryMatch.name
            callerName.isNotBlank() && callerName != "Unknown Caller" -> callerName
            else -> ""
        }
        val isOldNumber = memoryMatch?.isOldNumber == true

        isRinging = true
        currentCallerName = cleanName
        currentCallerNumber = cleanNumber

        Log.i(TAG, "Incoming call ringing from: '$cleanName' ($cleanNumber) [OldNumber=$isOldNumber]")

        // 1. Show interactive floating Heads-Up UI for direct hand tap
        val hudTitle = if (isOldNumber) "$cleanName (Old Number)" else cleanName.ifBlank { cleanNumber }.ifBlank { "Unknown Caller" }
        showFloatingCallHud(context, hudTitle)

        // 2. Start Hand Wave & Proximity Gesture Listener
        startProximityHandSensor(context)

        // 3. Announce Caller Name via TTS
        announceCallerWithPrompt(context, cleanName, cleanNumber, isOldNumber)
    }

    /**
     * Triggered when call is answered (OFFHOOK) or finished (IDLE).
     */
    fun onCallEnded(context: Context) {
        Log.i(TAG, "Call ended or answered — cleaning up announcer and sensors")
        isRinging = false
        currentCallerName = ""
        currentCallerNumber = ""

        stopProximityHandSensor(context)
        stopVoiceResponseListener()
        dismissFloatingCallHud()
        try {
            nativeTts?.stop()
        } catch (_: Exception) {}
    }

    // ── 1. Caller Announcement with Voice Prompt ──────────────────────────────
    private fun announceCallerWithPrompt(context: Context, callerName: String, number: String, isOldNumber: Boolean = false) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val ringMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (ringMode == AudioManager.RINGER_MODE_SILENT) return

        val displayName = callerName.ifBlank { number }.ifBlank { "Unknown Caller" }
        val speechText = if (isOldNumber) {
            "Incoming call from $displayName ke purane number se. Say Accept to answer, or Decline to reject."
        } else {
            "Incoming call from $displayName. Say Accept to answer, or Decline to reject."
        }

        if (nativeTts == null) init(context)

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_RING)
        }

        nativeTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == UTTERANCE_CALL_ANNOUNCE && isRinging) {
                    mainHandler.post {
                        startVoiceResponseListener(context)
                    }
                }
            }
            @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId, errorCode)"))
            @Suppress("DEPRECATION")
            override fun onError(utteranceId: String?) {}
            override fun onError(utteranceId: String?, errorCode: Int) {}
        })

        nativeTts?.speak(speechText, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_CALL_ANNOUNCE)
    }

    // ── 2. Hand Gesture Mechanism (Proximity Sensor) ──────────────────────────
    private fun startProximityHandSensor(context: Context) {
        try {
            sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

            if (proximitySensor == null) {
                Log.w(TAG, "Device does not possess a hardware proximity sensor")
                return
            }

            proximityListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    event ?: return
                    val distance = event.values[0]
                    val maxRange = proximitySensor?.maximumRange ?: 5f
                    val isNear = distance < maxRange && distance < 4f

                    val now = System.currentTimeMillis()
                    if (isNear) {
                        handNearTimestamp = now
                        Log.d(TAG, "Hand near sensor: distance=$distance")
                    } else {
                        if (handNearTimestamp > 0 && isRinging) {
                            val duration = now - handNearTimestamp
                            // Wave duration: hand was near for between 70ms and 1800ms
                            if (duration in 70..1800) {
                                Log.i(TAG, "Wave gesture detected ($duration ms)! Answering call...")
                                onCallEnded(context)
                                answerCall(context)
                            } else if (duration > 2000) {
                                Log.i(TAG, "Long hover/cover detected ($duration ms)! Declining call...")
                                onCallEnded(context)
                                declineCall(context)
                            }
                        }
                        handNearTimestamp = 0
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }

            sensorManager?.registerListener(proximityListener, proximitySensor, SensorManager.SENSOR_DELAY_UI)
            Log.i(TAG, "Proximity hand gesture listener registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register proximity sensor", e)
        }
    }

    private fun stopProximityHandSensor(context: Context) {
        try {
            proximityListener?.let {
                sensorManager?.unregisterListener(it)
            }
            proximityListener = null
            handNearTimestamp = 0
        } catch (_: Exception) {}
    }

    // ── 3. Hands-Free Voice Response Recognition ──────────────────────────────
    private fun startVoiceResponseListener(context: Context) {
        if (!isRinging || isListeningVoiceResponse) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        try {
            isListeningVoiceResponse = true
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.i(TAG, "Listening for call answer/decline voice commands...")
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    isListeningVoiceResponse = false
                }
                override fun onResults(results: Bundle?) {
                    isListeningVoiceResponse = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
                    evaluateVoiceResponse(context, matches)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
                    evaluateVoiceResponse(context, matches)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            speechRecognizer?.startListening(intent)

            // Auto timeout voice listening after 6 seconds
            mainHandler.postDelayed({
                stopVoiceResponseListener()
            }, 6000L)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start speech recognizer for call response", e)
            isListeningVoiceResponse = false
        }
    }

    private fun evaluateVoiceResponse(context: Context, matches: List<String>) {
        if (!isRinging) return
        val joined = matches.joinToString(" ").lowercase(Locale.ROOT)
        Log.i(TAG, "Voice response heard: '$joined'")

        val isAccept = joined.contains("accept") || joined.contains("answer") ||
                       joined.contains("pick up") || joined.contains("haan") ||
                       joined.contains("ha") || joined.contains("uthao") ||
                       joined.contains("yes") || joined.contains("receive")

        val isDecline = joined.contains("decline") || joined.contains("reject") ||
                        joined.contains("cut") || joined.contains("kaat do") ||
                        joined.contains("nahi") || joined.contains("cancel") ||
                        joined.contains("ignore") || joined.contains("no") || joined.contains("busy")

        if (isAccept) {
            Log.i(TAG, "Voice command accepted call!")
            onCallEnded(context)
            answerCall(context)
        } else if (isDecline) {
            Log.i(TAG, "Voice command declined call!")
            onCallEnded(context)
            declineCall(context)
        }
    }

    private fun stopVoiceResponseListener() {
        if (!isListeningVoiceResponse && speechRecognizer == null) return
        try {
            isListeningVoiceResponse = false
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (_: Exception) {}
    }

    // ── 4. Interactive Floating Call Heads-Up HUD (Hand Tap) ───────────────────
    private fun showFloatingCallHud(context: Context, callerDisplay: String) {
        mainHandler.post {
            try {
                if (floatingCallView != null) dismissFloatingCallHud()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                    Log.w(TAG, "Overlay permission not granted; cannot display floating call HUD")
                    return@post
                }

                windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                val wm = windowManager ?: return@post

                val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val dm = context.resources.displayMetrics
                val width = (dm.widthPixels * 0.94f).toInt()

                @Suppress("DEPRECATION")
                val params = WindowManager.LayoutParams(
                    width,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = (dm.heightPixels * 0.05f).toInt()
                }

                // Construct sleek AMOLED card with green/red actions
                val root = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(48, 36, 48, 36)
                    val bg = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#EE121214")) // AMOLED Black Glass
                        cornerRadius = 48f
                        setStroke(3, android.graphics.Color.parseColor("#4D10A37F")) // Subtle Neon Green Border
                    }
                    background = bg
                    elevation = 24f
                }

                // Header
                val header = TextView(context).apply {
                    text = "ISHA CALL ANNOUNCER"
                    setTextColor(android.graphics.Color.parseColor("#10A37F"))
                    textSize = 11f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    letterSpacing = 0.08f
                }
                root.addView(header)

                // Caller Name
                val callerTv = TextView(context).apply {
                    text = callerDisplay
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 18f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding(0, 8, 0, 4)
                }
                root.addView(callerTv)

                // Helper Subtitle
                val subTv = TextView(context).apply {
                    text = "👋 Wave hand over phone to accept • Or say 'Accept' / 'Decline'"
                    setTextColor(android.graphics.Color.parseColor("#9E9E9E"))
                    textSize = 12f
                    setPadding(0, 0, 0, 24)
                }
                root.addView(subTv)

                // Action Buttons Row (Hand Tap Accept / Decline)
                val btnRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    weightSum = 2f
                }

                // Decline Button (Red)
                val declineBtn = TextView(context).apply {
                    text = "✕ Decline"
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 15f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(0, 32, 0, 32)
                    val redBg = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#E53935"))
                        cornerRadius = 28f
                    }
                    background = redBg
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        Log.i(TAG, "Decline button clicked on call HUD")
                        onCallEnded(context)
                        declineCall(context)
                    }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = 16
                    }
                }
                btnRow.addView(declineBtn)

                // Accept Button (Green)
                val acceptBtn = TextView(context).apply {
                    text = "✓ Accept"
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 15f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(0, 32, 0, 32)
                    val greenBg = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#10A37F"))
                        cornerRadius = 28f
                    }
                    background = greenBg
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        Log.i(TAG, "Accept button clicked on call HUD")
                        onCallEnded(context)
                        answerCall(context)
                    }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = 16
                    }
                }
                btnRow.addView(acceptBtn)

                root.addView(btnRow)

                floatingCallView = root
                wm.addView(root, params)
                Log.i(TAG, "Floating call HUD displayed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to display floating call HUD", e)
            }
        }
    }

    private fun dismissFloatingCallHud() {
        mainHandler.post {
            try {
                floatingCallView?.let { view ->
                    windowManager?.removeView(view)
                }
                floatingCallView = null
            } catch (_: Exception) {}
        }
    }

    // ── 5. Bulletproof Call Action Implementations ────────────────────────────
    /**
     * Answers the currently ringing call via all available Android APIs.
     */
    fun answerCall(context: Context): Boolean {
        Log.i(TAG, "Executing answerCall...")
        var answered = false

        // 1. IshaNotificationListenerService VoIP / dialer action intent
        if (IshaNotificationListenerService.answerActiveCall(context)) {
            answered = true
            Log.i(TAG, "Call answered via IshaNotificationListenerService active intent")
        }

        // 2. TelecomManager acceptRingingCall (Android 8.0+)
        if (!answered && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    telecom?.acceptRingingCall()
                    answered = true
                    Log.i(TAG, "Call answered via TelecomManager.acceptRingingCall()")
                }
            } catch (e: Exception) {
                Log.w(TAG, "TelecomManager acceptRingingCall failed: ${e.message}")
            }
        }

        // 3. Scan active notifications for Answer / Accept action
        if (!answered) {
            val notifService = IshaNotificationListenerService.instance
            val sbns = notifService?.activeNotifications
            if (sbns != null) {
                for (sbn in sbns) {
                    val actions = sbn.notification.actions ?: continue
                    for (action in actions) {
                        val title = (action.title?.toString() ?: "").lowercase(Locale.ROOT)
                        if (title.contains("answer") || title.contains("accept") || title.contains("उत्तर") || title.contains("उठाओ")) {
                            try {
                                action.actionIntent.send()
                                answered = true
                                Log.i(TAG, "Call answered via notification action from ${sbn.packageName}")
                                break
                            } catch (e: Exception) {
                                Log.w(TAG, "Notification action intent send failed: ${e.message}")
                            }
                        }
                    }
                    if (answered) break
                }
            }
        }

        // 4. Accessibility Service click & gesture
        if (!answered) {
            val a11y = IshaAccessibilityService.instance
            if (a11y != null) {
                answered = a11y.answerIncomingCall()
                if (answered) Log.i(TAG, "Call answered via IshaAccessibilityService")
            }
        }

        // 5. Headset hook media button broadcast fallback
        if (!answered) {
            try {
                val mediaIntentDown = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                    putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK))
                    flags = Intent.FLAG_RECEIVER_FOREGROUND
                }
                context.sendBroadcast(mediaIntentDown)
                val mediaIntentUp = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                    putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK))
                    flags = Intent.FLAG_RECEIVER_FOREGROUND
                }
                context.sendBroadcast(mediaIntentUp)
                answered = true
                Log.i(TAG, "Dispatched HEADSETHOOK key events as fallback")
            } catch (e: Exception) {
                Log.w(TAG, "Headset hook fallback error: ${e.message}")
            }
        }

        vibrateFeedback(context, isSuccess = true)
        speakFeedback("Call accepted")
        return answered
    }

    /**
     * Declines the currently ringing call via all available Android APIs.
     */
    fun declineCall(context: Context): Boolean {
        Log.i(TAG, "Executing declineCall...")
        var declined = false

        // 1. IshaNotificationListenerService VoIP / dialer action intent
        if (IshaNotificationListenerService.declineActiveCall(context)) {
            declined = true
            Log.i(TAG, "Call declined via IshaNotificationListenerService active intent")
        }

        // 2. TelecomManager endCall (Android 9.0+)
        if (!declined && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    @Suppress("DEPRECATION")
                    declined = telecom?.endCall() ?: false
                    Log.i(TAG, "Call declined via TelecomManager.endCall(): $declined")
                }
            } catch (e: Exception) {
                Log.w(TAG, "TelecomManager endCall failed: ${e.message}")
            }
        }

        // 3. Scan active notifications for Decline / Reject action
        if (!declined) {
            val notifService = IshaNotificationListenerService.instance
            val sbns = notifService?.activeNotifications
            if (sbns != null) {
                for (sbn in sbns) {
                    val actions = sbn.notification.actions ?: continue
                    for (action in actions) {
                        val title = (action.title?.toString() ?: "").lowercase(Locale.ROOT)
                        if (title.contains("decline") || title.contains("reject") || title.contains("dismiss") || title.contains("अस्वीकार")) {
                            try {
                                action.actionIntent.send()
                                declined = true
                                Log.i(TAG, "Call declined via notification action from ${sbn.packageName}")
                                break
                            } catch (e: Exception) {
                                Log.w(TAG, "Notification action intent send failed: ${e.message}")
                            }
                        }
                    }
                    if (declined) break
                }
            }
        }

        // 4. Accessibility Service click & gesture
        if (!declined) {
            val a11y = IshaAccessibilityService.instance
            if (a11y != null) {
                declined = a11y.declineIncomingCall()
                if (declined) Log.i(TAG, "Call declined via IshaAccessibilityService")
            }
        }

        vibrateFeedback(context, isSuccess = false)
        speakFeedback("Call declined")
        return declined
    }

    private fun vibrateFeedback(context: Context, isSuccess: Boolean) {
        try {
            val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = if (isSuccess) longArrayOf(0, 80, 50, 80) else longArrayOf(0, 160)
                vib.vibrate(VibrationEffect.createWaveform(timings, -1))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(if (isSuccess) 120 else 200)
            }
        } catch (_: Exception) {}
    }

    private fun speakFeedback(text: String) {
        try {
            nativeTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "CALL_FEEDBACK")
        } catch (_: Exception) {}
    }
}
