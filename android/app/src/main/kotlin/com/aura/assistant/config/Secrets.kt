package com.aura.assistant.config

import android.content.Context

/**
 * Verified primary API credentials for AURA.
 * Supports user-entered custom keys from ApiKeyManager with fallback.
 */
object Secrets {
    // Default fallback key (used if no custom key has been entered)
    const val GEMINI_API_KEY = "AQ.Ab8RN6KGIOlNrVk9A0XwRGWS6WMu99oz8XhKk-AQ3s9u7WDMxg"

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
