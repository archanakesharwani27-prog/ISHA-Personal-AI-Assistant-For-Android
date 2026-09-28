package com.aura.assistant.config

import android.content.Context
import com.aura.assistant.BuildConfig

/**
 * Verified primary API credentials for ISHA.
 * Supports user-entered custom keys from ApiKeyManager with fallback.
 */
object Secrets {
    // Default fallback key (injected at build time from local.properties or BuildConfig)
    val GEMINI_API_KEY: String = BuildConfig.GEMINI_API_KEY

    /**
     * Returns the active Gemini API key.
     * Prioritizes user-entered key from ApiKeyManager if context is available.
     */
    fun getActiveGeminiKey(context: Context? = null): String {
        if (context != null) {
            val userKey = ApiKeyManager.getApiKey(context)
            if (userKey.isNotBlank()) {
                return userKey
            }
        }
        return GEMINI_API_KEY
    }
}
