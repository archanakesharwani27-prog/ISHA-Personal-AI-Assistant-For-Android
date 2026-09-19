package com.aura.assistant.security

import com.aura.assistant.tools.RiskLevel

enum class PolicyDecision {
    ALLOW_AUTOMATIC,
    REQUIRE_CONFIRMATION,
    DENY_UNSAFE
}

/**
 * Policy and Risk Governance Engine for AURA 2.0.
 * In accordance with Section 25 of 2.0.md:
 * - LOW risk tools execute automatically (volume, flashlight, battery, read notifications).
 * - MEDIUM risk tools execute or prompt depending on user policy (messages, calendar events).
 * - HIGH risk tools strictly require confirmation (deletions, emergency SOS, uninstalls).
 */
object PolicyEngine {

    fun evaluate(toolName: String, riskLevel: RiskLevel): PolicyDecision {
        return when (toolName) {
            "empty_recycle_bin", "manage_app_storage" -> PolicyDecision.REQUIRE_CONFIRMATION
            "send_sos" -> PolicyDecision.REQUIRE_CONFIRMATION
            else -> when (riskLevel) {
                RiskLevel.LOW -> PolicyDecision.ALLOW_AUTOMATIC
                RiskLevel.MEDIUM -> PolicyDecision.ALLOW_AUTOMATIC
                RiskLevel.HIGH -> PolicyDecision.REQUIRE_CONFIRMATION
                RiskLevel.CRITICAL -> PolicyDecision.DENY_UNSAFE
            }
        }
    }
}
