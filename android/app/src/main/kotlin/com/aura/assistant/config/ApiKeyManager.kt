package com.aura.assistant.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Manages user-configured Gemini API keys persistently in SharedPreferences.
 * Provides live online verification before saving.
 */
object ApiKeyManager {

    private const val TAG = "ApiKeyManager"
    private const val PREFS_NAME = "aura_api_config"
    private const val KEY_USER_API_KEY = "user_gemini_api_key"
    private const val KEY_OPENROUTER_API_KEY = "user_openrouter_api_key"
    private const val KEY_TRUECALLER_TOKEN = "user_truecaller_token"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Retrieves the stored Gemini API key if present and non-blank.
     */
    fun getApiKey(context: Context): String {
        return getPrefs(context).getString(KEY_USER_API_KEY, "")?.trim() ?: ""
    }

    /**
     * Persistently saves a new Gemini API key.
     */
    fun saveApiKey(context: Context, key: String) {
        val clean = key.trim()
        getPrefs(context).edit().putString(KEY_USER_API_KEY, clean).apply()
        Log.i(TAG, "Saved custom Gemini API key (length=${clean.length})")
    }

    /**
     * Clears the user-configured API key from storage.
     */
    fun clearApiKey(context: Context) {
        getPrefs(context).edit().remove(KEY_USER_API_KEY).apply()
        Log.i(TAG, "Cleared user Gemini API key")
    }

    /**
     * Returns true if a custom key is saved.
     */
    fun hasCustomKey(context: Context): Boolean {
        return getApiKey(context).isNotBlank()
    }

    /**
     * Retrieves the stored OpenRouter API key if present and non-blank.
     */
    fun getOpenRouterKey(context: Context): String {
        return getPrefs(context).getString(KEY_OPENROUTER_API_KEY, "")?.trim() ?: ""
    }

    /**
     * Persistently saves a new OpenRouter API key.
     */
    fun saveOpenRouterKey(context: Context, key: String) {
        val clean = key.trim()
        getPrefs(context).edit().putString(KEY_OPENROUTER_API_KEY, clean).apply()
        Log.i(TAG, "Saved custom OpenRouter API key (length=${clean.length})")
    }

    /**
     * Clears the user-configured OpenRouter API key from storage.
     */
    fun clearOpenRouterKey(context: Context) {
        getPrefs(context).edit().remove(KEY_OPENROUTER_API_KEY).apply()
        Log.i(TAG, "Cleared user OpenRouter API key")
    }

    /**
     * Returns true if a custom OpenRouter key is saved.
     */
    fun hasOpenRouterKey(context: Context): Boolean {
        return getOpenRouterKey(context).isNotBlank()
    }

    private const val KEY_GROQ_API_KEY = "user_groq_api_key"

    fun getGroqKey(context: Context): String {
        return getPrefs(context).getString(KEY_GROQ_API_KEY, "")?.trim() ?: ""
    }

    fun saveGroqKey(context: Context, key: String) {
        val clean = key.trim()
        getPrefs(context).edit().putString(KEY_GROQ_API_KEY, clean).apply()
        Log.i(TAG, "Saved custom Groq API key (length=${clean.length})")
    }

    fun clearGroqKey(context: Context) {
        getPrefs(context).edit().remove(KEY_GROQ_API_KEY).apply()
        Log.i(TAG, "Cleared user Groq API key")
    }

    fun hasGroqKey(context: Context): Boolean {
        return getGroqKey(context).isNotBlank()
    }

    /**
     * Retrieves the stored Truecaller installation token if present and non-blank.
     */
    fun getTruecallerToken(context: Context): String {
        return getPrefs(context).getString(KEY_TRUECALLER_TOKEN, "")?.trim() ?: ""
    }

    /**
     * Persistently saves a user-configured Truecaller installation Bearer token.
     */
    fun saveTruecallerToken(context: Context, token: String) {
        val clean = token.trim().removePrefix("Bearer ").trim()
        getPrefs(context).edit().putString(KEY_TRUECALLER_TOKEN, clean).apply()
        Log.i(TAG, "Saved Truecaller installation token (length=${clean.length})")
    }

    /**
     * Clears the stored Truecaller token.
     */
    fun clearTruecallerToken(context: Context) {
        getPrefs(context).edit().remove(KEY_TRUECALLER_TOKEN).apply()
        Log.i(TAG, "Cleared user Truecaller token")
    }

    fun hasTruecallerToken(context: Context): Boolean {
        return getTruecallerToken(context).isNotBlank()
    }


    /**
     * Returns a safely masked version of the key for UI presentation.
     * Example: "AIzaSy...WDMx"
     */
    fun maskKey(key: String): String {
        val trimmed = key.trim()
        if (trimmed.length <= 10) return "••••••••"
        return "${trimmed.take(6)}...${trimmed.takeLast(4)}"
    }

    /**
     * Performs a lightweight live validation against Google Gemini v1beta endpoint.
     * Returns Result.success(true) if valid, or Result.failure with error message.
     */
    suspend fun validateKeyOnline(apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        val clean = apiKey.trim()
        if (clean.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("API key cannot be empty"))
        }

        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent?key=$clean"
            val jsonPayload = """
                {
                    "contents": [
                        {
                            "parts": [
                                {"text": "ping"}
                            ]
                        }
                    ],
                    "generationConfig": {
                        "maxOutputTokens": 2
                    }
                }
            """.trimIndent()

            val request = Request.Builder()
                .url(url)
                .post(jsonPayload.toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val code = response.code
            val body = response.body?.string() ?: ""

            if (response.isSuccessful) {
                Result.success("API key verified successfully!")
            } else {
                Log.w(TAG, "Key validation failed HTTP $code: $body")
                val errorMsg = when (code) {
                    400 -> "Invalid API key format or parameters."
                    403 -> "API key is forbidden or lacks Gemini API permissions."
                    404 -> "Model endpoint not found for this key."
                    429 -> "Quota exceeded for this API key."
                    else -> "Validation failed (HTTP $code)"
                }
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error validating key", e)
            Result.failure(Exception("Network error: ${e.localizedMessage ?: "Unable to connect"}"))
        }
    }
}
