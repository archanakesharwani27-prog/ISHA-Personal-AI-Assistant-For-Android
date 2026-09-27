package com.aura.assistant.memory

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class ExecutionLesson(
    val toolName: String,
    val success: Boolean,
    val lesson: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Long-term autonomous experience memory for AURA 2.0.
 *
 * Stores lessons learned from:
 *  - Real tool execution outcomes (success / failure)
 *  - Pre-seeded corpus wisdom from AuraTrainingEngine
 *
 * AURA never repeats the same mistake twice.
 * Lessons are injected into the system prompt on every session.
 */
object ExperienceMemory {

    private const val TAG = "ExperienceMemory"
    private const val PREFS_NAME = "aura_experience_memory"
    private const val KEY_LESSONS = "execution_lessons"

    /**
     * Max lessons to keep in memory total.
     * Increased from 3-per-tool to support pre-seeded corpus.
     */
    private const val MAX_LESSONS_PER_TOOL = 5
    private const val MAX_TOTAL_LESSONS = 200
    private const val MAX_PROMPT_LESSONS = 15   // how many to inject per session

    private val gson = Gson()

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Write ─────────────────────────────────────────────────────────

    /**
     * Records a lesson from a tool execution outcome or training corpus seed.
     * Deduplicates and respects per-tool + total capacity limits.
     */
    fun recordLesson(context: Context, toolName: String, success: Boolean, lesson: String) {
        if (lesson.isBlank() || toolName.isBlank()) return

        val allLessons = getLessons(context).toMutableList()

        // Avoid exact duplicates
        if (allLessons.any { it.toolName == toolName && it.lesson == lesson }) return

        // Per-tool capacity: remove oldest when tool has too many
        val toolLessons = allLessons.filter { it.toolName == toolName }
        if (toolLessons.size >= MAX_LESSONS_PER_TOOL) {
            allLessons.remove(toolLessons.first())
        }

        // Global capacity: remove oldest overall when full
        if (allLessons.size >= MAX_TOTAL_LESSONS) {
            allLessons.removeAt(0)
        }

        allLessons.add(ExecutionLesson(toolName, success, lesson))
        saveLessons(context, allLessons)

        // Asynchronously persist to AuraExperienceDatabase for rich persistence and inspection
        try {
            AuraExperienceDatabase.getInstance(context).recordExecution(
                ExperienceRecord(
                    normalizedIntent = toolName,
                    userInput = lesson,
                    tool = toolName,
                    executionStatus = if (success) "SUCCESS" else "FAILED",
                    verificationStatus = if (success) "VERIFIED_SUCCESS" else "FAILED_VERIFICATION",
                    failureReason = if (!success) lesson else null
                )
            )
        } catch (_: Exception) {}
    }

    /**
     * Batch-records multiple lessons at once (used by AuraTrainingEngine seeding).
     * More efficient than individual calls.
     */
    fun recordLessonsBatch(
        context: Context,
        entries: List<Triple<String, Boolean, String>>
    ) {
        val allLessons = getLessons(context).toMutableList()

        for ((toolName, success, lesson) in entries) {
            if (lesson.isBlank() || toolName.isBlank()) continue
            if (allLessons.any { it.toolName == toolName && it.lesson == lesson }) continue

            val toolLessons = allLessons.filter { it.toolName == toolName }
            if (toolLessons.size >= MAX_LESSONS_PER_TOOL) {
                allLessons.remove(toolLessons.first())
            }
            if (allLessons.size >= MAX_TOTAL_LESSONS) {
                allLessons.removeAt(0)
            }
            allLessons.add(ExecutionLesson(toolName, success, lesson))
        }

        saveLessons(context, allLessons)
    }

    // ── Read ──────────────────────────────────────────────────────────

    /**
     * Retrieves all recorded execution lessons.
     */
    fun getLessons(context: Context): List<ExecutionLesson> {
        val json = getPrefs(context).getString(KEY_LESSONS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ExecutionLesson>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Returns lessons for a specific tool name.
     */
    fun getLessonsForTool(context: Context, toolName: String): List<ExecutionLesson> =
        getLessons(context).filter { it.toolName == toolName }

    /**
     * Total number of lessons stored.
     */
    fun lessonCount(context: Context): Int = getLessons(context).size

    /**
     * Summary statistics for diagnostics.
     */
    fun stats(context: Context): String {
        val lessons = getLessons(context)
        val byTool = lessons.groupBy { it.toolName }
        return "ExperienceMemory: ${lessons.size} lessons across ${byTool.size} tools. " +
               "Success: ${lessons.count { it.success }}, Warnings: ${lessons.count { !it.success }}."
    }

    // ── Wipe / Cleanup ────────────────────────────────────────────────
    
    /**
     * Purges stale false negative verification records (action_dispatched_unverified)
     * from both SharedPreferences memory and SQLite database.
     */
    fun purgeStaleVerificationFailures(context: Context) {
        try {
            val all = getLessons(context).filter {
                !it.lesson.contains("action_dispatched_unverified", ignoreCase = true)
            }
            saveLessons(context, all)
            AuraExperienceDatabase.getInstance(context).purgeStaleVerificationFailures()
        } catch (_: Exception) {}
    }

    /**
     * Clears all lessons. Use only with explicit user confirmation.
     */
    fun clearAll(context: Context) {
        getPrefs(context).edit().remove(KEY_LESSONS).apply()
    }

    // ── Prompt Injection ──────────────────────────────────────────────

    /**
     * Formats the most important lessons for injection into the system prompt.
     *
     * Strategy:
     *  - Failures/warnings first (most critical to avoid re-making mistakes)
     *  - Successes / patterns second
     *  - Capped at MAX_PROMPT_LESSONS to keep prompt concise
     */
    fun formatLessonsForPrompt(context: Context): String {
        // Auto-sanitize any past false negative verification logs before building prompt
        purgeStaleVerificationFailures(context)

        val dbLessons = try {
            AuraExperienceDatabase.getInstance(context).getLessonsForPrompt(MAX_PROMPT_LESSONS)
        } catch (_: Exception) { "" }

        if (dbLessons.isNotBlank()) {
            return dbLessons
        }

        val allLessons = getLessons(context)
        if (allLessons.isEmpty()) return ""

        // Prioritise: warnings first, then successes; take the most recent
        val warnings = allLessons.filter { !it.success }.takeLast(MAX_PROMPT_LESSONS / 2)
        val successes = allLessons.filter { it.success }.takeLast(MAX_PROMPT_LESSONS / 2)
        val selected = (warnings + successes).distinctBy { it.lesson }.take(MAX_PROMPT_LESSONS)

        if (selected.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("\n📚 ISHA'S LEARNED EXPERIENCES & LESSONS (IN_MEMORY — DO NOT REPEAT MISTAKES):")
        for (l in selected) {
            val icon = if (l.success) "✅" else "⚠️"
            sb.appendLine("$icon [${l.toolName}]: ${l.lesson}")
        }
        return sb.toString()
    }

    // ── Private ───────────────────────────────────────────────────────

    private fun saveLessons(context: Context, lessons: List<ExecutionLesson>) {
        val json = gson.toJson(lessons)
        getPrefs(context).edit().putString(KEY_LESSONS, json).apply()
    }
}
