package com.aura.assistant.training

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import com.aura.assistant.MainActivity
import java.nio.charset.StandardCharsets

/**
 * Headless ADB Command Injection & Training Receiver.
 * Forwards user commands directly into AURA's chat interface
 * so AURA herself processes and executes them live on the device.
 */
class AuraAdbReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AuraAdbReceiver"
        const val ACTION_ADB_INJECT = "com.aura.assistant.ADB_INJECT"
        const val ACTION_RUN_TESTS = "com.aura.assistant.RUN_TESTS"
        const val ACTION_RUN_1000_TRAINING = "com.aura.assistant.RUN_1000_TRAINING"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return

        when (action) {
            ACTION_ADB_INJECT -> {
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
                    Log.w(TAG, "ADB_INJECT received with empty command text")
                    return
                }

                Log.i(TAG, "⚡ [ADB_INJECT] Passing command directly to AURA: \"$text\"")

                val act = MainActivity.instance
                if (act != null) {
                    act.injectUserMessage(text)
                } else {
                    // Bring AURA to the foreground and inject command
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("inject_text", text)
                    }
                    if (launchIntent != null) {
                        context.startActivity(launchIntent)
                    }
                }
            }

            ACTION_RUN_1000_TRAINING -> {
                Log.i(TAG, "🚀 [RUN_1000_TRAINING] Broadcast received! Starting on-device 1000+ task training batch...")
                Thread {
                    try {
                        val report = AuraTrainingEngine.run1000TasksBatch(context)
                        Log.i(TAG, "🎉 [RUN_1000_TRAINING] Completed successfully!\n$report")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ [RUN_1000_TRAINING] Failed with error", e)
                    }
                }.apply { name = "aura-1000-trainer"; isDaemon = true; start() }
            }

            ACTION_RUN_TESTS -> {
                Log.i(TAG, "🧪 [RUN_TESTS] Broadcast received. Running tool test suite...")
                AuraTrainingEngine.runTestsIfNeeded(context)
            }
        }
    }
}
