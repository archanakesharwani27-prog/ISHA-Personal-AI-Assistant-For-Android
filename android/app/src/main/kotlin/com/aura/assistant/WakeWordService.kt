package com.aura.assistant

import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import android.provider.Settings
import kotlin.math.abs
import kotlin.math.sqrt
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.WakeWordModel
import com.rementia.openwakeword.lib.model.DetectionMode
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import com.aura.assistant.audio.IshaDiagnostics
import com.aura.assistant.audio.AuraDiagnostics
import com.aura.assistant.IshaDynamicIsland
import com.aura.assistant.audio.MicOwnershipManager
import com.aura.assistant.audio.MicOwner
import com.aura.assistant.wakeword.HeyIshaDetector
import kotlinx.coroutines.isActive
import android.annotation.SuppressLint as SL

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

        private const val COOLDOWN_MS     = 5_000L  // 5.0s cooldown after trigger to prevent echo

        // ── Google/Alexa-style N-Consecutive-Frames Confirmation ──────────────
        // Anti-false-positive calibration:
        // Sustained two-word phrase ("Hey Isha") detection: Requires 4 consecutive frames (>= 0.75f)
        // OR very high confidence (>= 0.90f) sustained for at least 3 consecutive frames (~240ms).
        // Single isolated frame spikes (from horns, vehicle revs, doors, or single words) are NEVER allowed to trigger!
        private const val MIN_SCORE              = 0.75f  // Keyword score threshold
        private const val HIGH_CONFIDENCE_SCORE  = 0.90f  // High-confidence threshold (requires at least 3 frames)
        private const val CONFIRM_FRAMES         = 4      // 4 consecutive frames (~320ms) required for standard score
        private const val HIGH_CONF_FRAMES       = 3      // 3 consecutive frames required (~240ms) to reject single noise spikes

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

    // openWakeWord ONNX acoustic keyword engine (fallback for non-Isha models)
    private var wakeWordEngine: WakeWordEngine? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "WakeWord serviceScope caught uncaught exception: ${t.message}", t)
    })
    private var detectionJob: Job? = null

    // ── HeyIshaDetector (custom 128-dim mel ONNX pipeline) ─────────────────────
    private var heyIshaDetector: HeyIshaDetector? = null
    private var heyIshaAudioRecord: AudioRecord? = null
    private var heyIshaJob: Job? = null
    @Volatile private var heyIshaConsecutive = 0

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
                    Log.i(TAG, "Screen turned OFF — wake word continues listening in background")
                    // Do NOT pause wake engine here; hands-free wake word must listen while phone is locked/resting!
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.i(TAG, "Screen turned ON")
                    if (isRunning && wakeEnabled && !isPaused && wakeWordEngine == null) {
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
        try {
            com.aura.assistant.auth.IshaAuthManager.init(this)
            com.aura.assistant.sync.IshaDeviceRegistry.init(this)
            com.aura.assistant.sync.IshaCrossDeviceBridge.init(this)
        } catch (e: Exception) {
            android.util.Log.w("WakeWordService", "CrossDeviceBridge init error: ${e.message}")
        }
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
                isRunning = true
                consecutiveFramesAboveThreshold = 0
                lastTriggerTime = System.currentTimeMillis() + 1500L // 1.5s grace period on resume
                // Add 1500ms delay before opening mic so speaker echoes/ambient chatter settle completely
                handler.removeCallbacksAndMessages("WAKE_RESUME_TOKEN")
                handler.postAtTime({
                    if (!isPaused && wakeEnabled && !isTtsSpeaking) {
                        startWakeEngine()
                    }
                }, "WAKE_RESUME_TOKEN", SystemClock.uptimeMillis() + 1500L)
                return START_STICKY
            }
            ACTION_CONFIG -> {
                wakeEnabled = intent.getBooleanExtra(EXTRA_WAKE_ENABLED, prefs.getBoolean("wake_word_enabled", true))
                shakeEnabled = intent.getBooleanExtra(EXTRA_SHAKE_ENABLED, prefs.getBoolean("shake_enabled", true))
                isRunning = true
                updateForegroundNotification()
                if (!wakeEnabled || isPaused) {
                    pauseWakeEngine()
                } else {
                    startWakeEngine()
                }
                if (!shakeEnabled) {
                    stopShakeSensor()
                } else {
                    startShakeSensor()
                }
                return START_STICKY
            }
            ACTION_START -> {
                wakeEnabled = intent.getBooleanExtra(EXTRA_WAKE_ENABLED, prefs.getBoolean("wake_word_enabled", true))
                shakeEnabled = intent.getBooleanExtra(EXTRA_SHAKE_ENABLED, prefs.getBoolean("shake_enabled", true))
                updateForegroundNotification()
                startListening()
                return START_STICKY
            }
            else -> {
                wakeEnabled = intent?.getBooleanExtra(EXTRA_WAKE_ENABLED, prefs.getBoolean("wake_word_enabled", true))
                    ?: prefs.getBoolean("wake_word_enabled", true)
                shakeEnabled = intent?.getBooleanExtra(EXTRA_SHAKE_ENABLED, prefs.getBoolean("shake_enabled", true))
                    ?: prefs.getBoolean("shake_enabled", true)

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
        val hasIsha = (isAssetFileValid("hey_isha.onnx") && getAssetSize("hey_isha.onnx") > 1000) ||
                      (isAssetFileValid("wakeword/hey_isha.onnx") && getAssetSize("wakeword/hey_isha.onnx") > 1000)
        val hasMira = (isAssetFileValid("hey_mira.onnx") && getAssetSize("hey_mira.onnx") > 1000) ||
                      (isAssetFileValid("wakeword/hey_mira.onnx") && getAssetSize("wakeword/hey_mira.onnx") > 1000)
        val hasAura = (isAssetFileValid("wakeword/hey_aura.onnx") && getAssetSize("wakeword/hey_aura.onnx") > 1000)
        return hasMel && hasEmb && (hasIsha || hasMira || hasAura)
    }

    // ── Hybrid engine init ────────────────────────────────────────────────────

    /**
     * Returns true if hey_isha.onnx is available and small enough to use
     * our custom HeyIshaDetector (128-dim mel pipeline).
     * Returns false → fall back to WakeWordEngine for other models.
     */
    private fun shouldUseHeyIshaDetector(): Boolean {
        // Return false: hey_isha.onnx is trained directly on Google openWakeWord embeddings (16x96),
        // so WakeWordEngine (xyz.rementia:openwakeword) executes it directly with maximum accuracy and stability.
        return false
    }

    /**
     * Start the standalone HeyIshaDetector loop.
     * Opens its own AudioRecord, feeds 512-sample frames into HeyIshaDetector,
     * and fires onTriggerDetected on confirmation.
     */
    @SuppressLint("MissingPermission")
    private fun startHeyIshaDetector() {
        if (heyIshaDetector != null) return
        val modelPath = if (isAssetFileValid("wakeword/hey_isha.onnx")) "wakeword/hey_isha.onnx" else "hey_isha.onnx"
        try {
            val detector = HeyIshaDetector(assets, modelPath)
            heyIshaDetector = detector
            Log.i(TAG, "HeyIshaDetector loaded from assets/$modelPath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load HeyIshaDetector — falling back to WakeWordEngine", e)
            heyIshaDetector = null
            initWakeWordEngineFallback()
            return
        }

        val minBufSize = AudioRecord.getMinBufferSize(
            HeyIshaDetector.SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(HeyIshaDetector.FRAME_SIZE * 4)

        val audioRecord = try {
            AudioRecord(
                android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                HeyIshaDetector.SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                minBufSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "HeyIsha AudioRecord create failed", e)
            heyIshaDetector?.close(); heyIshaDetector = null
            return
        }

        heyIshaAudioRecord = audioRecord
        audioRecord.startRecording()
        heyIshaConsecutive = 0
        IshaDiagnostics.updateWake { it.copy(modelLoaded = true, inferenceRunning = true) }
        Log.i(TAG, "HeyIshaDetector AudioRecord started (buffer=$minBufSize)")

        heyIshaJob?.cancel()
        heyIshaJob = serviceScope.launch(Dispatchers.IO) {
            val buf = ShortArray(HeyIshaDetector.FRAME_SIZE)
            val detector = heyIshaDetector ?: return@launch
            while (isActive) {
                val read = audioRecord.read(buf, 0, buf.size)
                if (read <= 0) continue

                val score = detector.process(buf.copyOf(read))
                if (score < 0f) continue  // Not enough samples yet

                val now = System.currentTimeMillis()
                if (score >= HeyIshaDetector.MIN_SCORE) {
                    heyIshaConsecutive++
                    if (score >= 0.10f) {
                        Log.d(TAG, "HeyIsha score=$score consecutive=$heyIshaConsecutive")
                    }
                } else {
                    if (heyIshaConsecutive > 0) {
                        Log.d(TAG, "HeyIsha streak reset (score=$score)")
                    }
                    heyIshaConsecutive = 0
                }

                val triggered = score >= HeyIshaDetector.HIGH_CONFIDENCE_SCORE ||
                                heyIshaConsecutive >= HeyIshaDetector.CONFIRM_FRAMES
                if (triggered && (now - lastTriggerTime > COOLDOWN_MS)) {
                    heyIshaConsecutive = 0
                    lastTriggerTime = now
                    Log.i(TAG, "Hey Isha CONFIRMED! score=$score — triggering")
                    detector.reset()
                    handler.post {
                        stopHeyIshaDetector()
                        onTriggerDetected("wake", score, "Hey Isha")
                    }
                }
            }
        }
    }

    private fun stopHeyIshaDetector() {
        heyIshaJob?.cancel(); heyIshaJob = null
        heyIshaConsecutive = 0
        try {
            heyIshaAudioRecord?.stop()
            heyIshaAudioRecord?.release()
        } catch (_: Exception) {}
        heyIshaAudioRecord = null
        heyIshaDetector?.close(); heyIshaDetector = null
        IshaDiagnostics.updateWake { it.copy(inferenceRunning = false) }
        Log.i(TAG, "HeyIshaDetector stopped")
    }

    /**
     * Fallback: initialise the openWakeWord/rementia WakeWordEngine for non-Isha models.
     */
    private fun initWakeWordEngineFallback() {
        if (wakeWordEngine != null) return
        val hasAudioPerm = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasAudioPerm) {
            Log.w(TAG, "RECORD_AUDIO permission not granted yet. Wake engine startup deferred.")
            return
        }

        try {
            val models = mutableListOf<WakeWordModel>()
            val hasIsha = (isAssetFileValid("wakeword/hey_isha.onnx") && getAssetSize("wakeword/hey_isha.onnx") > 1000) ||
                          (isAssetFileValid("hey_isha.onnx") && getAssetSize("hey_isha.onnx") > 1000)
            val hasAura = isAssetFileValid("wakeword/hey_aura.onnx") && getAssetSize("wakeword/hey_aura.onnx") > 1000
            val phrasePath = when {
                isAssetFileValid("wakeword/hey_isha.onnx") && getAssetSize("wakeword/hey_isha.onnx") > 1000 -> "wakeword/hey_isha.onnx"
                isAssetFileValid("hey_isha.onnx") && getAssetSize("hey_isha.onnx") > 1000                   -> "hey_isha.onnx"
                hasAura                                                                                     -> "wakeword/hey_aura.onnx"
                isAssetFileValid("hey_mira.onnx")                                                           -> "hey_mira.onnx"
                else                                                                                        -> "wakeword/hey_mira.onnx"
            }
            val phraseName = if (hasIsha) "Hey Isha" else if (hasAura) "Hey Aura" else "Hey ISHA"
            val threshold = if (hasIsha) 0.75f else 0.45f
            models.add(WakeWordModel(name = phraseName, modelPath = phrasePath, threshold = threshold))

            val engine = WakeWordEngine(
                context = applicationContext,
                models = models,
                detectionMode = DetectionMode.SINGLE_BEST,
                detectionCooldownMs = 0L,
                scope = serviceScope
            )
            wakeWordEngine = engine
            IshaDiagnostics.updateWake { it.copy(modelLoaded = true) }

            detectionJob?.cancel()
            detectionJob = serviceScope.launch {
                engine.scores
                    .onEach { scoreItem ->
                        val score = scoreItem.score
                        val now   = System.currentTimeMillis()

                        if (score >= 0.15f) {
                            Log.d(TAG, "KWS score=$score for ${scoreItem.model.name}")
                        }

                        if (score >= MIN_SCORE) {
                            consecutiveFramesAboveThreshold++
                        } else {
                            consecutiveFramesAboveThreshold = 0
                        }

                        val isTriggered = (consecutiveFramesAboveThreshold >= CONFIRM_FRAMES) ||
                                          (score >= HIGH_CONFIDENCE_SCORE && consecutiveFramesAboveThreshold >= HIGH_CONF_FRAMES)
                        if (isTriggered && (now - lastTriggerTime > COOLDOWN_MS)) {
                            consecutiveFramesAboveThreshold = 0
                            lastTriggerTime = now
                            Log.i(TAG, "Wake word CONFIRMED! '${scoreItem.model.name}' (score=$score)")
                            handler.post {
                                pauseWakeEngine()
                                onTriggerDetected("wake", score, scoreItem.model.name)
                            }
                        }
                    }
                    .collect()
            }

            engine.start()
            IshaDiagnostics.updateWake { it.copy(inferenceRunning = true, modelLoaded = true) }
            Log.i(TAG, "WakeWordEngine (fallback) started for ${models.map { it.name }}")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize WakeWordEngine fallback", e)
            MicOwnershipManager.releaseOwnership(MicOwner.WAKE_WORD)
            IshaDiagnostics.updateWake { it.copy(micOwned = false, inferenceRunning = false) }
        }
    }

    /** Legacy entry-point kept for compatibility; now routes to correct engine. */
    private fun initWakeWordEngine() = initWakeWordEngineFallback()

    private fun startWakeEngine() {
        if (!wakeEnabled || isPaused || isTtsSpeaking) return
        if (!MicOwnershipManager.requestOwnership(MicOwner.WAKE_WORD)) {
            Log.w(TAG, "Mic currently owned by ${MicOwnershipManager.currentOwner.value} — deferring wake engine")
            IshaDiagnostics.updateWake { it.copy(micOwned = false) }
            return
        }
        IshaDiagnostics.updateWake { it.copy(micOwned = true, serviceRunning = true) }

        // ── Hybrid routing: HeyIshaDetector for hey_isha.onnx, WakeWordEngine for others ──
        if (shouldUseHeyIshaDetector()) {
            if (heyIshaDetector == null) {
                startHeyIshaDetector()
            }
            return
        }

        // Fallback: openWakeWord/rementia engine for hey_mira / hey_aura
        if (!areModelFilesReady()) {
            Log.w(TAG, "Wake model files not ready — deferring")
            return
        }
        if (wakeWordEngine == null) {
            initWakeWordEngineFallback()
            return
        }
        try {
            wakeWordEngine?.start()
            Log.i(TAG, "OpenWakeWord engine started/resumed")
        } catch (e: Throwable) {
            Log.w(TAG, "Wake engine start failed (stale OrtSession), re-initialising...", e)
            runCatching { wakeWordEngine?.release() }
            wakeWordEngine = null
            handler.postDelayed({ initWakeWordEngineFallback() }, 300L)
        }
    }

    private fun pauseWakeEngine() {
        try {
            MicOwnershipManager.releaseOwnership(MicOwner.WAKE_WORD)
            IshaDiagnostics.updateWake { it.copy(micOwned = false, inferenceRunning = false) }

            // Stop HeyIshaDetector if running
            stopHeyIshaDetector()

            // Stop WakeWordEngine if running
            detectionJob?.cancel()
            detectionJob = null
            val engine = wakeWordEngine
            wakeWordEngine = null
            if (engine != null) {
                engine.stop()
                serviceScope.launch {
                    kotlinx.coroutines.delay(120L)
                    runCatching { engine.release() }
                }
                Log.i(TAG, "OpenWakeWord engine stopped cleanly (mic closed)")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error pausing wake engine", e)
        }
    }

    private fun stopListening() {
        isRunning = false
        MicOwnershipManager.releaseOwnership(MicOwner.WAKE_WORD)
        IshaDiagnostics.updateWake { it.copy(serviceRunning = false, micOwned = false, inferenceRunning = false) }
        stopHeyIshaDetector()
        stopShakeSensor()
        detectionJob?.cancel()
        detectionJob = null
        try {
            wakeWordEngine?.stop()
            wakeWordEngine?.release()
            Log.i(TAG, "OpenWakeWord engine released")
        } catch (e: Throwable) {
            Log.e(TAG, "Error releasing OpenWakeWord engine", e)
        }
        wakeWordEngine = null
    }

    // ── Gemini-Style Assistant Wake Trigger ────────────────────────────────────

    private fun onTriggerDetected(reason: String, score: Float = 0.0f, modelName: String = "") {
        Log.i(TAG, "Trigger detected: $reason (score=$score, model=$modelName) — starting Gemini Live Voice Chat directly")

        // Immediately pause wake engine and release mic ownership so Gemini Live can acquire it without contention
        pauseWakeEngine()
        MicOwnershipManager.releaseOwnership(MicOwner.WAKE_WORD)

        // 0. Immediate Haptic & Audio Chime Feedback so Boss knows ISHA is actively listening
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib?.vibrate(VibrationEffect.createOneShot(75L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib?.vibrate(75L)
            }
        } catch (_: Exception) {}

        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager?.ringerMode != AudioManager.RINGER_MODE_SILENT) {
                val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
                toneGen.startTone(ToneGenerator.TONE_PROP_PROMPT, 140)
                handler.postDelayed({
                    try { toneGen.release() } catch (_: Exception) {}
                }, 300L)
            }
        } catch (_: Exception) {}

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

        // 1. Show Dynamic Island overlay bubble on top (preserves user's foreground apps e.g. games, YouTube, WhatsApp)
        IshaDynamicIsland.show(applicationContext, "voice_mode")

        // 2. Engage Gemini Live voice duplex directly
        val act = MainActivity.instance
        if (act != null) {
            act.startVoiceModeDirectly()
        } else {
            // If MainActivity not yet in memory, launch it
            try {
                val voiceIntent = Intent(applicationContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("open_voice_mode", true)
                    putExtra(EXTRA_REASON, reason)
                    putExtra(EXTRA_SCORE, score)
                    putExtra(EXTRA_MODEL, modelName)
                }
                startActivity(voiceIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch MainActivity for voice mode", e)
            }
        }

        // 3. Broadcast to MainActivity in case it is already active
        sendBroadcast(Intent(ACTION_WAKE).apply {
            `package` = packageName
            putExtra(EXTRA_REASON, reason)
            putExtra(EXTRA_SCORE, score)
            putExtra(EXTRA_MODEL, modelName)
        })
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

