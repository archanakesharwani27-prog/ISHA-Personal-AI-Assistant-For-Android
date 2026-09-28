package com.aura.assistant

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.telephony.TelephonyManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.aura.assistant.auth.IshaAuthManager
import com.aura.assistant.ui.contract.IshaAssistantViewModel
import com.aura.assistant.ui.screens.IshaLoginScreen
import com.aura.assistant.ui.screens.IshaMainScreen
import com.aura.assistant.ui.theme.IshaTheme
import java.util.Locale
import kotlin.math.sqrt

/**
 * 100% Native Jetpack Compose Entry Activity for AURA.
 * Replaces legacy Flutter Activity, eliminating Dart VM and 200MB+ overhead.
 */
class MainActivity : ComponentActivity(), SensorEventListener {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSIONS_REQUEST_CODE = 101
        @Volatile var instance: MainActivity? = null
    }

    private val viewModel: IshaAssistantViewModel by viewModels()
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Shake Sensor State ─────────────────────────────────────────────────────
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var isShakeDetectionEnabled = false

    // ── Settings Toggle Receivers ──────────────────────────────────────────────
    private val shakeToggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.aura.assistant.SHAKE_TOGGLE") {
                val enabled = intent.getBooleanExtra("enabled", false)
                isShakeDetectionEnabled = enabled
                getSharedPreferences("aura_settings", Context.MODE_PRIVATE).edit().putBoolean("shake_enabled", enabled).apply()
                if (enabled && accelerometer != null) {
                    sensorManager?.registerListener(this@MainActivity, accelerometer, SensorManager.SENSOR_DELAY_UI)
                    Log.i(TAG, "Shake detection ENABLED")
                } else {
                    sensorManager?.unregisterListener(this@MainActivity)
                    Log.i(TAG, "Shake detection DISABLED")
                }
            }
        }
    }

    private val wakeToggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.aura.assistant.WAKE_WORD_TOGGLE") {
                val enabled = intent.getBooleanExtra("enabled", false)
                try {
                    val serviceIntent = Intent(this@MainActivity, WakeWordService::class.java).apply {
                        action = WakeWordService.ACTION_CONFIG
                        putExtra(WakeWordService.EXTRA_WAKE_ENABLED, enabled)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                    Log.i(TAG, "Wake word toggle sent to WakeWordService: enabled=$enabled")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to toggle WakeWordService", e)
                }
            }
        }
    }

    private val screenShareStopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.aura.screenshare.STOP") {
                mainHandler.post {
                    viewModel.stopLiveScreenShare()
                }
            }
        }
    }
    private var shakePeakCount = 0
    private var sawBaselineBetweenPeaks = false
    private var firstPeakTimestamp = 0L
    private var lastPeakTimestamp = 0L
    private var lastShakeTriggerTimestamp = 0L
    private val SHAKE_THRESHOLD = 3.25f
    private val BASELINE_RESET_G = 1.50f
    private val SHAKE_MIN_INTERVAL_MS = 120L
    private val SHAKE_MAX_INTERVAL_MS = 600L
    private val SHAKE_COOLDOWN_MS = 2500L

    // ── Native Caller Name Announcer TTS ──────────────────────────────────────
    private var nativeTts: TextToSpeech? = null
    private var isTtsReady = false
    private var hasAnnouncedCurrentCall = false
    private var lastAnnouncedCallerTime = 0L
    private var lastAnnouncedCallerNumber = ""

    // ── Wake Word Receiver ────────────────────────────────────────────────────
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WakeWordService.ACTION_WAKE) {
                val reason = intent.getStringExtra(WakeWordService.EXTRA_REASON) ?: "wake"
                Log.i(TAG, "Wake broadcast received: reason=$reason")
                if (reason != "shake") {
                    mainHandler.post {
                        viewModel.onMicButtonPressed()
                    }
                }
            }
        }
    }

    // ── Call State Receiver ───────────────────────────────────────────────────
    @SuppressLint("NewApi")
    private val callReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val ctx = context ?: this@MainActivity
            if (intent?.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""
                val callerName = if (incomingNumber.isNotBlank()) getContactName(incomingNumber) else "Unknown Caller"

                when (stateStr) {
                    TelephonyManager.EXTRA_STATE_RINGING -> {
                        IshaCallAnnouncerManager.onCallRinging(ctx, callerName, incomingNumber)
                    }
                    TelephonyManager.EXTRA_STATE_OFFHOOK,
                    TelephonyManager.EXTRA_STATE_IDLE -> {
                        IshaCallAnnouncerManager.onCallEnded(ctx)
                        hasAnnouncedCurrentCall = false
                    }
                }
            } else if (intent?.action == IshaCallScreeningService.ACTION_PRE_CALL_SCREENED) {
                val callerName = intent.getStringExtra(IshaCallScreeningService.EXTRA_CALLER_NAME) ?: "Unknown Caller"
                val number = intent.getStringExtra(IshaCallScreeningService.EXTRA_NUMBER) ?: ""
                IshaCallAnnouncerManager.onCallRinging(ctx, callerName, number)
            } else if (intent?.action == IshaNotificationListenerService.ACTION_CALL_EVENT) {
                val state = intent.getStringExtra("state")
                val callerName = intent.getStringExtra("callerName") ?: "Unknown Caller"
                val number = intent.getStringExtra("number") ?: ""
                if (state == "ringing") {
                    IshaCallAnnouncerManager.onCallRinging(ctx, callerName, number)
                } else {
                    IshaCallAnnouncerManager.onCallEnded(ctx)
                    hasAnnouncedCurrentCall = false
                }
            }
        }
    }

    // ── External / ADB Command Injection ─────────────────────────────────────
    fun injectUserMessage(text: String) {
        runOnUiThread {
            viewModel.onSendTextMessage(text)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        instance = this
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Wake screen on assistant invocation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        checkAndRequestPermissions()
        registerReceivers()
        initShakeSensor()
        initNativeTts()
        IshaCallAnnouncerManager.init(this)


        IshaAuthManager.init(this)
        startWakeWordService()
        handleWakeIntent(intent)

        setContent {
            IshaTheme {
                val session by IshaAuthManager.sessionState.collectAsState()

                LaunchedEffect(session.uid, session.isLoggedIn) {
                    viewModel.switchUser(session)
                }

                AnimatedContent(
                    targetState = session.isLoggedIn,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "AuthTransition"
                ) { loggedIn ->
                    if (loggedIn) {
                        IshaMainScreen(
                            viewModel = viewModel,
                            userProfile = session,
                            onLogout = { IshaAuthManager.logout(this@MainActivity) }
                        )
                    } else {
                        IshaLoginScreen(
                            onLoginSuccess = {
                                // Session flow triggers automatic crossfade
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeIntent(intent)
    }

    private fun handleWakeIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action == "com.aura.assistant.LOGOUT") {
            IshaAuthManager.logout(this)
            return
        }
        val injectText = intent.getStringExtra("inject_text")
        if (!injectText.isNullOrBlank()) {
            mainHandler.postDelayed({
                injectUserMessage(injectText)
            }, 300)
            return
        }
        if (intent.getBooleanExtra("run_diagnostics", false) || action == "com.aura.assistant.RUN_DIAGNOSTICS") {
            Thread {
                val diagResults = com.aura.assistant.ai.AuraMemoryManager.runMemoryAndContextDiagnostics(this)
                val summary = diagResults.joinToString("\n") { r ->
                    "${if (r.passed) "✅" else "❌"} ${r.testName}: ${r.details}"
                }
                mainHandler.post {
                    injectUserMessage("System Diagnostics: Memory & Context Test Suite Results:\n$summary")
                }
            }.start()
            return
        }
        val reason = intent.getStringExtra(WakeWordService.EXTRA_REASON)
        if (action == "com.aura.assistant.WAKE_ACTIVATE" || action == WakeWordService.ACTION_WAKE || reason != null) {
            mainHandler.postDelayed({
                viewModel.onMicButtonPressed()
            }, 300)
        } else if (action == Intent.ACTION_ASSIST || action == "android.intent.action.VOICE_COMMAND") {
            mainHandler.postDelayed({
                viewModel.onMicButtonPressed()
            }, 300)
        } else if (intent.getBooleanExtra("open_voice_mode", false)) {
            mainHandler.postDelayed({
                if (!viewModel.isVoiceModeActive.value) {
                    viewModel.startVoiceMode()
                }
            }, 200)
        }
    }

    private fun startWakeWordService() {
        try {
            val prefs = getSharedPreferences("aura_settings", Context.MODE_PRIVATE)
            val isWakeEnabled = prefs.getBoolean("wake_word_enabled", false)
            val isShakeEnabled = prefs.getBoolean("shake_enabled", true)
            val serviceIntent = Intent(this, WakeWordService::class.java).apply {
                action = WakeWordService.ACTION_CONFIG
                putExtra(WakeWordService.EXTRA_WAKE_ENABLED, isWakeEnabled)
                putExtra(WakeWordService.EXTRA_SHAKE_ENABLED, isShakeEnabled)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.i(TAG, "Started WakeWordService with wakeEnabled=$isWakeEnabled, shakeEnabled=$isShakeEnabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WakeWordService", e)
        }
    }

    /**
     * Instantly sets the window brightness of MainActivity with immediate visual feedback.
     */
    fun setScreenBrightness(percent: Int) {
        val clamped = percent.coerceIn(1, 100)
        runOnUiThread {
            try {
                val lp = window.attributes
                lp.screenBrightness = clamped / 100.0f
                window.attributes = lp
                window.decorView.invalidate()
                Log.i(TAG, "Window screenBrightness set to $clamped% (${lp.screenBrightness})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set window brightness", e)
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val perms = mutableListOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.CALL_PHONE,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.RECEIVE_SMS,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            perms.add(android.Manifest.permission.ANSWER_PHONE_CALLS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
            perms.add(android.Manifest.permission.READ_MEDIA_IMAGES)
            perms.add(android.Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            perms.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            perms.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val needed = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), PERMISSIONS_REQUEST_CODE)
        }

        // Overlay permission for Dynamic Island background presence
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            try {
                val overlayIntent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(overlayIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch overlay permission screen: ${e.message}")
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag", "NewApi")
    private fun registerReceivers() {
        try {
            val wakeFilter = IntentFilter(WakeWordService.ACTION_WAKE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(wakeReceiver, wakeFilter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(wakeReceiver, wakeFilter)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "wakeReceiver registration error", e)
        }

        try {
            val callFilter = IntentFilter().apply {
                addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
                addAction(IshaCallScreeningService.ACTION_PRE_CALL_SCREENED)
                addAction(IshaNotificationListenerService.ACTION_CALL_EVENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(callReceiver, callFilter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(callReceiver, callFilter)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "callReceiver registration error", e)
        }

        // Settings toggle receivers (not exported — internal only)
        try {
            val shakeFilter = IntentFilter("com.aura.assistant.SHAKE_TOGGLE")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(shakeToggleReceiver, shakeFilter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(shakeToggleReceiver, shakeFilter)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "shakeToggleReceiver registration error", e)
        }

        try {
            val wakeWordFilter = IntentFilter("com.aura.assistant.WAKE_WORD_TOGGLE")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(wakeToggleReceiver, wakeWordFilter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(wakeToggleReceiver, wakeWordFilter)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "wakeToggleReceiver registration error", e)
        }

        try {
            val stopFilter = IntentFilter("com.aura.screenshare.STOP")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenShareStopReceiver, stopFilter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(screenShareStopReceiver, stopFilter)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "screenShareStopReceiver registration error", e)
        }
    }


    // ── Shake Sensor ──────────────────────────────────────────────────────────
    private fun initShakeSensor() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        isShakeDetectionEnabled = getSharedPreferences("aura_settings", Context.MODE_PRIVATE).getBoolean("shake_enabled", false)
        if (isShakeDetectionEnabled && accelerometer != null) {
            sensorManager?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onResume() {
        super.onResume()
        instance = this
        AuraDynamicIsland.isAppInForeground = true
        AuraDynamicIsland.hide()
        isShakeDetectionEnabled = getSharedPreferences("aura_settings", Context.MODE_PRIVATE).getBoolean("shake_enabled", false)
        if (isShakeDetectionEnabled && accelerometer != null) {
            sensorManager?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        } else {
            sensorManager?.unregisterListener(this)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        try {
            AuraDynamicIsland.isAppInForeground = false
            if (Settings.canDrawOverlays(this)) {
                val isVoiceActive = viewModel.isVoiceModeActive.value
                val isScreenShareActive = viewModel.isLiveScreenSharing.value
                if (isScreenShareActive) {
                    AuraDynamicIsland.show(this, "screen_share")
                } else if (isVoiceActive) {
                    AuraDynamicIsland.show(this, "voice_mode")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show Dynamic Island on leave", e)
        }
    }

    override fun onPause() {
        sensorManager?.unregisterListener(this)
        AuraDynamicIsland.isAppInForeground = false
        super.onPause()
        try {
            if (Settings.canDrawOverlays(this) && (viewModel.isVoiceModeActive.value || viewModel.isLiveScreenSharing.value)) {
                AuraDynamicIsland.show(this, if (viewModel.isLiveScreenSharing.value) "screen_share" else "voice_mode")
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        if (instance == this) {
            instance = null
        }
        sensorManager?.unregisterListener(this)
        try { unregisterReceiver(wakeReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(callReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(shakeToggleReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(wakeToggleReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(screenShareStopReceiver) } catch (_: Exception) {}
        stopCallerAnnouncement()
        try { nativeTts?.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isShakeDetectionEnabled || event == null) {
            sensorManager?.unregisterListener(this)
            return
        }
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val gX = x / SensorManager.GRAVITY_EARTH
        val gY = y / SensorManager.GRAVITY_EARTH
        val gZ = z / SensorManager.GRAVITY_EARTH
        val gForce = sqrt((gX * gX + gY * gY + gZ * gZ).toDouble()).toFloat()

        val now = System.currentTimeMillis()
        if (now - lastShakeTriggerTimestamp < SHAKE_COOLDOWN_MS) return

        if (shakePeakCount == 1 && gForce <= BASELINE_RESET_G) {
            sawBaselineBetweenPeaks = true
        }

        if (gForce >= SHAKE_THRESHOLD) {
            if (shakePeakCount == 0) {
                shakePeakCount = 1
                sawBaselineBetweenPeaks = false
                firstPeakTimestamp = now
                lastPeakTimestamp = now
            } else if (shakePeakCount == 1) {
                val elapsedSinceFirst = now - firstPeakTimestamp
                val elapsedSinceLast = now - lastPeakTimestamp

                if (sawBaselineBetweenPeaks && elapsedSinceLast >= SHAKE_MIN_INTERVAL_MS && elapsedSinceFirst <= SHAKE_MAX_INTERVAL_MS) {
                    shakePeakCount = 0
                    sawBaselineBetweenPeaks = false
                    lastShakeTriggerTimestamp = now
                    vibrateQuickShake()
                    mainHandler.post {
                        IshaDynamicIsland.show(this@MainActivity, "shake")
                    }
                } else if (elapsedSinceFirst > SHAKE_MAX_INTERVAL_MS) {
                    shakePeakCount = 1
                    sawBaselineBetweenPeaks = false
                    firstPeakTimestamp = now
                    lastPeakTimestamp = now
                }
            }
        } else {
            if (shakePeakCount == 1 && gForce <= BASELINE_RESET_G) {
                sawBaselineBetweenPeaks = true
            }
            if (shakePeakCount > 0 && (now - firstPeakTimestamp > SHAKE_MAX_INTERVAL_MS)) {
                shakePeakCount = 0
                sawBaselineBetweenPeaks = false
            }
        }
    }

    private fun vibrateQuickShake() {
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 75, 60, 75), -1))
            } else {
                @Suppress("DEPRECATION")
                vib?.vibrate(longArrayOf(0, 75, 60, 75), -1)
            }
        } catch (_: Exception) {}
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ── Caller Name Announcer ──────────────────────────────────────────────────
    private fun initNativeTts() {
        try {
            nativeTts = TextToSpeech(applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    nativeTts?.language = Locale("en", "IN")
                    nativeTts?.setSpeechRate(0.92f)
                    isTtsReady = true
                }
            }
        } catch (_: Exception) {}
    }

    private fun announceCaller(callerName: String, number: String) {
        if (hasAnnouncedCurrentCall) return
        val now = System.currentTimeMillis()
        if (now - lastAnnouncedCallerTime < 10000L && (lastAnnouncedCallerNumber == number || number.isBlank())) return

        hasAnnouncedCurrentCall = true
        lastAnnouncedCallerTime = now
        lastAnnouncedCallerNumber = number

        val announcement = if (callerName.isNotBlank() && callerName != "Unknown Caller") {
            "Incoming call from $callerName identified by ISHA"
        } else if (number.isNotBlank()) {
            "Incoming call from $number identified by ISHA"
        } else {
            "Incoming call identified by ISHA"
        }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val ringMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (ringMode == AudioManager.RINGER_MODE_SILENT) return

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_RING)
        }
        if (isTtsReady && nativeTts != null) {
            nativeTts?.speak(announcement, TextToSpeech.QUEUE_FLUSH, params, "ISHA_CALL_ANNOUNCE")
        }
    }

    private fun stopCallerAnnouncement() {
        try {
            nativeTts?.stop()
        } catch (_: Exception) {}
    }

    private fun getContactName(phoneNumber: String): String {
        if (phoneNumber.isBlank()) return "Unknown Caller"

        // 1. Check ISHA's persistent Contact Memory (with old number awareness!)
        val memoryMatch = com.aura.assistant.ai.IshaContactMemoryManager.resolveIncomingNumber(this, phoneNumber)
        if (memoryMatch != null) {
            return if (memoryMatch.isOldNumber) {
                "${memoryMatch.name} (purana number)"
            } else {
                memoryMatch.name
            }
        }

        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        val name = cursor.getString(nameIdx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        } catch (_: Exception) {}
        return phoneNumber
    }
}
