package com.aura.assistant.execution

/**
 * Retry and Fallback configuration for AURA 2.0 autonomous execution.
 */
data class RetryPolicy(
    val maxRetries: Int = 2,
    val initialBackoffMs: Long = 300L,
    val backoffMultiplier: Double = 1.5,
    val fallbackToolId: String? = null
) {
    companion object {
        val DEFAULT = RetryPolicy()
        val NO_RETRY = RetryPolicy(maxRetries = 0)

        /**
         * Returns recommended fallback tool or strategy when a primary tool fails.
         */
        fun getFallback(toolName: String): String? {
            return when (toolName) {
                "send_whatsapp" -> "open_app"      // Fallback: Open WhatsApp directly
                "make_call" -> "open_dialer"       // Fallback: Prefill in dialer
                "send_sms" -> "open_sms_composer"  // Fallback: Prefill in messaging app
                "set_brightness" -> "open_system_settings" // Fallback: Open display settings
                "toggle_bluetooth" -> "open_system_settings" // Fallback: Open bluetooth settings
                else -> null
            }
        }
    }
}
