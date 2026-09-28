package com.aura.assistant.ai

import android.content.Context
import android.util.Log
import com.aura.assistant.config.Secrets
import com.aura.assistant.data.ChatMessage
import com.aura.assistant.data.MessageRole
import com.aura.assistant.memory.AuraExperienceDatabase
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

interface ChatStreamCallback {
    fun onToken(chunk: String)
    fun onToolExecution(toolName: String, args: String, result: String)
    fun onComplete(fullText: String)
    fun onError(errorMessage: String)
}

/**
 * High-performance Gemini 2.0 Flash Streaming Chat Service.
 * Implements SSE (Server-Sent Events) streaming with full multi-turn conversation memory,
 * automatic tool calling (IshaToolRegistry), and instant generation abort support.
 */
class GeminiChatService(private val context: Context) {

    companion object {
        private const val TAG = "GeminiChatService"
        // ╔══════════════════════════════════════════════════════════════════════════════════╗
        // ║ 🔒 STRICTLY FROZEN - DO NOT MODIFY, REVERT, OR RENAME THESE MODELS               ║
        // ║ Verified & tested on real device via live Google Gemini v1beta API endpoints.     ║
        // ║ Changing these to 1.5/2.0 causes immediate HTTP 404 Not Found from Google server. ║
        // ╚══════════════════════════════════════════════════════════════════════════════════╝
        val CANDIDATE_MODELS = listOf(
            "gemini-3.6-flash",
            "gemini-3.5-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.1-flash-lite",
            "gemini-flash-latest"
        )
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var activeCall: Call? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Sends a chat prompt along with previous conversation history and optional image attachment to Gemini.
     */
    fun sendChatStream(
        history: List<ChatMessage>,
        newPrompt: String,
        imageBytes: ByteArray? = null,
        callback: ChatStreamCallback
    ) {
        cancelCurrentGeneration()

        serviceScope.launch {
            try {
                executeStreamRequest(history, newPrompt, imageBytes, callback)
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Stream execution failed", e)
                    callback.onError(e.localizedMessage ?: "Network error")
                }
            }
        }
    }

    private suspend fun executeStreamRequest(
        history: List<ChatMessage>,
        newPrompt: String,
        imageBytes: ByteArray?,
        callback: ChatStreamCallback
    ) {
        // Detect user feedback on previous turn to power continuous adaptive learning
        val cleanInput = newPrompt.lowercase().trim()
        val negativePhrases = listOf("nahi gaya", "nahi hua", "galat hai", "wrong", "did not work", "fail ho gaya", "nahi chala", "nhi gya", "nhi hua")
        val positivePhrases = listOf("shabash", "badhiya", "perfect", "good", "thank you", "thanks", "sahi hai", "ho gaya", "great")

        if (negativePhrases.any { cleanInput.contains(it) }) {
            Log.i(TAG, "⚠️ Negative user feedback detected: '$newPrompt'. Correcting Experience DB...")
            try {
                AuraExperienceDatabase.getInstance(context).recordUserFeedback(
                    taskId = "recent_task",
                    feedbackText = newPrompt,
                    isSuccess = false
                )
            } catch (_: Exception) {}
        } else if (positivePhrases.any { cleanInput.contains(it) }) {
                try {
                    AuraExperienceDatabase.getInstance(context).recordUserFeedback(
                        taskId = "recent_task",
                        feedbackText = newPrompt,
                        isSuccess = true
                    )
                } catch (_: Exception) {}
            }

            // Check if user's prompt matches a user-taught command rule
            val matchingRule = AuraMemoryManager.findMatchingRule(context, newPrompt)
            val promptToSend = if (matchingRule != null) {
                Log.i(TAG, "🎯 Taught Rule Matched for prompt '$newPrompt': trigger='${matchingRule.triggerPattern}', action='${matchingRule.actionInstruction}', tool=${matchingRule.primaryTool}")
                val toolHint = if (!matchingRule.primaryTool.isNullOrBlank()) " using tool '${matchingRule.primaryTool}'" else ""
                "$newPrompt\n\n[USER-TAUGHT ACTION RULE ACTIVE]: The Boss taught you that for trigger '${matchingRule.triggerPattern}', you MUST execute: ${matchingRule.actionInstruction}$toolHint. Execute this exact action now!"
            } else {
                newPrompt
            }

        val apiKey = Secrets.getActiveGeminiKey(context)
        val requestBodyJson = buildRequestBody(history, promptToSend, imageBytes)
        val bodyStr = requestBodyJson.toString()

        var lastErrorMsg = "Gemini API Error"
        for (model in CANDIDATE_MODELS) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse&key=$apiKey"
            val body = bodyStr.toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            val call = client.newCall(request)
            activeCall = call

            val response = try {
                withContext(Dispatchers.IO) { call.execute() }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Model $model connection failed: ${e.message}, trying fallback...")
                lastErrorMsg = e.localizedMessage ?: "Connection error"
                continue
            }

            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: "(no body)"
                Log.w(TAG, "Model $model failed with HTTP ${response.code}: $errBody")
                lastErrorMsg = when (response.code) {
                    400 -> "Bad request — check request format"
                    401, 403 -> "API key invalid or quota exceeded"
                    404 -> "Model not found ($model)"
                    429 -> "Rate limit reached — thoda wait karein Boss"
                    503 -> "Server thoda busy hai Boss — ek second mein try karti hoon!"
                    else -> "Gemini API Error (${response.code})"
                }
                // If 404, 429, 5xx or model deprecated/unsupported in 400, try next fallback model
                val isUnsupportedModel = response.code == 400 && (
                    errBody.contains("not available", ignoreCase = true) ||
                    errBody.contains("not supported", ignoreCase = true) ||
                    errBody.contains("update your code", ignoreCase = true)
                )
                if (response.code == 503 || response.code == 429) {
                    // Server overloaded — wait before trying next model to avoid hammering
                    val delayMs = if (response.code == 503) 1500L else 2000L
                    Log.w(TAG, "HTTP ${response.code} from $model, waiting ${delayMs}ms before fallback...")
                    delay(delayMs)
                    continue
                }
                if (response.code == 404 || response.code > 500 || isUnsupportedModel) {
                    continue
                } else {
                    callback.onError(lastErrorMsg)
                    return
                }
            }

            val responseStream = response.body?.byteStream()
            if (responseStream == null) {
                continue
            }

            Log.i(TAG, "Streaming from model: $model successfully")
            val reader = BufferedReader(InputStreamReader(responseStream))
            val fullAccumulator = StringBuilder()
            val pendingFunctionCalls = mutableListOf<JsonObject>()
            var exactModelTurnContent: JsonObject? = null

        try {
            var line: String?
            while (withContext(Dispatchers.IO) { reader.readLine() }.also { line = it } != null) {
                val currentLine = line ?: break
                if (currentLine.startsWith("data: ")) {
                    val jsonStr = currentLine.removePrefix("data: ").trim()
                    if (jsonStr.isBlank() || jsonStr == "[DONE]") continue

                    try {
                        val parsed = JsonParser.parseString(jsonStr).asJsonObject
                        val candidates = parsed.getAsJsonArray("candidates")
                        if (candidates != null && candidates.size() > 0) {
                            val candidate = candidates.get(0).asJsonObject
                            val content = candidate.getAsJsonObject("content")
                            if (content != null && content.has("parts")) {
                                val parts = content.getAsJsonArray("parts")
                                for (p in 0 until parts.size()) {
                                    val part = parts.get(p).asJsonObject

                                    // Check for streaming text token
                                    if (part.has("text")) {
                                        val token = part.get("text").asString
                                        fullAccumulator.append(token)
                                        callback.onToken(token)
                                    }

                                    // Check for tool function call
                                    if (part.has("functionCall")) {
                                        pendingFunctionCalls.add(part.getAsJsonObject("functionCall"))
                                        exactModelTurnContent = content
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error parsing chunk: $jsonStr", e)
                    }
                }
            }
        } finally {
            try { reader.close() } catch (_: Exception) {}
        }

        // If function calls were requested, execute all natively in order
        if (pendingFunctionCalls.isNotEmpty()) {
            val executedTools = mutableListOf<Triple<String, JsonObject, JsonObject>>()
            for (fnCall in pendingFunctionCalls) {
                val fnName = fnCall.get("name")?.asString ?: continue
                val fnArgs = fnCall.getAsJsonObject("args") ?: JsonObject()

                Log.i(TAG, "Executing function call: $fnName with args: $fnArgs")
                val toolResult = IshaToolRegistry.executeTool(context, fnName, fnArgs)
                val resultSummary = toolResult.get("message")?.asString
                    ?: toolResult.get("status")?.asString
                    ?: "Completed"

                callback.onToolExecution(
                    toolName = fnName,
                    args = fnArgs.toString(),
                    result = resultSummary
                )
                executedTools.add(Triple(fnName, fnArgs, toolResult))
            }

            // Send tool responses back to complete conversation turn with natural speech
            executeToolResponsesStream(model, history, newPrompt, executedTools, exactModelTurnContent, callback)
        } else {
            callback.onComplete(fullAccumulator.toString())
        }
        return // Successfully completed request with this model
    }

    // If all candidate models failed
    callback.onError(lastErrorMsg)
}

    private suspend fun executeToolResponsesStream(
        model: String,
        history: List<ChatMessage>,
        originalPrompt: String,
        executedTools: List<Triple<String, JsonObject, JsonObject>>,
        modelTurnContent: JsonObject?,
        callback: ChatStreamCallback,
        currentStep: Int = 1,
        priorContents: JsonArray? = null
    ) {
        val apiKey = Secrets.getActiveGeminiKey(context)

        val root = JsonObject().apply {
            // System instruction with rich persona and permanent memory
            add("system_instruction", createSystemInstruction())

            val contents = if (priorContents != null) {
                priorContents
            } else {
                val initContents = createHistoryContents(history)
                val userTurn = JsonObject().apply {
                    addProperty("role", "user")
                    val parts = JsonArray().apply {
                        val textPart = JsonObject().apply { addProperty("text", originalPrompt) }
                        add(textPart)
                    }
                    add("parts", parts)
                }
                initContents.add(userTurn)
                initContents
            }

            // Model turn calling the functions
            val modelTurn = modelTurnContent ?: JsonObject().apply {
                addProperty("role", "model")
                val parts = JsonArray()
                for ((toolName, toolArgs, _) in executedTools) {
                    val fnCallPart = JsonObject().apply {
                        val fnObj = JsonObject().apply {
                            addProperty("name", toolName)
                            add("args", toolArgs)
                        }
                        add("functionCall", fnObj)
                    }
                    parts.add(fnCallPart)
                }
                add("parts", parts)
            }
            contents.add(modelTurn)

            // Function response turn (Role MUST be "user" in Gemini v1beta REST)
            val fnResponseTurn = JsonObject().apply {
                addProperty("role", "user")
                val parts = JsonArray()
                for ((toolName, _, toolResult) in executedTools) {
                    val fnRespPart = JsonObject().apply {
                        val fnRespObj = JsonObject().apply {
                            addProperty("name", toolName)
                            val resp = JsonObject().apply {
                                val outputObj = if (toolResult.isJsonObject) toolResult.asJsonObject.deepCopy() else JsonObject()
                                outputObj.addProperty(
                                    "_task_guidance",
                                    "Step $currentStep complete. Original user request: \"$originalPrompt\". Agar aur tasks baaki hain (jaise chat_on_whatsapp ke baad reply bhejna), to IMMEDIATELY unka tool call karo — text summary abhi mat do!"
                                )
                                add("output", outputObj)
                            }
                            add("response", resp)
                        }
                        add("functionResponse", fnRespObj)
                    }
                    parts.add(fnRespPart)
                }
                add("parts", parts)
            }
            contents.add(fnResponseTurn)

            add("contents", contents)

            // Include tool declarations so model has schema context
            val tools = JsonArray().apply {
                val toolHolder = JsonObject()
                toolHolder.add("function_declarations", IshaToolRegistry.getGeminiToolDeclarations())
                add(toolHolder)
            }
            add("tools", tools)

            // Generation Config for rich multi-turn reasoning without truncation
            val genConfig = JsonObject().apply {
                addProperty("temperature", 0.7)
                addProperty("topP", 0.95)
                addProperty("maxOutputTokens", 8192)
            }
            add("generationConfig", genConfig)
        }

        val body = root.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val startIndex = CANDIDATE_MODELS.indexOf(model).coerceAtLeast(0)
        val modelsToTry = CANDIDATE_MODELS.subList(startIndex, CANDIDATE_MODELS.size) + CANDIDATE_MODELS.subList(0, startIndex)

        for (candidateModel in modelsToTry) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$candidateModel:streamGenerateContent?alt=sse&key=$apiKey"
            val request = Request.Builder().url(url).post(body).build()

            val call = client.newCall(request)
            activeCall = call

            val response = try {
                withContext(Dispatchers.IO) { call.execute() }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Tool response model $candidateModel connection failed: ${e.message}, trying fallback...")
                continue
            }

            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                Log.w(TAG, "Tool response model $candidateModel failed with HTTP ${response.code}: $errBody")
                if (response.code == 503 || response.code == 429) {
                    delay(if (response.code == 503) 1500L else 2000L)
                    continue
                }
                if (response.code == 404 || response.code > 500) {
                    continue
                }
                break
            }

            val stream = response.body?.byteStream() ?: continue
        val reader = BufferedReader(InputStreamReader(stream))
        val postToolAccumulator = StringBuilder()
        val nextPendingFunctionCalls = mutableListOf<JsonObject>()
        var nextExactModelTurnContent: JsonObject? = null

        try {
            var line: String?
            while (withContext(Dispatchers.IO) { reader.readLine() }.also { line = it } != null) {
                val currentLine = line ?: break
                if (currentLine.startsWith("data: ")) {
                    val jsonStr = currentLine.removePrefix("data: ").trim()
                    if (jsonStr.isBlank() || jsonStr == "[DONE]") continue
                    try {
                        val parsed = JsonParser.parseString(jsonStr).asJsonObject
                        val candidates = parsed.getAsJsonArray("candidates")
                        if (candidates != null && candidates.size() > 0) {
                            val candidate = candidates.get(0).asJsonObject
                            val content = candidate.getAsJsonObject("content")
                            if (content != null && content.has("parts")) {
                                val parts = content.getAsJsonArray("parts")
                                for (p in 0 until parts.size()) {
                                    val part = parts.get(p).asJsonObject
                                    if (part.has("text")) {
                                        val token = part.get("text").asString
                                        postToolAccumulator.append(token)
                                        callback.onToken(token)
                                    }
                                    if (part.has("functionCall")) {
                                        nextPendingFunctionCalls.add(part.getAsJsonObject("functionCall"))
                                        nextExactModelTurnContent = content
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        } finally {
            try { reader.close() } catch (_: Exception) {}
        }

        // Multi-Step Autonomous ReAct Planning:
        // If the model chained another function call (e.g. open_app -> get_screen_context -> find_and_tap)
        if (nextPendingFunctionCalls.isNotEmpty() && currentStep < 8) {
            Log.i(TAG, "Multi-step tool chaining: Step $currentStep complete, executing next ${nextPendingFunctionCalls.size} tools")
            val nextExecutedTools = mutableListOf<Triple<String, JsonObject, JsonObject>>()
            for (fnCall in nextPendingFunctionCalls) {
                val fnName = fnCall.get("name")?.asString ?: continue
                val fnArgs = fnCall.getAsJsonObject("args") ?: JsonObject()
                val toolResult = IshaToolRegistry.executeTool(context, fnName, fnArgs)
                val summary = toolResult.get("message")?.asString ?: toolResult.get("status")?.asString ?: "OK"
                callback.onToolExecution(fnName, fnArgs.toString(), summary)
                nextExecutedTools.add(Triple(fnName, fnArgs, toolResult))
            }

            executeToolResponsesStream(
                model = model,
                history = history,
                originalPrompt = originalPrompt,
                executedTools = nextExecutedTools,
                modelTurnContent = nextExactModelTurnContent,
                callback = callback,
                currentStep = currentStep + 1,
                priorContents = root.getAsJsonArray("contents")
            )
            return
        }

            val fullSpokenText = postToolAccumulator.toString().trim()
            if (fullSpokenText.isNotBlank()) {
                callback.onComplete(fullSpokenText)
                return
            }
        }

        // If all candidate models failed or returned empty, use clean fallback
        val fallback = executedTools.joinToString("\n") { (name, _, res) ->
            res.get("message")?.asString ?: "$name complete ho gaya Boss! ✅"
        }
        callback.onComplete(fallback)
    }

    private fun buildRequestBody(
        history: List<ChatMessage>,
        newPrompt: String,
        imageBytes: ByteArray? = null
    ): JsonObject {
        val root = JsonObject()

        // 1. System Instruction (Persona & Tool Guidance)
        root.add("system_instruction", createSystemInstruction())

        // 2. Contents Array
        val contents = createHistoryContents(history)
        val userTurn = JsonObject().apply {
            addProperty("role", "user")
            val parts = JsonArray().apply {
                if (imageBytes != null && imageBytes.isNotEmpty()) {
                    val inlineData = JsonObject().apply {
                        addProperty("mime_type", "image/jpeg")
                        addProperty("data", android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP))
                    }
                    val imagePart = JsonObject().apply {
                        add("inline_data", inlineData)
                    }
                    add(imagePart)
                }
                val part = JsonObject().apply { addProperty("text", newPrompt) }
                add(part)
            }
            add("parts", parts)
        }
        contents.add(userTurn)
        root.add("contents", contents)

        Log.i(TAG, "buildRequestBody: prompt='$newPrompt', historySize=${history.size}, totalTurns=${contents.size()}")

        // 3. Tools (Function Declarations) - Full comprehensive catalog
        val tools = JsonArray().apply {
            val toolHolder = JsonObject()
            toolHolder.add("function_declarations", IshaToolRegistry.getGeminiToolDeclarations())
            add(toolHolder)
        }
        root.add("tools", tools)

        // 4. Generation Config
        val genConfig = JsonObject().apply {
            addProperty("temperature", 0.7)
            addProperty("topP", 0.95)
            addProperty("maxOutputTokens", 8192)
        }
        root.add("generationConfig", genConfig)

        return root
    }

    private fun createSystemInstruction(): JsonObject {
        return JsonObject().apply {
            val parts = JsonArray().apply {
                val part = JsonObject().apply {
                    addProperty("text", AuraMemoryManager.buildSystemPrompt(context, isVoiceMode = false))
                }
                add(part)
            }
            add("parts", parts)
        }
    }

    private fun createHistoryContents(history: List<ChatMessage>): JsonArray {
        val contents = JsonArray()
        // Include recent USER, ASSISTANT, and TOOL messages (up to 30 items) for maximum context retention
        val cleanHistory = history.filter {
            it.text.isNotBlank() && !it.isStreaming
        }.takeLast(30)

        var lastRole: String? = null
        for (msg in cleanHistory) {
            val role = if (msg.role == MessageRole.USER) "user" else "model"
            val messageText = when {
                msg.role == MessageRole.TOOL -> {
                    "[Tool Output (${msg.toolName ?: "action"})]: ${msg.text}"
                }
                !msg.toolResult.isNullOrBlank() -> {
                    "${msg.text}\n[Executed Action Output]: ${msg.toolResult}"
                }
                else -> msg.text
            }

            if (role == lastRole) {
                // If consecutive messages of same role exist, append to previous turn
                if (contents.size() > 0) {
                    val lastTurn = contents.get(contents.size() - 1).asJsonObject
                    val parts = lastTurn.getAsJsonArray("parts")
                    val part = JsonObject().apply { addProperty("text", messageText) }
                    parts.add(part)
                }
                continue
            }
            val turn = JsonObject().apply {
                addProperty("role", role)
                val parts = JsonArray().apply {
                    val part = JsonObject().apply { addProperty("text", messageText) }
                    add(part)
                }
                add("parts", parts)
            }
            contents.add(turn)
            lastRole = role
        }

        // Guarantee strict Gemini REST compliance:
        // 1. History must START with a "user" turn (Gemini rejects history starting with "model")
        while (contents.size() > 0 && contents.get(0).asJsonObject.get("role").asString != "user") {
            contents.remove(0)
        }

        // 2. Prior history must END with a "model" turn before new "user" turn is appended
        if (contents.size() > 0) {
            val lastTurn = contents.get(contents.size() - 1).asJsonObject
            if (lastTurn.get("role").asString == "user") {
                contents.remove(contents.size() - 1)
            }
        }

        return contents
    }

    fun cancelCurrentGeneration() {
        try {
            activeCall?.cancel()
            activeCall = null
        } catch (_: Exception) {}
    }
}
