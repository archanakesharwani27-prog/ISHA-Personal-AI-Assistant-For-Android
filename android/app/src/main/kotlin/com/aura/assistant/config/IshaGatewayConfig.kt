package com.aura.assistant.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Manages configuration and routing for the ISHA Cloudflare Edge Gateway.
 * Enables the "Zero-Key" experience so end-users can use ISHA without entering an API key,
 * while still supporting custom API keys for power users.
 */
object IshaGatewayConfig {

    private const val TAG = "IshaGatewayConfig"
    private const val PREFS_NAME = "isha_gateway_config"

    private const val KEY_GATEWAY_ENABLED = "gateway_enabled"
    private const val KEY_GATEWAY_URL = "gateway_url"
    private const val KEY_GATEWAY_SECRET = "gateway_secret"

    // Default Gateway URL deployed on Cloudflare Workers
    const val DEFAULT_GATEWAY_URL = "https://isha-gateway.archanakesharwani27.workers.dev"
    const val DEFAULT_GATEWAY_WS_URL = "wss://isha-gateway.archanakesharwani27.workers.dev/live"
    const val DEFAULT_APP_SECRET = "isha_mobile_sec_v2_edge"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Returns whether the Cloudflare Gateway proxy is enabled.
     * Enabled by default to provide Zero-Key functionality out of the box.
     */
    fun isGatewayEnabled(context: Context): Boolean {
        // If user explicitly configured a custom API key, prefer direct key unless gateway is forced
        val hasCustomKey = ApiKeyManager.hasCustomKey(context)
        val defaultVal = !hasCustomKey
        return getPrefs(context).getBoolean(KEY_GATEWAY_ENABLED, defaultVal)
    }

    fun setGatewayEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_GATEWAY_ENABLED, enabled).apply()
        Log.i(TAG, "Gateway enabled set to: $enabled")
    }

    fun getGatewayBaseUrl(context: Context): String {
        return getPrefs(context).getString(KEY_GATEWAY_URL, DEFAULT_GATEWAY_URL)?.trim()
            ?.removeSuffix("/") ?: DEFAULT_GATEWAY_URL
    }

    fun setGatewayBaseUrl(context: Context, url: String) {
        val clean = url.trim().removeSuffix("/")
        getPrefs(context).edit().putString(KEY_GATEWAY_URL, clean).apply()
        Log.i(TAG, "Gateway URL updated to: $clean")
    }

    fun getAppSecret(context: Context): String {
        return getPrefs(context).getString(KEY_GATEWAY_SECRET, DEFAULT_APP_SECRET) ?: DEFAULT_APP_SECRET
    }

    /**
     * Resolves the streaming chat URL for a given model.
     * If Gateway is active, returns Cloudflare edge proxy URL.
     * Otherwise, returns direct Google Generative Language endpoint with key.
     */
    fun getChatStreamUrl(context: Context, model: String, apiKey: String): Pair<String, Map<String, String>> {
        val headers = mutableMapOf<String, String>()
        val shouldUseGateway = isGatewayEnabled(context) || apiKey.isBlank()

        return if (shouldUseGateway) {
            val base = getGatewayBaseUrl(context)
            headers["X-Isha-App-Token"] = getAppSecret(context)
            Pair("$base/v1beta/models/$model:streamGenerateContent?alt=sse", headers)
        } else {
            Pair("https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse&key=$apiKey", headers)
        }
    }

    /**
     * Resolves the WebSocket URL for Gemini Live native audio duplex.
     */
    fun getLiveWsEndpoint(context: Context, apiKey: String): Pair<String, Map<String, String>> {
        val headers = mutableMapOf<String, String>()
        val shouldUseGateway = isGatewayEnabled(context) || apiKey.isBlank()

        return if (shouldUseGateway) {
            val base = getGatewayBaseUrl(context)
            val wsBase = if (base.startsWith("https://")) {
                base.replaceFirst("https://", "wss://")
            } else if (base.startsWith("http://")) {
                base.replaceFirst("http://", "ws://")
            } else {
                DEFAULT_GATEWAY_WS_URL
            }
            val wsUrl = "$wsBase/live"
            headers["X-Isha-App-Token"] = getAppSecret(context)
            Pair(wsUrl, headers)
        } else {
            val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
            Pair(wsUrl, headers)
        }
    }
}
