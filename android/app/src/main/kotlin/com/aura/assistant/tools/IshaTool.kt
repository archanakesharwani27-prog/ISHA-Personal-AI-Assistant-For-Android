package com.aura.assistant.tools

import com.google.gson.JsonObject

enum class ToolCategory {
    COMMUNICATION,
    SYSTEM,
    MEDIA,
    PRODUCTIVITY,
    APPS,
    NAVIGATION,
    VISION,
    FILES,
    AUTOMATION
}

enum class RiskLevel {
    LOW,        // Read-only, safe (e.g. read battery, get volume)
    MEDIUM,     // Minor state change (e.g. set volume, toggle flashlight)
    HIGH,       // External or permanent action (e.g. send SMS, make call, delete file)
    CRITICAL    // Factory reset, wipe data, uninstall app
}

enum class CapabilityStatus {
    AVAILABLE,
    PERMISSION_REQUIRED,
    HARDWARE_MISSING,
    DISABLED
}

/**
 * Authorization contract for AURA Agent Core v4.
 * Clearly separates permission/confirmation check from post-execution verification.
 */
enum class AuthorizationResult {
    ALLOWED,
    DENIED,
    NEEDS_CONFIRMATION
}

/**
 * Verification contract for AURA Agent Core v4.
 * Default is UNVERIFIED (AURA never assumes success until verified).
 */
enum class VerificationStatus {
    UNVERIFIED,            // Default: action was dispatched, but real state change not yet verified
    VERIFIED_SUCCESS,      // State verified by device inspection (system setting, notification, UI tree)
    FAILED_VERIFICATION    // Verification detected that the state did not change or error appeared
}

data class VerificationResult(
    val status: VerificationStatus = VerificationStatus.UNVERIFIED,
    val observedState: String = "No verification performed",
    val failureReason: String? = null
)

/**
 * Universal contract for all ISHA autonomous tools.
 * Every tool implements discovery, authorization, execution, and verification.
 */
interface IshaTool {
    val id: String
    val name: String
    val description: String
    val category: ToolCategory
    val riskLevel: RiskLevel get() = RiskLevel.LOW
    val requiresConfirmation: Boolean get() = false

    /**
     * Checks whether this tool can be executed in the current device environment.
     */
    fun isAvailable(context: ToolContext): CapabilityStatus = CapabilityStatus.AVAILABLE

    /**
     * Evaluates authorization before execution.
     */
    fun authorize(context: ToolContext): AuthorizationResult {
        return if (requiresConfirmation || riskLevel == RiskLevel.CRITICAL) {
            AuthorizationResult.NEEDS_CONFIRMATION
        } else {
            AuthorizationResult.ALLOWED
        }
    }

    /**
     * Executes the tool with the provided arguments and context.
     */
    suspend fun execute(
        arguments: JsonObject,
        context: ToolContext
    ): ToolResult

    /**
     * Inspects actual device/app state to verify whether the tool succeeded.
     * Default is UNVERIFIED (never assume success).
     */
    suspend fun verify(
        arguments: JsonObject,
        result: ToolResult,
        context: ToolContext
    ): VerificationResult = VerificationResult(
        status = VerificationStatus.UNVERIFIED,
        observedState = "Default unverified state"
    )
}

/** Backward compatibility alias for AuraTool */
typealias AuraTool = IshaTool

