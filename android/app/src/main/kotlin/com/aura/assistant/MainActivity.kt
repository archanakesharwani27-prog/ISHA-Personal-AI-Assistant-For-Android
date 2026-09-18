package com.aura.assistant

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
import android.speech.tts.TextToSpeech
import android.telephony.TelephonyManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.aura.assistant.ui.contract.AuraAssistantViewModel
import com.aura.assistant.ui.screens.AuraMainScreen
import com.aura.assistant.ui.theme.AuraTheme
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
    }

    private val viewModel: AuraAssistantViewModel by viewModels()
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Shake Sensor State ─────────────────────────────────────────────────────
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var isShakeDetectionEnabled = true

    // ── Settings Toggle Receivers ──────────────────────────────────────────────
    private val shakeToggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.aura.assistant.SHAKE_TOGGLE") {
                val enabled = intent.getBooleanExtra("enabled", true)
                isShakeDetectionEnabled = enabled
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
    private val callReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""
                val callerName = if (incomingNumber.isNotBlank()) getContactName(incomingNumber) else "Unknown Caller"

                when (stateStr) {
                    TelephonyManager.EXTRA_STATE_RINGING -> {
                        announceCaller(callerName, incomingNumber)
                    }
                    TelephonyManager.EXTRA_STATE_OFFHOOK,
                    TelephonyManager.EXTRA_STATE_IDLE -> {
                        stopCallerAnnouncement()
                        hasAnnouncedCurrentCall = false
                    }
                }
            } else if (intent?.action == AuraCallScreeningService.ACTION_PRE_CALL_SCREENED) {
                val callerName = intent.getStringExtra(AuraCallScreeningService.EXTRA_CALLER_NAME) ?: "Unknown Caller"
                val number = intent.getStringExtra(AuraCallScreeningService.EXTRA_NUMBER) ?: ""
                announceCaller(callerName, number)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
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
        startWakeWordService()
        handleWakeIntent(intent)

        setContent {
            AuraTheme {
                AuraMainScreen(viewModel = viewModel)
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
        val reason = intent.getStringExtra(WakeWordService.EXTRA_REASON)
        if (action == "com.aura.assistant.WAKE_ACTIVATE" || action == WakeWordService.ACTION_WAKE || reason != null) {
            mainHandler.postDelayed({
                viewModel.onMicButtonPressed()
            }, 300)
        } else if (action == Intent.ACTION_ASSIST || action == "android.intent.action.VOICE_COMMAND") {
            mainHandler.postDelayed({
                viewModel.onMicButtonPressed()
            }, 300)
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

    private fun checkAndRequestPermissions() {
        val perms = mutableListOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.CALL_PHONE,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), PERMISSIONS_REQUEST_CODE)
        }
    }

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
                addAction(AuraCallScreeningService.ACTION_PRE_CALL_SCREENED)
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
    }

    // ── Shake Sensor ──────────────────────────────────────────────────────────
    private fun initShakeSensor() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (isShakeDetectionEnabled && accelerometer != null) {
            sensorManager?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onResume() {
        super.onResume()
        if (isShakeDetectionEnabled && accelerometer != null) {
            sensorManager?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        sensorManager?.unregisterListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        try { unregisterReceiver(wakeReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(callReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(shakeToggleReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(wakeToggleReceiver) } catch (_: Exception) {}
        stopCallerAnnouncement()
        try { nativeTts?.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isShakeDetectionEnabled || event == null) return
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
                        AuraDynamicIsland.show(this@MainActivity, "shake")
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
            "Incoming call from $callerName identified by AURA"
        } else if (number.isNotBlank()) {
            "Incoming call from $number identified by AURA"
        } else {
            "Incoming call identified by AURA"
        }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val ringMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (ringMode == AudioManager.RINGER_MODE_SILENT) return

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_RING)
        }
        if (isTtsReady && nativeTts != null) {
            nativeTts?.speak(announcement, TextToSpeech.QUEUE_FLUSH, params, "AURA_CALL_ANNOUNCE")
        }
    }

    private fun stopCallerAnnouncement() {
        try {
            nativeTts?.stop()
        } catch (_: Exception) {}
    }

    private fun getContactName(phoneNumber: String): String {
        if (phoneNumber.isBlank()) return "Unknown Caller"
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
