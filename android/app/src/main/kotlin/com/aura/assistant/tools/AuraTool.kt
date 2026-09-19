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
 * Universal contract for all AURA 2.0 autonomous tools.
 * Every tool implements discovery, execution, and verification.
 */
interface AuraTool {
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
     * Executes the tool with the provided arguments and context.
     */
    suspend fun execute(
        arguments: JsonObject,
        context: ToolContext
    ): ToolResult
}
