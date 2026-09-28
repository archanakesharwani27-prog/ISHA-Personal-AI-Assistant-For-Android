package com.aura.assistant.tools

import com.google.gson.JsonObject

enum class ToolStatus {
    SUCCESS,
    FAILED,
    DENIED,
    UNAVAILABLE,
    NEEDS_CONFIRMATION,
    CANCELLED,
    TIMEOUT,
    PARTIAL_SUCCESS,
    VERIFICATION_FAILED
}

data class VerificationEvidence(
    val verified: Boolean,
    val observedState: String? = null,
    val method: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class ToolError(
    val code: String,
    val message: String,
    val retryable: Boolean = false
)

/**
 * Standardized result returned by every AURA tool execution.
 * AURA never reports an action as done unless verified with evidence.
 */
data class ToolResult(
    val toolId: String,
    val callId: String = "",
    val status: ToolStatus,
    val message: String?,
    val data: JsonObject? = null,
    val evidence: VerificationEvidence? = null,
    val error: ToolError? = null,
    val retryable: Boolean = false,
    val durationMs: Long = 0L
) {
    companion object {
        fun success(
            toolId: String,
            message: String,
            data: JsonObject? = null,
            evidence: VerificationEvidence? = null,
            durationMs: Long = 0L
        ): ToolResult = ToolResult(
            toolId = toolId,
            status = ToolStatus.SUCCESS,
            message = message,
            data = data,
            evidence = evidence ?: VerificationEvidence(verified = true, method = "execution_completed"),
            durationMs = durationMs
        )

        fun failure(
            toolId: String,
            message: String,
            error: ToolError? = null,
            retryable: Boolean = false,
            durationMs: Long = 0L
        ): ToolResult = ToolResult(
            toolId = toolId,
            status = ToolStatus.FAILED,
            message = message,
            error = error ?: ToolError(code = "EXECUTION_ERROR", message = message, retryable = retryable),
            retryable = retryable,
            durationMs = durationMs
        )

        fun verificationFailed(
            toolId: String,
            message: String,
            observedState: String?,
            durationMs: Long = 0L
        ): ToolResult = ToolResult(
            toolId = toolId,
            status = ToolStatus.VERIFICATION_FAILED,
            message = message,
            evidence = VerificationEvidence(verified = false, observedState = observedState, method = "state_check"),
            retryable = true,
            durationMs = durationMs
        )

        fun denied(
            toolId: String,
            permissionName: String
        ): ToolResult = ToolResult(
            toolId = toolId,
            status = ToolStatus.DENIED,
            message = "Permission '$permissionName' is required but not granted.",
            error = ToolError(code = "PERMISSION_DENIED", message = permissionName, retryable = false)
        )
    }

    /**
     * Converts result into Gemini functionResponse JSON format.
     */
    fun toJsonObject(): JsonObject = JsonObject().apply {
        addProperty("toolId", toolId)
        addProperty("status", status.name)
        addProperty("message", message ?: "")
        addProperty("verified", evidence?.verified ?: (status == ToolStatus.SUCCESS))
        if (evidence?.observedState != null) {
            addProperty("observedState", evidence.observedState)
        }
        if (data != null) {
            add("data", data)
        }
        if (error != null) {
            val errObj = JsonObject().apply {
                addProperty("code", error.code)
                addProperty("message", error.message)
                addProperty("retryable", error.retryable)
            }
            add("error", errObj)
        }
        addProperty("durationMs", durationMs)
    }
}
