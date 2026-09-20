package com.aura.assistant.training

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aura.assistant.MainActivity
import com.aura.assistant.brain.OfflineReflexEngine

/**
 * Headless ADB Command Injection & Training Receiver.
 * Declared in AndroidManifest.xml so it can execute even when
 * MainActivity is paused or backgrounded.
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
                val text = intent.getStringExtra("text")
                    ?: intent.getStringExtra("command")
                    ?: intent.getStringExtra("cmd")
                if (text.isNullOrBlank()) {
                    Log.w(TAG, "ADB_INJECT received with empty command text")
                    return
                }

                val start = System.currentTimeMillis()
                Log.i(TAG, "⚡ [ADB_INJECT] Received command: \"$text\"")

                // 1. Try Offline Fast Reflex first (<20ms)
                val reflex = OfflineReflexEngine.tryExecute(context, text)
                val duration = System.currentTimeMillis() - start
                if (reflex != null) {
                    Log.i(TAG, "✅ [ADB_INJECT] Offline Reflex executed [${reflex.toolName}] in ${duration}ms: \"${reflex.naturalResponse}\"")
                } else {
                    Log.i(TAG, "🤖 [ADB_INJECT] Routing command to AI / Tool Engine in ${duration}ms: \"$text\"")
                }

                // 2. If MainActivity is active in foreground, forward to ViewModel to update UI
                MainActivity.instance?.let { act ->
                    act.injectUserMessage(text)
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
