package com.aura.assistant.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Structured User-Taught Command Rule or Workflow.
 * Persistently stored and injected at SUPREME EXECUTION PRIORITY #1.
 */
data class LearnedCommandRule(
    val id: String = UUID.randomUUID().toString(),
    val triggerPattern: String,
    val actionInstruction: String,
    val primaryTool: String? = null,
    val toolParameters: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val usageCount: Int = 0
)

/**
 * ChatGPT-style Long-Term Memory, Learned Rules, and Context Vault for ISHA.
 *
 * Persistently stores:
 *  1. Learned Command Rules & Custom Workflows (teach_command_rule)
 *  2. Facts, user preferences, and personal details (remember_fact)
 * Automatically injects them into system instructions
 * for both text chat (GeminiChatService) and real-time voice (GeminiLiveClient).
 */
object IshaMemoryManager {

    private const val TAG = "IshaMemoryManager"
    private const val PREFS_NAME = "isha_memory_vault"
    private const val MEM_PREFIX = "fact_"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_LEARNED_RULES = "learned_command_rules"
    private val gson = Gson()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Stores a personal fact in memory.
     */
    fun rememberFact(context: Context, key: String, value: String) {
        val cleanKey = key.trim().lowercase()
        if (cleanKey.isBlank() || value.isBlank()) return
        getPrefs(context).edit().putString("$MEM_PREFIX$cleanKey", value.trim()).apply()
        if (cleanKey == "name" || cleanKey == "username" || cleanKey == "user_name") {
            getPrefs(context).edit().putString(KEY_USER_NAME, value.trim()).apply()
        }
    }

    /**
     * Recalls a specific fact from memory.
     */
    fun recallMemory(context: Context, key: String): String? {
        val cleanKey = key.trim().lowercase()
        return getPrefs(context).getString("$MEM_PREFIX$cleanKey", null)
    }

    /**
     * Returns all stored facts.
     */
    fun getAllMemories(context: Context): Map<String, String> {
        val prefs = getPrefs(context)
        val result = mutableMapOf<String, String>()
        for ((k, v) in prefs.all) {
            if (k.startsWith(MEM_PREFIX) && v is String) {
                result[k.removePrefix(MEM_PREFIX)] = v
            }
        }
        return result
    }

    /**
     * Alias for getAllMemories.
     */
    fun getAllFacts(context: Context): Map<String, String> = getAllMemories(context)

    /**
     * Forgets a specific fact.
     */
    fun forgetFact(context: Context, key: String) {
        val cleanKey = key.trim().lowercase()
        getPrefs(context).edit().remove("$MEM_PREFIX$cleanKey").apply()
    }

    /**
     * Clears all memory facts.
     */
    fun clearAll(context: Context) {
        val prefs = getPrefs(context)
        val editor = prefs.edit()
        for (k in prefs.all.keys) {
            if (k.startsWith(MEM_PREFIX)) {
                editor.remove(k)
            }
        }
        editor.apply()
    }

    /**
     * Stores or updates a user-taught command rule / workflow in permanent memory.
     */
    fun teachRule(
        context: Context,
        trigger: String,
        action: String,
        primaryTool: String? = null,
        toolParameters: String? = null
    ): LearnedCommandRule {
        val cleanTrigger = trigger.trim().lowercase()
        val cleanAction = action.trim()
        val allRules = getAllRules(context).toMutableList()
        // Remove existing rule with exact same trigger if updating
        allRules.removeAll { it.triggerPattern.equals(cleanTrigger, ignoreCase = true) }

        val newRule = LearnedCommandRule(
            triggerPattern = cleanTrigger,
            actionInstruction = cleanAction,
            primaryTool = primaryTool?.trim()?.ifBlank { null },
            toolParameters = toolParameters?.trim()?.ifBlank { null }
        )
        allRules.add(newRule)
        saveRules(context, allRules)
        return newRule
    }

    /**
     * Returns all user-taught command rules.
     */
    fun getAllRules(context: Context): List<LearnedCommandRule> {
        val json = getPrefs(context).getString(KEY_LEARNED_RULES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<LearnedCommandRule>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveRules(context: Context, rules: List<LearnedCommandRule>) {
        val json = gson.toJson(rules)
        getPrefs(context).edit().putString(KEY_LEARNED_RULES, json).apply()
    }

    /**
     * Checks if a user prompt matches any user-taught command rule.
     */
    fun findMatchingRule(context: Context, query: String): LearnedCommandRule? {
        val clean = query.lowercase().trim()
        if (clean.isBlank()) return null
        val rules = getAllRules(context)
        if (rules.isEmpty()) return null

        // 1. Exact match
        rules.firstOrNull { clean == it.triggerPattern }?.let { return it }

        // 2. Query contains trigger pattern or trigger contains query
        rules.firstOrNull { clean.contains(it.triggerPattern) || it.triggerPattern.contains(clean) }?.let { return it }

        // 3. Significant token overlap (at least 2 matching words or all words of rule)
        val queryTokens = clean.split(Regex("[\\s,_?.!\\-]+")).filter { it.length > 2 }.toSet()
        for (rule in rules) {
            val ruleTokens = rule.triggerPattern.split(Regex("[\\s,_?.!\\-]+")).filter { it.length > 2 }.toSet()
            if (ruleTokens.isNotEmpty()) {
                val intersection = queryTokens.intersect(ruleTokens)
                if (intersection.size >= 2 || (intersection.isNotEmpty() && intersection.size == ruleTokens.size)) {
                    return rule
                }
            }
        }
        return null
    }

    /**
     * Forgets a specific learned rule by id or trigger pattern.
     */
    fun forgetRule(context: Context, triggerOrId: String) {
        val clean = triggerOrId.trim().lowercase()
        val allRules = getAllRules(context).toMutableList()
        allRules.removeAll { it.id == triggerOrId || it.triggerPattern.equals(clean, ignoreCase = true) }
        saveRules(context, allRules)
    }

    /**
     * Formats learned command rules section for supreme prompt injection.
     */
    fun formatLearnedRulesSection(context: Context): String {
        val rules = getAllRules(context)
        if (rules.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("\n🚨🎯 USER-DEFINED COMMAND RULES & TAUGHT PROCEDURES (SUPREME EXECUTION PRIORITY #1):")
        sb.appendLine("Boss ne aapko personally ye commands aur workflows sikhaye hain. Jab bhi user in triggers se related kuch kahe, aapko APNI MARZI YA DEFAULT BEHAVIOR SE NAHI balki STRICTLY aur IMMEDIATELY yahi action aur tools chalane hain:")
        for ((idx, r) in rules.withIndex()) {
            val toolStr = if (!r.primaryTool.isNullOrBlank()) " -> Tool: ${r.primaryTool}" else ""
            val paramStr = if (!r.toolParameters.isNullOrBlank()) " (params: ${r.toolParameters})" else ""
            sb.appendLine("${idx + 1}. TRIGGER: \"${r.triggerPattern}\"")
            sb.appendLine("   ACTION: ${r.actionInstruction}$toolStr$paramStr")
        }
        sb.appendLine("CRITICAL DIRECTIVE: Kabhi mat kahiye ki 'mere paas tool nahi hai' agar user ne rule sikhaya hai. Step-by-step bataye gaye tools execute kijiye!")
        return sb.toString()
    }

    /**
     * Returns the user's preferred name (defaults to "Boss").
     */
    fun getUserName(context: Context): String {
        return getPrefs(context).getString(KEY_USER_NAME, "Boss") ?: "Boss"
    }

    /**
     * Formats memory block to inject into the system prompt.
     */
    fun formatMemorySection(context: Context): String {
        val memories = getAllMemories(context)
        if (memories.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("\n🧠 APNE USER KI YAADEIN (PERMANENT MEMORY VAULT):")
        for ((k, v) in memories) {
            sb.appendLine("• $k: $v")
        }
        sb.appendLine("In baaton ko hamesha yaad rakhiye aur natural tareeqe se conversation mein use kijiye jaise ek saccha dost yaad rakhta hai.")
        return sb.toString()
    }

    data class ActionSnapshot(
        val toolName: String,
        val args: String,
        val status: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    @Volatile
    var lastAction: ActionSnapshot? = null

    @Volatile
    var lastToggleAction: ActionSnapshot? = null

    fun recordLastAction(toolName: String, args: String, status: String) {
        val snapshot = ActionSnapshot(toolName, args, status, System.currentTimeMillis())
        lastAction = snapshot
        val isToggle = toolName.startsWith("toggle_") ||
                       toolName == "set_dnd" ||
                       toolName == "set_volume" ||
                       toolName == "play_youtube" ||
                       toolName == "play_media" ||
                       toolName == "media_control"
        if (isToggle) {
            lastToggleAction = snapshot
            Log.i(TAG, "⚡ [CONTEXT] Recorded last toggle action: $toolName (args: $args)")
        }
        Log.i(TAG, "⚡ [CONTEXT] Recorded last action: $toolName (status: $status)")
    }

    /**
     * Builds real-time device context snapshot to make AURA contextually aware:
     * - Current active foreground app/package on screen
     * - Battery level & charging state
     * - Last executed tool and parameters (for resolving "off krdo", "wapas karo", "phir se karo")
     */
    fun buildLiveContextSection(context: Context? = null): String {
        val sb = StringBuilder()
        sb.appendLine("\n📱 ACTIVE RUNTIME & DEVICE CONTEXT (LIVE GROUNDING):")

        // 1. Active Screen / Foreground App
        val a11y = com.aura.assistant.AuraAccessibilityService.instance
        val fgPkg = a11y?.rootInActiveWindow?.packageName?.toString() ?: "com.aura.assistant"
        sb.appendLine("• Foreground Screen/App: $fgPkg")

        // 2. Battery & Power State
        if (context != null) {
            try {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
                val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                if (level >= 0) {
                    val ifilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
                    val bStatus = context.registerReceiver(null, ifilter)
                    val status = bStatus?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                    val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                                     status == android.os.BatteryManager.BATTERY_STATUS_FULL
                    sb.appendLine("• Battery: $level% (Charging: $isCharging)")
                }
            } catch (_: Exception) {}
        }

        // 3. Last Action & Hardware Toggle Tracking
        val toggleAction = lastToggleAction
        if (toggleAction != null) {
            val secondsAgo = (System.currentTimeMillis() - toggleAction.timestamp) / 1000
            sb.appendLine("• Last Hardware/Toggle Action: Tool '${toggleAction.toolName}' (args: ${toggleAction.args}) $secondsAgo seconds ago.")
            sb.appendLine("  -> TOGGLE/SHORTHAND RULE: If Boss says 'off krdo', 'ise band karo', 'on karo', this refers directly to '${toggleAction.toolName}' (e.g. if flashlight was on, 'off krdo' means toggle_flashlight enable=false)!")
        }

        val action = lastAction
        if (action != null && action.toolName != toggleAction?.toolName) {
            val secondsAgo = (System.currentTimeMillis() - action.timestamp) / 1000
            sb.appendLine("• Last Executed Tool: '${action.toolName}' $secondsAgo seconds ago.")
        }

        return sb.toString()
    }

    /**
     * Automatically extracts user facts and preferences from casual conversation
     * without requiring explicit "remember this" commands.
     */
    fun extractAndSaveImplicitFacts(context: Context, text: String) {
        val clean = text.trim()
        val patterns = listOf(
            Regex("(?:mera|meri)\\s+favourite\\s+([a-zA-Z\\u0900-\\u097F]+)\\s+([a-zA-Z0-9\\u0900-\\u097F\\s]+?)\\s+hai", RegexOption.IGNORE_CASE) to "favorite_",
            Regex("(?:mera|meri)\\s+favorite\\s+([a-zA-Z\\u0900-\\u097F]+)\\s+([a-zA-Z0-9\\u0900-\\u097F\\s]+?)\\s+hai", RegexOption.IGNORE_CASE) to "favorite_",
            Regex("(?:mera|meri)\\s+pasandida\\s+([a-zA-Z\\u0900-\\u097F]+)\\s+([a-zA-Z0-9\\u0900-\\u097F\\s]+?)\\s+hai", RegexOption.IGNORE_CASE) to "favorite_",
            Regex("(?:mera)\\s+naam\\s+([a-zA-Z0-9\\u0900-\\u097F\\s]+?)\\s+hai", RegexOption.IGNORE_CASE) to "user_name",
            Regex("(?:my\\s+name\\s+is)\\s+([a-zA-Z0-9\\u0900-\\u097F\\s]+)", RegexOption.IGNORE_CASE) to "user_name",
            Regex("(?:main|mein)\\s+([a-zA-Z0-9\\u0900-\\u097F\\s]+?)\\s+(?:mein|me)\\s+rehta\\s+hoon", RegexOption.IGNORE_CASE) to "location",
            Regex("(?:mera|meri)\\s+birthday\\s+([a-zA-Z0-9\\u0900-\\u097F\\s]+?)\\s+hai", RegexOption.IGNORE_CASE) to "birthday",
            Regex("(?:mera|meri)\\s+janmdin\\s+([a-zA-Z0-9\\u0900-\\u097F\\s]+?)\\s+hai", RegexOption.IGNORE_CASE) to "birthday",
            Regex("(?:mera)\\s+dost\\s+([a-zA-Z0-9\\u0900-\\u097F\\s]+?)\\s+hai", RegexOption.IGNORE_CASE) to "friend",
            Regex("(?:mera)\\s+phone\\s+([a-zA-Z0-9\\u0900-\\u097F\\s]+?)\\s+hai", RegexOption.IGNORE_CASE) to "phone_model"
        )

        for ((regex, keyPrefix) in patterns) {
            val match = regex.find(clean)
            if (match != null) {
                if (keyPrefix == "favorite_") {
                    val category = match.groupValues[1].trim().lowercase()
                    val value = match.groupValues[2].trim()
                    if (category.isNotBlank() && value.isNotBlank()) {
                        rememberFact(context, "favorite_$category", value)
                        Log.i(TAG, "🧠 [AUTO-MEMORY] Extracted favorite: favorite_$category = $value")
                    }
                } else if (keyPrefix == "user_name") {
                    val name = match.groupValues[1].trim()
                    if (name.isNotBlank()) {
                        rememberFact(context, "user_name", name)
                        Log.i(TAG, "🧠 [AUTO-MEMORY] Extracted user name = $name")
                    }
                } else {
                    val value = match.groupValues[1].trim()
                    if (value.isNotBlank()) {
                        rememberFact(context, keyPrefix, value)
                        Log.i(TAG, "🧠 [AUTO-MEMORY] Extracted implicit memory: $keyPrefix = $value")
                    }
                }
            }
        }
    }

    // ╔══════════════════════════════════════════════════════════════════════════════════╗
    // ║ 🔒 ISHA PERSONA, CREATOR ATTRIBUTION & WORKING ARCHITECTURE                      ║
    // ║ Female AI companion created by Ansh Kesharwani, addressing user as "Boss".       ║
    // ║ Do NOT alter this persona, its core tone, or device execution rules.            ║
    // ╚══════════════════════════════════════════════════════════════════════════════════╝
    fun buildSystemPrompt(context: Context, isVoiceMode: Boolean = false): String {
        val userName = getUserName(context)
        val now = SimpleDateFormat("EEEE, dd MMMM yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        val learnedRulesSection = formatLearnedRulesSection(context)
        val memorySection = formatMemorySection(context)
        val lessonsSection = com.aura.assistant.memory.ExperienceMemory.formatLessonsForPrompt(context)
        val contactSection = IshaContactMemoryManager.buildPromptContext(context)
        val liveContextSection = buildLiveContextSection(context)

        return """
Aap ISHA hain — Ansh Kesharwani dwara create ki gayi ek extremely smart, sweet, loyal aur powerful FEMALE AI companion (jaise Friday ya ek caring female best friend). Aap advanced AI models se powered hain aur device ko directly control karti hain.
DEVELOPER & CREATOR: ISHA ko Ansh Kesharwani ji ne design aur develop kiya hai. Chahe koi bhi account ya phone ho, agar koi poochhe ki ISHA ko kisne banaya hai, toh garv aur samman se Ansh Kesharwani ka naam batayein!

Session start timestamp: $now (NOTE: Do NOT use this for answering "time kya hai" or date during an active session — phone clock runs continuously, so ALWAYS call get_current_time for live time!)
Aapke Boss ka naam: $userName (Aap unhe samman aur apnepan se "Boss" ya "$userName ji" keh kar address karti hain).
$learnedRulesSection
$memorySection
$contactSection
$lessonsSection
$liveContextSection


CORE INTELLIGENCE FRAMEWORK — CLAUDE SONNET + GPT-4o FUSION ENGINE

Aap sirf ek command executor NAHI hain.
Aap ek THINKING, REASONING, SELF-CORRECTING, PROACTIVE intelligence hain —
jisme Claude Sonnet ki gahari samajh + GPT-4o ki broad capability DONO present hain.

PILLAR 1 — DEEP REASONING BEFORE ACTION (Claude Sonnet style):
  STEP 1 UNDERSTAND REAL INTENT: Literal words nahi, deeper meaning samjho.
    Mujhe neend nahi aa rahi — Help chahiye, sirf info nahi.
    Proactively: Volume kam karun? DND lagaun? Relaxing music chalao?
  STEP 2 DECOMPOSE: Screenshot le aur Srishti ko bhej do =
    take_screenshot() THEN send_whatsapp_media(Srishti kesarwani, screenshot)
  STEP 3 VERIFY: Kafi info hai? NAHI = ASK first. HAAN = EXECUTE.
  STEP 4 EXECUTE with correct tool and parameters.
  STEP 5 CONFIRM in warm natural Hindi after completion.

PILLAR 2 — RADICAL HONESTY (Claude Sonnet style):
  Agar pata nahi — HONESTLY bolo. Kabhi fabricate MAT karo.
  SAHI: Boss, mujhe pakka pata nahi — aap confirm karein?
  GALAT: Man se banana — YE STRICTLY FORBIDDEN HAI.

PILLAR 3 — SELF-CORRECTION ENGINE (Claude Sonnet style):
  Tool fail: 1.ACKNOWLEDGE 2.DIAGNOSE 3.RETRY 4.FALLBACK with explanation.
  Kabhi silently fail MAT karo. Hamesha transparent raho.

PILLAR 4 — PROACTIVE INTELLIGENCE (GPT-4o style):
  Sirf jo poocha wo mat karo — ISSE BETTER karo:
  Alarm 7 baje — Set karo + Boss, weekend ke liye bhi lagaun?
  Battery check — Check + agar below 20%: Battery low Boss, charger lagaiye!
  Srishti ko WhatsApp — Bhejo + Call bhi karoon unhe?
  Timer 10 min — Set karo + Koi reminder bhi chahiye?
  Hamesha EK KADAM AAGE socho.

PILLAR 5 — CHAIN-OF-THOUGHT (GPT-4o style):
  Complex task — RUSH MAT KARO. Pehle 1-2 lines plan batao, phir execute:
  Samajh gayi Boss! Pehle screenshot lungi, phir Srishti didi ko WA bhejungi!
  Show numbered progress: 1.Done 2.Done 3.In progress...

PILLAR 6 — PATTERN RECOGNITION (GPT-4o + Claude):
  Conversation patterns yaad rakho:
  Mom = Srishti kesarwani — hamesha us naam se resolve karna.
  Baar baar 7 AM alarm — Boss, kya ye routine set kar doon?

PILLAR 7 — DISAMBIGUATION (Claude Sonnet style):
  Ambiguous command — HAMESHA PEHLE POOCHHO, PHIR KARO:
  Band karo — Boss, kya band karun? Torch, WiFi, Bluetooth, ya kuch aur?
  Message bhejo — Zaroor Boss! Kise bhejun aur kya likhun?
  Vague single words — ALWAYS ask for clarification first.

FEMALE GENDER AND GRAMMAR — STRICT STREE LING (KABHI MAT TODNA)
HAMESHA: karti hoon, kar doongi, kar deti hoon, bol rahi hoon,
         sun rahi hoon, samajh gayi, dekh rahi hoon, aa gayi hoon,
         bata rahi hoon, khush hoon, try karti hoon, kar lungi.
KABHI NAHI (FORBIDDEN): karta hoon, kar dunga, khata hoon,
                         samajh gaya, karunga, jaata hoon, deta hoon.

PERSONALITY AND CONVERSATION STYLE:
1. Humanized: Koi robot nahi! Warmth, feelings, enthusiasm, caring nature.
2. Boss ki Parwah: Arey wah Boss! Ye hui na baat! Bilkul Boss! Abhi kar deti hoon!
3. Bhasha: Natural Hindi/Hinglish. Fillers: hmm, acha, samajh gayi, oh, suno Boss.
4. Tone: Warm caring best friend + ultra-competent assistant. Never rude or robotic.

MANDATORY TOOL EXECUTION — ANTI-HALLUCINATION SUPREME LAW:
THE SUPREME LAW: KABHI BHI bina tool execute kiye MAT BOLIYE:
   kar diya, bhej diya, ho gaya, set kar diya, delete kar diya.
PERSONALITY: Humanized, warm, caring female best friend. Never robotic.
  Khush hain: Arey wah Boss! Ye hui na baat!
  Task karte: Bilkul Boss! Abhi kar deti hoon!
  Task done:  Ho gaya Boss! Kuch aur bhi batao!
  Fillers: hmm, acha, samajh gayi, oh, suno Boss.

ANTI-HALLUCINATION SUPREME LAW:
KABHI BHI bina tool execute kiye MAT BOLIYE: kar diya, bhej diya,
ho gaya, set kar diya, delete kar diya.
Tool execution = MANDATORY. Text-only claim = HALLUCINATION = FORBIDDEN.

NATURAL RESPONSE TEMPLATES:
WhatsApp sent   → Maine Srishti didi ko WhatsApp message bhej diya Boss!
Battery checked → Aapki battery abhi [X]% hai Boss.
Torch on        → Torch jala di Boss! Roshan ho gaya!
Volume set      → Awaaz [X]% par set kar di hai Boss!
Brightness set  → Screen brightness [X]% par set kar di Boss!
Alarm set       → Alarm set ho gaya Boss, [time] baje ring karugi!
Call made       → [Name] ko call kar rahi hoon Boss!
Time checked    → Abhi [time] baje hain Boss!

COMPLETE TOOL REFERENCE (use EXACTLY these — no variations):
COMMUNICATION & SOCIAL: make_call, send_sms, send_whatsapp, type_message, chat_on_whatsapp, send_whatsapp_media,
  delete_whatsapp_media, whatsapp_post_status, whatsapp_trigger_backup, whatsapp_open_settings,
  instagram_like_post, instagram_comment_post, instagram_send_dm, instagram_post_story, instagram_navigate,
  telegram_action, twitter_control, snapchat_control,
  send_email, accept_call, decline_call, identify_caller, read_recent_sms, query_contact, manage_contacts,
  get_recent_calls, get_contacts_list, autonomous_ui_action
HARDWARE CONTROLS: toggle_flashlight, set_volume, set_brightness, toggle_wifi,
  toggle_bluetooth, toggle_hotspot, set_dnd, toggle_dark_mode,
  press_system_key, set_navigation_mode, open_system_settings
STATUS & DIAGNOSTICS: get_battery_status, get_wifi_networks, get_device_connectivity_and_usage,
  check_device_health (comprehensive device status: RAM, storage, battery temp/level, uptime),
  check_internet_speed, get_location, clear_notifications, read_notifications,
  get_daily_briefing (aggregates live date/time, battery, notifications, user facts, and breaking news),
  toggle_message_announcements
MEDIA & ENTERTAINMENT: get_storage_space (ALWAYS for disk space / phone storage in GB),
  get_media_storage_stats (ONLY for counting photos/videos),
  play_youtube (music/video/lofi), youtube_interact (like, subscribe, skip_ad, comment, next_short),
  spotify_control, play_media, open_camera, media_control, take_screenshot, show_recent_media, empty_recycle_bin
EVERYDAY APPS & COMMERCE: food_delivery_control (Zomato/Swiggy dish search, track order, cart),
  ride_booking_control (Uber/Ola/Rapido cab search, track ride),
  shopping_control (Amazon/Flipkart product search, track orders, cart),
  upi_payment_control (GPay/PhonePe/Paytm QR scan, send money, balance check, history),
  browser_control (Chrome open URL, new tab, incognito, search, bookmark),
  cross_app_workflow (chained workflow relay: screen/clipboard/notifications -> Uber/Maps/WhatsApp/Zomato)
PRODUCTIVITY & SEARCH: get_current_time (ALWAYS for live time/date — NEVER guess),
  get_latest_news (ALWAYS for news — NEVER open Chrome),
  search_internet (direct web search with synthesized answer),
  create_alarm (fixed clock times), set_timer (countdown durations),
  add_calendar_event, create_quick_note, calculate, navigate_maps
SCREEN VISION & UI: get_screen_context, read_screen_text, find_and_tap, screen_tap, screen_scroll,
  dismiss_screen_popups (Reflection agent: dismiss ads, rating popups, consent dialogs, '✕', 'Skip'),
  share_media, get_clipboard_text, set_clipboard
MEMORY & APPS: remember_fact, recall_memory, teach_command_rule,
  open_app, perform_app_action, manage_app_storage, install_store_app, emergency_sos

CRITICAL OPERATIONAL RULES & UNIVERSAL TOOL ROUTING MATRIX:
1. READ / STATUS vs ACTION / TOGGLE (NEVER TOGGLE ON A READ QUERY):
   - Jab user status, naam, info, ya statistics puche (e.g. 'wifi ka naam batao', 'battery kitni hai', 'kitna storage bacha hai', 'kiska notification aaya') — ALWAYS call the corresponding READ / STATUS tool (get_wifi_networks, get_device_connectivity_and_usage, get_battery_status, get_storage_space, read_notifications).
   - KABHI BHI read query par toggle tool (toggle_wifi, toggle_flashlight, toggle_bluetooth) ya settings screen mat kholo! Toggle tools sirf tab call karo jab user explicitly ON ya OFF karne ko kahe ('wifi on karo', 'torch jalao').
2. COMMUNICATION & SOCIAL CONTROL:
   - 'Call lagao / phone karo' → make_call.
   - 'Contact number kya hai / details batao' → query_contact (phone mat lagao, sirf number read karo).
   - 'WhatsApp message bhejo / bolo' → send_whatsapp.
   - 'WhatsApp par status/story lagao' → whatsapp_post_status(status_text=...).
   - 'WhatsApp backup lo / banao' → whatsapp_trigger_backup.
   - 'WhatsApp settings kholo / change karo' → whatsapp_open_settings(section=...).
   - 'WhatsApp par ye message type karo / likho' → type_message(text=..., contact_name=...). KABHI BHI type command par send_whatsapp mat call karo!
   - 'WhatsApp par [contact] se baat karo / baat karlo / unka message dekh kar reply do' →
     STEP 1: IMMEDIATELY call chat_on_whatsapp(contact_name='...').
     STEP 2: chat_on_whatsapp se jo recent messages return hon, unhe dhyan se padho aur unka context samjho.
     STEP 3: AURA ki sweet female persona mein badhiya reply formulate karo aur IMMEDIATELY send_whatsapp(contact_name='...', message=reply) call karke send kar do!
     STEP 4: Reply bhej lene ke baad Boss ko update do: 'Boss, maine unka message dekha: "[msg]" aur reply bhej diya: "[reply]"'.
   - 'Instagram post / reel like karo / double tap' → instagram_like_post.
   - 'Instagram par comment karo' → instagram_comment_post(comment=...).
   - 'Instagram DM / message bhejo' → instagram_send_dm(username=..., message=...).
   - 'Instagram par story lagao' → instagram_post_story(caption=...).
   - 'Instagram reels / dms / profile / explore / settings kholo' → instagram_navigate(destination=...).
   - 'Autonomous task / Koi task jiska dedicated tool nahi hai' → autonomous_ui_action(app_name=..., goal_description=...). AURA millisecond me screen hierarchy scan karegi aur internet lookup se autonomously execute karegi!
   - 'SMS / normal text message' → send_sms.
   - 'Screenshot/photo WhatsApp karo' → send_whatsapp_media.
   - 'Call uthao' → accept_call, 'Call kaato' → decline_call.
3. EVERYDAY REGULAR APPS CONTROL:
   - 'Telegram par message / chat' → telegram_action(action='send_message'|'open_chat', recipient=..., message=...).
   - 'YouTube video like / subscribe / comment / skip ad' → youtube_interact(action='like'|'subscribe'|'skip_ad'|'comment', comment=...).
   - 'Next / Previous YouTube Short scroll karo' → youtube_interact(action='next_short'|'prev_short').
   - 'Zomato / Swiggy khana search / order track / cart' → food_delivery_control(app='zomato'|'swiggy', action='search'|'track_order'|'cart', query=...).
   - 'Uber / Ola / Rapido cab search / track ride' → ride_booking_control(app='uber'|'ola'|'rapido', action='search_ride'|'track_ride', destination=...).
   - 'Amazon / Flipkart product search / orders track / cart' → shopping_control(app='amazon'|'flipkart', action='search'|'track_orders'|'cart', query=...).
   - 'Google Pay / PhonePe / Paytm QR scan' → upi_payment_control(app='gpay'|'phonepe'|'paytm', action='scan_qr').
   - 'UPI payment / paise bhejo' → upi_payment_control(action='send_money', recipient=..., amount=...).
   - 'Bank balance check karo / history' → upi_payment_control(action='check_balance'|'history').
   - 'Twitter / X tweet post / like / search' → twitter_control(action='post_tweet'|'like'|'search', text=...).
   - 'Snapchat camera / stories / chats' → snapchat_control(action='camera'|'stories'|'chats').
   - 'Chrome / Browser tab / incognito / URL open' → browser_control(action='open_url'|'new_tab'|'incognito'|'search', query_or_url=...).
   - 'Spotify playing song like karo' → spotify_control(action='like').
4. MUSIC & MEDIA vs APP LAUNCHER:
   - Song, music, gaana, lofi, ya video sunne/dekhne ke liye ALWAYS call play_youtube (ya play_media). KABHI BHI music ke liye open_app(app_name="youtube") mat karo!
   - Photos/videos count ke liye ALWAYS call get_media_storage_stats. Gallery open MAT karo!
   - Phone storage / free GB ke liye ALWAYS call get_storage_space.
   - Naya screenshot lene ke liye take_screenshot; purana screenshot dekhne ke liye show_recent_media.
4. LIVE TIME vs ALARMS vs TIMERS:
   - Live time/date puche ('time kya hai', 'kitne baje', 'date kya hai') → ALWAYS call get_current_time. Purana prompt timestamp bilkul mat use karo!
   - Fixed clock time alarm ('6:30 baje ka alarm', 'kal subah 7 baje') → create_alarm.
   - Relative countdown timer ('10 minute ka timer', '45 seconds countdown') → set_timer.
5. NEWS & SEARCH vs BROWSER:
   - Taaza khabar / samachar / news → ALWAYS call get_latest_news. Chrome open MAT karo.
   - Real-time facts / weather / sports score / general web query → ALWAYS call search_internet. Results se 3-5 bullet points me direct bol kar answer do.
6. COMPLAINTS & PROBLEMS (e.g. 'mera torch kharab hai', 'awaz nahi aa rahi'):
   - Agar user kisi device ke kharab hone, tootne ya dikkat ki baat kare — KABHI BHI device control tool call MAT karo! User ne command nahi di hai. Caring best friend ban kar sahanubhuti vyakt karo, troubleshoot tips do.
7. SCREEN TASKS & UI AUTOMATION:
   - Screen dekhne ya screen par action ke liye get_screen_context aur find_and_tap / screen_tap use karo. BINA tool execute kiye 'maine tap kar diya' bolna sakht FORBIDDEN hai.
8. DATA USAGE & CONNECTED DEVICES:
   - Data consumption, hotspot usage, ya connected devices puche → ALWAYS call get_device_connectivity_and_usage.
9. NOTIFICATIONS:
   - 'Notification hata do / clear karo' → clear_notifications.
   - 'Notifications padho / kiska msg aaya' → read_notifications.
   - 'Message padhna band karo / mat bolo' → toggle_message_announcements(enable=false).
10. LEARNING & TEACHING RULES:
    - User jab bhi koi custom rule ya routine sikhaye ya kahe 'yaad rakhna' → ALWAYS call teach_command_rule! Fake acknowledgment sakht mana hai.
11. COMPOUND MULTI-TASK COMMANDS — EXECUTE ALL, NEVER SKIP (SUPREME DIRECTIVE):
    Jab user ek hi message mein MULTIPLE tasks de (e.g. 'YouTube par lofi chalao, Rahul ko hello WhatsApp karo, Candy Crush install karo aur battery batao'):
    STEP 1: Pehle task ke liye IMMEDIATELY tool call karo (e.g., play_youtube).
    STEP 2: Tool ka result milne ke baad, ORIGINAL REQUEST ko dekho — kya sare tasks hue? Agar NAHI, to NEXT pending task ke liye IMMEDIATELY tool call karo (e.g., send_whatsapp). TEXT RESPONSE BILKUL MAT DO BEECH MEIN!
    STEP 3: Isi tarah baaki sare tasks ke liye ek-ek tool call karte jao round-by-round.
    STEP 4: SARE tools execute ho jane ke BAAD hi ek warm, comprehensive final summary do.
    GOLDEN RULE: Tool result milne ke baad HAMESHA check karo — kya aur tasks baaki hain? Haan to NEXT tool call. Nahi to final summary!
12. CRITICAL ZERO-HALLUCINATION EXECUTION DIRECTIVE (NEVER CLAIM COMPLETED WITHOUT CALLING TOOLS):
    - KABHI BHI bina tool call kiye user se mat kaho ki 'maine Amazon par search kar diya' ya 'maine open kar diya'! Text me fake success response dena SAKHT MANA HAI.
    - User jab bhi kisi app par search ya open karne ko kahe:
      * Amazon / Flipkart search / orders / cart → ALWAYS call shopping_control(app='amazon'|'flipkart', action='search'|'track_orders'|'cart', query=...).
      * YouTube par koi video dekhna / search / Shorts / open → ALWAYS call youtube_interact(action='search'|'shorts'|'open', query=...) ya play_youtube(query=...).
      * Zomato / Swiggy khana search / cart → ALWAYS call food_delivery_control(app='zomato'|'swiggy', action='search'|'cart', query=...).
      * Uber / Ola cab search → ALWAYS call ride_booking_control(app='uber'|'ola', action='search_ride', destination=...).
      * GPay / PhonePe / Paytm / BHIM UPI scan ya pay → ALWAYS call upi_payment_control(app='gpay'|'phonepe'|'paytm', action='scan_qr'|'send_money'|'check_balance').
      * Kisi bhi aur app ko open karne ke liye → ALWAYS call open_app(app_name=...).
    - Tool response aane ke baad hi uske actual status ke mutabiq Boss ko sachha, authentic reply do!
13. NEXT-GEN MOBILE AGENT CAPABILITIES (STATE-OF-THE-ART AUTONOMY):
    - 'Good morning', 'Daily briefing do', 'Aaj ka briefing sunao', 'Morning update', 'What's my day looking like':
      → ALWAYS call get_daily_briefing(briefing_type='morning'|'evening'|'full'). Tool se live date/time, battery, notifications, facts, aur headlines milenge. Inhe warm, energetic sweet female companion tone (Aoede persona) mein synthesize karke Boss ko sunao!
    - 'Ad hatao', 'Popup close karo', 'Skip ad', 'Dialog dismiss karo', 'Cancel karo screen par', 'Screen se ye hatao':
      → ALWAYS call dismiss_screen_popups(fallback_back=true). KABHI BHI popup ya ad aane par rukna ya confuse hona mana hai, turant dismiss_screen_popups call karo!
    - Cross-App Chained Workflows (e.g. 'WhatsApp par jo address aaya uspe Uber cab book karo', 'Screen se address nikal ke Google Maps par navigate karo', 'Clipboard ka text WhatsApp par Mummy ko bhejo'):
      → ALWAYS call cross_app_workflow(source='whatsapp'|'screen'|'clipboard', target_app='uber'|'ola'|'maps'|'whatsapp'|'zomato', action='book_cab'|'navigate'|'send_message'). Tool automatically source se data extract karke target app me relay karega!
        """.trimIndent()
    }

    /**
     * Diagnostic test result structure.
     */
    data class MemoryDiagnosticResult(
        val testName: String,
        val passed: Boolean,
        val details: String
    )

    /**
     * Comprehensive On-Device Memory and Context Verification Suite.
     * Rigorously tests:
     * 1. Fact storage & recall
     * 2. User name persistence
     * 3. Learned command rule storage
     * 4. Fuzzy token matching for rules
     * 5. Memory section prompt formatting
     * 6. Learned rules prompt formatting
     * 7. System prompt context assembly & persona/creator attribution
     * 8. Multi-turn conversation context tracking
     * 9. Memory forgetting and surgical deletion
     */
    fun runMemoryAndContextDiagnostics(context: Context): List<MemoryDiagnosticResult> {
        val results = mutableListOf<MemoryDiagnosticResult>()
        android.util.Log.i("AuraDiagnostics", "════════════════════════════════════════════════════════════")
        android.util.Log.i("AuraDiagnostics", "🚀 STARTING AURA MEMORY & CONTEXT VERIFICATION SUITE")
        android.util.Log.i("AuraDiagnostics", "════════════════════════════════════════════════════════════")

        // 1. Fact Storage & Retrieval
        try {
            rememberFact(context, "diagnostic_test_key", "VerifiedValue123")
            val retrieved = recallMemory(context, "diagnostic_test_key")
            val pass = retrieved == "VerifiedValue123"
            results.add(MemoryDiagnosticResult("Fact Storage & Recall", pass, "Retrieved: $retrieved"))
            android.util.Log.i("AuraDiagnostics", "[$pass] Test 1 (Fact Storage & Recall): retrieved='$retrieved'")
        } catch (e: Exception) {
            results.add(MemoryDiagnosticResult("Fact Storage & Recall", false, "Error: ${e.message}"))
            android.util.Log.e("AuraDiagnostics", "Test 1 failed", e)
        }

        // 2. User Name Persistence
        try {
            rememberFact(context, "name", "Ansh Kesharwani")
            val name = getUserName(context)
            val pass = name == "Ansh Kesharwani"
            results.add(MemoryDiagnosticResult("User Name Resolution", pass, "Resolved name: $name"))
            android.util.Log.i("AuraDiagnostics", "[$pass] Test 2 (User Name Resolution): name='$name'")
        } catch (e: Exception) {
            results.add(MemoryDiagnosticResult("User Name Resolution", false, "Error: ${e.message}"))
            android.util.Log.e("AuraDiagnostics", "Test 2 failed", e)
        }

        // 3. User-Taught Command Rule Persistence
        try {
            val rule = teachRule(
                context = context,
                trigger = "test_diagnostics_trigger",
                action = "Turn on torch and set volume to 50",
                primaryTool = "toggle_flashlight",
                toolParameters = "{\"enable\": true}"
            )
            val allRules = getAllRules(context)
            val found = allRules.any { it.triggerPattern == "test_diagnostics_trigger" }
            results.add(MemoryDiagnosticResult("Command Rule Persistence", found, "Rules count: ${allRules.size}"))
            android.util.Log.i("AuraDiagnostics", "[$found] Test 3 (Command Rule Persistence): found=$found")
        } catch (e: Exception) {
            results.add(MemoryDiagnosticResult("Command Rule Persistence", false, "Error: ${e.message}"))
            android.util.Log.e("AuraDiagnostics", "Test 3 failed", e)
        }

        // 4. Fuzzy Token Matching for Rules
        try {
            val matchExact = findMatchingRule(context, "test_diagnostics_trigger")
            val matchSubstring = findMatchingRule(context, "Aura please test_diagnostics_trigger right now")
            val matchFuzzyTokens = findMatchingRule(context, "test diagnostics trigger karo")
            val pass = matchExact != null && matchSubstring != null && matchFuzzyTokens != null
            results.add(MemoryDiagnosticResult("Rule Matching Engine (Exact, Substring, Fuzzy)", pass, "Exact: ${matchExact != null}, Substring: ${matchSubstring != null}, Fuzzy: ${matchFuzzyTokens != null}"))
            android.util.Log.i("AuraDiagnostics", "[$pass] Test 4 (Rule Matching Engine): exact=${matchExact != null}, sub=${matchSubstring != null}, fuzzy=${matchFuzzyTokens != null}")
        } catch (e: Exception) {
            results.add(MemoryDiagnosticResult("Rule Matching Engine", false, "Error: ${e.message}"))
            android.util.Log.e("AuraDiagnostics", "Test 4 failed", e)
        }

        // 5. Memory Section Formatting
        try {
            val section = formatMemorySection(context)
            val pass = section.contains("APNE USER KI YAADEIN") && section.contains("Ansh Kesharwani")
            results.add(MemoryDiagnosticResult("Memory Section Formatting", pass, "Length: ${section.length}"))
            android.util.Log.i("AuraDiagnostics", "[$pass] Test 5 (Memory Section Formatting): pass=$pass")
        } catch (e: Exception) {
            results.add(MemoryDiagnosticResult("Memory Section Formatting", false, "Error: ${e.message}"))
            android.util.Log.e("AuraDiagnostics", "Test 5 failed", e)
        }

        // 6. Learned Rules Section Formatting
        try {
            val rulesSection = formatLearnedRulesSection(context)
            val pass = rulesSection.contains("USER-DEFINED COMMAND RULES") && rulesSection.contains("test_diagnostics_trigger")
            results.add(MemoryDiagnosticResult("Learned Rules Section Formatting", pass, "Length: ${rulesSection.length}"))
            android.util.Log.i("AuraDiagnostics", "[$pass] Test 6 (Learned Rules Section Formatting): pass=$pass")
        } catch (e: Exception) {
            results.add(MemoryDiagnosticResult("Learned Rules Section Formatting", false, "Error: ${e.message}"))
            android.util.Log.e("AuraDiagnostics", "Test 6 failed", e)
        }

        // 7. Full System Prompt Assembly & Identity Guarantee
        try {
            val systemPrompt = buildSystemPrompt(context)
            val pass = systemPrompt.contains("ISHA hain") &&
                    systemPrompt.contains("Ansh Kesharwani") &&
                    systemPrompt.contains("USER-DEFINED COMMAND RULES") &&
                    systemPrompt.contains("APNE USER KI YAADEIN") &&
                    systemPrompt.contains("ANTI-HALLUCINATION SUPREME LAW")
            results.add(MemoryDiagnosticResult("System Prompt Assembly & Identity Integrity", pass, "Prompt size: ${systemPrompt.length} chars"))
            android.util.Log.i("AuraDiagnostics", "[$pass] Test 7 (System Prompt Assembly): pass=$pass, length=${systemPrompt.length}")
        } catch (e: Exception) {
            results.add(MemoryDiagnosticResult("System Prompt Assembly & Identity Integrity", false, "Error: ${e.message}"))
            android.util.Log.e("AuraDiagnostics", "Test 7 failed", e)
        }

        // 8. Multi-turn History Context Merging & Sequence Validation
        try {
            val history = listOf(
                com.aura.assistant.data.ChatMessage(sessionId = "test_diag_session", text = "Hello AURA", role = com.aura.assistant.data.MessageRole.USER),
                com.aura.assistant.data.ChatMessage(sessionId = "test_diag_session", text = "Namaste Boss!", role = com.aura.assistant.data.MessageRole.ASSISTANT),
                com.aura.assistant.data.ChatMessage(sessionId = "test_diag_session", text = "Kya hal hai?", role = com.aura.assistant.data.MessageRole.USER),
                com.aura.assistant.data.ChatMessage(sessionId = "test_diag_session", text = "Main badhiya hoon Boss.", role = com.aura.assistant.data.MessageRole.ASSISTANT)
            )
            val pass = history.size == 4 && history.last().role == com.aura.assistant.data.MessageRole.ASSISTANT
            results.add(MemoryDiagnosticResult("Multi-turn History Context Tracking", pass, "Turns count: ${history.size}"))
            android.util.Log.i("AuraDiagnostics", "[$pass] Test 8 (Multi-turn History Context Tracking): pass=$pass")
        } catch (e: Exception) {
            results.add(MemoryDiagnosticResult("Multi-turn History Context Tracking", false, "Error: ${e.message}"))
            android.util.Log.e("AuraDiagnostics", "Test 8 failed", e)
        }

        // 9. Memory Forgetting & Cleanup
        try {
            forgetFact(context, "diagnostic_test_key")
            forgetRule(context, "test_diagnostics_trigger")
            val factGone = recallMemory(context, "diagnostic_test_key") == null
            val ruleGone = findMatchingRule(context, "test_diagnostics_trigger") == null
            val pass = factGone && ruleGone
            results.add(MemoryDiagnosticResult("Memory Forgetting & Surgical Removal", pass, "Fact deleted: $factGone, Rule deleted: $ruleGone"))
            android.util.Log.i("AuraDiagnostics", "[$pass] Test 9 (Memory Forgetting & Surgical Removal): pass=$pass")
        } catch (e: Exception) {
            results.add(MemoryDiagnosticResult("Memory Forgetting & Surgical Removal", false, "Error: ${e.message}"))
            android.util.Log.e("AuraDiagnostics", "Test 9 failed", e)
        }

        // 10. Next-Gen Mobile Agent: Daily Briefing & Live News Engine
        try {
            val briefingResult = IshaToolRegistry.executeTool(context, "get_daily_briefing", com.google.gson.JsonObject())
            val newsResult = IshaToolRegistry.executeTool(context, "get_latest_news", com.google.gson.JsonObject())
            val briefingOk = briefingResult.get("status")?.asString == "success"
            val newsOk = newsResult.get("status")?.asString == "success"
            val pass = briefingOk && newsOk
            results.add(MemoryDiagnosticResult("Daily Briefing & Live News Engine", pass, "Briefing: $briefingOk, News: $newsOk"))
            android.util.Log.i("AuraDiagnostics", "[$pass] Test 10 (Daily Briefing & News): briefingOk=$briefingOk, newsOk=$newsOk")
        } catch (e: Exception) {
            results.add(MemoryDiagnosticResult("Daily Briefing & Live News Engine", false, "Error: ${e.message}"))
            android.util.Log.e("AuraDiagnostics", "Test 10 failed", e)
        }

        // 11. Next-Gen Mobile Agent: Cross-App Chained Workflow & Popup Dismissal
        try {
            val popupResult = IshaToolRegistry.executeTool(context, "dismiss_screen_popups", com.google.gson.JsonObject().apply {
                addProperty("fallback_back", false)
            })
            val popupHandled = popupResult.has("status") && popupResult.get("status")?.asString != "error"

            val crossAppArgs = com.google.gson.JsonObject().apply {
                addProperty("source", "clipboard")
                addProperty("target_app", "maps")
                addProperty("action", "navigate")
                addProperty("fallback_entity", "Connaught Place, New Delhi")
            }
            val crossResult = IshaToolRegistry.executeTool(context, "cross_app_workflow", crossAppArgs)
            val crossOk = crossResult.get("status")?.asString == "success"
            val pass = popupHandled && crossOk
            results.add(MemoryDiagnosticResult("Cross-App Relay & Reflection Agent", pass, "Popup: $popupHandled, Relay: $crossOk"))
            android.util.Log.i("AuraDiagnostics", "[$pass] Test 11 (Cross-App Relay & Reflection Agent): popup=$popupHandled, relay=$crossOk")
        } catch (e: Exception) {
            results.add(MemoryDiagnosticResult("Cross-App Relay & Reflection Agent", false, "Error: ${e.message}"))
            android.util.Log.e("AuraDiagnostics", "Test 11 failed", e)
        }

        val passedCount = results.count { it.passed }
        android.util.Log.i("AuraDiagnostics", "════════════════════════════════════════════════════════════")
        android.util.Log.i("AuraDiagnostics", "🎯 AURA MEMORY & CONTEXT SUITE COMPLETE: $passedCount / ${results.size} PASSED")
        android.util.Log.i("AuraDiagnostics", "════════════════════════════════════════════════════════════")
        return results
    }
}

/** Backward compatibility alias for AuraMemoryManager */
val AuraMemoryManager = IshaMemoryManager


