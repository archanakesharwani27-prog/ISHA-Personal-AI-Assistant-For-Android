package com.aura.assistant.training

/**
 * ╔══════════════════════════════════════════════════════════════╗
 *  AuraCommandTrainer  v2.0
 *
 *  Generates a compact, structured COMMAND ROUTING GUIDE from
 *  AuraCommandCorpus and injects it into the Gemini system prompt.
 *
 *  This is the most powerful training mechanism:
 *  Instead of hoping the LLM remembers tool names, we give it a
 *  concrete phrasings→tool mapping reference on EVERY session.
 * ╚══════════════════════════════════════════════════════════════╝
 */
object AuraCommandTrainer {

    /**
     * Generates the complete command routing guide for system prompt injection.
     * Grouped by tool, with real example phrasings from the corpus.
     *
     * @param includeEdgeCases Whether to include edge-case warnings.
     * @param maxPhrasingsPerTool Cap to keep prompt compact.
     */
    fun generateRoutingGuide(
        includeEdgeCases: Boolean = true,
        maxPhrasingsPerTool: Int = 4
    ): String = buildString {

        appendLine()
        appendLine("══════════════════════════════════════════════════════════")
        appendLine("  AURA COMMAND ROUTING GUIDE  (from training corpus v4.0)")
        appendLine("══════════════════════════════════════════════════════════")
        appendLine("Use this table to route user commands to the EXACT tool.")
        appendLine("Match the closest phrasing → call the mapped tool.")
        appendLine()

        // Group by tool, skip meta-tokens
        val SKIP_TOOLS = setOf("CONVERSATIONAL", "CLARIFY", "GRACEFUL_DECLINE",
            "CONFIRMATIONAL", "COMPOUND")

        val toolGroups = AuraCommandCorpus.commands
            .filter { it.toolName !in SKIP_TOOLS }
            .groupBy { it.toolName }

        for ((toolName, cmds) in toolGroups.entries.sortedBy { it.key }) {
            val allPhrasings = cmds
                .flatMap { listOf(it.rawText) + it.variants }
                .distinct()
                .take(maxPhrasingsPerTool)

            val exampleParams = cmds.firstOrNull { it.params.isNotEmpty() }?.params
            val paramsStr = if (exampleParams.isNullOrEmpty()) "()"
            else "(" + exampleParams.entries.take(2).joinToString(", ") { "${it.key}=\"${it.value}\"" } + ")"

            appendLine("[$toolName]$paramsStr")
            allPhrasings.forEach { phrasing ->
                appendLine("  • $phrasing")
            }
        }

        appendLine()
        appendLine("══════════════════════════════════════════════════════════")
        appendLine("  SPECIAL ROUTING RULES")
        appendLine("══════════════════════════════════════════════════════════")
        appendLine(generateSpecialRules(includeEdgeCases))
    }

    /**
     * Generates concise special routing rules that cover the most common
     * mistake patterns and ambiguities from edge cases.
     */
    fun generateSpecialRules(includeEdgeCases: Boolean = true): String = buildString {

        appendLine()
        appendLine("┌─ ANTI-HALLUCINATION RULES (MANDATORY) ─────────────────┐")
        appendLine("│ ❌ NEVER say 'done' without ACTUALLY calling the tool   │")
        appendLine("│ ❌ NEVER guess battery %. ALWAYS call get_battery_status │")
        appendLine("│ ❌ NEVER hallucinate a phone number. If none → ask user  │")
        appendLine("│ ❌ NEVER say 'bhej diya' without calling send_* tool     │")
        appendLine("│ ✅ If tool returned success → THEN confirm action done   │")
        appendLine("└──────────────────────────────────────────────────────────┘")
        appendLine()

        appendLine("┌─ DISAMBIGUATION RULES ──────────────────────────────────┐")
        appendLine("│ 'Band karo' / 'Chalu karo' alone → ALWAYS ask FIRST     │")
        appendLine("│ 'Camera kholo' → open_camera(photo) NOT open_app(Camera) │")
        appendLine("│ 'WhatsApp kholo' only → open_app. WITH message→send_wapp │")
        appendLine("│ 'Maps kholo' only → open_app. WITH destination→navigate  │")
        appendLine("│ 'Volume band karo' = level_percent=0, NOT set_dnd        │")
        appendLine("│ 'Silent mode' = set_dnd(true), NOT volume=0              │")
        appendLine("│ 'Calculator' math in speech → calculate. App → open_app  │")
        appendLine("└──────────────────────────────────────────────────────────┘")
        appendLine()

        appendLine("┌─ TIME CONVERSION RULES ─────────────────────────────────┐")
        appendLine("│ Subah = AM    Dopahar/Sham = PM    Raat = PM (20-23h)   │")
        appendLine("│ 'X baje' subah: hour=X,min=0       Raat 11=23:00        │")
        appendLine("│ 'Half past X' = X:30               'Quarter to X' = X-1:45 │")
        appendLine("└──────────────────────────────────────────────────────────┘")
        appendLine("┌─ STORAGE & HEALTH ROUTING RULES ─────────────────────────┐")
        appendLine("│ 'Phone storage kitna hai' → get_storage_space (GB/Disk) │")
        appendLine("│ 'Photos/videos kitne hain' → get_media_storage_stats     │")
        appendLine("│ 'Device health check karo' → check_device_health (Full)  │")
        appendLine("│ 'Internet speed btao' → check_internet_speed (Mbps+Ping) │")
        appendLine("│ 'Chrome pe search karo X' → open_app(chrome, query=X)    │")
        appendLine("│ 'Screen pe tap karo / kro' → find_and_tap (NEVER talk!) │")
        appendLine("└──────────────────────────────────────────────────────────┘")
        appendLine()

        appendLine("┌─ EMERGENCY RULES (EXECUTE IMMEDIATELY, NO CONFIRMATION) ┐")
        appendLine("│ Ambulance / 108 → emergency_sos(108) NOW                │")
        appendLine("│ Police / 100    → emergency_sos(100) NOW                │")
        appendLine("│ Fire Brigade/101→ emergency_sos(101) NOW                │")
        appendLine("│ SOS/Help/Bachao  → emergency_sos(112) NOW               │")
        appendLine("└──────────────────────────────────────────────────────────┘")
        appendLine()

        appendLine("┌─ COMPOUND COMMAND RULES ────────────────────────────────┐")
        appendLine("│ 'X karo aur Y karo' → call BOTH tools SEQUENTIALLY      │")
        appendLine("│ Report success only after ALL steps complete             │")
        appendLine("│ If any step fails → report which step failed             │")
        appendLine("└──────────────────────────────────────────────────────────┘")
        appendLine()

        appendLine("┌─ GRAMMAR RULES (MANDATORY FOR HINDI RESPONSES) ─────────┐")
        appendLine("│ ✅ CORRECT: 'kar diya', 'kar rahi hoon', 'karti hoon'    │")
        appendLine("│ ❌ WRONG:   'kar diya', 'kar raha hoon', 'karta hoon'    │")
        appendLine("│ AURA uses FEMININE grammar at ALL times                  │")
        appendLine("└──────────────────────────────────────────────────────────┘")

        if (includeEdgeCases) {
            appendLine()
            appendLine("┌─ SAFETY RULES ──────────────────────────────────────────┐")
            appendLine("│ 'Data clear karo' = WARN user (logs out app) THEN exec  │")
            appendLine("│ 'Delete karo' = CONFIRM once before delete_file         │")
            appendLine("│ 'Uninstall karo' = CONFIRM before manage_app_storage    │")
            appendLine("│ 'Factory reset' = TRIPLE confirm, NEVER execute blindly │")
            appendLine("└──────────────────────────────────────────────────────────┘")
        }
    }

    /**
     * Ultra-compact routing reference for voice mode (very tight token budget).
     */
    fun generateVoiceModeCompactGuide(): String = buildString {
        appendLine("\n📋 TOOL ROUTING QUICK-REF:")
        val quickRef = mapOf(
            "make_call"            to "Call/phone/dial [name/number]",
            "send_sms"             to "SMS/text/message [name] = [text]",
            "send_whatsapp"        to "WhatsApp/WA [name] = [text]",
            "send_whatsapp_media"  to "Photo/screenshot WA [name]",
            "take_screenshot"      to "Screenshot/screen capture",
            "open_app"             to "Open/launch/kholo [app]",
            "open_camera"          to "Camera/photo/selfie/video",
            "toggle_flashlight"    to "Torch/flashlight on/off",
            "set_volume"           to "Volume [0-100]% / awaaz",
            "set_brightness"       to "Brightness [0-100]%",
            "toggle_wifi"          to "WiFi on/off",
            "toggle_bluetooth"     to "Bluetooth/BT on/off",
            "toggle_hotspot"       to "Hotspot/tethering on/off",
            "toggle_dark_mode"     to "Dark mode on/off",
            "set_dnd"              to "DND/Silent/Do Not Disturb",
            "get_battery_status"   to "Battery/charging status",
            "create_alarm"         to "Alarm [time]",
            "set_timer"            to "Timer [duration]",
            "navigate_maps"        to "Navigate/rasta [place]",
            "web_search"           to "Google/search [query]",
            "play_youtube"         to "YouTube [song/video]",
            "play_media"           to "[App] pe [song] chalao",
            "media_control"        to "Play/pause/next/prev music",
            "calculate"            to "Calculate/kitna [math]",
            "remember_fact"        to "Yaad rakhna [key=value]",
            "recall_memory"        to "Yaad hai kya [key]",
            "create_quick_note"    to "Note karo [text]",
            "add_calendar_event"   to "Calendar [event] [date]",
            "send_email"           to "Email [to] [subject]",
            "read_recent_sms"      to "SMS/OTP padho",
            "read_notifications"   to "Notifications padho",
            "query_contact"        to "[name] ka number",
            "manage_contacts"      to "Contact save/update/delete",
            "emergency_sos"        to "Ambulance/Police/SOS!",
            "manage_app_storage"   to "Cache/data clear [app]",
            "install_store_app"    to "Install [app]",
            "perform_app_action"   to "[App] mein [action]",
            "press_system_key"     to "Lock/home/back/recents",
            "screen_tap"           to "Tap/click [target]",
            "screen_scroll"        to "Scroll up/down",
            "show_recent_media"    to "Last/recent photo/video",
            "share_media"          to "Share [media]",
            "empty_recycle_bin"    to "Recycle bin clear",
            "search_files"         to "File dhundho [query]",
            "delete_file"          to "File delete [confirm!]",
            "accept_call"          to "Call uthao/receive",
            "decline_call"         to "Call reject/kaatna",
            "identify_caller"      to "Kaun call kar raha?",
            "get_clipboard_text"   to "Clipboard mein kya?",
            "open_system_settings" to "Settings [section]",
            "set_navigation_mode"  to "Gesture/button navigation",
            "get_storage_space"    to "Phone storage/free GB/disk space [NEVER photos/videos!]",
            "check_device_health"  to "Device health/RAM/storage/temp/uptime",
            "check_internet_speed" to "Internet speed/Mbps/ping/latency",
            "clear_notifications"  to "Clear/dismiss all notifications",
            "toggle_message_announcements" to "Message read on/off [enable=true/false]",
            "get_current_time"     to "Current live time/ghadi/kitne baje/date [NEVER prompt start time!]",

            "search_internet"      to "Live fast web search (4s timeout) [query]",
        )
        for ((tool, desc) in quickRef) {
            appendLine("  $tool → $desc")
        }
    }

    /**
     * Returns training stats for logging/debugging.
     */
    fun stats(): String = buildString {
        val corpus = AuraCommandCorpus
        appendLine("AuraCommandTrainer v2.0:")
        appendLine(corpus.stats())
        val toolCoverage = corpus.commands
            .filter { it.toolName !in setOf("CONVERSATIONAL","CLARIFY","GRACEFUL_DECLINE","CONFIRMATIONAL","COMPOUND") }
            .groupBy { it.toolName }
        val totalRealTools = 52
        appendLine("  Tool coverage    : ${toolCoverage.size}/$totalRealTools (${toolCoverage.size * 100 / totalRealTools}%)")
        appendLine("  Tools with 3+ cmds: ${toolCoverage.count { it.value.size >= 3 }}")
    }
}
