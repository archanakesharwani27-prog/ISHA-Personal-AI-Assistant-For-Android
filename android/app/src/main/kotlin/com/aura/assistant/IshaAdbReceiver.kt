package com.aura.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import java.nio.charset.StandardCharsets

/**
 * Headless ADB Command Injection Receiver.
 * Allows testing and feeding commands directly into AURA via:
 * adb shell am broadcast -a com.aura.assistant.ADB_INJECT --es text "your command"
 */
open class IshaAdbReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "IshaAdbReceiver"
        const val ACTION_ADB_INJECT = "com.aura.assistant.ADB_INJECT"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action == ACTION_ADB_INJECT) {
            val rawText = intent.getStringExtra("text")
                ?: intent.getStringExtra("command")
                ?: intent.getStringExtra("cmd")

            val text = if (!rawText.isNullOrBlank()) {
                rawText.trim()
            } else {
                val b64 = intent.getStringExtra("b64")
                if (!b64.isNullOrBlank()) {
                    try {
                        String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8).trim()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to decode base64 command", e)
                        null
                    }
                } else null
            }

            if (text.isNullOrBlank()) {
                Log.w(TAG, "ADB_INJECT received empty command text")
                return
            }

            Log.i(TAG, "⚡ [ADB_INJECT] Injecting command into AURA: \"$text\"")

            val act = MainActivity.instance
            if (act != null) {
                act.injectUserMessage(text)
            } else {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("inject_text", text)
                }
                if (launchIntent != null) {
                    context.startActivity(launchIntent)
                }
            }
        }
    }
}

/** Backward compatibility subclass for AuraAdbReceiver */
class AuraAdbReceiver : IshaAdbReceiver()

