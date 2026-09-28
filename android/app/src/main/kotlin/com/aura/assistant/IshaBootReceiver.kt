package com.aura.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Restarts the WakeWordService after device reboot if permissions are granted.
 */
open class IshaBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        // Do NOT start foreground service on MY_PACKAGE_REPLACED — Android 14 strictly forbids
        // starting foreground microphone/specialUse services from the background upon package replacement.
        if (action == Intent.ACTION_BOOT_COMPLETED) {
            val hasAudioPerm = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (!hasAudioPerm) {
                // Defer until user launches app and grants permissions
                return
            }

            val svc = Intent(context, WakeWordService::class.java).apply {
                this.action = WakeWordService.ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svc)
                } else {
                    context.startService(svc)
                }
            } catch (_: Throwable) {
                // Background start restriction on Android 12+ or pending permissions
            }
        }
    }
}

/** Backward compatibility subclass for AuraBootReceiver */
class AuraBootReceiver : IshaBootReceiver()

