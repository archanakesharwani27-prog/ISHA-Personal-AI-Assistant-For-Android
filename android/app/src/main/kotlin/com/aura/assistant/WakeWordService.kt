package com.aura.assistant

import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import android.provider.Settings
import kotlin.math.abs
import kotlin.math.sqrt
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.WakeWordModel
import com.rementia.openwakeword.lib.model.DetectionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import com.aura.assistant.audio.IshaDiagnostics
import com.aura.assistant.audio.AuraDiagnostics
import com.aura.assistant.IshaDynamicIsland
import com.aura.assistant.audio.MicOwnershipManager
import com.aura.assistant.audio.MicOwner

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import java.net.URLEncoder

/**
 * Persistent background wake-word and shake service.
 *
 * Listens silently using openWakeWord ONNX acoustic keyword spotting (16 kHz mono PCM)
 * to detect the "Hey Aura" trigger phrase in the background with high precision and low battery usage.
 *
 * Also supports device shake detection via accelerometer (at SENSOR_DELAY_UI for low battery usage).
 *
 * On trigger:
 *   - Broadcasts ACTION_WAKE (with reason "wake" or "shake") to MainActivity so Flutter knows.
 *   - Shows AuraDynamicIsland at the top.
 */
class WakeWordService : Service(), SensorEventListener {

    companion object {
        const val ACTION_WAKE = "com.aura.WAKE_WORD"
        const val ACTION_START = "com.aura.wake.START"
        const val ACTION_STOP  = "com.aura.wake.STOP"
        const val ACTION_CONFIG = "com.aura.wake.CONFIG"
        const val ACTION_PAUSE  = "com.aura.wake.PAUSE"
        const val ACTION_RESUME = "com.aura.wake.RESUME"

        const val EXTRA_REASON = "reason"
        const val EXTRA_SCORE  = "score"
        const val EXTRA_MODEL  = "model"
        const val EXTRA_WAKE_ENABLED = "wake_enabled"
        const val EXTRA_SHAKE_ENABLED = "shake_enabled"

        private const val CHANNEL_ID   = "aura_wake_silent"
        private const val CHANNEL_NAME = "AURA Background Listening"
        private const val NOTIF_ID     = 9001

        private const val TAG = "AuraWakeWord"

        // Audio config
        private const val SAMPLE_RATE   = 16_000
        private const val FRAME_SIZE    = 512   // ~32 ms per frame @ 16 kHz

        private const val COOLDOWN_MS     = 3_500L  // 3.5s cooldown after trigger

        // ── Google/Alexa-style N-Consecutive-Frames Confirmation ──────────────
        // A score spike from a single noise/clap frame is NOT a wake word.
        // Score must stay above MIN_SCORE for CONFIRM_FRAMES consecutive frames
        // (~240ms @ 80ms/chunk) before we fire. This prevents random voice false triggers.
        private const val MIN_SCORE      = 0.60f  // Robust acoustic keyword threshold (prevents random noise triggers)
        private const val CONFIRM_FRAMES = 3       // 3 consecutive frames required for confirmation

        // AURA Calibrated Double Quick-Shake Config
        private const val SHAKE_THRESHOLD       = 3.25f // Calibrated shake peak (3.25g)
        private const val BASELINE_RESET_G      = 1.50f // Drops below ~1.5g after first peak
        private const val SHAKE_MIN_INTERVAL_MS = 120L  // Minimum reversal time between shakes
        private const val SHAKE_MAX_INTERVAL_MS = 600L  // Max window for 2 consecutive shakes
        private const val SHAKE_COOLDOWN_MS     = 2500L // Cooldown after activation

        @Volatile var isRunning = false
        @Volatile var wakeEnabled = false
        @Volatile var shakeEnabled = false
        @Volatile var isPaused = false
        @Volatile var isTtsSpeaking = false  // True while Aura is speaking — prevents self-echo

        const val ACTION_TTS_START = "com.aura.wake.TTS_START"  // Flutter → Kotlin: TTS started
        const val ACTION_TTS_END   = "com.aura.wake.TTS_END"    // Flutter → Kotlin: TTS finished
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastTriggerTime = 0L

    // N-consecutive-frames counter (Google KWS style)
    @Volatile private var consecutiveFramesAboveThreshold = 0

    // openWakeWord ONNX acoustic keyword engine
    private var wakeWordEngine: WakeWordEngine? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private var detectionJob: Job? = null

    // AURA Quick Shake state
    private var shakePeakCount = 0
    private var sawBaselineBetweenPeaks = false
    private var firstPeakTimestamp = 0L
    private var lastPeakTimestamp = 0L
    private var lastShakeTriggerTimestamp = 0L

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null

    // Screen + TTS state receiver
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(TAG, "Screen turned OFF — pausing mic (privacy + battery)")
                    pauseWakeEngine()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.i(TAG, "Screen turned ON — resuming wake word mic if enabled")
                    if (isRunning && wakeEnabled && !isPaused) {
                        startWakeEngine()
                    }
                }
                ACTION_TTS_START -> {
                    Log.i(TAG, "TTS speaking — muting wake mic to prevent self-echo")
                    isTtsSpeaking = true
                    pauseWakeEngine()  // Stop mic entirely while Aura speaks
                }
                ACTION_TTS_END -> {
                    Log.i(TAG, "TTS finished — resuming wake mic after 1800ms delay (echo settling)")
                    isTtsSpeaking = false
                    // Resume with 1800ms delay to let speaker audio fully decay from mic input.
                    handler.postDelayed({
                        if (isRunning && wakeEnabled && !isPaused && !isTtsSpeaking) {
                            startWakeEngine()
                        }
                    }, 1800L)
                }
                Intent.ACTION_USER_PRESENT -> {
                    Log.i(TAG, "Device unlocked (ACTION_USER_PRESENT) — checking morning priority reminders")
                    checkAndTriggerMorningReminder(context ?: applicationContext)
                }
                "com.aura.assistant.SHAKE_TOGGLE" -> {
                    val enabled = intent.getBooleanExtra("enabled", false)
                    shakeEnabled = enabled
                    if (enabled) startShakeSensor() else stopShakeSensor()
                    Log.i(TAG, "Shake detection runtime toggle received in WakeWordService: enabled=$enabled")
                }
            }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("aura_settings", Context.MODE_PRIVATE)
        shakeEnabled = prefs.getBoolean("shake_enabled", false)
        createSilentNotificationChannel()
        initShakeSensor()
        registerScreenReceiver()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerScreenReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(ACTION_TTS_START)   // Flutter broadcasts when TTS starts speaking
                addAction(ACTION_TTS_END)     // Flutter broadcasts when TTS finishes
                addAction("com.aura.assistant.SHAKE_TOGGLE")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register screenReceiver", e)
        }
    }

    private fun updateForegroundNotification() {
        try {
            val notif = buildSilentNotification()
            val hasMicPerm = androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val fgsType = if (hasMicPerm) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                startForeground(NOTIF_ID, notif, fgsType)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsType = if (hasMicPerm) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                }
                startForeground(NOTIF_ID, notif, fgsType)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateForegroundNotification error (${e.message})", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("aura_settings", Context.MODE_PRIVATE)
        when (intent?.action) {
            ACTION_STOP -> { stopListening(); stopShakeSensor(); stopSelf(); return START_NOT_STICKY }
            ACTION_PAUSE -> { isPaused = true; pauseWakeEngine(); return START_STICKY }
            ACTION_RESUME -> {
                isPaused = false
                if (isRunning && wakeEnabled) startWakeEngine()
                return START_STICKY
            }
            ACTION_CONFIG -> {
                wakeEnabled = intent.getBooleanExtra(EXTRA_WAKE_ENABLED, prefs.getBoolean("wake_word_enabled", false))
                shakeEnabled = intent.getBooleanExtra(EXTRA_SHAKE_ENABLED, prefs.getBoolean("shake_enabled", false))
                updateForegroundNotification()
                if (!wakeEnabled || isPaused) pauseWakeEngine()
                else if (isRunning) startWakeEngine()
                if (!shakeEnabled) stopShakeSensor()
                else startShakeSensor()
                return START_STICKY
            }
            else -> {
                wakeEnabled = intent?.getBooleanExtra(EXTRA_WAKE_ENABLED, prefs.getBoolean("wake_word_enabled", false))
                    ?: prefs.getBoolean("wake_word_enabled", false)
                shakeEnabled = intent?.getBooleanExtra(EXTRA_SHAKE_ENABLED, prefs.getBoolean("shake_enabled", false))
                    ?: prefs.getBoolean("shake_enabled", false)

                updateForegroundNotification()
                startListening()
            }
        }
        return START_STICKY   // Restart if killed
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        stopListening()
        super.onDestroy()
    }

    // ── Notification (silent, minimal — like Gemini) ───────────────────────────

    private fun createSilentNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_MIN   // No sound, no vibration, no heads-up
        ).apply {
            description = "AURA wake word detection"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        mgr.createNotificationChannel(channel)
    }

    private fun buildSilentNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val openIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this, 0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        // Safe status bar icon: use dedicated vector icon or standard Android system icon
        val smallIconRes = try {
            val resId = resources.getIdentifier("ic_isha_notification", "drawable", packageName)
            if (resId != 0) resId else {
                val auraId = resources.getIdentifier("ic_aura_notification", "drawable", packageName)
                if (auraId != 0) auraId else android.R.drawable.ic_popup_reminder
            }
        } catch (_: Exception) {
            android.R.drawable.ic_popup_reminder
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ISHA")
            .setContentText("ISHA Assistant active — say \"Hey Isha\" or shake")
            .setSmallIcon(smallIconRes)
            .apply { if (openIntent != null) setContentIntent(openIntent) }
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)  // Hidden from shade by default
            .setVisibility(NotificationCompat.VISIBILITY_SECRET) // Hidden on lock screen
            .build()
    }

    // ── AURA Quick Shake Sensor ───────────────────────────────────────────────

    private fun initShakeSensor() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        startShakeSensor()
    }

    private fun startShakeSensor() {
        if (shakeEnabled && accelerometer != null) {
            sensorManager?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun stopShakeSensor() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!shakeEnabled || event == null) {
            stopShakeSensor()
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

        // Baseline-return requirement: phone must drop below ~1.5g after peak 1 before peak 2 can be accepted
        if (shakePeakCount == 1 && gForce <= BASELINE_RESET_G) {
            sawBaselineBetweenPeaks = true
        }

        // Standard double natural shake cadence (reversal within 1.0s)
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
                    // Release mic first; delay broadcast by 200ms so the mic pipeline
                    // fully settles before Flutter's STT tries to open AudioRecord.
                    pauseWakeEngine()
                    handler.postDelayed({
                        onTriggerDetected("shake", 1.0f, "Shake Sensor")
                    }, 200L)
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
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 75, 60, 75), -1))
            } else {
                @Suppress("DEPRECATION")
                vib?.vibrate(longArrayOf(0, 75, 60, 75), -1)
            }
        } catch (_: Exception) {}
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ── Audio Recording Loop with Duty-Cycling and Cadence Filtering ─────────

    private fun startListening() {
        startShakeSensor()
        if (wakeEnabled && !isPaused && !isTtsSpeaking) {
            startWakeEngine()
        } else {
            pauseWakeEngine()
        }
        isRunning = true
    }

    private fun isAssetFileValid(path: String): Boolean {
        return try {
            assets.open(path).use { stream ->
                val buf = ByteArray(64)
                stream.read(buf) > 0
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun getAssetSize(path: String): Long {
        return try {
            assets.open(path).use { it.available().toLong() }
        } catch (_: Exception) {
            0L
        }
    }

    private fun areModelFilesReady(): Boolean {
        val hasMel = isAssetFileValid("melspectrogram.onnx") || isAssetFileValid("wakeword/melspectrogram.onnx")
        val hasEmb = isAssetFileValid("embedding_model.onnx") || isAssetFileValid("wakeword/embedding_model.onnx")
        val hasMira = (isAssetFileValid("hey_mira.onnx") && getAssetSize("hey_mira.onnx") > 1000) ||
                      (isAssetFileValid("wakeword/hey_mira.onnx") && getAssetSize("wakeword/hey_mira.onnx") > 1000)
        val hasAura = (isAssetFileValid("wakeword/hey_aura.onnx") && getAssetSize("wakeword/hey_aura.onnx") > 1000)
        return hasMel && hasEmb && (hasMira || hasAura)
    }

    private fun initWakeWordEngine() {
        if (wakeWordEngine != null) return
        val hasAudioPerm = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasAudioPerm) {
            Log.w(TAG, "RECORD_AUDIO permission not granted yet. Wake engine startup deferred.")
            return
        }

        if (!areModelFilesReady()) {
            Log.w(TAG, "OpenWakeWord models pending in assets/. Ensure acoustic models are present.")
            return
        }

        try {
            val models = mutableListOf<WakeWordModel>()
            val hasAura = isAssetFileValid("wakeword/hey_aura.onnx") && getAssetSize("wakeword/hey_aura.onnx") > 1000
            val phrasePath = if (hasAura) {
                "wakeword/hey_aura.onnx"
            } else if (isAssetFileValid("hey_mira.onnx")) {
                "hey_mira.onnx"
            } else {
                "wakeword/hey_mira.onnx"
            }
            val phraseName = if (hasAura) "Hey Aura" else "Hey Mira"
            models.add(
                WakeWordModel(
                    name = phraseName,
                    modelPath = phrasePath,
                    // Calibrated threshold for acoustic chunk evaluation to prevent noise false triggers
                    threshold = 0.55f
                )
            )

            val engine = WakeWordEngine(
                context = applicationContext,
                models = models,
                detectionMode = DetectionMode.SINGLE_BEST,
                detectionCooldownMs = 0L // 0ms cooldown so engine.scores streams continuously without library throttling
            )
            wakeWordEngine = engine

            detectionJob?.cancel()
            detectionJob = serviceScope.launch {
                engine.scores
                    .onEach { scoreItem ->
                        val score = scoreItem.score
                        val now   = System.currentTimeMillis()

                        // ── Continuous N-Consecutive-Frames Confirmation ──────
                        // Only count frames that are above calibrated MIN_SCORE.
                        if (score >= MIN_SCORE) {
                            consecutiveFramesAboveThreshold++
                            Log.d(TAG, "KWS hit frame ${consecutiveFramesAboveThreshold}/$CONFIRM_FRAMES — score=$score model=${scoreItem.model.name}")
                        } else {
                            // Instant reset on silence or below-threshold acoustic energy
                            if (consecutiveFramesAboveThreshold > 0) {
                                Log.d(TAG, "KWS streak reset to 0 (score=$score dropped below $MIN_SCORE)")
                            }
                            consecutiveFramesAboveThreshold = 0
                        }

                        // ── Fire only after CONFIRM_FRAMES consecutive hits ──
                        if (consecutiveFramesAboveThreshold >= CONFIRM_FRAMES &&
                            now - lastTriggerTime > COOLDOWN_MS) {
                            consecutiveFramesAboveThreshold = 0  // Reset for next detection
                            lastTriggerTime = now
                            Log.i(TAG, "Wake word CONFIRMED after $CONFIRM_FRAMES frames — '${scoreItem.model.name}' (score: $score) — triggering")
                            handler.post {
                                pauseWakeEngine()
                                onTriggerDetected("wake", score, scoreItem.model.name)
                            }
                        }
                    }
                    .collect()
            }

            try {
                engine.start()
                Log.i(TAG, "OpenWakeWord engine initialized and started successfully for ${models.map { it.name }}")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to start OpenWakeWord engine at runtime", e)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize OpenWakeWord engine", e)
        }
    }

    private fun startWakeEngine() {
        if (!wakeEnabled || isPaused || isTtsSpeaking) return
        if (!MicOwnershipManager.requestOwnership(MicOwner.WAKE_WORD)) {
            Log.w(TAG, "Mic currently owned by ${MicOwnershipManager.currentOwner.value} — deferring wake engine")
            IshaDiagnostics.updateWake { it.copy(micOwned = false) }
            return
        }
        IshaDiagnostics.updateWake { it.copy(micOwned = true, serviceRunning = true) }
        if (wakeWordEngine == null) {
            initWakeWordEngine()
            return
        }
        try {
            wakeWordEngine?.start()
            Log.i(TAG, "OpenWakeWord engine started/resumed")
        } catch (e: Throwable) {
            // OrtSession may be closed after a previous stop() — release and re-create the engine.
            Log.w(TAG, "Wake engine start failed (stale OrtSession), re-initialising...", e)
            runCatching { wakeWordEngine?.release() }
            wakeWordEngine = null
            handler.postDelayed({ initWakeWordEngine() }, 300L)
        }
    }

    private fun pauseWakeEngine() {
        try {
            MicOwnershipManager.releaseOwnership(MicOwner.WAKE_WORD)
            IshaDiagnostics.updateWake { it.copy(micOwned = false, inferenceRunning = false) }
            detectionJob?.cancel()
            detectionJob = null
            wakeWordEngine?.stop()
            wakeWordEngine?.release()
            wakeWordEngine = null
            Log.i(TAG, "OpenWakeWord engine stopped and released (mic closed, zero green dot)")
        } catch (e: Throwable) {
            Log.e(TAG, "Error pausing OpenWakeWord engine", e)
        }
    }

    private fun stopListening() {
        isRunning = false
        MicOwnershipManager.releaseOwnership(MicOwner.WAKE_WORD)
        IshaDiagnostics.updateWake { it.copy(serviceRunning = false, micOwned = false, inferenceRunning = false) }
        pauseWakeEngine()
        stopShakeSensor()
        detectionJob?.cancel()
        detectionJob = null
        try {
            wakeWordEngine?.release()
            Log.i(TAG, "OpenWakeWord engine released")
        } catch (e: Throwable) {
            Log.e(TAG, "Error releasing OpenWakeWord engine", e)
        }
        wakeWordEngine = null
    }

    // ── Gemini-Style Assistant Wake Trigger ────────────────────────────────────

    private fun onTriggerDetected(reason: String, score: Float = 0.0f, modelName: String = "") {
        Log.i(TAG, "Trigger detected: $reason (score=$score, model=$modelName) — presenting Gemini Assistant Floating Overlay")

        // Immediately release mic ownership so Gemini Live can acquire it without contention
        MicOwnershipManager.releaseOwnership(MicOwner.WAKE_WORD)
        IshaDiagnostics.updateWake {
            it.copy(
                micOwned = false,
                detectionCount = it.detectionCount + 1,
                lastDetectionTime = System.currentTimeMillis(),
                lastModelName = modelName,
                lastScore = score
            )
        }

        // Acquire a brief 2-second wake lock in case the display was sleeping
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wakeLock = powerManager?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "aura:GeminiOverlayWakeLock"
            )
            wakeLock?.acquire(2000L)
        } catch (_: Exception) {}

        // 1. Show the sleek Gemini Floating Overlay Card directly over the active app!
        // (YouTube, WhatsApp, Browser remain completely visible underneath)
        IshaDynamicIsland.show(applicationContext, reason)

        // 2. Broadcast to MainActivity in case it is already active
        sendBroadcast(Intent(ACTION_WAKE).apply {
            `package` = packageName
            putExtra(EXTRA_REASON, reason)
            putExtra(EXTRA_SCORE, score)
            putExtra(EXTRA_MODEL, modelName)
        })

        // Notice: We intentionally do NOT call bringAppToForeground() here!
        // Full app opens only if the user explicitly taps "Open Full App" on the overlay.
    }

    // ── Morning First-Unlock Event Reminder Trigger ───────────────────────────

    private fun checkAndTriggerMorningReminder(ctx: Context) {
        try {
            val prefs = ctx.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            val isActive = prefs.getBoolean("flutter.aura_morning_reminder_active", false)
            if (!isActive) return

            val title = prefs.getString("flutter.aura_morning_reminder_title", "Boss, Important Reminder! ☀️") ?: "Boss, Important Reminder! ☀️"
            val message = prefs.getString("flutter.aura_morning_reminder_message", "Aapka scheduled reminder ready hai.") ?: "Aapka scheduled reminder ready hai."
            val contact = prefs.getString("flutter.aura_morning_reminder_contact", "") ?: ""

            // Reset active flag so it doesn't repeat on subsequent unlocks today
            prefs.edit().putBoolean("flutter.aura_morning_reminder_active", false).apply()

            Log.i(TAG, "Triggering morning unlock reminder: $title")

            // 1. Post rich heads-up notification with 1-tap WhatsApp action
            showMorningReminderNotification(ctx, title, message, contact)

            // 2. Also trigger Dynamic Island overlay if overlay permission is active
            if (Settings.canDrawOverlays(ctx)) {
                IshaDynamicIsland.show(ctx, "morning_reminder")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking morning reminder", e)
        }
    }

    private fun showMorningReminderNotification(ctx: Context, title: String, message: String, contact: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channelId = "aura_reminders_priority"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "AURA Priority Reminders",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "High priority reminders for events, birthdays and occasions"
                    enableVibration(true)
                    setShowBadge(true)
                }
                nm.createNotificationChannel(channel)
            }

            val launchIntent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("from_morning_reminder", true)
                putExtra("reminder_title", title)
                putExtra("reminder_message", message)
            }
            val contentPI = PendingIntent.getActivity(
                ctx, 1001, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(ctx, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(contentPI)

            if (contact.isNotBlank()) {
                val cleanNum = contact.replace(Regex("[^0-9+]"), "")
                val uriStr = if (cleanNum.length >= 7) {
                    "https://api.whatsapp.com/send?phone=$cleanNum&text=${URLEncoder.encode(message, "UTF-8")}"
                } else {
                    "https://api.whatsapp.com/send?text=${URLEncoder.encode(message, "UTF-8")}"
                }
                val waIntent = Intent(Intent.ACTION_VIEW, Uri.parse(uriStr)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                val waPI = PendingIntent.getActivity(
                    ctx, 1002, waIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(android.R.drawable.ic_menu_send, "WhatsApp Pe Wish Karein 💬", waPI)
            }

            nm.notify(9002, builder.build())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show morning reminder notification", e)
        }
    }
}

