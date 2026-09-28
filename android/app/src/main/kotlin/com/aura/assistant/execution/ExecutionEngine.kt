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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Autonomous Tool Execution Engine for AURA Agent Core v4.
 *
 * Implements the 10-stage execution lifecycle:
 * DISCOVER → VALIDATE → AUTHORIZE → PREPARE → EXECUTE → OBSERVE → VERIFY → RECOVER → COMMIT → LEARN
 */
object ExecutionEngine {
    private const val TAG = "ExecutionEngine"

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
            callId = callId
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
        val auth = tool.authorize(toolContext)
        when (auth) {
            AuthorizationResult.DENIED -> {
                Log.e(TAG, "Authorization denied for tool '${tool.name}'")
                return ToolResult.denied(tool.id, "Security policy or user settings denied this action.")
            }
            AuthorizationResult.NEEDS_CONFIRMATION -> {
                Log.w(TAG, "Tool '${tool.name}' requires explicit confirmation")
                return ToolResult(
                    toolId = tool.id,
                    callId = callId,
                    status = ToolStatus.NEEDS_CONFIRMATION,
                    message = "High risk action requires confirmation: ${tool.name}"
                )
            }
            AuthorizationResult.ALLOWED -> {}
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
            val evidence = ExecutionVerifier.verify(
                context = context,
                tool = tool,
                arguments = arguments,
                executionResult = executionResult,
                toolContext = toolContext
            )

            val verifiedResult = if (executionResult.status == ToolStatus.SUCCESS) {
                if (!evidence.verified) {
                    Log.w(TAG, "Tool '${tool.name}' executed but verification remains UNVERIFIED: ${evidence.observedState}")
                    executionResult.copy(
                        evidence = evidence,
                        durationMs = System.currentTimeMillis() - startTime
                    )
                } else {
                    executionResult.copy(
                        evidence = evidence,
                        durationMs = System.currentTimeMillis() - startTime
                    )
                }
            } else {
                executionResult.copy(durationMs = System.currentTimeMillis() - startTime)
            }

            if (verifiedResult.status == ToolStatus.SUCCESS) {
                // ── Stage 9: COMMIT & Stage 10: LEARN ───────────────────
                Log.i(TAG, "Tool '${tool.name}' successfully executed and observed in ${verifiedResult.durationMs}ms")
                recordLearning(context, tool, arguments, verifiedResult, true)
                return verifiedResult
            }

            lastResult = verifiedResult

            if (!verifiedResult.retryable || attempts > retryPolicy.maxRetries) {
                break
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
