package com.aura.assistant.execution

import android.content.Context
import android.util.Log
import com.aura.assistant.memory.AuraExperienceDatabase
import com.aura.assistant.memory.ExperienceRecord
import com.aura.assistant.tools.AuthorizationResult
import com.aura.assistant.tools.AuraTool
import com.aura.assistant.tools.CapabilityStatus
import com.aura.assistant.tools.ToolContext
import com.aura.assistant.tools.ToolRegistry
import com.aura.assistant.tools.ToolResult
import com.aura.assistant.tools.ToolStatus
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Autonomous Tool Execution Engine for AURA Agent Core v4.
 *
 * Implements the 10-stage execution lifecycle:
 * DISCOVER → VALIDATE → AUTHORIZE → PREPARE → EXECUTE → OBSERVE → VERIFY → RECOVER → COMMIT → LEARN
 */
object ExecutionEngine {
    private const val TAG = "ExecutionEngine"

    private val TRANSIENT_SETTLE_METHODS = setOf(
        "foreground_transition_pending",
        "keyguard_still_locked",
        "youtube_autoplay_pending",
        "youtube_fg_loading",
        "media_playback_pending"
    )

    private val ASYNC_IN_PROGRESS_METHODS = setOf(
        "youtube_autoplay_pending",
        "youtube_fg_loading",
        "youtube_unverified",
        "media_playback_pending"
    )

    private val RETRYABLE_STATE_MISMATCH_METHODS = setOf(
        "AudioManager.getStreamVolume",
        "Settings.System.SCREEN_BRIGHTNESS",
        "NotificationManager.currentInterruptionFilter",
        "BluetoothManager.adapter.isEnabled",
        "WifiManager.isWifiEnabled",
        "keyguard_still_locked",
        "foreground_transition_pending",
        "siren_state_check",
        "siren_stopped_check",
        "tool_contract_verify"
    )

    suspend fun execute(
        tool: AuraTool,
        arguments: JsonObject,
        context: Context,
        scope: CoroutineScope,
        callId: String = "",
        retryPolicy: RetryPolicy = RetryPolicy.DEFAULT
    ): ToolResult {
        val toolContext = ToolContext(
            applicationContext = context,
            scope = scope,
            callId = callId,
            timeoutMs = RetryPolicy.getToolTimeout(tool.name)
        )
        val startTime = System.currentTimeMillis()

        // ── Stage 1: DISCOVER & Stage 2: VALIDATE ───────────────────────
        val availability = tool.isAvailable(toolContext)
        if (availability != CapabilityStatus.AVAILABLE) {
            Log.w(TAG, "Tool '${tool.name}' is not available: $availability")
            return ToolResult(
                toolId = tool.id,
                callId = callId,
                status = ToolStatus.UNAVAILABLE,
                message = "Tool '${tool.name}' unavailable in current device environment: $availability"
            )
        }

        // ── Stage 3: AUTHORIZE ──────────────────────────────────────────
        val policyDecision = com.aura.assistant.security.PolicyEngine.evaluate(tool.name, tool.riskLevel)
        if (policyDecision == com.aura.assistant.security.PolicyDecision.DENY_UNSAFE) {
            Log.e(TAG, "Tool '${tool.name}' denied by PolicyEngine (CRITICAL risk).")
            return ToolResult.denied(tool.id, "Security policy denied critical/unsafe action '${tool.name}'.")
        }

        val auth = tool.authorize(toolContext)
        val needsConfirmation = policyDecision == com.aura.assistant.security.PolicyDecision.REQUIRE_CONFIRMATION ||
                                auth == AuthorizationResult.NEEDS_CONFIRMATION

        if (needsConfirmation) {
            val confirmed = arguments.has("confirmed") && arguments.get("confirmed").asBoolean
            if (!confirmed) {
                Log.w(TAG, "Tool '${tool.name}' requires explicit confirmation.")
                val isAppForeground = try {
                    com.aura.assistant.IshaDynamicIsland.isAppInForeground
                } catch (_: Exception) {
                    false
                }
                if (isAppForeground) {
                    val decision = CompletableDeferred<Boolean>()
                    com.aura.assistant.security.ConfirmationEngine.request(
                        toolName = tool.name,
                        promptText = "AURA Security Governance: Kya aap '${tool.name}' ko run karna chahte hain?",
                        onConfirm = {
                            Log.i(TAG, "User confirmed action: ${tool.name}")
                            decision.complete(true)
                        },
                        onCancel = {
                            Log.i(TAG, "User cancelled action: ${tool.name}")
                            decision.complete(false)
                        }
                    )
                    val approved = withTimeoutOrNull(25_000L) { decision.await() }
                    if (approved == false) {
                        return ToolResult(
                            toolId = tool.id,
                            callId = callId,
                            status = ToolStatus.CANCELLED,
                            message = "Boss ne '${tool.name}' action cancel kar diya.",
                            retryable = false
                        )
                    } else if (approved == null) {
                        com.aura.assistant.security.ConfirmationEngine.cancel()
                        return ToolResult(
                            toolId = tool.id,
                            callId = callId,
                            status = ToolStatus.NEEDS_CONFIRMATION,
                            message = "Confirmation timed out for high-risk action: ${tool.name}",
                            retryable = false
                        )
                    }
                    // Approved == true: continue straight into Stage 4 -> Stage 10!
                } else {
                    com.aura.assistant.security.ConfirmationEngine.request(
                        toolName = tool.name,
                        promptText = "AURA Security Governance: Kya aap '${tool.name}' ko run karna chahte hain?",
                        onConfirm = { Log.i(TAG, "User confirmed action: ${tool.name}") }
                    )
                    return ToolResult(
                        toolId = tool.id,
                        callId = callId,
                        status = ToolStatus.NEEDS_CONFIRMATION,
                        message = "High risk action requires confirmation: ${tool.name}",
                        retryable = false
                    )
                }
            }
        } else if (auth == AuthorizationResult.DENIED) {
            Log.e(TAG, "Authorization denied for tool '${tool.name}'")
            return ToolResult.denied(tool.id, "Security policy or user settings denied this action.")
        }

        // ── Stage 4: PREPARE ────────────────────────────────────────────
        var attempts = 0
        var currentBackoff = retryPolicy.initialBackoffMs
        var lastResult: ToolResult? = null

        while (attempts <= retryPolicy.maxRetries) {
            attempts++
            Log.i(TAG, "Executing tool '${tool.name}' (attempt $attempts/${retryPolicy.maxRetries + 1})")

            // ── Stage 5: EXECUTE ────────────────────────────────────────
            val executionResult = withTimeoutOrNull(toolContext.timeoutMs) {
                try {
                    tool.execute(arguments, toolContext)
                } catch (e: Exception) {
                    Log.e(TAG, "Exception during '${tool.name}' execution: ${e.message}", e)
                    ToolResult.failure(
                        toolId = tool.id,
                        message = "Execution failed: ${e.message}",
                        retryable = true
                    )
                }
            } ?: ToolResult(
                toolId = tool.id,
                callId = callId,
                status = ToolStatus.TIMEOUT,
                message = "Tool '${tool.name}' timed out after ${toolContext.timeoutMs}ms",
                retryable = true
            )

            // ── Stage 6: OBSERVE & Stage 7: VERIFY ──────────────────────
            var evidence = ExecutionVerifier.verify(
                context = context,
                tool = tool,
                arguments = arguments,
                executionResult = executionResult,
                toolContext = toolContext
            )

            // Non-blocking transient settle poll (e.g. Activity window transitions, keyguard dismiss animations)
            if (executionResult.status == ToolStatus.SUCCESS && !evidence.verified && evidence.method in TRANSIENT_SETTLE_METHODS) {
                for (settleAttempt in 1..2) {
                    delay(400L)
                    evidence = ExecutionVerifier.verify(
                        context = context,
                        tool = tool,
                        arguments = arguments,
                        executionResult = executionResult,
                        toolContext = toolContext
                    )
                    if (evidence.verified) {
                        Log.i(TAG, "Tool '${tool.name}' state settled & verified on poll #$settleAttempt (${evidence.method})")
                        break
                    }
                }
            }

            val verifiedResult = executionResult.copy(
                evidence = evidence,
                durationMs = System.currentTimeMillis() - startTime
            )

            if (verifiedResult.status == ToolStatus.SUCCESS) {
                val shouldRetryUnverified = !evidence.verified &&
                    evidence.method !in ASYNC_IN_PROGRESS_METHODS &&
                    evidence.method in RETRYABLE_STATE_MISMATCH_METHODS &&
                    attempts <= retryPolicy.maxRetries

                if (!shouldRetryUnverified) {
                    if (!evidence.verified) {
                        Log.w(TAG, "Tool '${tool.name}' executed but verification remains UNVERIFIED: ${evidence.observedState}")
                    }
                    // ── Stage 9: COMMIT & Stage 10: LEARN ───────────────────
                    Log.i(TAG, "Tool '${tool.name}' successfully executed and observed in ${verifiedResult.durationMs}ms (verified: ${evidence.verified})")
                    recordLearning(context, tool, arguments, verifiedResult, evidence.verified)
                    return verifiedResult
                }

                Log.w(TAG, "Tool '${tool.name}' reported success but physical state mismatch (${evidence.method}: ${evidence.observedState}); triggering Stage 8 retry...")
                lastResult = verifiedResult.copy(retryable = true)
            } else {
                lastResult = verifiedResult
                if (!verifiedResult.retryable || attempts > retryPolicy.maxRetries) {
                    break
                }
            }

            // ── Stage 8: RECOVER (Backoff retry) ────────────────────────
            Log.w(TAG, "Retrying '${tool.name}' in ${currentBackoff}ms...")
            delay(currentBackoff)
            currentBackoff = (currentBackoff * retryPolicy.backoffMultiplier).toLong()
        }

        // Check for autonomous fallback if all attempts failed
        val fallbackToolName = RetryPolicy.getFallback(tool.name)
        if (fallbackToolName != null && fallbackToolName != tool.name) {
            Log.i(TAG, "Tool '${tool.name}' failed after $attempts attempts. Fallback tool available: '$fallbackToolName'")
            val fallbackTool = ToolRegistry.getTool(fallbackToolName)
            if (fallbackTool != null) {
                Log.i(TAG, "Executing fallback tool '$fallbackToolName' for failed '${tool.name}'")
                return execute(
                    tool = fallbackTool,
                    arguments = arguments,
                    context = context,
                    scope = scope,
                    callId = callId,
                    retryPolicy = RetryPolicy.NO_RETRY
                )
            }
        }

        val finalResult = lastResult ?: ToolResult.failure(
            toolId = tool.id,
            message = "Tool '${tool.name}' failed after $attempts attempts.",
            durationMs = System.currentTimeMillis() - startTime
        )

        recordLearning(context, tool, arguments, finalResult, false)
        return finalResult
    }

    private fun recordLearning(
        context: Context,
        tool: AuraTool,
        arguments: JsonObject,
        result: ToolResult,
        success: Boolean
    ) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                AuraExperienceDatabase.getInstance(context).recordExecution(
                    ExperienceRecord(
                        normalizedIntent = tool.name,
                        userInput = tool.description,
                        tool = tool.name,
                        arguments = arguments.toString(),
                        executionStatus = result.status.name,
                        verificationStatus = if (result.evidence?.verified == true) "VERIFIED_SUCCESS" else "UNVERIFIED",
                        failureReason = result.error?.message ?: result.message,
                        latencyMs = result.durationMs
                    )
                )
            } catch (_: Exception) {}
        }
    }

    /**
     * Executes a tool through the full 10-stage autonomous lifecycle:
     * DISCOVER → VALIDATE → AUTHORIZE (PolicyEngine) → PREPARE → EXECUTE (Timeout) →
     * OBSERVE → VERIFY (ExecutionVerifier) → RECOVER (Retry & Fallback) → COMMIT → LEARN (ExperienceDatabase)
     *
     * Returns a structured JsonObject preserving exact REST schema for Gemini and internal services.
     */
    suspend fun executeTool(
        context: Context,
        toolName: String,
        arguments: JsonObject,
        scope: CoroutineScope = CoroutineScope(kotlinx.coroutines.Dispatchers.IO),
        callId: String = ""
    ): JsonObject {
        val tool = ToolRegistry.getTool(toolName) ?: ToolRegistry.createDynamicTool(toolName)
        val policy = RetryPolicy.getPolicyForTool(toolName)
        val toolResult = execute(
            tool = tool,
            arguments = arguments,
            context = context,
            scope = scope,
            callId = callId,
            retryPolicy = policy
        )

        val output = JsonObject()
        val isVerified = toolResult.evidence?.verified == true
        val observedState = toolResult.evidence?.observedState

        when (toolResult.status) {
            ToolStatus.SUCCESS -> {
                if (isVerified) {
                    output.addProperty("status", "success")
                    output.addProperty("message", toolResult.message)
                    toolResult.data?.entrySet()?.forEach { (k, v) ->
                        if (!output.has(k)) output.add(k, v)
                    }
                    if (!output.has("_task_guidance")) {
                        val stateInfo = if (!observedState.isNullOrBlank()) " ($observedState)" else ""
                        output.addProperty(
                            "_task_guidance",
                            "Tool '$toolName' finished and physically verified$stateInfo. Give a short, sweet spoken Hindi/Hinglish confirmation to Boss."
                        )
                    }
                } else {
                    // Action dispatched or in-progress, but physical evidence is NOT yet verified
                    output.addProperty("status", "in_progress")
                    output.addProperty("message", toolResult.message)
                    toolResult.data?.entrySet()?.forEach { (k, v) ->
                        if (!output.has(k)) output.add(k, v)
                    }
                    val guidanceMsg = if (!observedState.isNullOrBlank()) observedState else "action in progress"
                    output.addProperty(
                        "_task_guidance",
                        "CRITICAL VERIFICATION NOTICE: Tool '$toolName' is in progress but NOT yet physically verified ($guidanceMsg). " +
                                "DO NOT say 'Done', 'Ho gaya', 'Play kar diya', ya 'Bhej diya'! " +
                                "Acknowledge the ongoing action honestly in natural Hindi/Hinglish (e.g. '$toolName open karke play kar rahi hoon...' ya 'process chal rahi hai...')."
                    )
                }
            }
            ToolStatus.NEEDS_CONFIRMATION -> {
                output.addProperty("status", "needs_confirmation")
                output.addProperty("message", toolResult.message)
                output.addProperty("tool_name", toolName)
                output.addProperty("requires_confirmation", true)
            }
            ToolStatus.UNAVAILABLE -> {
                output.addProperty("status", "error")
                output.addProperty("message", toolResult.message)
            }
            ToolStatus.DENIED -> {
                output.addProperty("status", "error")
                output.addProperty("message", toolResult.message)
            }
            else -> {
                output.addProperty("status", "error")
                output.addProperty("message", toolResult.message)
                if (toolResult.error != null) {
                    output.addProperty("error", toolResult.error.message)
                }
                toolResult.data?.entrySet()?.forEach { (k, v) ->
                    if (!output.has(k)) output.add(k, v)
                }
            }
        }
        toolResult.evidence?.let {
            output.addProperty("verified", it.verified)
            if (it.observedState != null) {
                output.addProperty("observedState", it.observedState)
            }
        }
        return output
    }
}
