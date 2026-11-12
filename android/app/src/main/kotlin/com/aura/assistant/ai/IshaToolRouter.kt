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
        "unlock_device",
        "open_system_settings",
        "press_system_key"
    )

    val COMMS_TOOLS = setOf(
        "make_call",
        "accept_call",
        "decline_call",
        "identify_caller",
        "send_sms",
        "read_recent_sms",
        "read_notifications",
        "clear_notifications",
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
        "share_media",
        "unlock_device",
        "press_system_key"
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
        "jarvis_status_briefing",
        "get_battery_status",
        "get_storage_space",
        "check_device_health",
        "check_internet_speed",
        "read_notifications",
        "clear_notifications",
        "search_files",
        "delete_file",
        "empty_recycle_bin",
        "check_app_update"
    )

    val APPS_NAV_TOOLS = setOf(
        "open_app",
        "open_camera",
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
        "open_local_file",
        "unlock_device",
        "lock_device",
        "open_system_settings",
        "screen_tap",
        // JARVIS Duplex Audio Core Additions
        "jarvis_status_briefing",
        "take_screenshot",
        "get_screen_context",
        "find_and_tap",
        "screen_scroll",
        "press_system_key",
        "media_control",
        "set_dnd",
        "open_camera",
        "read_notifications",
        "check_device_health",
        "clear_notifications",
        "read_recent_sms"
    )

    /**
     * Checks if a tool is supported in Gemini Live Duplex Audio session.
     */
    fun isToolSupportedInLiveVoice(toolName: String): Boolean {
        return toolName in LIVE_VOICE_CORE_TOOLS
    }

    // ── 3. Regex Patterns for Instant Intent Classification (<1ms) ───────────────

    private val CHAINING_PATTERN = Pattern.compile(
        "\\b(aur|then|uske baad|phir|fir|also|dono|teeno|and|pehle.*phir|pehle.*fir)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val MEDIA_PATTERN = Pattern.compile(
        "\\b(song|gana|gaana|music|geet|play|baja|bajao|chala|chalao|sunao|youtube|spotify|video|volume badhao|volume kam|pause|resume|next song)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val HARDWARE_PATTERN = Pattern.compile(
        "\\b(torch|flashlight|wifi|wi-fi|bluetooth|hotspot|dark mode|dnd|silent|vibrate|volume|brightness|lock|unlock|screen on|screen jalao|lock kholo|pin daalo|pin dalo|pattern daalo|pattern dalo|switch on|switch off|chalu|band kar|on kar|off kar)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val UNLOCK_PATTERN = Pattern.compile(
        "\\b(unlock|screen on|screen jalao|lock kholo|kholo lock|lock open|pin daalo|pin dalo|pattern daalo|pattern dalo|enter pin|enter pattern|screen wake|wake screen|wake up|screen jagao)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val COMMS_PATTERN = Pattern.compile(
        "\\b(call|dial|phone|whatsapp|whatsap|message|msg|sms|recent sms|bhejo|bhej|send|share|text|chat|kaat do|uthalo|contact|number|kiska|caller|spam|unknown|identify|pehechan|pahchan|reply|jawab|baat|baatein|bolo|likho|kaho|kah do|unhe|usse|continuous|continue|continues|padho|padh|check|dekho|sunao|kya bola|kya likha|kya aaya|kya bheja|pucho|poocho|notification|notifications|clear notification|clear notifications|hata do|saaf kardo)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val TIME_PATTERN = Pattern.compile(
        "\\b(alarm|timer|time|samay|kitne baje|remind|reminder|note|calculator|hisaab|calculate)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val DIAGNOSTICS_PATTERN = Pattern.compile(
        "\\b(battery|percentage|charge|charging|storage|space|ram|clean|recycle|files|internet speed|update|version|naya update|status report|briefing|hud|jarvis|diagnostics|device health|system report|phone status)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val APPS_NAV_PATTERN = Pattern.compile(
        "\\b(open|kholo|launch|map|maps|rasta|location|kahan hai|direction|camera|photo khicho|selfie)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val WEB_PATTERN = Pattern.compile(
        "\\b(search|google|dhoondo|pata karo|latest news|khabar|today news|weather|mausam|mosam|forecast|barish|rain|temperature|hawa)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val SCREEN_INTERACTIVE_PATTERN = Pattern.compile(
        "\\b(tap|tap on|click|click kar|touch|scroll|scroll down|scroll up|swipe|screenshot|screen shot|screen capture|button dabao|dabao|daba do|back jao|go back|home screen|home jao|recent apps|recents|notification bar|notifications|piche jao)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val CODE_AND_FILE_PATTERN = Pattern.compile(
        "\\b(html|file|save|write.*file|code|script|banao.*file|chrome.*open|calculator.*html|run.*code|execute|synthesize|self heal|macro|widget|calculator banao|game banao)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val FOLLOW_UP_PATTERN = Pattern.compile(
        "\\b(kya kya|kya hai|kya bacha|aur kya|aur batao|aur dikhao|sab dikhao|list dikhao|kitna hai|kitne hai|kaun kaun|kaunsa|kaun sa|isse|isse bhi|isko|unko|inhe|inko|use|ise|iska|uska|unka|unse|wahi|wohi|phir|fir|bhi|delete karo|hatao|band karo|cancel karo|remove karo|clear karo|check karo|dekh kar|padho|batao|konsa|kon sa|kisko|kise)\\b",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Determines the optimal, minimal set of tool declarations for a given prompt.
     *
     * @param prompt User prompt text.
     * @param hasImage Whether an image or screen capture is directly attached.
     * @param hasPendingToolTurn Whether the previous conversation turn was a tool execution.
     * @param recentTools Tools executed in recent turns, used for anaphoric/follow-up routing.
     * @param recentUserPrompts Recent user prompts, used to maintain topic continuity.
     * @return Minimal tool declarations JSON array, or NULL if zero tools needed.
     */
    fun routeToolsForPrompt(
        prompt: String,
        hasImage: Boolean = false,
        hasPendingToolTurn: Boolean = false,
        recentTools: List<String> = emptyList(),
        recentUserPrompts: List<String> = emptyList()
    ): JsonArray? {
        val clean = prompt.trim()

        // 1. Direct Multimodal Screen / Camera Perception:
        // When an image is already attached (from camera or screenshot), Gemini's native vision
        // answers directly in 0.5s when NO tool schema is attached, UNLESS an explicit action/comm intent is present!
        if (hasImage && !SCREEN_INTERACTIVE_PATTERN.matcher(clean).find() && !COMMS_PATTERN.matcher(clean).find()) {
            return null // 0 tools -> Instant single-turn multimodal vision!
        }

        // 2. Domain Intent Matching
        val selectedNames = mutableSetOf<String>()

        if (MEDIA_PATTERN.matcher(clean).find()) {
            selectedNames.addAll(MEDIA_TOOLS)
        }
        if (HARDWARE_PATTERN.matcher(clean).find() || UNLOCK_PATTERN.matcher(clean).find()) {
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

        // 3. Contextual Continuity & Follow-Up Resolution (Anaphora / Ellipsis):
        // When current prompt is anaphoric/follow-up ("kya kya hai", "aur kya", "isko band karo") OR previous turn had tools:
        val isFollowUp = FOLLOW_UP_PATTERN.matcher(clean).find()

        if (selectedNames.isEmpty() && (hasPendingToolTurn || isFollowUp)) {
            // A. Inherit tools from recent executed tools
            for (t in recentTools) {
                when {
                    MEDIA_TOOLS.contains(t) -> selectedNames.addAll(MEDIA_TOOLS)
                    HARDWARE_TOOLS.contains(t) -> selectedNames.addAll(HARDWARE_TOOLS)
                    COMMS_TOOLS.contains(t) -> selectedNames.addAll(COMMS_TOOLS)
                    TIME_PRODUCTIVITY_TOOLS.contains(t) -> selectedNames.addAll(TIME_PRODUCTIVITY_TOOLS)
                    SYSTEM_DIAGNOSTICS_TOOLS.contains(t) -> selectedNames.addAll(SYSTEM_DIAGNOSTICS_TOOLS)
                    APPS_NAV_TOOLS.contains(t) -> selectedNames.addAll(APPS_NAV_TOOLS)
                    WEB_TOOLS.contains(t) -> selectedNames.addAll(WEB_TOOLS)
                    SCREEN_INTERACTIVE_TOOLS.contains(t) -> selectedNames.addAll(SCREEN_INTERACTIVE_TOOLS)
                    CODE_AND_FILE_TOOLS.contains(t) -> selectedNames.addAll(CODE_AND_FILE_TOOLS)
                }
            }

            // B. Check recent user prompts for domain context if recentTools didn't capture it
            if (selectedNames.isEmpty()) {
                for (prevPrompt in recentUserPrompts.reversed()) {
                    val prevClean = prevPrompt.trim()
                    if (TIME_PATTERN.matcher(prevClean).find()) selectedNames.addAll(TIME_PRODUCTIVITY_TOOLS)
                    if (HARDWARE_PATTERN.matcher(prevClean).find()) selectedNames.addAll(HARDWARE_TOOLS)
                    if (COMMS_PATTERN.matcher(prevClean).find()) selectedNames.addAll(COMMS_TOOLS)
                    if (MEDIA_PATTERN.matcher(prevClean).find()) selectedNames.addAll(MEDIA_TOOLS)
                    if (DIAGNOSTICS_PATTERN.matcher(prevClean).find()) selectedNames.addAll(SYSTEM_DIAGNOSTICS_TOOLS)
                    if (APPS_NAV_PATTERN.matcher(prevClean).find()) selectedNames.addAll(APPS_NAV_TOOLS)
                    if (WEB_PATTERN.matcher(prevClean).find()) selectedNames.addAll(WEB_TOOLS)
                    if (selectedNames.isNotEmpty()) break
                }
            }

            // C. If still empty but it's an explicit follow-up question, attach core essential bundle
            if (selectedNames.isEmpty() && isFollowUp) {
                selectedNames.addAll(TIME_PRODUCTIVITY_TOOLS)
                selectedNames.addAll(HARDWARE_TOOLS)
                selectedNames.addAll(COMMS_TOOLS)
                selectedNames.addAll(SYSTEM_DIAGNOSTICS_TOOLS)
            }
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
            UNLOCK_PATTERN.matcher(clean).find(),
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
