package com.aura.assistant.brain

enum class IntentType {
    DEVICE_CONTROL,
    COMMUNICATION,
    MEDIA_PLAYBACK,
    INFORMATION,
    ATTENTION_CHECK,
    CONVERSATIONAL
}

data class RoutedIntent(
    val type: IntentType,
    val recommendedTool: String? = null,
    val extractedEntities: Map<String, String> = emptyMap(),
    val confidence: Float = 1.0f
)

/**
 * Fast deterministic intent router for AURA 2.0.
 * Determines whether a turn is an immediate device action or general conversational dialogue.
 */
object IntentRouter {

    fun route(text: String): RoutedIntent {
        val clean = text.lowercase().trim()

        // 1. Attention Check
        if (clean.contains("sun rahi ho") || clean.contains("meri baat sun") || clean.contains("are you listening")) {
            return RoutedIntent(type = IntentType.ATTENTION_CHECK)
        }

        // 2. Communication
        if (clean.contains("call") || clean.contains("phone lagao") || clean.contains("dial")) {
            return RoutedIntent(
                type = IntentType.COMMUNICATION,
                recommendedTool = "make_call"
            )
        }
        if (clean.contains("whatsapp") || clean.contains("message bhej") || clean.contains("msg bhej")) {
            return RoutedIntent(
                type = IntentType.COMMUNICATION,
                recommendedTool = "send_whatsapp"
            )
        }

        // 3. Media
        if (clean.contains("gana bajao") || clean.contains("play song") || clean.contains("play music") || clean.contains("chalao")) {
            return RoutedIntent(
                type = IntentType.MEDIA_PLAYBACK,
                recommendedTool = "play_media"
            )
        }

        // 4. Device Controls
        val isNegative = clean.contains("kharab") || clean.contains("problem") || clean.contains("dikkat") || clean.contains("issue")
        val isTorchAction = !isNegative && (clean.contains("flashlight") || clean.contains("torch")) &&
                (clean.contains("on") || clean.contains("chalu") || clean.contains("jala") || clean.contains("chala") ||
                 clean.contains("band") || clean.contains("off") || clean.contains("bujha") || clean.contains("rok"))
        if (isTorchAction) {
            val isOff = clean.contains("off") || clean.contains("band") || clean.contains("bujha") || clean.contains("rok")
            return RoutedIntent(
                type = IntentType.DEVICE_CONTROL,
                recommendedTool = "toggle_flashlight",
                extractedEntities = mapOf("enable" to (!isOff).toString())
            )
        }
        if (clean.contains("volume") || clean.contains("awaaz")) {
            return RoutedIntent(
                type = IntentType.DEVICE_CONTROL,
                recommendedTool = "set_volume"
            )
        }
        if (clean.contains("bluetooth")) {
            return RoutedIntent(
                type = IntentType.DEVICE_CONTROL,
                recommendedTool = "toggle_bluetooth"
            )
        }

        return RoutedIntent(type = IntentType.CONVERSATIONAL)
    }
}
