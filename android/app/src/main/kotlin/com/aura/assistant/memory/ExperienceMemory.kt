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
 * Stores lessons learned from past tool successes and failures
 * so AURA never repeats the same mistake twice.
 */
object ExperienceMemory {
    private const val PREFS_NAME = "aura_experience_memory"
    private const val KEY_LESSONS = "execution_lessons"
    private val gson = Gson()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Records a lesson from an execution outcome.
     */
    fun recordLesson(context: Context, toolName: String, success: Boolean, lesson: String) {
        val currentLessons = getLessons(context).toMutableList()
        // Remove older lessons for the same tool if more than 3 exist
        val existingToolLessons = currentLessons.filter { it.toolName == toolName }
        if (existingToolLessons.size >= 3) {
            currentLessons.remove(existingToolLessons.first())
        }
        currentLessons.add(ExecutionLesson(toolName, success, lesson))
        saveLessons(context, currentLessons)
    }

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

    private fun saveLessons(context: Context, lessons: List<ExecutionLesson>) {
        val json = gson.toJson(lessons)
        getPrefs(context).edit().putString(KEY_LESSONS, json).apply()
    }

    /**
     * Formats lessons for system prompt injection.
     */
    fun formatLessonsForPrompt(context: Context): String {
        val lessons = getLessons(context)
        if (lessons.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("\n📚 AURA'S LEARNED EXPERIENCES & LESSONS (DO NOT REPEAT MISTAKES):")
        for (l in lessons.takeLast(10)) {
            val statusIcon = if (l.success) "✅" else "⚠️"
            sb.appendLine("$statusIcon [${l.toolName}]: ${l.lesson}")
        }
        return sb.toString()
    }
}
