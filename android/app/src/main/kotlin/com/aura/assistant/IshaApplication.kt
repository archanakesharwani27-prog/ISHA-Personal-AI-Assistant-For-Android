package com.aura.assistant

import android.app.Application
import android.util.Log
import com.aura.assistant.auth.IshaAuthManager
import com.aura.assistant.sync.IshaCrossDeviceBridge
import com.aura.assistant.sync.IshaDeviceRegistry

/**
 * Global Application class for ISHA.
 * Pre-warms TTS, Auth, Device Registry, and Cross-Device Bridge 24/7
 * so incoming calls, wake words, and remote commands execute with 0ms cold-start latency.
 */
class IshaApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i("IshaApplication", "Initializing ISHA Core Systems...")
        try {
            // 1. Pre-warm Call Announcer & TTS engine (Truecaller style zero-latency announcement)
            IshaCallAnnouncerManager.init(this)

            // 2. Multi-device ecosystem sync & remote bridge
            IshaAuthManager.init(this)
            IshaDeviceRegistry.init(this)
            IshaCrossDeviceBridge.init(this)
        } catch (e: Exception) {
            Log.e("IshaApplication", "Error during IshaApplication initialization", e)
        }
    }
}
