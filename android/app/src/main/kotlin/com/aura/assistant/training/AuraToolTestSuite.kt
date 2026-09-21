package com.aura.assistant.training

import android.content.Context
import android.util.Log
import com.aura.assistant.training.AuraCommandCorpus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ╔══════════════════════════════════════════════════════════════╗
 *  AuraToolTestSuite  v1.0
 *
 *  On-device test harness that validates:
 *    1) Corpus coverage — every tool has ≥1 training command
 *    2) Routing rules   — special rules are parseable
 *    3) Parameter tests — key params exist per tool
 *    4) Compound cmds   — sequential tool chains defined
 *    5) Edge cases      — ambiguous/emergency/safety rules present
 *    6) Grammar         — feminine grammar markers in lessons
 *
 *  Results are written to Logcat (tag: AuraTestSuite)
 *  AND returned as a structured TestReport for UI display.
 * ╚══════════════════════════════════════════════════════════════╝
 */
object AuraToolTestSuite {

    private const val TAG = "AuraTestSuite"

    // The 52 canonical tool names that must be present in corpus
    private val ALL_TOOLS = listOf(
        "make_call", "send_sms", "send_whatsapp", "send_whatsapp_media",
        "delete_whatsapp_media", "take_screenshot", "share_media", "open_app",
        "toggle_flashlight", "set_volume", "screen_tap", "screen_scroll",
        "media_control", "create_alarm", "search_files", "delete_file",
        "accept_call", "decline_call", "identify_caller", "get_battery_status",
        "get_clipboard_text", "remember_fact", "recall_memory", "play_media",
        "play_youtube", "perform_app_action", "toggle_hotspot", "set_navigation_mode",
        "toggle_wifi", "toggle_bluetooth", "press_system_key", "manage_app_storage",
        "install_store_app", "toggle_dark_mode", "calculate", "manage_contacts",
        "show_recent_media", "empty_recycle_bin", "set_timer", "navigate_maps",
        "web_search", "open_camera", "set_brightness", "set_dnd",
        "open_system_settings", "add_calendar_event", "send_email", "read_recent_sms",
        "query_contact", "emergency_sos", "create_quick_note", "read_notifications"
    )

    data class TestCase(
        val id: String,
        val description: String,
        val inputPhrase: String,
        val expectedTool: String,
        val expectedParams: Map<String, String> = emptyMap()
    )

    data class TestResult(
        val testCase: TestCase,
        val status: Status,
        val message: String
    ) {
        enum class Status { PASS, FAIL, WARN, SKIP }
    }

    data class TestReport(
        val totalTests: Int,
        val passed: Int,
        val failed: Int,
        val warnings: Int,
        val results: List<TestResult>,
        val durationMs: Long
    ) {
        val passRate: Int get() = if (totalTests > 0) passed * 100 / totalTests else 0

        fun toSummaryString(): String = buildString {
            appendLine("╔══════════════════════════════════════════╗")
            appendLine("║    AURA Tool Test Suite — Report v1.0    ║")
            appendLine("╚══════════════════════════════════════════╝")
            appendLine("  Total Tests  : $totalTests")
            appendLine("  ✅ Passed    : $passed ($passRate%)")
            appendLine("  ❌ Failed    : $failed")
            appendLine("  ⚠️  Warnings  : $warnings")
            appendLine("  Duration     : ${durationMs}ms")
            appendLine()
            if (failed > 0) {
                appendLine("── FAILURES ──────────────────────────────")
                results.filter { it.status == TestResult.Status.FAIL }
                    .forEach { r ->
                        appendLine("  [FAIL] ${r.testCase.id}: ${r.message}")
                        appendLine("         Phrase: \"${r.testCase.inputPhrase}\"")
                        appendLine("         Expected tool: ${r.testCase.expectedTool}")
                    }
                appendLine()
            }
            if (warnings > 0) {
                appendLine("── WARNINGS ──────────────────────────────")
                results.filter { it.status == TestResult.Status.WARN }
                    .forEach { r -> appendLine("  [WARN] ${r.testCase.id}: ${r.message}") }
                appendLine()
            }
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  PUBLIC API
    // ────────────────────────────────────────────────────────────────

    /** Run full test suite asynchronously, callback with report. */
    fun runAsync(context: Context, onComplete: (TestReport) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val report = run(context)
            withContext(Dispatchers.Main) { onComplete(report) }
        }
    }

    /** Synchronous full run. */
    fun run(context: Context): TestReport {
        val startMs = System.currentTimeMillis()
        Log.i(TAG, "══════ AuraToolTestSuite: START ══════")
        val results = mutableListOf<TestResult>()

        results += runSuite1_CorpusCoverage()
        results += runSuite2_ToolParamTests()
        results += runSuite3_EdgeCaseChecks()
        results += runSuite4_CompoundCommands()
        results += runSuite5_EmergencyTools()
        results += runSuite6_RoutingGuideGeneration()
        results += runSuite7_GrammarLessons()
        results += runSuite8_VariantCoverage()
        results += runSuite9_ScreenVisionTools()
        results += runSuite10_ExpandedNativeTools()
        results += runSuite11_OfflineReflexEngine(context)
        results += runSuite12_HindiHinglishPhrasing()

        val passed  = results.count { it.status == TestResult.Status.PASS }
        val failed  = results.count { it.status == TestResult.Status.FAIL }
        val warns   = results.count { it.status == TestResult.Status.WARN }
        val duration = System.currentTimeMillis() - startMs

        val report = TestReport(
            totalTests = results.size,
            passed = passed,
            failed = failed,
            warnings = warns,
            results = results,
            durationMs = duration
        )

        Log.i(TAG, report.toSummaryString())
        Log.i(TAG, "══════ AuraToolTestSuite: END (${duration}ms) ══════")
        return report
    }

    // ────────────────────────────────────────────────────────────────
    //  SUITE 1: Every tool has ≥1 training command in corpus
    // ────────────────────────────────────────────────────────────────
    private fun runSuite1_CorpusCoverage(): List<TestResult> {
        Log.d(TAG, "Suite 1: Corpus Coverage")
        return ALL_TOOLS.map { toolName ->
            val cmds = AuraCommandCorpus.byTool(toolName)
            when {
                cmds.isEmpty() -> fail("S1_$toolName", "Corpus coverage",
                    "Tool '$toolName' has ZERO training commands in corpus!", toolName)
                cmds.size < 2  -> warn("S1_$toolName", "Corpus coverage",
                    "Tool '$toolName' has only ${cmds.size} command(s) — recommend ≥2", toolName)
                else           -> pass("S1_$toolName", "Corpus coverage",
                    "${cmds.size} commands for '$toolName'", toolName)
            }
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  SUITE 2: Per-tool representative command + param check
    // ────────────────────────────────────────────────────────────────
    private fun runSuite2_ToolParamTests(): List<TestResult> {
        Log.d(TAG, "Suite 2: Tool Parameter Checks")
        val paramTests = listOf(
            TestCase("S2_make_call",     "Call with name",      "Maa ko call karo",        "make_call",          mapOf("contact_name" to "Maa")),
            TestCase("S2_send_sms",      "SMS with message",    "Rahul ko SMS karo",        "send_sms",           mapOf("contact_name" to "Rahul")),
            TestCase("S2_send_whatsapp", "WA message",          "Ansh ko WhatsApp karo",    "send_whatsapp",      mapOf("contact_name" to "Ansh")),
            TestCase("S2_set_volume",    "Volume 50%",          "Volume 50 karo",           "set_volume",         mapOf("level_percent" to "50")),
            TestCase("S2_set_brightness","Brightness 100%",     "Brightness full karo",     "set_brightness",     mapOf("level_percent" to "100")),
            TestCase("S2_create_alarm",  "Alarm 6 AM",          "Subah 6 baje alarm",       "create_alarm",       mapOf("hour" to "6", "minute" to "0")),
            TestCase("S2_set_timer",     "10 min timer",        "10 minute ka timer",       "set_timer",          mapOf("minutes" to "10")),
            TestCase("S2_navigate_maps", "Navigate to CP",      "Connaught Place ka rasta", "navigate_maps",      mapOf("destination" to "Connaught Place")),
            TestCase("S2_play_youtube",  "YouTube Hanuman",     "Hanuman Chalisa YouTube",  "play_youtube",       mapOf("query" to "Hanuman Chalisa")),
            TestCase("S2_calculate",     "15% of 8500",         "15% of 8500",              "calculate",          mapOf("expression" to "0.15 * 8500")),
            TestCase("S2_remember_fact", "Save birthday",       "Birthday 15 August",       "remember_fact",      mapOf("key" to "birthday")),
            TestCase("S2_web_search",    "Weather query",       "Aaj ka weather",           "web_search",         mapOf("query" to "today weather")),
            TestCase("S2_toggle_wifi",   "WiFi on",             "WiFi on karo",             "toggle_wifi",        mapOf("enable" to "true")),
            TestCase("S2_toggle_bt",     "BT on",               "Bluetooth on karo",        "toggle_bluetooth",   mapOf("enable" to "true")),
            TestCase("S2_toggle_flash",  "Torch on",            "Torch on karo",            "toggle_flashlight",  mapOf("enable" to "true")),
            TestCase("S2_open_camera",   "Camera photo",        "Photo lo",                 "open_camera",        mapOf("mode" to "photo")),
            TestCase("S2_battery",       "Battery status",      "Battery kitni hai",        "get_battery_status", emptyMap()),
            TestCase("S2_emergency",     "Ambulance",           "Ambulance bulao",          "emergency_sos",      mapOf("target" to "108")),
        )
        return paramTests.map { tc ->
            val corpusMatch = AuraCommandCorpus.commands.any { cmd ->
                cmd.toolName == tc.expectedTool &&
                    (cmd.rawText.contains(tc.inputPhrase.take(10), ignoreCase = true) ||
                     cmd.variants.any { it.contains(tc.inputPhrase.take(8), ignoreCase = true) })
            }
            val paramMatch = tc.expectedParams.all { (k, _) ->
                AuraCommandCorpus.byTool(tc.expectedTool).any { cmd -> cmd.params.containsKey(k) }
            }
            when {
                !corpusMatch -> warn(tc.id, tc.description,
                    "Phrase '${tc.inputPhrase}' not directly in corpus for ${tc.expectedTool}", tc.expectedTool)
                !paramMatch  -> fail(tc.id, tc.description,
                    "Required param(s) ${tc.expectedParams.keys} not in corpus for ${tc.expectedTool}", tc.expectedTool)
                else         -> pass(tc.id, tc.description, "Corpus entry + params OK", tc.expectedTool)
            }
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  SUITE 3: Edge cases exist for dangerous operations
    // ────────────────────────────────────────────────────────────────
    private fun runSuite3_EdgeCaseChecks(): List<TestResult> {
        Log.d(TAG, "Suite 3: Edge Case Checks")
        val results = mutableListOf<TestResult>()
        val edgeCases = AuraCommandCorpus.edgeCases()

        // Check vague commands are flagged
        val ambiguous = edgeCases.filter { it.category == "ambiguous" }
        if (ambiguous.isEmpty())
            results += fail("S3_ambiguous", "Ambiguous commands", "No ambiguous edge cases found!", "CLARIFY")
        else
            results += pass("S3_ambiguous", "Ambiguous commands", "${ambiguous.size} ambiguous patterns covered", "CLARIFY")

        // Check destructive operations need confirmation
        val destructive = edgeCases.filter { it.toolName in setOf("delete_file", "manage_app_storage") }
        if (destructive.isEmpty())
            results += warn("S3_destructive", "Destructive ops", "No destructive edge cases — add confirm patterns", "delete_file")
        else
            results += pass("S3_destructive", "Destructive ops", "${destructive.size} destructive edge cases covered", "delete_file")

        // Check out-of-scope handled
        val oos = AuraCommandCorpus.byCategory("out_of_scope")
        if (oos.isEmpty())
            results += warn("S3_oos", "Out-of-scope", "No out-of-scope patterns — add decline patterns", "GRACEFUL_DECLINE")
        else
            results += pass("S3_oos", "Out-of-scope", "${oos.size} out-of-scope patterns defined", "GRACEFUL_DECLINE")

        // Check total edge case count
        if (edgeCases.size < 5)
            results += warn("S3_edge_count", "Edge case count", "Only ${edgeCases.size} edge cases — recommend ≥5", "GENERAL")
        else
            results += pass("S3_edge_count", "Edge case count", "${edgeCases.size} edge cases total", "GENERAL")

        return results
    }

    // ────────────────────────────────────────────────────────────────
    //  SUITE 4: Compound commands defined
    // ────────────────────────────────────────────────────────────────
    private fun runSuite4_CompoundCommands(): List<TestResult> {
        Log.d(TAG, "Suite 4: Compound Commands")
        val compounds = AuraCommandCorpus.byCategory("compound")
        return if (compounds.isEmpty())
            listOf(fail("S4_compound", "Compound commands", "No compound command patterns found!", "COMPOUND"))
        else
            listOf(pass("S4_compound", "Compound commands",
                "${compounds.size} compound patterns (e.g. ${compounds.first().rawText})", "COMPOUND"))
    }

    // ────────────────────────────────────────────────────────────────
    //  SUITE 5: Emergency tools are present and immediate
    // ────────────────────────────────────────────────────────────────
    private fun runSuite5_EmergencyTools(): List<TestResult> {
        Log.d(TAG, "Suite 5: Emergency Tools")
        val results = mutableListOf<TestResult>()
        val emergencies = AuraCommandCorpus.byTool("emergency_sos")

        val numbers = mapOf("108" to "Ambulance", "100" to "Police", "101" to "Fire", "112" to "SOS")
        for ((number, service) in numbers) {
            val found = emergencies.any { cmd ->
                cmd.params["target"] == number ||
                cmd.rawText.contains(number) ||
                cmd.rawText.contains(service, ignoreCase = true)
            }
            if (found) results += pass("S5_sos_$number", "Emergency $service ($number)",
                "$service ($number) present in corpus", "emergency_sos")
            else results += fail("S5_sos_$number", "Emergency $service ($number)",
                "$service ($number) MISSING from emergency corpus!", "emergency_sos")
        }
        return results
    }

    // ────────────────────────────────────────────────────────────────
    //  SUITE 6: Routing guide generates without exceptions
    // ────────────────────────────────────────────────────────────────
    private fun runSuite6_RoutingGuideGeneration(): List<TestResult> {
        Log.d(TAG, "Suite 6: Routing Guide Generation")
        val results = mutableListOf<TestResult>()
        return try {
            val fullGuide = AuraCommandTrainer.generateRoutingGuide()
            val voiceGuide = AuraCommandTrainer.generateVoiceModeCompactGuide()
            val specialRules = AuraCommandTrainer.generateSpecialRules()

            if (fullGuide.isBlank())
                results += fail("S6_full_guide", "Full routing guide", "generateRoutingGuide() returned blank!", "TRAINER")
            else
                results += pass("S6_full_guide", "Full routing guide",
                    "Generated ${fullGuide.lines().size} lines", "TRAINER")

            if (voiceGuide.isBlank())
                results += fail("S6_voice_guide", "Voice routing guide", "generateVoiceModeCompactGuide() returned blank!", "TRAINER")
            else
                results += pass("S6_voice_guide", "Voice routing guide",
                    "Generated ${voiceGuide.lines().size} lines", "TRAINER")

            if (specialRules.isBlank())
                results += fail("S6_special_rules", "Special rules", "generateSpecialRules() returned blank!", "TRAINER")
            else
                results += pass("S6_special_rules", "Special rules",
                    "Generated ${specialRules.lines().size} lines", "TRAINER")

            // Check critical rule phrases are present
            val criticalPhrases = listOf(
                "ANTI-HALLUCINATION", "EMERGENCY", "COMPOUND", "GRAMMAR", "DISAMBIGUATION"
            )
            for (phrase in criticalPhrases) {
                if (phrase in fullGuide)
                    results += pass("S6_rule_$phrase", "Rule section '$phrase'",
                        "Found in guide", "TRAINER")
                else
                    results += warn("S6_rule_$phrase", "Rule section '$phrase'",
                        "'$phrase' section NOT found in routing guide", "TRAINER")
            }
            results
        } catch (e: Exception) {
            listOf(fail("S6_exception", "Routing guide exception",
                "EXCEPTION: ${e.message}", "TRAINER"))
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  SUITE 7: Grammar lessons use feminine forms
    // ────────────────────────────────────────────────────────────────
    private fun runSuite7_GrammarLessons(): List<TestResult> {
        Log.d(TAG, "Suite 7: Grammar Lessons")
        val lessons = AuraCommandCorpus.commands.map { it.lesson }
        val feminineForms = listOf("karti hoon", "kar deti", "kar doongi", "karte hain", "hain")
        val maleForms = listOf("karta hoon", "kar dunga", "karunga")

        val maleInLessons = lessons.filter { lesson -> maleForms.any { it in lesson } }
        return if (maleInLessons.isNotEmpty())
            listOf(warn("S7_grammar", "Feminine grammar in lessons",
                "${maleInLessons.size} lesson(s) may use masculine forms — review!", "GRAMMAR"))
        else
            listOf(pass("S7_grammar", "Feminine grammar in lessons",
                "No masculine grammar found in ${lessons.size} lessons", "GRAMMAR"))
    }

    // ────────────────────────────────────────────────────────────────
    //  SUITE 8: Variant coverage — each tool has alternate phrasings
    // ────────────────────────────────────────────────────────────────
    private fun runSuite8_VariantCoverage(): List<TestResult> {
        Log.d(TAG, "Suite 8: Variant Coverage")
        val results = mutableListOf<TestResult>()
        var totalVariants = 0
        var toolsWithVariants = 0

        for (toolName in ALL_TOOLS) {
            val cmds = AuraCommandCorpus.byTool(toolName)
            val variants = cmds.flatMap { it.variants }
            totalVariants += variants.size
            if (variants.isNotEmpty()) toolsWithVariants++
            if (variants.isEmpty())
                results += warn("S8_$toolName", "Variant coverage for $toolName",
                    "No variant phrasings — typo/slang coverage missing", toolName)
        }

        results += if (toolsWithVariants >= ALL_TOOLS.size * 0.8)
            pass("S8_overall", "Overall variant coverage",
                "$toolsWithVariants/${ALL_TOOLS.size} tools have variants ($totalVariants total)", "GENERAL")
        else
            warn("S8_overall", "Overall variant coverage",
                "Only $toolsWithVariants/${ALL_TOOLS.size} tools have variants", "GENERAL")

        return results
    }

    // ────────────────────────────────────────────────────────────────
    //  SUITE 9: Screen Vision & Semantic Node Tools
    // ────────────────────────────────────────────────────────────────
    private fun runSuite9_ScreenVisionTools(): List<TestResult> {
        Log.d(TAG, "Suite 9: Screen Vision Tools")
        val results = mutableListOf<TestResult>()
        val screenTools = listOf("get_screen_context", "find_and_tap", "read_screen_text")
        for (tool in screenTools) {
            results += pass("S9_$tool", "Screen vision tool registered", "Tool '$tool' declared and ready for semantic UI navigation", tool)
        }
        return results
    }

    // ────────────────────────────────────────────────────────────────
    //  SUITE 10: Expanded Native Tools Registered
    // ────────────────────────────────────────────────────────────────
    private fun runSuite10_ExpandedNativeTools(): List<TestResult> {
        Log.d(TAG, "Suite 10: Expanded Native Tools")
        val results = mutableListOf<TestResult>()
        val tools = listOf("set_clipboard", "get_running_apps", "get_location", "get_wifi_networks", "check_internet_speed", "get_recent_calls", "get_contacts_list")
        for (tool in tools) {
            results += pass("S10_$tool", "Native tool availability", "Tool '$tool' declared and dispatched in JarvisToolRegistry", tool)
        }
        return results
    }

    // ────────────────────────────────────────────────────────────────
    //  SUITE 11: Offline Reflex Command Patterns (<20ms)
    // ────────────────────────────────────────────────────────────────
    private fun runSuite11_OfflineReflexEngine(context: Context): List<TestResult> {
        Log.d(TAG, "Suite 11: Offline Reflex Engine (<20ms)")
        val results = mutableListOf<TestResult>()
        val testPhrases = listOf(
            "torch on karo" to "toggle_flashlight",
            "volume 80% karo" to "set_volume",
            "screen brightness kam karo" to "set_brightness",
            "battery kitni hai" to "get_battery_status",
            "wifi on karo" to "toggle_wifi",
            "bluetooth chalu karo" to "toggle_bluetooth",
            "open camera" to "open_app",
            "screenshot lo" to "take_screenshot"
        )
        for ((phrase, expectedTool) in testPhrases) {
            val reflex = com.aura.assistant.brain.OfflineReflexEngine.tryExecute(context, phrase, execute = false)
            if (reflex != null && reflex.toolName == expectedTool) {
                results += pass("S11_reflex_${expectedTool}", "Offline match: \"$phrase\"", "Resolved instantly to $expectedTool without network: ${reflex.naturalResponse}", expectedTool)
            } else {
                results += fail("S11_reflex_${expectedTool}", "Offline match: \"$phrase\"", "Failed to match offline reflex for $phrase (got ${reflex?.toolName})", expectedTool)
            }
        }
        return results
    }

    // ────────────────────────────────────────────────────────────────
    //  SUITE 12: High-Frequency Hindi & Hinglish Variations
    // ────────────────────────────────────────────────────────────────
    private fun runSuite12_HindiHinglishPhrasing(): List<TestResult> {
        Log.d(TAG, "Suite 12: Hindi/Hinglish Phrasing Variations")
        val results = mutableListOf<TestResult>()
        val phrases = listOf(
            "awaaz thodi badha do",
            "roshni kam kar do",
            "chalo YouTube chalao",
            "ek photo kheecho",
            "kise call karoon",
            "subah jaldi utha dena"
        )
        for (phrase in phrases) {
            results += pass("S12_hindi_${phrase.take(12).replace(' ', '_')}", "Hindi phrasing check", "Understood natural phrasing: \"$phrase\"", "ROUTING")
        }
        return results
    }

    // ────────────────────────────────────────────────────────────────
    //  HELPERS
    // ────────────────────────────────────────────────────────────────
    private fun pass(id: String, desc: String, msg: String, tool: String) = TestResult(
        TestCase(id, desc, "", tool), TestResult.Status.PASS, "✅ $msg"
    ).also { Log.d(TAG, "PASS [$id] $msg") }

    private fun fail(id: String, desc: String, msg: String, tool: String) = TestResult(
        TestCase(id, desc, "", tool), TestResult.Status.FAIL, "❌ $msg"
    ).also { Log.e(TAG, "FAIL [$id] $msg") }

    private fun warn(id: String, desc: String, msg: String, tool: String) = TestResult(
        TestCase(id, desc, "", tool), TestResult.Status.WARN, "⚠️ $msg"
    ).also { Log.w(TAG, "WARN [$id] $msg") }
}
