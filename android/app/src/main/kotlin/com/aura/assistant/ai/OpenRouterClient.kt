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
 * Provides ~80ms to 500ms sub-second text chat and instant native tool calling:
 * - Direct SSE streaming from Groq (api.groq.com) and OpenRouter (openrouter.ai).
 * - Full OpenAI Function Calling schema support for native Android tools.
 * - Used autonomously by ISHA for fast conversational turns and sub-second tool execution.
 */
class OpenRouterClient(private val context: Context) {

    companion object {
        private const val TAG = "FastLlmClient"
        private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"

        private val GROQ_MODELS = listOf(
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "qwen/qwen3.8-27b",
            "openai/gpt-oss-20b"
        )

        private val OPENROUTER_MODELS = listOf(
            "meta-llama/llama-3.1-8b-instruct",
            "meta-llama/llama-3.3-70b-instruct",
            "qwen/qwen-2.5-7b-instruct",
            "meta-llama/llama-3.2-3b-instruct"
        )
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
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
        val openRouterKey = Secrets.getActiveOpenRouterKey(context)
        if (openRouterKey.isNotBlank()) {
            try {
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
            } catch (e: Exception) {
                Log.w(TAG, "OpenRouter attempt failed (${e.message}), trying Groq...")
            }
        }

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
                Log.w(TAG, "Groq attempt failed (${e.message})...")
            }
        }

        throw IllegalStateException("Neither OpenRouter nor Groq API keys are available")
    }

    /**
     * Executes fast chat with OpenAI-compatible tool calling support using OpenRouter or Groq.
     * When tools are provided, the model can invoke native device tools in <500ms.
     */
    suspend fun streamChatWithTools(
        systemPrompt: String,
        history: List<ChatMessage>,
        prompt: String,
        tools: JsonArray?,
        callback: ChatStreamCallback,
        onExecuteTool: suspend (name: String, args: JsonObject) -> JsonObject
    ): String = withContext(Dispatchers.IO) {
        if (tools == null || tools.size() == 0) {
            return@withContext streamFastChat(systemPrompt, history, prompt, callback)
        }

        val openRouterKey = Secrets.getActiveOpenRouterKey(context)
        if (openRouterKey.isNotBlank()) {
            try {
                Log.i(TAG, "⚡ Attempting Tier-1 OpenRouter with ${tools.size()} tools (<500ms)...")
                return@withContext executeChatWithTools(
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
                    tools = tools,
                    callback = callback,
                    onExecuteTool = onExecuteTool
                )
            } catch (e: Exception) {
                Log.w(TAG, "OpenRouter tools attempt failed (${e.message}), trying Groq...")
            }
        }

        val groqKey = Secrets.getActiveGroqKey(context)
        if (groqKey.isNotBlank()) {
            try {
                Log.i(TAG, "⚡ Attempting Tier-0 Groq LPU with ${tools.size()} tools...")
                return@withContext executeChatWithTools(
                    url = GROQ_URL,
                    apiKey = groqKey,
                    models = GROQ_MODELS,
                    extraHeaders = emptyMap(),
                    systemPrompt = systemPrompt,
                    history = history,
                    prompt = prompt,
                    tools = tools,
                    callback = callback,
                    onExecuteTool = onExecuteTool
                )
            } catch (e: Exception) {
                Log.w(TAG, "Groq tools attempt failed (${e.message})...")
            }
        }

        throw IllegalStateException("Neither OpenRouter nor Groq API keys are available")
    }

    private suspend fun executeChatWithTools(
        url: String,
        apiKey: String,
        models: List<String>,
        extraHeaders: Map<String, String>,
        systemPrompt: String,
        history: List<ChatMessage>,
        prompt: String,
        tools: JsonArray,
        callback: ChatStreamCallback,
        onExecuteTool: suspend (name: String, args: JsonObject) -> JsonObject
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
            try {
                Log.i(TAG, "Requesting tool completion from model: $model at $url...")
                val activeMessages = messagesArray.deepCopy()
                var step = 0
                val maxToolSteps = 3

                while (step < maxToolSteps) {
                    step++
                    val root = JsonObject().apply {
                        addProperty("model", model)
                        add("messages", activeMessages)
                        add("tools", tools)
                        addProperty("tool_choice", "auto")
                        addProperty("stream", false)
                        addProperty("temperature", 0.3)
                        addProperty("max_tokens", 1024)
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

                    val response = httpClient.newCall(reqBuilder.build()).execute()
                    if (!response.isSuccessful) {
                        val err = response.body?.string() ?: ""
                        Log.w(TAG, "Model $model returned HTTP ${response.code}: $err")
                        throw RuntimeException("HTTP ${response.code}: $err")
                    }

                    val responseBody = response.body?.string() ?: throw RuntimeException("Empty response body")
                    val parsed = JsonParser.parseString(responseBody).asJsonObject
                    val choices = parsed.getAsJsonArray("choices")
                    if (choices == null || choices.size() == 0) {
                        throw RuntimeException("No choices in model response")
                    }

                    val choice = choices[0].asJsonObject
                    val message = choice.getAsJsonObject("message")
                    val toolCalls = if (message.has("tool_calls") && !message.get("tool_calls").isJsonNull) {
                        message.getAsJsonArray("tool_calls")
                    } else null

                    // If model requested tool calls:
                    if (toolCalls != null && toolCalls.size() > 0) {
                        Log.i(TAG, "Model $model requested ${toolCalls.size()} tool call(s) at step $step")
                        activeMessages.add(message) // Add assistant turn with tool_calls

                        for (i in 0 until toolCalls.size()) {
                            val callObj = toolCalls.get(i).asJsonObject
                            val callId = callObj.get("id")?.asString ?: "call_${System.currentTimeMillis()}"
                            val fn = callObj.getAsJsonObject("function")
                            val toolName = fn.get("name")?.asString ?: continue
                            val argsStr = fn.get("arguments")?.asString ?: "{}"
                            val toolArgs = try {
                                JsonParser.parseString(argsStr).asJsonObject
                            } catch (_: Exception) {
                                JsonObject()
                            }

                            Log.i(TAG, "⚡ Executing native tool: $toolName with args: $toolArgs")
                            val toolResult = onExecuteTool(toolName, toolArgs)

                            val toolRespMsg = JsonObject().apply {
                                addProperty("role", "tool")
                                addProperty("tool_call_id", callId)
                                addProperty("name", toolName)
                                addProperty("content", toolResult.toString())
                            }
                            activeMessages.add(toolRespMsg)
                        }

                        // Stream final completion for sweet spoken Hindi response
                        return@withContext streamFinalCompletion(
                            url = url,
                            apiKey = apiKey,
                            model = model,
                            extraHeaders = extraHeaders,
                            messages = activeMessages,
                            callback = callback
                        )
                    } else {
                        // Model responded directly with conversation text (0 tools called)
                        val content = if (message.has("content") && !message.get("content").isJsonNull) {
                            message.get("content").asString
                        } else ""
                        if (content.isNotBlank()) {
                            val lowerContent = content.lowercase()
                            val lowerPrompt = prompt.lowercase()
                            val isActionIntent = lowerPrompt.contains("bhej") || lowerPrompt.contains("send") || lowerPrompt.contains("share") || lowerPrompt.contains("play") || lowerPrompt.contains("torch")
                            val isFakeActionText = lowerContent.contains("kar rahi hoon") || lowerContent.contains("bhej rahi") || lowerContent.contains("send kar rahi") || lowerContent.contains("fetch kar rahi") || lowerContent.contains("maps.google.com/?q=23.")
                            if (isActionIntent && isFakeActionText) {
                                Log.w(TAG, "Fast LLM hallucinated action without tool execution: $content")
                                throw IllegalStateException("Fast LLM hallucinated action narrative without calling tool")
                            }

                            Log.i(TAG, "Model $model returned direct text (${content.length} chars)")
                            callback.onToken(content)
                            callback.onComplete(content)
                            return@withContext content
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Model $model tool execution error: ${e.message}")
                lastException = e
            }
        }

        throw (lastException ?: RuntimeException("All candidate models failed with tools at $url"))
    }

    private suspend fun streamFinalCompletion(
        url: String,
        apiKey: String,
        model: String,
        extraHeaders: Map<String, String>,
        messages: JsonArray,
        callback: ChatStreamCallback
    ): String = withContext(Dispatchers.IO) {
        val root = JsonObject().apply {
            addProperty("model", model)
            add("messages", messages)
            addProperty("stream", true)
            addProperty("temperature", 0.4)
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

        val response = httpClient.newCall(reqBuilder.build()).execute()
        if (!response.isSuccessful) {
            val err = response.body?.string() ?: ""
            throw RuntimeException("Final stream error HTTP ${response.code}: $err")
        }

        val stream = response.body?.byteStream() ?: throw RuntimeException("Null response stream")
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
                            if (delta != null && delta.has("content") && !delta.get("content").isJsonNull) {
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
            callback.onComplete(fullResponse)
            return@withContext fullResponse
        }

        throw RuntimeException("Empty final completion response from model $model")
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
                                    if (delta != null && delta.has("content") && !delta.get("content").isJsonNull) {
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
