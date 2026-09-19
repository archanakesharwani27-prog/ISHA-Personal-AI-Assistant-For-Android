package com.aura.assistant.training

import android.content.Context
import android.util.Log
import com.aura.assistant.memory.ExperienceMemory

/**
 * =========================================================
 *  AuraTrainingEngine  v3.0
 *
 *  Seeds AURA's long-term ExperienceMemory with pre-built lessons
 *  from AuraCommandCorpus so that from the very first run AURA
 *  already "knows" how to handle every category of user command.
 *
 *  Run once on first launch (or when version bumps) via:
 *      AuraTrainingEngine.seedIfNeeded(context)
 * =========================================================
 */
object AuraTrainingEngine {

    private const val TAG = "AuraTrainingEngine"
    private const val PREFS_NAME = "aura_training_engine"
    private const val KEY_SEEDED_VERSION = "seeded_corpus_version"

    /** Bump this whenever the corpus is significantly updated. */
    private const val CORPUS_VERSION = 3

    /**
     * Seeds ExperienceMemory with corpus lessons if not yet seeded
     * for the current corpus version.  Safe to call on every startup —
     * it is a no-op after the first successful seed for this version.
     */
    fun seedIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val seededVersion = prefs.getInt(KEY_SEEDED_VERSION, 0)

        if (seededVersion >= CORPUS_VERSION) {
            Log.d(TAG, "Corpus v$CORPUS_VERSION already seeded — skipping.")
            return
        }

        Log.i(TAG, "Seeding ExperienceMemory from AuraCommandCorpus v$CORPUS_VERSION …")
        seedLessons(context)

        prefs.edit().putInt(KEY_SEEDED_VERSION, CORPUS_VERSION).apply()
        Log.i(TAG, "✅ Seeding complete. ${AuraCommandCorpus.commands.size} command patterns loaded.")
    }

    /**
     * Forces a full re-seed regardless of version.
     * Use only for debugging / testing.
     */
    fun forceSeed(context: Context) {
        Log.w(TAG, "Force-seeding ExperienceMemory …")
        seedLessons(context)
    }

    // ── Internal ─────────────────────────────────────────────────────

    private fun seedLessons(context: Context) {
        val lessonsMap = AuraCommandCorpus.lessonsMap()
        var total = 0

        for ((toolName, lessons) in lessonsMap) {
            // Skip meta-tokens that are not real tools
            if (toolName in NON_TOOL_TOKENS) continue

            // Deduplicate lessons for this tool, keep the richest ones
            val uniqueLessons = lessons.distinct().take(MAX_LESSONS_PER_TOOL)

            for (lesson in uniqueLessons) {
                // success=true for standard patterns, false for edge-case warnings
                val isEdgeCase = AuraCommandCorpus.commands.any {
                    it.toolName == toolName && it.lesson == lesson && it.isEdgeCase
                }
                ExperienceMemory.recordLesson(
                    context = context,
                    toolName = toolName,
                    success = !isEdgeCase,
                    lesson = lesson
                )
                total++
            }
        }

        // Seed compound-command lessons separately
        seedCompoundLessons(context)

        Log.i(TAG, "Seeded $total lessons across ${lessonsMap.size} tools/categories.")
    }

    private fun seedCompoundLessons(context: Context) {
        val compoundLessons = listOf(
            "Compound commands with '+' in toolName must be executed SEQUENTIALLY. Never skip the second step.",
            "When user says 'X karo aur Y karo', call both tools one after the other in order.",
            "For compound commands, report completion only after ALL steps succeed.",
            "If any step in a compound command fails, report the failure immediately and do not continue blindly.",
        )
        for (lesson in compoundLessons) {
            ExperienceMemory.recordLesson(context, "COMPOUND", true, lesson)
        }

        val clarifyLessons = listOf(
            "When user command is ambiguous (single word like 'Band karo', 'Chalu karo'), ALWAYS ask for clarification before acting.",
            "Never guess the intent when a vague command could map to multiple tools — ask the user.",
            "Short filler words like 'yaar', 'bhai', 'na', 'toh', 'arey' are conversational — strip them to find core intent.",
        )
        for (lesson in clarifyLessons) {
            ExperienceMemory.recordLesson(context, "CLARIFY", false, lesson)
        }

        val safetyLessons = listOf(
            "CAUTION: Factory reset, data wipe, or format commands require TRIPLE explicit user confirmation. Never execute blindly.",
            "Destructive actions (delete, wipe, uninstall) require at least one confirmation before execution.",
            "NEVER hallucinate a successful execution. If a tool hasn't run, do not claim the action was done.",
            "For emergency keywords (Ambulance, Police, Fire, SOS, 112), call emergency_sos IMMEDIATELY without delay.",
            "Never call make_call with a hallucinated phone number. If no number/contact given, ask the user.",
        )
        for (lesson in safetyLessons) {
            ExperienceMemory.recordLesson(context, "SAFETY", false, lesson)
        }

        val antiHallucinationLessons = listOf(
            "Battery level: ALWAYS call get_battery_status. NEVER say a percentage you made up.",
            "Volume/Brightness: ALWAYS call the respective tool. Never pretend it was set without calling.",
            "WhatsApp/SMS: ALWAYS invoke the send tool. Never say 'bhej diya' without tool execution.",
            "For ANY hardware action, tool execution is MANDATORY. Text confirmation without tool call = hallucination = forbidden.",
        )
        for (lesson in antiHallucinationLessons) {
            ExperienceMemory.recordLesson(context, "ANTI_HALLUCINATION", false, lesson)
        }

        val grammarLessons = listOf(
            "ALWAYS use feminine Hindi grammar: 'karti hoon', 'kar doongi', 'samajh gayi', 'bata rahi hoon'.",
            "NEVER use male grammar: 'karta hoon', 'kar dunga', 'samajh gaya', 'karunga' — these are FORBIDDEN.",
            "After successful tool execution, respond in warm conversational Hindi/Hinglish — never say raw 'status: success'.",
        )
        for (lesson in grammarLessons) {
            ExperienceMemory.recordLesson(context, "GRAMMAR", true, lesson)
        }
    }

    /** Token values in toolName that are not real Android tools. */
    private val NON_TOOL_TOKENS = setOf(
        "CONVERSATIONAL", "CLARIFY", "GRACEFUL_DECLINE", "CAUTION",
        "CONFIRMATIONAL", "COMPOUND"
    )

    private const val MAX_LESSONS_PER_TOOL = 5

    // ── Statistics ───────────────────────────────────────────────────

    /**
     * Returns a human-readable summary of the corpus and current
     * ExperienceMemory state.
     */
    fun diagnosticsReport(context: Context): String = buildString {
        appendLine("═══════════════════════════════════════════")
        appendLine("   AURA Training Engine Diagnostics v$CORPUS_VERSION")
        appendLine("═══════════════════════════════════════════")
        appendLine(AuraCommandCorpus.stats())
        appendLine()

        val lessons = ExperienceMemory.getLessons(context)
        appendLine("📚 ExperienceMemory:")
        appendLine("  Total lessons stored : ${lessons.size}")
        val byTool = lessons.groupBy { it.toolName }
        appendLine("  Tools with lessons   : ${byTool.size}")
        for ((tool, tls) in byTool.entries.take(10)) {
            appendLine("    [$tool] → ${tls.size} lesson(s)")
        }
        if (byTool.size > 10) appendLine("    … and ${byTool.size - 10} more tools")
        appendLine()
        appendLine("  Categories in corpus :")
        AuraCommandCorpus.commands
            .groupBy { it.category }
            .entries
            .sortedByDescending { it.value.size }
            .forEach { (cat, cmds) ->
                appendLine("    [$cat] → ${cmds.size} commands (${cmds.count { it.isEdgeCase }} edge cases)")
            }
        appendLine("═══════════════════════════════════════════")
    }

    /**
     * Returns all raw command texts for a given category —
     * useful for in-app testing / QA.
     */
    fun sampleCommands(category: String, limit: Int = 5): List<String> =
        AuraCommandCorpus.byCategory(category).take(limit).map { it.rawText }

    /**
     * Returns all edge-case command texts across the corpus.
     */
    fun allEdgeCases(): List<String> =
        AuraCommandCorpus.edgeCases().map { it.rawText }
}
