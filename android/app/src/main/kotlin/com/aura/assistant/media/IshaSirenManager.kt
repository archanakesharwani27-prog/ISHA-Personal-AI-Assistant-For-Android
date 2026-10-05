package com.aura.assistant.media

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log

/**
 * High-decibel emergency siren and phone finder engine.
 * Sets STREAM_ALARM to 100%, loops the alarm sound, flashes the torch strobe,
 * and wakes the screen. Can be invoked locally or remotely across the ISHA device mesh.
 */
object IshaSirenManager {
    private const val TAG = "IshaSirenManager"
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var strobeHandler: Handler? = null
    private var isStrobeRunning = false
    private var flashState = false

    @Synchronized
    fun startSiren(context: Context): Boolean {
        return try {
            stopSiren(context)

            val app = context.applicationContext
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, AudioManager.FLAG_SHOW_UI)

            // Acquire wake lock to turn screen on
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "isha:siren_wakelock"
            ).apply { acquire(180_000L) } // 3 minutes safety timeout

            // Use default alarm tone or ringtone
            var alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (alertUri == null) {
                alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(app, alertUri)
                isLooping = true
                setVolume(1.0f, 1.0f)
                prepare()
                start()
            }

            // Start strobe flashlight flashing
            startFlashlightStrobe(app)
            Log.i(TAG, "🚨 Siren started successfully at max alarm volume")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start siren: ${e.message}", e)
            false
        }
    }

    @Synchronized
    fun stopSiren(context: Context): Boolean {
        return try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            mediaPlayer = null

            stopFlashlightStrobe(context.applicationContext)

            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
            Log.i(TAG, "🛑 Siren stopped successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping siren: ${e.message}")
            false
        }
    }

    val isSirenPlaying: Boolean
        get() = mediaPlayer?.isPlaying == true

    private fun startFlashlightStrobe(context: Context) {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
            val camId = cm.cameraIdList.firstOrNull() ?: return
            isStrobeRunning = true
            strobeHandler = Handler(Looper.getMainLooper())
            val strobeRunnable = object : Runnable {
                override fun run() {
                    if (!isStrobeRunning) {
                        try { cm.setTorchMode(camId, false) } catch (_: Exception) {}
                        return
                    }
                    flashState = !flashState
                    try { cm.setTorchMode(camId, flashState) } catch (_: Exception) {}
                    strobeHandler?.postDelayed(this, 180L)
                }
            }
            strobeHandler?.post(strobeRunnable)
        } catch (_: Exception) {}
    }

    private fun stopFlashlightStrobe(context: Context) {
        isStrobeRunning = false
        strobeHandler?.removeCallbacksAndMessages(null)
        strobeHandler = null
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            val camId = cm?.cameraIdList?.firstOrNull() ?: return
            cm.setTorchMode(camId, false)
        } catch (_: Exception) {}
    }
}
