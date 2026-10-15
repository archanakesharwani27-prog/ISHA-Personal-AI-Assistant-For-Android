package com.aura.assistant.ai

import com.google.gson.JsonArray
import java.util.regex.Pattern

/**
 * ⚡ Domain-Based Intent Tool Router for ISHA.
 *
 * Drastically cuts TTFT (Time-to-First-Token) and payload size:
 * - Pure conversation ("Hello", "Kaise ho", "Explain X", jokes, poetry) ➔ Returns NULL (0 Tools, <1KB payload).
 * - Direct Screen Perception (when image or screen text is attached) ➔ Returns NULL (0 Tools, 0.5s native Gemini vision).
 * - Specific Action Intents (Media, Hardware, Social, Calls) ➔ Returns ONLY that domain's 2-5 tools.
 * - Multi-Step Chained Intents ("aur", "phir", "then", "uske baad") ➔ Returns full fallback tools.
 */
object IshaToolRouter {

    // ── 1. Domain Tool Bundles ───────────────────────────────────────────────────

    val MEDIA_TOOLS = setOf(
        "play_youtube",
        "play_media",
        "media_control",
        "youtube_interact",
        "spotify_control"
    )

    val HARDWARE_TOOLS = setOf(
        "toggle_flashlight",
        "set_volume",
        "set_brightness",
        "toggle_wifi",
        "toggle_bluetooth",
        "toggle_hotspot",
        "toggle_dark_mode",
        "set_dnd",
        "lock_device",
        "open_system_settings"
    )

    val COMMS_TOOLS = setOf(
        "make_call",
        "accept_call",
        "decline_call",
        "identify_caller",
        "send_sms",
        "send_whatsapp",
        "send_whatsapp_media",
        "type_message",
        "type_text",
        "chat_on_whatsapp",
        "start_continuous_chat",
        "stop_continuous_chat",
        "share_media",
        "query_contact",
        "get_contacts_list",
        "remember_contact",
        "read_screen_text"
    )

    val SCREEN_INTERACTIVE_TOOLS = setOf(
        "read_screen_text",
        "get_screen_context",
        "screen_tap",
        "find_and_tap",
        "screen_scroll",
        "take_screenshot",
        "share_media"
    )

    val TIME_PRODUCTIVITY_TOOLS = setOf(
        "create_alarm",
        "set_timer",
        "get_current_time",
        "add_calendar_event",
        "create_quick_note",
        "calculate"
    )

    val SYSTEM_DIAGNOSTICS_TOOLS = setOf(
        "get_battery_status",
        "get_storage_space",
        "check_device_health",
        "check_internet_speed",
        "search_files",
        "delete_file",
        "empty_recycle_bin",
        "check_app_update"
    )

    val APPS_NAV_TOOLS = setOf(
        "open_app",
        "navigate_maps",
        "google_maps",
        "get_location"
    )

    val WEB_TOOLS = setOf(
        "search_internet",
        "get_latest_news",
        "browser_control"
    )

    val CODE_AND_FILE_TOOLS = setOf(
        "write_local_file",
        "open_local_file",
        "execute_code",
        "synthesize_action"
    )

    // ── 2. Live Voice Core Tools (Lean Setup for Gemini Live WebSocket) ──────────
    val LIVE_VOICE_CORE_TOOLS = setOf(
        "open_app",
        "make_call",
        "accept_call",
        "decline_call",
        "identify_caller",
        "send_whatsapp",
        "chat_on_whatsapp",
        "type_message",
        "stop_continuous_chat",
        "toggle_flashlight",
        "set_volume",
        "set_brightness",
        "toggle_wifi",
        "toggle_bluetooth",
        "toggle_hotspot",
        "play_media",
        "play_youtube",
        "create_alarm",
        "set_timer",
        "get_current_time",
        "get_battery_status",
        "search_internet",
        "read_screen_text",
        "write_local_file",
        "open_local_file"
    )

    // ── 3. Regex Patterns for Instant Intent Classification (<1ms) ───────────────

    private val CHAINING_PATTERN = Pattern.compile(
        "\\b(aur|then|uske baad|phir|also|dono|teeno|and|pehle.*phir)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val MEDIA_PATTERN = Pattern.compile(
        "\\b(song|gana|gaana|music|geet|play|baja|bajao|chala|chalao|sunao|youtube|spotify|video|volume badhao|volume kam|pause|resume|next song)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val HARDWARE_PATTERN = Pattern.compile(
        "\\b(torch|flashlight|wifi|wi-fi|bluetooth|hotspot|dark mode|dnd|silent|vibrate|volume|brightness|lock|switch on|switch off|chalu|band kar|on kar|off kar)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val COMMS_PATTERN = Pattern.compile(
        "\\b(call|dial|phone|whatsapp|whatsap|message|msg|sms|bhejo|bhej|send|share|text|chat|kaat do|uthalo|contact|number|kiska|caller|spam|unknown|identify|pehechan|pahchan|reply|jawab|baat|baatein|bolo|likho|kaho|kah do|unhe|usse|continuous|continue|continues|padho|padh|check|dekho|sunao|kya bola|kya likha|kya aaya|kya bheja|pucho|poocho)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val TIME_PATTERN = Pattern.compile(
        "\\b(alarm|timer|time|samay|kitne baje|remind|reminder|note|calculator|hisaab|calculate)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val DIAGNOSTICS_PATTERN = Pattern.compile(
        "\\b(battery|percentage|charge|charging|storage|space|ram|clean|recycle|files|internet speed|update|version|naya update)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val APPS_NAV_PATTERN = Pattern.compile(
        "\\b(open|kholo|launch|map|maps|rasta|location|kahan hai|direction)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val WEB_PATTERN = Pattern.compile(
        "\\b(search|google|dhoondo|pata karo|latest news|khabar|today news|weather|mausam|mosam|forecast|barish|rain|temperature|hawa)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val SCREEN_INTERACTIVE_PATTERN = Pattern.compile(
        "\\b(tap on|click kar|scroll down|scroll up|screenshot|screen shot|screen capture|button dabao)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val CODE_AND_FILE_PATTERN = Pattern.compile(
        "\\b(html|file|save|write.*file|code|script|banao.*file|chrome.*open|calculator.*html|run.*code|execute|synthesize|self heal|macro|widget|calculator banao|game banao)\\b",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Determines the optimal, minimal set of tool declarations for a given prompt.
     *
     * @param prompt User prompt text.
     * @param hasImage Whether an image or screen capture is directly attached.
     * @param hasPendingToolTurn Whether the previous conversation turn was a tool execution.
     * @return Minimal tool declarations JSON array, or NULL if zero tools needed.
     */
    fun routeToolsForPrompt(
        prompt: String,
        hasImage: Boolean = false,
        hasPendingToolTurn: Boolean = false
    ): JsonArray? {
        val clean = prompt.trim()

        // 1. If previous turn was a tool execution, provide full tool set so chained steps can finish
        if (hasPendingToolTurn) {
            return IshaToolRegistry.getGeminiToolDeclarations()
        }

        // 2. Direct Multimodal Screen / Camera Perception:
        // When an image is already attached (from camera or screenshot), Gemini's native vision
        // answers directly in 0.5s when NO tool schema is attached, UNLESS an explicit action/comm intent is present!
        if (hasImage && !SCREEN_INTERACTIVE_PATTERN.matcher(clean).find() && !COMMS_PATTERN.matcher(clean).find()) {
            return null // 0 tools -> Instant single-turn multimodal vision!
        }

        // 3. Domain Intent Matching
        val selectedNames = mutableSetOf<String>()

        if (MEDIA_PATTERN.matcher(clean).find()) {
            selectedNames.addAll(MEDIA_TOOLS)
        }
        if (HARDWARE_PATTERN.matcher(clean).find()) {
            selectedNames.addAll(HARDWARE_TOOLS)
        }
        val isWhatsappOpen = try {
            com.aura.assistant.IshaAccessibilityService.instance?.isWhatsappChatOpen() == true
        } catch (_: Exception) { false }
        if (COMMS_PATTERN.matcher(clean).find() || isWhatsappOpen) {
            selectedNames.addAll(COMMS_TOOLS)
        }
        if (TIME_PATTERN.matcher(clean).find()) {
            selectedNames.addAll(TIME_PRODUCTIVITY_TOOLS)
        }
        if (DIAGNOSTICS_PATTERN.matcher(clean).find()) {
            selectedNames.addAll(SYSTEM_DIAGNOSTICS_TOOLS)
        }
        if (APPS_NAV_PATTERN.matcher(clean).find()) {
            selectedNames.addAll(APPS_NAV_TOOLS)
        }
        if (WEB_PATTERN.matcher(clean).find()) {
            selectedNames.addAll(WEB_TOOLS)
        }
        if (SCREEN_INTERACTIVE_PATTERN.matcher(clean).find()) {
            selectedNames.addAll(SCREEN_INTERACTIVE_TOOLS)
        }
        if (CODE_AND_FILE_PATTERN.matcher(clean).find()) {
            selectedNames.addAll(CODE_AND_FILE_TOOLS)
        }

        // 4. Pure Conversation / Greetings / Logic / QA:
        // No action intent matched -> Return NULL (0 Tools)!
        // This drops the payload from 165KB to <2KB and response time from 3s to <1s!
        // Prevents conversational Hindi sentences with 'aur' (and) from bloating with 80+ tools.
        if (selectedNames.isEmpty()) {
            return null
        }

        // 5. Multi-Step Chained Intent or Multi-Domain Intent:
        // If an action intent was matched AND prompt has chaining keywords ("aur", "phir", "then")
        // or spans multiple domains, provide full tool set for Gemini chaining.
        val domainMatches = listOf(
            MEDIA_PATTERN.matcher(clean).find(),
            HARDWARE_PATTERN.matcher(clean).find(),
            COMMS_PATTERN.matcher(clean).find(),
            TIME_PATTERN.matcher(clean).find(),
            DIAGNOSTICS_PATTERN.matcher(clean).find(),
            APPS_NAV_PATTERN.matcher(clean).find(),
            WEB_PATTERN.matcher(clean).find(),
            SCREEN_INTERACTIVE_PATTERN.matcher(clean).find(),
            CODE_AND_FILE_PATTERN.matcher(clean).find()
        ).count { it }

        if (CHAINING_PATTERN.matcher(clean).find() || domainMatches > 2 || (clean.contains(",") && selectedNames.size > 2)) {
            return IshaToolRegistry.getGeminiToolDeclarations()
        }

        // 6. Return only the pruned declarations for the detected domain
        return IshaToolRegistry.getSpecificToolDeclarations(selectedNames)
    }
}
