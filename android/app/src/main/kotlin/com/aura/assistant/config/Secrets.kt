package com.aura.assistant.config

/**
 * Verified primary API credentials for AURA.
 * This file is git-ignored and never committed to version control.
 */
object Secrets {
    // ── Primary Verified Gemini API Key (anshkesharwani0807@gmail.com) ─────────
    const val GEMINI_API_KEY = "AQ.Ab8RN6KGIOlNrVk9A0XwRGWS6WMu99oz8XhKk-AQ3s9u7WDMxg"

    /**
     * Returns the active Gemini API key.
     */
    fun getActiveGeminiKey(): String = GEMINI_API_KEY
}
