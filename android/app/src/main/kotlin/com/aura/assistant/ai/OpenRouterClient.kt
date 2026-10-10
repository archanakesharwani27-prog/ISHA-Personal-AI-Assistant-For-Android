package com.aura.assistant.ai

import android.content.Context
import android.util.Log
import com.aura.assistant.config.Secrets
import com.aura.assistant.data.ChatMessage
import com.aura.assistant.data.MessageRole
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * ⚡ Ultra-Low Latency Fast LLM Client (Groq LPU & OpenRouter Gateway).
 *
 * Provides ~80ms to 200ms sub-second text chat and instant conversational responses:
 * - Direct SSE streaming from Groq (api.groq.com) and OpenRouter (openrouter.ai).
 * - Used autonomously by ISHA for fast conversational turns when Groq/OpenRouter keys are configured.
 */
class OpenRouterClient(private val context: Context) {

    companion object {
        private const val TAG = "FastLlmClient"
        private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"

        private val GROQ_MODELS = listOf(
            "qwen/qwen3.8-27b",
            "openai/gpt-oss-20b",
            "llama-3.3-70b-versatile"
        )

        private val OPENROUTER_MODELS = listOf(
            "qwen/qwen-2.5-7b-instruct",
            "meta-llama/llama-3.1-8b-instruct",
            "meta-llama/llama-3.2-3b-instruct",
            "meta-llama/llama-3.3-70b-instruct"
        )
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Autonomously streams chat response using Groq (Tier 0: ~80ms) or OpenRouter (Tier 1: ~150ms).
     */
    suspend fun streamFastChat(
        systemPrompt: String,
        history: List<ChatMessage>,
        prompt: String,
        callback: ChatStreamCallback
    ): String = withContext(Dispatchers.IO) {
        val groqKey = Secrets.getActiveGroqKey(context)
        if (groqKey.isNotBlank()) {
            try {
                Log.i(TAG, "⚡ Attempting Tier-0 Groq LPU streaming (<100ms)...")
                return@withContext executeChatStream(
                    url = GROQ_URL,
                    apiKey = groqKey,
                    models = GROQ_MODELS,
                    extraHeaders = emptyMap(),
                    systemPrompt = systemPrompt,
                    history = history,
                    prompt = prompt,
                    callback = callback
                )
            } catch (e: Exception) {
                Log.w(TAG, "Groq attempt failed (${e.message}), trying OpenRouter...")
            }
        }

        val openRouterKey = Secrets.getActiveOpenRouterKey(context)
        if (openRouterKey.isNotBlank()) {
            Log.i(TAG, "⚡ Attempting Tier-1 OpenRouter streaming (<200ms)...")
            return@withContext executeChatStream(
                url = OPENROUTER_URL,
                apiKey = openRouterKey,
                models = OPENROUTER_MODELS,
                extraHeaders = mapOf(
                    "HTTP-Referer" to "https://github.com/archanakesharwani27-prog/ISHA-Personal-AI-Assistant-For-Android",
                    "X-Title" to "ISHA AI Assistant"
                ),
                systemPrompt = systemPrompt,
                history = history,
                prompt = prompt,
                callback = callback
            )
        }

        throw IllegalStateException("Neither Groq nor OpenRouter API keys are available")
    }

    private suspend fun executeChatStream(
        url: String,
        apiKey: String,
        models: List<String>,
        extraHeaders: Map<String, String>,
        systemPrompt: String,
        history: List<ChatMessage>,
        prompt: String,
        callback: ChatStreamCallback
    ): String = withContext(Dispatchers.IO) {
        val messagesArray = JsonArray()

        // 1. System Prompt
        if (systemPrompt.isNotBlank()) {
            messagesArray.add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", systemPrompt)
            })
        }

        // 2. Prior History (last 8 turns for speed)
        val recentHistory = history.takeLast(8)
        for (msg in recentHistory) {
            val role = when (msg.role) {
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
                MessageRole.TOOL -> "system"
            }
            if (msg.text.isNotBlank()) {
                messagesArray.add(JsonObject().apply {
                    addProperty("role", role)
                    addProperty("content", msg.text)
                })
            }
        }

        // 3. User Turn
        messagesArray.add(JsonObject().apply {
            addProperty("role", "user")
            addProperty("content", prompt)
        })

        var lastException: Exception? = null

        for (model in models) {
            val root = JsonObject().apply {
                addProperty("model", model)
                add("messages", messagesArray)
                addProperty("stream", true)
                addProperty("temperature", 0.5)
                addProperty("max_tokens", 2048)
            }

            val body = root.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val reqBuilder = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("User-Agent", "ISHA-Assistant/2.1 (Android)")
                .post(body)

            for ((k, v) in extraHeaders) {
                reqBuilder.addHeader(k, v)
            }

            try {
                Log.i(TAG, "Streaming from model: $model at $url...")
                val response = httpClient.newCall(reqBuilder.build()).execute()
                if (!response.isSuccessful) {
                    val err = response.body?.string() ?: ""
                    Log.w(TAG, "Model $model returned HTTP ${response.code}: $err")
                    continue
                }

                val stream = response.body?.byteStream() ?: continue
                val reader = BufferedReader(InputStreamReader(stream))
                val accumulator = StringBuilder()

                try {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val current = line ?: break
                        if (current.startsWith("data: ")) {
                            val data = current.removePrefix("data: ").trim()
                            if (data == "[DONE]") break
                            try {
                                val json = JsonParser.parseString(data).asJsonObject
                                val choices = json.getAsJsonArray("choices")
                                if (choices != null && choices.size() > 0) {
                                    val delta = choices[0].asJsonObject.getAsJsonObject("delta")
                                    if (delta != null && delta.has("content")) {
                                        val token = delta.get("content").asString
                                        accumulator.append(token)
                                        callback.onToken(token)
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                } finally {
                    try { reader.close() } catch (_: Exception) {}
                }

                val fullResponse = accumulator.toString().trim()
                if (fullResponse.isNotBlank()) {
                    Log.i(TAG, "Fast LLM completed successfully (${fullResponse.length} chars)")
                    callback.onComplete(fullResponse)
                    return@withContext fullResponse
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fast LLM model $model error: ${e.message}")
                lastException = e
            }
        }

        throw (lastException ?: RuntimeException("All candidate models failed at $url"))
    }
}
