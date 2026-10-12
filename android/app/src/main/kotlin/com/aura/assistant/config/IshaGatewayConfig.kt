package com.aura.assistant.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Manages configuration and routing for the ISHA Cloudflare Edge Gateway.
 * Enables the "Zero-Key" experience so end-users can use ISHA without entering an API key,
 * while still supporting custom API keys for power users.
 * 
 * Supports:
 * 1. SSE Chat Stream Gateway proxy
 * 2. Ephemeral Token Minting & in-memory caching for zero-latency Gemini Live WebSocket
 */
object IshaGatewayConfig {

    private const val TAG = "IshaGatewayConfig"
    private const val PREFS_NAME = "isha_gateway_config"

    private const val KEY_GATEWAY_ENABLED = "gateway_enabled"
    private const val KEY_GATEWAY_URL = "gateway_url"
    private const val KEY_GATEWAY_SECRET = "gateway_secret"

    // Default Gateway URL deployed on Cloudflare Workers
    const val DEFAULT_GATEWAY_URL = "https://isha-gateway.anshkesharwani0807.workers.dev"
    const val DEFAULT_GATEWAY_WS_URL = "wss://isha-gateway.anshkesharwani0807.workers.dev/live"
    const val DEFAULT_APP_SECRET = "isha_mobile_sec_v2_edge"

    // In-memory cache for Ephemeral Tokens (Valid for 30m, refreshed at 25m)
    @Volatile private var cachedEphemeralToken: String? = null
    @Volatile private var tokenExpiryTime: Long = 0L

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Returns whether the Cloudflare Gateway proxy is enabled.
     * Enabled by default to provide Zero-Key functionality out of the box.
     */
    fun isGatewayEnabled(context: Context): Boolean {
        val hasDirectKey = Secrets.getActiveGeminiKey(context).isNotBlank()
        if (hasDirectKey) {
            return getPrefs(context).getBoolean(KEY_GATEWAY_ENABLED, false)
        }
        return getPrefs(context).getBoolean(KEY_GATEWAY_ENABLED, true)
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
     * If Gateway is active and not the broken default worker, returns Cloudflare edge proxy URL.
     * Otherwise, returns direct Google Generative Language endpoint with key.
     */
    fun getChatStreamUrl(context: Context, model: String, apiKey: String): Pair<String, Map<String, String>> {
        val headers = mutableMapOf<String, String>()
        val base = getGatewayBaseUrl(context)
        val isDefaultBrokenWorker = base.contains("isha-gateway.anshkesharwani0807.workers.dev")
        val useGateway = isGatewayEnabled(context) && !isDefaultBrokenWorker

        return if (useGateway || apiKey.isBlank()) {
            headers["X-Isha-App-Token"] = getAppSecret(context)
            Pair("$base/v1beta/models/$model:streamGenerateContent?alt=sse", headers)
        } else {
            Pair("https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse&key=$apiKey", headers)
        }
    }

    /**
     * Invalidate any cached ephemeral token (e.g. on disconnect or WebSocket close).
     */
    fun invalidateEphemeralToken() {
        cachedEphemeralToken = null
        tokenExpiryTime = 0L
    }

    /**
     * Retrieves or refreshes an ephemeral token from the Cloudflare Gateway.
     * When forceRefresh is true, mints a fresh single-use token from the gateway.
     */
    suspend fun getOrFetchEphemeralToken(context: Context, forceRefresh: Boolean = true): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = cachedEphemeralToken
        if (!forceRefresh && !cached.isNullOrBlank() && now < tokenExpiryTime) {
            return@withContext cached
        }

        try {
            val base = getGatewayBaseUrl(context)
            val url = "$base/api/token"
            val request = Request.Builder()
                .url(url)
                .post("{}".toRequestBody("application/json".toMediaType()))
                .addHeader("User-Agent", "Isha/2.1")
                .addHeader("X-Isha-App-Token", getAppSecret(context))
                .build()

            val resp = httpClient.newCall(request).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string() ?: ""
                val json = JsonParser.parseString(body).asJsonObject
                val tokenName = json.get("name")?.asString ?: ""
                val cleanToken = if (tokenName.contains("/")) tokenName.substringAfterLast("/") else tokenName
                if (cleanToken.isNotBlank()) {
                    cachedEphemeralToken = cleanToken
                    tokenExpiryTime = now + (15 * 60 * 1000L)
                    Log.i(TAG, "Minted fresh Gemini Live Ephemeral Token: ${cleanToken.take(10)}...")
                    return@withContext cleanToken
                }
            } else {
                Log.w(TAG, "Gateway /api/token returned HTTP ${resp.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch ephemeral token from Gateway", e)
        }
        return@withContext null
    }

    /**
     * Resolves the WebSocket URL for Gemini Live native audio duplex.
     * Always mints a fresh Ephemeral Token to prevent 'Token has been used too many times' 1011 close.
     */
    suspend fun getLiveWsUrlAsync(context: Context, customKey: String): String {
        // If user configured a custom key in Settings and gateway is not forced, use key directly
        if (customKey.isNotBlank() && !isGatewayEnabled(context)) {
            return "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$customKey"
        }

        // Zero-Key Mode: Always fetch a fresh single-use Ephemeral Token
        val token = getOrFetchEphemeralToken(context, forceRefresh = true)
        if (!token.isNullOrBlank()) {
            return "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained?access_token=$token"
        }

        // Fallback: If custom key or BuildConfig key exists
        if (customKey.isNotBlank()) {
            return "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$customKey"
        }

        return "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }

    /**
     * Synchronous fallback endpoint for backward compatibility.
     */
    fun getLiveWsEndpoint(context: Context, apiKey: String): Pair<String, Map<String, String>> {
        val headers = mutableMapOf<String, String>()
        val cached = cachedEphemeralToken
        return if (!cached.isNullOrBlank() && System.currentTimeMillis() < tokenExpiryTime) {
            Pair("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained?access_token=$cached", headers)
        } else if (apiKey.isNotBlank()) {
            Pair("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey", headers)
        } else {
            val base = getGatewayBaseUrl(context)
            headers["X-Isha-App-Token"] = getAppSecret(context)
            Pair("$base/live", headers)
        }
    }
}
