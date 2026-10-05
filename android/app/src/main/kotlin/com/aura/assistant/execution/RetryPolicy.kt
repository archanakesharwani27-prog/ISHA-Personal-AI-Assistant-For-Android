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
        val NETWORK_POLICY = RetryPolicy(maxRetries = 3, initialBackoffMs = 500L, backoffMultiplier = 2.0)

        /**
         * Returns recommended fallback tool or strategy when a primary tool fails.
         */
        fun getFallback(toolName: String): String? {
            return when (toolName) {
                "send_whatsapp" -> "open_app"               // Fallback: Open WhatsApp directly
                "make_call" -> "open_dialer"                // Fallback: Prefill in dialer
                "send_sms" -> "open_sms_composer"           // Fallback: Prefill in messaging app
                "set_brightness" -> "open_system_settings"  // Fallback: Open display settings
                "toggle_bluetooth" -> "open_system_settings" // Fallback: Open bluetooth settings
                "toggle_hotspot" -> "open_system_settings"  // Fallback: Open tethering settings
                "toggle_wifi" -> "open_system_settings"     // Fallback: Open network settings
                "play_siren" -> "play_youtube"              // Fallback: Play siren audio via YouTube
                "unlock_device" -> "screen_tap"             // Fallback: Manual physical touch coordinates
                "navigate_maps" -> "open_app"               // Fallback: Open Google Maps manually
                "take_screenshot" -> "autonomous_ui_action" // Fallback: Use accessibility global action
                else -> null
            }
        }

        /**
         * Returns tool-specific timeout in milliseconds.
         */
        fun getToolTimeout(toolName: String): Long {
            return when (toolName) {
                "chat_on_whatsapp", "send_whatsapp", "send_whatsapp_media" -> 15_000L
                "install_store_app", "autonomous_ui_action", "cross_app_workflow" -> 20_000L
                "web_search", "search_internet", "get_weather", "get_latest_news" -> 10_000L
                "take_screenshot", "screen_tap", "find_and_tap" -> 5_000L
                "toggle_flashlight", "set_volume", "set_brightness", "get_battery_status" -> 1_500L
                else -> 6_000L
            }
        }

        /**
         * Returns appropriate policy based on network dependency of the tool.
         */
        fun getPolicyForTool(toolName: String): RetryPolicy {
            return when (toolName) {
                "web_search", "search_internet", "get_weather", "get_latest_news",
                "send_whatsapp", "send_whatsapp_media", "dispatch_remote_command" -> NETWORK_POLICY
                else -> DEFAULT
            }
        }
    }
}
