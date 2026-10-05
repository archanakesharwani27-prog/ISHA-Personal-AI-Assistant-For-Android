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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

interface ChatStreamCallback {
    fun onToken(chunk: String)
    fun onToolStart(toolName: String, args: String) {}
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
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var cachedSystemInstruction: JsonObject? = null
    @Volatile private var lastSystemInstructionTime: Long = 0L
    @Volatile private var lastPromptText: String? = null
    @Volatile private var lastPromptTime: Long = 0L

    private val SCREEN_INTERACTIVE_TOOLS = setOf(
        "find_and_tap",
        "screen_tap",
        "screen_scroll",
        "type_message",
        "type_text",
        "open_app",
        "chat_on_whatsapp",
        "send_whatsapp",
        "send_whatsapp_media",
        "share_media",
        "make_call",
        "play_youtube",
        "autonomous_ui_action",
        "dismiss_screen_popups",
        "press_system_key",
        "take_screenshot",
        "open_camera"
    )

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
        val now = System.currentTimeMillis()
        if (newPrompt.isNotBlank() && newPrompt == lastPromptText && (now - lastPromptTime) < 350L && imageBytes == null) {
            Log.w(TAG, "Debounced duplicate prompt request within 350ms: '$newPrompt'")
            return
        }
        lastPromptText = newPrompt
        lastPromptTime = now

        cancelCurrentGeneration()

        serviceScope.launch {
            try {
                executeStreamRequest(history, newPrompt, imageBytes, callback)
            } catch (e: Exception) {
                val isCanceled = e is CancellationException || (e is java.io.IOException && e.message?.contains("canceled", ignoreCase = true) == true)
                if (!isCanceled) {
                    Log.e(TAG, "Stream execution failed", e)
                    callback.onError(e.localizedMessage ?: "Network error")
                } else {
                    Log.i(TAG, "Generation cleanly canceled by user.")
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
                if (e is CancellationException || (e is java.io.IOException && e.message?.contains("canceled", ignoreCase = true) == true)) {
                    Log.i(TAG, "Streaming request canceled by user.")
                    return
                }
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
            val rawModelParts = mutableListOf<JsonObject>()
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

                                    // Check for tool function call (Preserve exact part with thoughtSignature)
                                    if (part.has("functionCall")) {
                                        pendingFunctionCalls.add(part.getAsJsonObject("functionCall"))
                                        rawModelParts.add(part.deepCopy())
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

        // If function calls were requested, execute natively (parallel for independent tools, sequential for screen tools)
        if (pendingFunctionCalls.isNotEmpty()) {
            val executedTools = executeFunctionCalls(pendingFunctionCalls, callback)
            // Send tool responses back to complete conversation turn with natural speech
            executeToolResponsesStream(
                model = model,
                history = history,
                originalPrompt = newPrompt,
                executedTools = executedTools,
                callback = callback,
                rawModelParts = rawModelParts
            )
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
        callback: ChatStreamCallback,
        currentStep: Int = 1,
        priorContents: JsonArray? = null,
        rawModelParts: List<JsonObject>? = null
    ) {
        val apiKey = Secrets.getActiveGeminiKey(context)

        val root = JsonObject().apply {
            // System instruction with rich persona and permanent memory
            add("system_instruction", createSystemInstruction())

            val contents = if (priorContents != null) {
                priorContents.deepCopy()
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

            // Model turn calling the functions (Preserving exact thoughtSignature for Gemini 3 validity)
            val modelTurn = JsonObject().apply {
                addProperty("role", "model")
                val parts = JsonArray()
                if (!rawModelParts.isNullOrEmpty()) {
                    for (p in rawModelParts) {
                        val partCopy = p.deepCopy()
                        if (partCopy.has("thoughtSignature") && !partCopy.has("thought_signature")) {
                            partCopy.add("thought_signature", partCopy.get("thoughtSignature"))
                        } else if (partCopy.has("thought_signature") && !partCopy.has("thoughtSignature")) {
                            partCopy.add("thoughtSignature", partCopy.get("thought_signature"))
                        }
                        parts.add(partCopy)
                    }
                } else {
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
                                    "Step $currentStep complete. Original user request: \"$originalPrompt\". Agar aur tasks baaki hain (jaise screenshot ke baad WhatsApp par bhejna, ya YouTube ke baad DND lagana, app/game kholna, battery check karna), to IMMEDIATELY unka tool call karo — text summary abhi mat do! Sare tasks ke tool call hone ke baad hi natural Hindi me short spoken confirmation do."
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

            val toolConfig = JsonObject().apply {
                val fnCallingConfig = JsonObject().apply {
                    addProperty("mode", "AUTO")
                }
                add("function_calling_config", fnCallingConfig)
            }
            add("tool_config", toolConfig)

            // Generation Config: Low temperature (0.25) after tool completion ensures crisp, accurate spoken confirmation
            val genConfig = JsonObject().apply {
                addProperty("temperature", 0.25)
                addProperty("topP", 0.95)
                addProperty("maxOutputTokens", 4096)
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
                if (e is CancellationException || (e is java.io.IOException && e.message?.contains("canceled", ignoreCase = true) == true)) {
                    Log.i(TAG, "Tool response request canceled by user.")
                    return
                }
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
                if (response.code == 404 || response.code >= 400) {
                    continue
                }
                break
            }

            val stream = response.body?.byteStream() ?: continue
        val reader = BufferedReader(InputStreamReader(stream))
        val postToolAccumulator = StringBuilder()
        val nextPendingFunctionCalls = mutableListOf<JsonObject>()
        val nextRawModelParts = mutableListOf<JsonObject>()

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
                                        nextRawModelParts.add(part.deepCopy())
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
            val nextExecutedTools = executeFunctionCalls(nextPendingFunctionCalls, callback)

            executeToolResponsesStream(
                model = model,
                history = history,
                originalPrompt = originalPrompt,
                executedTools = nextExecutedTools,
                callback = callback,
                currentStep = currentStep + 1,
                priorContents = root.getAsJsonArray("contents"),
                rawModelParts = nextRawModelParts
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

        // tool_config: enforce AUTO function calling mode
        val toolConfig = JsonObject().apply {
            val fnCallingConfig = JsonObject().apply {
                addProperty("mode", "AUTO")
            }
            add("function_calling_config", fnCallingConfig)
        }
        root.add("tool_config", toolConfig)

        // 4. Generation Config: Fast dynamic temperature (0.2 for device actions, 0.7 for conversational chat)
        val genConfig = JsonObject().apply {
            addProperty("temperature", getDynamicTemperature(newPrompt))
            addProperty("topP", 0.95)
            addProperty("maxOutputTokens", 4096)
        }
        root.add("generationConfig", genConfig)

        return root
    }

    private fun getDynamicTemperature(prompt: String): Double {
        val lower = prompt.lowercase()
        val isActionIntent = listOf(
            "khol", "open", "band", "close", "on kar", "off kar", "toggle",
            "bhejo", "send", "call", "phone", "dial", "lagao", "set", "alarm",
            "timer", "volume", "brightness", "torch", "flashlight", "wifi",
            "bluetooth", "hotspot", "screenshot", "camera", "photo", "whatsapp",
            "message", "sms", "navigate", "location", "battery", "storage",
            "clean", "clear", "type", "likho", "tap", "click"
        ).any { lower.contains(it) }
        return if (isActionIntent) 0.2 else 0.7
    }

    fun invalidateSystemInstructionCache() {
        cachedSystemInstruction = null
        lastSystemInstructionTime = 0L
    }

    private fun createSystemInstruction(): JsonObject {
        val now = System.currentTimeMillis()
        val cached = cachedSystemInstruction
        if (cached != null && (now - lastSystemInstructionTime) < 10_000L) {
            return cached
        }
        val newInstruction = JsonObject().apply {
            val parts = JsonArray().apply {
                val part = JsonObject().apply {
                    addProperty("text", AuraMemoryManager.buildSystemPrompt(context, isVoiceMode = false))
                }
                add(part)
            }
            add("parts", parts)
        }
        cachedSystemInstruction = newInstruction
        lastSystemInstructionTime = now
        return newInstruction
    }

    private suspend fun executeFunctionCalls(
        functionCalls: List<JsonObject>,
        callback: ChatStreamCallback
    ): List<Triple<String, JsonObject, JsonObject>> = coroutineScope {
        val validCalls = functionCalls.mapNotNull { fnCall ->
            val name = fnCall.get("name")?.asString ?: return@mapNotNull null
            val args = fnCall.getAsJsonObject("args") ?: JsonObject()
            Pair(name, args)
        }

        if (validCalls.isEmpty()) return@coroutineScope emptyList()

        val requiresSequential = validCalls.size <= 1 || validCalls.any { (name, _) -> SCREEN_INTERACTIVE_TOOLS.contains(name) }

        if (requiresSequential) {
            val results = mutableListOf<Triple<String, JsonObject, JsonObject>>()
            for ((fnName, fnArgs) in validCalls) {
                Log.i(TAG, "Executing tool sequentially: $fnName with args: $fnArgs")
                withContext(Dispatchers.Main) {
                    callback.onToolStart(toolName = fnName, args = fnArgs.toString())
                }
                val toolResult = com.aura.assistant.execution.ExecutionEngine.executeTool(context, fnName, fnArgs, this)
                val status = toolResult.get("status")?.asString ?: "success"
                val rawMsg = toolResult.get("message")?.asString ?: status
                val resultSummary = if ((status == "error" || status == "failed") && !rawMsg.startsWith("Failed", ignoreCase = true) && !rawMsg.startsWith("Error", ignoreCase = true)) {
                    "Failed: $rawMsg"
                } else {
                    rawMsg
                }
                withContext(Dispatchers.Main) {
                    callback.onToolExecution(toolName = fnName, args = fnArgs.toString(), result = resultSummary)
                }

                // Verify and record to Experience Database
                try {
                    val evidence = com.aura.assistant.execution.ExecutionVerifier.verify(context, fnName, fnArgs, toolResult)
                    val record = com.aura.assistant.memory.ExperienceRecord(
                        id = java.util.UUID.randomUUID().toString(),
                        taskId = "turn_" + System.currentTimeMillis(),
                        normalizedIntent = fnName,
                        userInput = fnArgs.toString(),
                        entities = fnArgs.toString(),
                        deviceProfile = android.os.Build.MODEL,
                        appPackage = context.packageName,
                        screenContext = null,
                        strategy = "sequential_gemini_call",
                        tool = fnName,
                        arguments = fnArgs.toString(),
                        executionStatus = if (evidence.verified) "SUCCESS" else "FAILED",
                        verificationStatus = if (evidence.verified) "VERIFIED_SUCCESS" else "VERIFIED_FAILURE",
                        failureReason = if (!evidence.verified) evidence.observedState else null,
                        recoveryAction = null,
                        latencyMs = 0L,
                        userFeedback = null,
                        timestamp = System.currentTimeMillis()
                    )
                    AuraExperienceDatabase.getInstance(context).recordExecution(record)
                } catch (_: Exception) {}

                results.add(Triple(fnName, fnArgs, toolResult))
            }
            results
        } else {
            Log.i(TAG, "Executing ${validCalls.size} independent tools in parallel!")
            val callbackMutex = Mutex()
            validCalls.map { (fnName, fnArgs) ->
                async(Dispatchers.IO) {
                    callbackMutex.withLock {
                        withContext(Dispatchers.Main) {
                            callback.onToolStart(toolName = fnName, args = fnArgs.toString())
                        }
                    }
                    val toolResult = com.aura.assistant.execution.ExecutionEngine.executeTool(context, fnName, fnArgs, this)
                    val status = toolResult.get("status")?.asString ?: "success"
                    val rawMsg = toolResult.get("message")?.asString ?: status
                    val resultSummary = if ((status == "error" || status == "failed") && !rawMsg.startsWith("Failed", ignoreCase = true) && !rawMsg.startsWith("Error", ignoreCase = true)) {
                        "Failed: $rawMsg"
                    } else {
                        rawMsg
                    }
                    callbackMutex.withLock {
                        withContext(Dispatchers.Main) {
                            callback.onToolExecution(toolName = fnName, args = fnArgs.toString(), result = resultSummary)
                        }
                    }

                    // Verify and record to Experience Database
                    try {
                        val evidence = com.aura.assistant.execution.ExecutionVerifier.verify(context, fnName, fnArgs, toolResult)
                        val record = com.aura.assistant.memory.ExperienceRecord(
                            id = java.util.UUID.randomUUID().toString(),
                            taskId = "turn_" + System.currentTimeMillis(),
                            normalizedIntent = fnName,
                            userInput = fnArgs.toString(),
                            entities = fnArgs.toString(),
                            deviceProfile = android.os.Build.MODEL,
                            appPackage = context.packageName,
                            screenContext = null,
                            strategy = "parallel_gemini_call",
                            tool = fnName,
                            arguments = fnArgs.toString(),
                            executionStatus = if (evidence.verified) "SUCCESS" else "FAILED",
                            verificationStatus = if (evidence.verified) "VERIFIED_SUCCESS" else "VERIFIED_FAILURE",
                            failureReason = if (!evidence.verified) evidence.observedState else null,
                            recoveryAction = null,
                            latencyMs = 0L,
                            userFeedback = null,
                            timestamp = System.currentTimeMillis()
                        )
                        AuraExperienceDatabase.getInstance(context).recordExecution(record)
                    } catch (_: Exception) {}

                    Triple(fnName, fnArgs, toolResult)
                }
            }.awaitAll()
        }
    }

    private fun createHistoryContents(history: List<ChatMessage>): JsonArray {
        val contents = JsonArray()
        // Include recent USER, ASSISTANT, and TOOL messages (smart retention: first 3 context turns + last 20)
        val cleanHistory = history.filter {
            it.text.isNotBlank() && !it.isStreaming
        }
        val historyToSend = if (cleanHistory.size > 23) {
            cleanHistory.take(3) + cleanHistory.takeLast(20)
        } else {
            cleanHistory
        }

        var lastRole: String? = null
        for (msg in historyToSend) {
            val role = if (msg.role == MessageRole.USER) "user" else "model"
            val messageText = when {
                msg.role == MessageRole.TOOL -> {
                    val trimmed = if (msg.text.length > 400) msg.text.take(400) + "... [truncated]" else msg.text
                    "[Tool Output (${msg.toolName ?: "action"})]: $trimmed"
                }
                !msg.toolResult.isNullOrBlank() -> {
                    val trimmedResult = if (msg.toolResult.length > 400) msg.toolResult.take(400) + "... [truncated]" else msg.toolResult
                    "${msg.text}\n[Executed Action Output]: $trimmedResult"
                }
                else -> msg.text
            }

            if (role == lastRole) {
                // If consecutive messages of same role exist, append to previous turn with size guard
                if (contents.size() > 0) {
                    val lastTurn = contents.get(contents.size() - 1).asJsonObject
                    val parts = lastTurn.getAsJsonArray("parts")
                    val trimmed = if (messageText.length > 400) messageText.take(400) + "... [truncated]" else messageText
                    val part = JsonObject().apply { addProperty("text", trimmed) }
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
