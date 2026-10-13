package com.aura.assistant.ai

import android.content.Context
import android.util.Log
import com.aura.assistant.config.Secrets
import com.aura.assistant.config.IshaGatewayConfig
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
    fun onThinkingToken(chunk: String) {}
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
    private val openRouterClient = OpenRouterClient(context)
    @Volatile private var cachedLastImageBytes: ByteArray? = null

    private fun detectMimeType(bytes: ByteArray): String {
        if (bytes.size >= 8) {
            if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
            ) {
                return "image/png"
            }
            if (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()) {
                return "image/gif"
            }
            if (bytes.size >= 12 &&
                bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
                bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
            ) {
                return "image/webp"
            }
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
                return "image/jpeg"
            }
        }
        return "image/jpeg"
    }

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

        // Live Context Freshness: Invalidate cached system instructions on communication queries or when WhatsApp is open
        val isCommsOrWaPrompt = newPrompt.contains(Regex("\\b(reply|baat|whatsapp|kro|karo|bhejo|unse|usse|isse|unhe|likho|type|msg|message)\\b", RegexOption.IGNORE_CASE)) ||
                com.aura.assistant.IshaAccessibilityService.instance?.isWhatsappChatOpen() == true
        if (isCommsOrWaPrompt) {
            invalidateSystemInstructionCache()
        }

        // ⚡ Multimodal Continuity Engine:
        // Cache active image bytes, and restore for follow-up questions referencing images
        var effectiveImageBytes = imageBytes
        if (effectiveImageBytes != null && effectiveImageBytes.isNotEmpty()) {
            cachedLastImageBytes = effectiveImageBytes
        } else {
            val lower = newPrompt.lowercase().trim()
            val refersToImage = lower.contains("image") || lower.contains("photo") ||
                    lower.contains("tasveer") || lower.contains("pic") ||
                    lower.contains("picture") || lower.contains("isme") ||
                    lower.contains("bheja") || lower.contains("bheji") ||
                    lower.contains("dekho") || lower.contains("ye kya hai") ||
                    lower.contains("batao isme") || lower.contains("janwar") ||
                    lower.contains("ye kaun") || lower.contains("kya lag raha") ||
                    lower.contains("dog") || lower.contains("kutta") || lower.contains("billi") ||
                    lower.contains("cat") || lower.contains("breed")
            val lastImageUri = history.lastOrNull { !it.imageUri.isNullOrBlank() }?.imageUri
            val isFollowUpTurn = !lastImageUri.isNullOrBlank() || cachedLastImageBytes != null
            val shouldRestore = refersToImage || (isFollowUpTurn && (lower.length < 40 || lower.startsWith("ye") || lower.startsWith("kya") || lower.startsWith("kaun")))
            if (shouldRestore) {
                val cached = cachedLastImageBytes
                if (cached != null && cached.isNotEmpty()) {
                    effectiveImageBytes = cached
                    Log.i(TAG, "📸 Restored cached image bytes (${cached.size} bytes) for visual follow-up prompt")
                } else if (!lastImageUri.isNullOrBlank()) {
                    try {
                        val parsed = android.net.Uri.parse(lastImageUri)
                        val bytes = if (parsed.scheme == "file") {
                            val f = java.io.File(parsed.path ?: "")
                            if (f.exists() && f.length() > 0) f.readBytes() else null
                        } else {
                            context.contentResolver.openInputStream(parsed)?.use { it.readBytes() }
                        }
                        if (bytes != null && bytes.isNotEmpty()) {
                            effectiveImageBytes = bytes
                            cachedLastImageBytes = bytes
                            Log.i(TAG, "📸 Restored image from history URI: $lastImageUri (${bytes.size} bytes)")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not reload image from URI: $lastImageUri", e)
                    }
                }
            }
        }

        // ⚡ Autonomous Brain Router:
        // Fast LLM (Groq / OpenRouter) is used ONLY for pure text turns where 0 tools & NO images are needed.
        // If image is present or an action is requested, it MUST route to Gemini Flash!
        val hasFastLlmKey = Secrets.getActiveGroqKey(context).isNotBlank() || Secrets.getActiveOpenRouterKey(context).isNotBlank()
        // ⚡ O(1) check — tool turns are always at the tail of history
        val hasPendingToolTurn = history.lastOrNull()?.let {
            it.role == MessageRole.TOOL || !it.toolName.isNullOrBlank()
        } ?: false
        val toolsForTurn = IshaToolRouter.routeToolsForPrompt(
            prompt = promptToSend,
            hasImage = effectiveImageBytes != null,
            hasPendingToolTurn = hasPendingToolTurn
        )
        val isPureConversation = (toolsForTurn == null)
        val canUseFastLlm = hasFastLlmKey && effectiveImageBytes == null && !hasPendingToolTurn && isPureConversation

        if (canUseFastLlm) {
            try {
                Log.i(TAG, "⚡ Routing to Fast LLM (Groq / OpenRouter) for sub-second instant response...")
                val sysPrompt = AuraMemoryManager.buildSystemPrompt(context, isVoiceMode = false)
                openRouterClient.streamFastChat(
                    systemPrompt = sysPrompt,
                    history = history,
                    prompt = promptToSend,
                    callback = callback
                )
                return
            } catch (e: Exception) {
                val isCanceled = e is CancellationException || (e is java.io.IOException && e.message?.contains("canceled", ignoreCase = true) == true)
                if (isCanceled) throw e
                Log.w(TAG, "Fast LLM attempt failed (${e.message}), autonomously falling back to Gemini Flash...")
            }
        }

        val apiKey = Secrets.getActiveGeminiKey(context)
        val requestBodyJson = buildRequestBody(history, promptToSend, effectiveImageBytes)
        val bodyStr = requestBodyJson.toString()

        var lastErrorMsg = "Gemini API Error"
        for (model in CANDIDATE_MODELS) {
            val (url, extraHeaders) = IshaGatewayConfig.getChatStreamUrl(context, model, apiKey)
            val body = bodyStr.toRequestBody("application/json; charset=utf-8".toMediaType())

            val reqBuilder = Request.Builder()
                .url(url)
                .post(body)
            for ((k, v) in extraHeaders) {
                reqBuilder.addHeader(k, v)
            }
            val request = reqBuilder.build()

            val call = client.newCall(request)
            activeCall = call

            var response = try {
                withContext(Dispatchers.IO) { call.execute() }
            } catch (e: Exception) {
                if (e is CancellationException || (e is java.io.IOException && e.message?.contains("canceled", ignoreCase = true) == true)) {
                    Log.i(TAG, "Streaming request canceled by user.")
                    return
                }
                Log.w(TAG, "Model $model connection failed: ${e.message}, trying fallback...")
                lastErrorMsg = e.localizedMessage ?: "Connection error"
                null
            }

            // Dual-tier fallback: If gateway returned 401/403/502/connection error and direct key exists, retry direct Google endpoint!
            if ((response == null || (!response.isSuccessful && (response.code == 401 || response.code == 403 || response.code == 502))) &&
                apiKey.isNotBlank() && !url.contains("generativelanguage.googleapis.com")
            ) {
                try { response?.close() } catch (_: Exception) {}
                Log.w(TAG, "Gateway proxy failed for $model (${response?.code ?: "conn"}), retrying direct Google endpoint...")
                val directUrl = "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse&key=$apiKey"
                val directReq = Request.Builder().url(directUrl).post(body).build()
                val directCall = client.newCall(directReq)
                activeCall = directCall
                response = try {
                    withContext(Dispatchers.IO) { directCall.execute() }
                } catch (e: Exception) {
                    Log.w(TAG, "Direct Google fallback for $model failed: ${e.message}")
                    null
                }
            }

            if (response == null || !response.isSuccessful) {
                val errCode = response?.code ?: -1
                val errBody = try { response?.body?.string() ?: "" } catch (_: Exception) { "" }
                Log.w(TAG, "Model $model failed with HTTP $errCode: $errBody")
                lastErrorMsg = when (errCode) {
                    400 -> "Bad request — check request format"
                    401, 403 -> "API key invalid or quota exceeded"
                    404 -> "Model not found ($model)"
                    429 -> "Rate limit reached — thoda wait karein Boss"
                    503 -> "Server thoda busy hai Boss — ek second mein try karti hoon!"
                    else -> "Gemini API Error ($errCode)"
                }
                continue
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

                                    // Check for streaming text or thought token
                                    if (part.has("text")) {
                                        val token = part.get("text").asString
                                        val isThought = part.has("thought") && part.get("thought").asBoolean
                                        if (isThought) {
                                            callback.onThinkingToken(token)
                                        } else {
                                            fullAccumulator.append(token)
                                            callback.onToken(token)
                                        }
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
            val completedText = fullAccumulator.toString()
            IshaVoiceContextBridge.recordTextTurn(promptToSend, completedText)
            callback.onComplete(completedText)
        }
        return // Successfully completed request with this model
    }

    // If all candidate models failed, fallback to OpenRouter ONLY IF no image was attached
    // (OpenRouter is text-only; falling back with an image causes it to claim no image was sent)
    if (effectiveImageBytes == null && Secrets.getActiveOpenRouterKey(context).isNotBlank()) {
        try {
            Log.w(TAG, "All candidate Gemini models failed ($lastErrorMsg), falling back to OpenRouter...")
            val sysPrompt = AuraMemoryManager.buildSystemPrompt(context, isVoiceMode = false)
            openRouterClient.streamFastChat(
                systemPrompt = sysPrompt,
                history = history,
                prompt = promptToSend,
                callback = callback
            )
            return
        } catch (_: Exception) {}
    }
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
                                val status = outputObj.get("status")?.asString ?: "success"
                                val isFailure = status.equals("error", ignoreCase = true) ||
                                                status.equals("failed", ignoreCase = true) ||
                                                status.equals("unavailable", ignoreCase = true)
                                val errMsg = outputObj.get("message")?.asString
                                    ?: outputObj.get("error")?.asString
                                    ?: "Execution failed"

                                val guidance = if (isFailure) {
                                    "CRITICAL TOOL FAILURE: Tool '$toolName' FAILED with error: \"$errMsg\". You MUST be 100% honest and transparent with Boss: Do NOT claim that this tool or any verification succeeded! If a previous action (like creating alarm) already succeeded via a prior tool, acknowledge that it was scheduled, but explicitly tell Boss that '$toolName' (verification) failed with reason: \"$errMsg\". If permission or capability is unavailable, state the exact limitation clearly (e.g. 'Mere paas Location permission nahi hai')."
                                } else if (outputObj.has("_task_guidance") && outputObj.get("_task_guidance").asString.isNotBlank()) {
                                    outputObj.get("_task_guidance").asString
                                } else {
                                    "Step $currentStep complete. Original user request: \"$originalPrompt\". Agar aur tasks baaki hain (jaise screenshot ke baad WhatsApp par bhejna, ya YouTube ke baad DND lagana, app/game kholna, battery check karna), to IMMEDIATELY unka tool call karo — text summary abhi mat do! Sare tasks ke tool call hone ke baad hi natural Hindi me short spoken confirmation do."
                                }
                                outputObj.addProperty("_task_guidance", guidance)
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
            // Prune to executed tools schema (<1KB) for instant confirmation turn unless multi-step chained intent or follow-up tool needed
            val executedNames = executedTools.map { it.first }.toSet()
            val needsFollowUpTools = executedNames.any { it in setOf("chat_on_whatsapp", "take_screenshot", "open_camera", "read_notifications") }
            val hasChainedIntent = needsFollowUpTools || originalPrompt.contains(Regex("\\b(aur|then|uske baad|and|phir|also|bhejo|send|reply|baat|dono|teeno)\\b", RegexOption.IGNORE_CASE)) || originalPrompt.contains(",")
            val declarations = if (hasChainedIntent) {
                IshaToolRegistry.getGeminiToolDeclarations()
            } else {
                IshaToolRegistry.getSpecificToolDeclarations(executedNames)
            }

            val tools = JsonArray().apply {
                val toolHolder = JsonObject()
                toolHolder.add("function_declarations", declarations)
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

            // Generation Config: Low temperature (0.25) for instant spoken confirmation
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
            val (url, extraHeaders) = IshaGatewayConfig.getChatStreamUrl(context, candidateModel, apiKey)
            val reqBuilder = Request.Builder().url(url).post(body)
            for ((k, v) in extraHeaders) {
                reqBuilder.addHeader(k, v)
            }
            val request = reqBuilder.build()

            val call = client.newCall(request)
            activeCall = call

            var response = try {
                withContext(Dispatchers.IO) { call.execute() }
            } catch (e: Exception) {
                if (e is CancellationException || (e is java.io.IOException && e.message?.contains("canceled", ignoreCase = true) == true)) {
                    Log.i(TAG, "Tool response request canceled by user.")
                    return
                }
                Log.w(TAG, "Tool response model $candidateModel connection failed: ${e.message}, trying fallback...")
                null
            }

            // Dual-tier fallback: If gateway returned 401/403/502/connection error and direct key exists, retry direct Google endpoint!
            if ((response == null || (!response.isSuccessful && (response.code == 401 || response.code == 403 || response.code == 502))) &&
                apiKey.isNotBlank() && !url.contains("generativelanguage.googleapis.com")
            ) {
                try { response?.close() } catch (_: Exception) {}
                Log.w(TAG, "Gateway proxy failed for tool response $candidateModel (${response?.code ?: "conn"}), retrying direct Google endpoint...")
                val directUrl = "https://generativelanguage.googleapis.com/v1beta/models/$candidateModel:streamGenerateContent?alt=sse&key=$apiKey"
                val directReq = Request.Builder().url(directUrl).post(body).build()
                val directCall = client.newCall(directReq)
                activeCall = directCall
                response = try {
                    withContext(Dispatchers.IO) { directCall.execute() }
                } catch (e: Exception) {
                    Log.w(TAG, "Direct Google fallback for tool response $candidateModel failed: ${e.message}")
                    null
                }
            }

            if (response == null || !response.isSuccessful) {
                val errCode = response?.code ?: -1
                val errBody = try { response?.body?.string() ?: "" } catch (_: Exception) { "" }
                Log.w(TAG, "Tool response model $candidateModel failed with HTTP $errCode: $errBody")
                if (errCode == 503 || errCode == 429) {
                    Log.w(TAG, "Tool response model $candidateModel HTTP $errCode, falling back immediately...")
                    continue
                }
                if (errCode == 404 || errCode >= 400) {
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
                                        val isThought = part.has("thought") && part.get("thought").asBoolean
                                        if (isThought) {
                                            callback.onThinkingToken(token)
                                        } else {
                                            postToolAccumulator.append(token)
                                            callback.onToken(token)
                                        }
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
                model = candidateModel,
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
                IshaVoiceContextBridge.recordTextTurn(originalPrompt, fullSpokenText)
                callback.onComplete(fullSpokenText)
                return
            }
        }

        // If all candidate models failed or returned empty, use clean fallback
        val fallback = executedTools.joinToString("\n") { (name, _, res) ->
            val status = res.get("status")?.asString ?: "success"
            val msg = res.get("message")?.asString ?: res.get("error")?.asString ?: ""
            if (status == "error" || status == "failed") {
                "Boss, $name fail ho gaya: $msg"
            } else {
                res.get("message")?.asString ?: "$name complete ho gaya Boss! ✅"
            }
        }
        IshaVoiceContextBridge.recordTextTurn(originalPrompt, fallback)
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
                    val mime = detectMimeType(imageBytes)
                    val inlineData = JsonObject().apply {
                        addProperty("mime_type", mime)
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

        // 3. Systematic Domain-Based Intent Tool Pruning (IshaToolRouter):
        // Pure conversation ("Hi", "Hello", "Kaise ho") & direct screen perception ➔ 0 tools (<1KB payload, <1s TTFT)!
        // Specific domains (Media, Hardware, Social, Calls) ➔ Only that domain's 2-5 tools.
        // ⚡ O(1) check — tool turns are always at the tail of history
        val hasPendingToolTurn = history.lastOrNull()?.let {
            it.role == MessageRole.TOOL || !it.toolName.isNullOrBlank()
        } ?: false
        val routedDeclarations = IshaToolRouter.routeToolsForPrompt(
            prompt = newPrompt,
            hasImage = (imageBytes != null && imageBytes.isNotEmpty()),
            hasPendingToolTurn = hasPendingToolTurn
        )

        if (routedDeclarations != null && routedDeclarations.size() > 0) {
            val tools = JsonArray().apply {
                val toolHolder = JsonObject()
                toolHolder.add("function_declarations", routedDeclarations)
                add(toolHolder)
            }
            root.add("tools", tools)

            val toolConfig = JsonObject().apply {
                val fnCallingConfig = JsonObject().apply {
                    addProperty("mode", "AUTO")
                }
                add("function_calling_config", fnCallingConfig)
            }
            root.add("tool_config", toolConfig)
            Log.d(TAG, "Attached ${routedDeclarations.size()} pruned tool declarations for prompt")
        } else {
            Log.d(TAG, "⚡ Lean Turn: 0 tools attached for instant sub-second response")
        }

        // 4. Generation Config: Fast dynamic temperature + Intelligent Thinking Budget
        val genConfig = JsonObject().apply {
            addProperty("temperature", getDynamicTemperature(newPrompt))
            addProperty("topP", 0.95)
            addProperty("maxOutputTokens", 4096)
            if (isComplexReasoningIntent(newPrompt)) {
                val thinkingConfig = JsonObject().apply {
                    addProperty("thinkingBudget", 2048)
                }
                add("thinkingConfig", thinkingConfig)
                Log.d(TAG, "🧠 Attached 2048 token Thinking Budget for complex reasoning turn")
            }
        }
        root.add("generationConfig", genConfig)

        return root
    }

    private fun isComplexReasoningIntent(prompt: String): Boolean {
        val lower = prompt.lowercase().trim()
        val reasoningKeywords = listOf(
            "step by step", "deeply", "analyse", "analyze", "comparison", "compare",
            "logic", "reason", "puzzle", "math", "proof", "solve", "debug",
            "refactor", "code", "architecture", "philosophy", "pros and cons",
            "dhyan se socho", "soch kar batao", "explain in detail", "vistar se"
        )
        return reasoningKeywords.any { lower.contains(it) }
    }

    private fun isDeviceActionIntent(prompt: String): Boolean {
        val lower = prompt.lowercase().trim()
        val actionKeywords = listOf(
            "khol", "open", "band", "close", "on kar", "off kar", "toggle", "chalu",
            "bhejo", "send", "call", "phone", "dial", "lagao", "set", "alarm",
            "timer", "volume", "brightness", "torch", "flashlight", "wifi",
            "bluetooth", "hotspot", "screenshot", "camera", "photo", "whatsapp",
            "message", "sms", "navigate", "location", "battery", "storage",
            "clean", "clear", "type", "likho", "tap", "click", "search", "dhoondo",
            "play", "chalao", "sunao", "youtube", "music", "song", "insta", "instagram",
            "dialer", "contact", "read", "screen", "padh", "padho", "kya likha",
            "dekh", "dekho", "dekho screen", "batao battery", "percentage"
        )
        return actionKeywords.any { lower.contains(it) }
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
        // ⚡ 45s cache: buildSystemPrompt() triggers 4+ DB/SharedPrefs reads — cache aggressively
        if (cached != null && (now - lastSystemInstructionTime) < 45_000L) {
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

                // Verify and record to Experience Database asynchronously in background
                CoroutineScope(Dispatchers.IO).launch {
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
                }

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

                    // Verify and record to Experience Database asynchronously in background
                    CoroutineScope(Dispatchers.IO).launch {
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
                    }

                    Triple(fnName, fnArgs, toolResult)
                }
            }.awaitAll()
        }
    }

    private fun createHistoryContents(history: List<ChatMessage>): JsonArray {
        val contents = JsonArray()
        // Include recent USER, ASSISTANT, and TOOL messages (smart retention: first 3 context turns + last 20)
        val cleanHistory = history.filter {
            (it.text.isNotBlank() || !it.imageUri.isNullOrBlank()) && !it.isStreaming
        }
        // ⚡ Cross-Modal Bridge Safety Net:
        // If history is empty (e.g. from Dynamic Island, Widget, or Quick Action),
        // populate from IshaVoiceContextBridge so context is NEVER lost across surfaces!
        val effectiveHistory = if (cleanHistory.isEmpty()) {
            val bridgeTurns = IshaVoiceContextBridge.getRecentTurns(10)
            if (bridgeTurns.isNotEmpty()) {
                bridgeTurns.flatMap { turn ->
                    listOf(
                        ChatMessage(sessionId = "bridge", role = MessageRole.USER, text = turn.userText),
                        ChatMessage(sessionId = "bridge", role = MessageRole.ASSISTANT, text = turn.assistantText)
                    )
                }
            } else {
                cleanHistory
            }
        } else {
            cleanHistory
        }

        // ⚡ first(1) + last(22): First turn is anchor context; recent 22 turns give isha max short-term memory
        // (first 3 was often app-startup boilerplate, wasting 2 precious context slots)
        val historyToSend = if (effectiveHistory.size > 23) {
            effectiveHistory.take(1) + effectiveHistory.takeLast(22)
        } else {
            effectiveHistory
        }

        var lastRole: String? = null
        for (msg in historyToSend) {
            val role = if (msg.role == MessageRole.USER) "user" else "model"
            val messageText = when {
                msg.role == MessageRole.TOOL -> {
                    // ⚡ 800 chars: search results & screen text need more space to retain context accurately
                    val trimmed = if (msg.text.length > 800) msg.text.take(800) + "... [truncated]" else msg.text
                    "[Tool Output (${msg.toolName ?: "action"})]: $trimmed"
                }
                !msg.toolResult.isNullOrBlank() -> {
                    val trimmedResult = if (msg.toolResult.length > 800) msg.toolResult.take(800) + "... [truncated]" else msg.toolResult
                    "${msg.text}\n[Executed Action Output]: $trimmedResult"
                }
                else -> msg.text
            }

            if (role == lastRole) {
                // If consecutive messages of same role exist, append to previous turn with size guard
                if (contents.size() > 0) {
                    val lastTurn = contents.get(contents.size() - 1).asJsonObject
                    val parts = lastTurn.getAsJsonArray("parts")
                    val trimmed = if (messageText.length > 800) messageText.take(800) + "... [truncated]" else messageText
                    val part = JsonObject().apply { addProperty("text", trimmed) }
                    parts.add(part)
                }
                continue
            }
            val turn = JsonObject().apply {
                addProperty("role", role)
                val parts = JsonArray().apply {
                    if (role == "user" && !msg.imageUri.isNullOrBlank()) {
                        try {
                            val uri = android.net.Uri.parse(msg.imageUri)
                            val imgBytes = if (uri.scheme == "file") {
                                val f = java.io.File(uri.path ?: "")
                                if (f.exists() && f.length() > 0) f.readBytes() else null
                            } else {
                                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            }
                            if (imgBytes != null && imgBytes.isNotEmpty()) {
                                val mime = detectMimeType(imgBytes)
                                val inlineData = JsonObject().apply {
                                    addProperty("mime_type", mime)
                                    addProperty("data", android.util.Base64.encodeToString(imgBytes, android.util.Base64.NO_WRAP))
                                }
                                val imagePart = JsonObject().apply {
                                    add("inline_data", inlineData)
                                }
                                add(imagePart)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not load history image: ${msg.imageUri}", e)
                        }
                    }
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

    /**
     * Generates a rapid conversational text reply (e.g. for continuous WhatsApp auto-chat).
     * Routes via Fast LLM (Groq / OpenRouter) or candidate Gemini Flash models.
     * @param history Optional prior conversation turns for context retention.
     */
    suspend fun generateFastReply(
        prompt: String,
        history: List<ChatMessage> = emptyList(),
        systemInstructionText: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val hasFastLlmKey = Secrets.getActiveGroqKey(context).isNotBlank() || Secrets.getActiveOpenRouterKey(context).isNotBlank()
        if (hasFastLlmKey) {
            try {
                var resultText = ""
                openRouterClient.streamFastChat(
                    systemPrompt = systemInstructionText ?: "You are Isha, a sweet, witty, natural AI companion created by Ansh Kesharwani.",
                    history = history,
                    prompt = prompt,
                    callback = object : ChatStreamCallback {
                        val buf = StringBuilder()
                        override fun onToken(chunk: String) { buf.append(chunk) }
                        override fun onToolExecution(toolName: String, args: String, result: String) {}
                        override fun onComplete(fullText: String) { resultText = fullText }
                        override fun onError(errorMessage: String) {}
                    }
                )
                if (resultText.isNotBlank()) {
                    return@withContext cleanLlmReply(resultText)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fast LLM fast reply failed: ${e.message}, falling back to Gemini Flash")
            }
        }

        val apiKey = Secrets.getActiveGeminiKey(context)
        val sysPrompt = systemInstructionText ?: "You are Isha, a sweet, witty, natural AI companion created by Ansh Kesharwani. Respond in 1-2 natural, lively sentences in conversational Hinglish without quotes or markdown."

        val requestJson = JsonObject().apply {
            val contents = JsonArray().apply {
                // Inject prior conversation history for context
                for (msg in history) {
                    add(JsonObject().apply {
                        addProperty("role", if (msg.role == MessageRole.ASSISTANT) "model" else "user")
                        add("parts", JsonArray().apply {
                            add(JsonObject().apply { addProperty("text", msg.text) })
                        })
                    })
                }
                // Current user message
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("parts", JsonArray().apply {
                        add(JsonObject().apply { addProperty("text", prompt) })
                    })
                })
            }
            add("contents", contents)
            add("system_instruction", JsonObject().apply {
                add("parts", JsonArray().apply {
                    add(JsonObject().apply { addProperty("text", sysPrompt) })
                })
            })
            add("generationConfig", JsonObject().apply {
                addProperty("temperature", 0.7)
                addProperty("maxOutputTokens", 256)
            })
        }
        val bodyStr = requestJson.toString()

        for (model in CANDIDATE_MODELS) {
            val (url, extraHeaders) = IshaGatewayConfig.getChatStreamUrl(context, model, apiKey)
            val body = bodyStr.toRequestBody("application/json; charset=utf-8".toMediaType())
            val reqBuilder = Request.Builder().url(url).post(body)
            for ((k, v) in extraHeaders) reqBuilder.addHeader(k, v)
            val req = reqBuilder.build()

            var resp = try {
                client.newCall(req).execute()
            } catch (e: Exception) {
                Log.w(TAG, "Fast reply error with $model: ${e.message}")
                null
            }

            if ((resp == null || !resp.isSuccessful) && apiKey.isNotBlank() && !url.contains("generativelanguage.googleapis.com")) {
                try { resp?.close() } catch (_: Exception) {}
                val directUrl = "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse&key=$apiKey"
                val directReq = Request.Builder().url(directUrl).post(body).build()
                resp = try { client.newCall(directReq).execute() } catch (_: Exception) { null }
            }

            if (resp != null && resp.isSuccessful) {
                val stream = resp.body?.byteStream() ?: continue
                val reader = BufferedReader(InputStreamReader(stream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val cur = line ?: break
                    if (cur.startsWith("data: ")) {
                        val json = cur.removePrefix("data: ").trim()
                        if (json == "[DONE]" || json.isBlank()) continue
                        try {
                            val parsed = JsonParser.parseString(json).asJsonObject
                            val parts = parsed.getAsJsonArray("candidates")?.get(0)?.asJsonObject?.getAsJsonObject("content")?.getAsJsonArray("parts")
                            if (parts != null) {
                                for (i in 0 until parts.size()) {
                                    val part = parts.get(i).asJsonObject
                                    if (part.has("text") && (!part.has("thought") || !part.get("thought").asBoolean)) {
                                        sb.append(part.get("text").asString)
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
                resp.close()
                val finalStr = sb.toString().trim()
                if (finalStr.isNotBlank()) {
                    return@withContext cleanLlmReply(finalStr)
                }
            } else {
                resp?.close()
            }
        }
        null
    }

    private fun cleanLlmReply(raw: String): String {
        return raw.trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .lines()
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
    }

    fun cancelCurrentGeneration() {
        try {
            activeCall?.cancel()
            activeCall = null
        } catch (_: Exception) {}
    }
}
