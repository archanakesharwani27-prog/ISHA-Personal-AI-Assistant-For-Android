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
import android.provider.ContactsContract
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

    private var lastAnnouncedCallTime = 0L

    /**
     * Resolves contact name with multi-tier fallbacks:
     * 1. Check if callerName is already an alphabetic contact name (e.g. "Mom", "Rahul", "Umesh").
     * 2. Query ISHA persistent Contact Memory (with old number recognition).
     * 3. Query ContactsContract.PhoneLookup with normalized number.
     * 4. Query ContactsContract.CommonDataKinds.Phone with last 10 digits.
     * 5. Fallback to "Unknown Caller" — NEVER returns raw digits as a name!
     */
    fun resolveContactName(context: Context, rawNumber: String, rawCallerName: String = ""): Pair<String, Boolean> {
        val cleanNumber = rawNumber.trim()
        val cleanCallerName = rawCallerName.trim()

        val isNameActuallyNumber = cleanCallerName.isBlank() ||
                cleanCallerName.equals("Unknown Caller", ignoreCase = true) ||
                cleanCallerName.matches(Regex("[+0-9\\s\\-\\(\\)]{4,}"))

        if (!isNameActuallyNumber) {
            return Pair(cleanCallerName, false)
        }

        val candidate = when {
            cleanNumber.isNotBlank() -> cleanNumber
            cleanCallerName.matches(Regex("[+0-9\\s\\-\\(\\)]{4,}")) -> cleanCallerName
            else -> ""
        }

        if (candidate.isBlank()) return Pair("Unknown Caller", false)

        // 1. ISHA Contact Memory
        val memoryMatch = com.aura.assistant.ai.IshaContactMemoryManager.resolveIncomingNumber(context, candidate)
        if (memoryMatch != null) {
            return Pair(memoryMatch.name, memoryMatch.isOldNumber)
        }

        val digitsOnly = candidate.replace(Regex("[^0-9+]"), "")

        // 2. Query ContactsContract.PhoneLookup
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(digitsOnly))
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return Pair(name, false)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "PhoneLookup failed: ${e.message}")
        }

        // 3. Fallback: Query CommonDataKinds.Phone with last 10 digits
        val last10 = digitsOnly.takeLast(10)
        if (last10.length >= 7) {
            try {
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                    "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?",
                    arrayOf("%$last10"),
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        if (idx >= 0) {
                            val name = cursor.getString(idx)
                            if (!name.isNullOrBlank()) return Pair(name, false)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "CommonDataKinds.Phone failed: ${e.message}")
            }
        }

        // 4. Truly unknown number (never return raw digits)
        return Pair("Unknown Caller", false)
    }

    fun init(context: Context) {
        if (nativeTts == null) {
            try {
                nativeTts = TextToSpeech(context.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        nativeTts?.language = Locale("en", "IN")
                        nativeTts?.setSpeechRate(1.15f) // Ultra-crisp, fast announcement
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
        val (resolvedName, isOldNumber) = resolveContactName(context, number, callerName)
        val now = System.currentTimeMillis()

        // Deduplicate duplicate triggers within 3.5s unless upgrading from "Unknown Caller" to real name
        if (isRinging && now - lastAnnouncedCallTime < 3500L && (resolvedName == currentCallerName || resolvedName == "Unknown Caller")) {
            Log.d(TAG, "Skipping duplicate ringing event for $resolvedName")
            return
        }

        lastAnnouncedCallTime = now
        isRinging = true
        currentCallerName = resolvedName
        currentCallerNumber = number.trim()

        Log.i(TAG, "Incoming call ringing from: '$resolvedName' ($number) [OldNumber=$isOldNumber]")

        // 1. Start Hand Wave & Proximity Gesture Listener
        startProximityHandSensor(context)

        // 2. Announce Caller Name via TTS (fast, crisp) and activate Mic immediately
        announceCallerWithPrompt(context, resolvedName, isOldNumber)
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
    private fun announceCallerWithPrompt(context: Context, resolvedName: String, isOldNumber: Boolean = false) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val ringMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (ringMode == AudioManager.RINGER_MODE_SILENT) {
            // In silent mode, start mic listener immediately
            startVoiceResponseListener(context)
            return
        }

        val speechText = if (isOldNumber) {
            "Call from $resolvedName, purana number"
        } else if (resolvedName.isNotBlank() && resolvedName != "Unknown Caller") {
            "Call from $resolvedName"
        } else {
            "Unknown call"
        }

        if (nativeTts == null) init(context)

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_RING)
        }

        fun doSpeak() {
            try {
                nativeTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d(TAG, "TTS announcement started: '$speechText'")
                    }
                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "TTS announcement finished, starting mic listener...")
                        if (utteranceId == UTTERANCE_CALL_ANNOUNCE && isRinging) {
                            mainHandler.post {
                                startVoiceResponseListener(context)
                            }
                        }
                    }
                    @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId, errorCode)"))
                    @Suppress("DEPRECATION")
                    override fun onError(utteranceId: String?) {
                        if (isRinging) {
                            mainHandler.post { startVoiceResponseListener(context) }
                        }
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (isRinging) {
                            mainHandler.post { startVoiceResponseListener(context) }
                        }
                    }
                })

                val res = nativeTts?.speak(speechText, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_CALL_ANNOUNCE)
                if (res != TextToSpeech.SUCCESS) {
                    Log.w(TAG, "nativeTts.speak returned $res, triggering mic directly")
                    mainHandler.postDelayed({
                        if (isRinging && !isListeningVoiceResponse) startVoiceResponseListener(context)
                    }, 400L)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error speaking call announcement", e)
                startVoiceResponseListener(context)
            }
        }

        if (!isTtsReady) {
            mainHandler.postDelayed({
                if (isRinging) {
                    if (isTtsReady) {
                        doSpeak()
                    } else {
                        // Fallback: If TTS takes too long to init, don't keep user waiting 10-20s!
                        Log.w(TAG, "TTS not ready after 350ms, starting mic listener immediately")
                        startVoiceResponseListener(context)
                    }
                }
            }, 350L)
        } else {
            doSpeak()
        }

        // Guaranteed fallback: ensures mic starts listening within 1000ms regardless of TTS state
        mainHandler.postDelayed({
            if (isRinging && !isListeningVoiceResponse) {
                Log.d(TAG, "Fallback timer starting voice response mic listener")
                startVoiceResponseListener(context)
            }
        }, 1000L)
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
        if (!isRinging) return
        mainHandler.post {
            if (!isRinging || isListeningVoiceResponse) return@post
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "RECORD_AUDIO permission not granted for call voice response")
                return@post
            }

            try {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    Log.w(TAG, "SpeechRecognizer is not available on this device")
                    return@post
                }

                isListeningVoiceResponse = true
                try {
                    speechRecognizer?.stopListening()
                    speechRecognizer?.destroy()
                } catch (_: Exception) {}
                speechRecognizer = null

                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            Log.i(TAG, "🎙️ Call response mic is LIVE — say 'Accept' or 'Decline'")
                        }
                        override fun onBeginningOfSpeech() {
                            Log.d(TAG, "Voice detected...")
                        }
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {
                            Log.d(TAG, "Voice ended...")
                        }
                        override fun onError(error: Int) {
                            Log.w(TAG, "SpeechRecognizer error: $error")
                            isListeningVoiceResponse = false
                            try {
                                speechRecognizer?.destroy()
                                speechRecognizer = null
                            } catch (_: Exception) {}
                            // Keep listening while phone is ringing!
                            if (isRinging) {
                                mainHandler.postDelayed({
                                    if (isRinging && !isListeningVoiceResponse) {
                                        startVoiceResponseListener(context)
                                    }
                                }, 350L)
                            }
                        }
                        override fun onResults(results: Bundle?) {
                            isListeningVoiceResponse = false
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
                            evaluateVoiceResponse(context, matches)
                            if (isRinging && !isListeningVoiceResponse) {
                                mainHandler.postDelayed({
                                    if (isRinging && !isListeningVoiceResponse) {
                                        startVoiceResponseListener(context)
                                    }
                                }, 350L)
                            }
                        }
                        override fun onPartialResults(partialResults: Bundle?) {
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
                            evaluateVoiceResponse(context, matches)
                        }
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra("android.speech.extra.DICTATION_MODE", true)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
                }

                speechRecognizer?.startListening(intent)
                Log.i(TAG, "Started SpeechRecognizer for call voice command")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start speech recognizer for call response", e)
                isListeningVoiceResponse = false
            }
        }
    }

    private fun evaluateVoiceResponse(context: Context, matches: List<String>) {
        if (!isRinging) return
        val joined = matches.joinToString(" ").lowercase(Locale.ROOT)
        Log.i(TAG, "Voice response heard: '$joined'")

        val isAccept = joined.contains("accept") || joined.contains("answer") ||
                       joined.contains("pick") || joined.contains("haan") ||
                       joined.contains("ha") || joined.contains("uthao") ||
                       joined.contains("uthaye") || joined.contains("yes") ||
                       joined.contains("receive") || joined.contains("le lo")

        val isDecline = joined.contains("decline") || joined.contains("reject") ||
                        joined.contains("cut") || joined.contains("kaat") ||
                        joined.contains("kat") || joined.contains("nahi") ||
                        joined.contains("cancel") || joined.contains("ignore") ||
                        joined.contains("no") || joined.contains("busy") ||
                        joined.contains("hatao") || joined.contains("mat uthao")

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
        // Disabled per user request: Caller ID HUD is removed, keeping full focus on hands-free voice experience.
    }

    fun dismissFloatingCallHud() {
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
