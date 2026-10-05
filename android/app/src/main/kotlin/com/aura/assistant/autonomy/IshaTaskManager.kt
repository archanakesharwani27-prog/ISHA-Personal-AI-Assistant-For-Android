package com.aura.assistant.autonomy

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

enum class TaskState {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class IshaTask(
    val id: String = UUID.randomUUID().toString(),
    val goal: String,
    val toolName: String,
    val argumentsJson: String = "{}",
    val state: TaskState = TaskState.QUEUED,
    val scheduledAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis()
)

typealias AuraTask = IshaTask

/**
 * Manages background long-running tasks and reminders without maintaining persistent WebSocket connections.
 *
 * FUTURE SCAFFOLDING — Architecturally complete but not yet wired to active execution.
 * Integration steps:
 *   1. Wire IshaAssistantViewModel to call scheduleTask() when Gemini requests deferred execution
 *   2. Create a WorkManager-backed IshaTaskWorker that polls getTasks() and dispatches via ExecutionEngine
 *   3. Call updateTaskState() with COMPLETED/FAILED after each worker execution
 */
object IshaTaskManager {
    private const val TAG = "IshaTaskManager"
    private const val PREFS_NAME = "isha_tasks_vault"
    private const val KEY_TASKS = "tasks_list"
    private val gson = Gson()

    fun scheduleTask(context: Context, goal: String, toolName: String, argsJson: String, delayMs: Long): IshaTask {
        val task = IshaTask(
            goal = goal,
            toolName = toolName,
            argumentsJson = argsJson,
            scheduledAt = System.currentTimeMillis() + delayMs
        )
        val current = getTasks(context).toMutableList()
        current.add(task)
        saveTasks(context, current)
        Log.i(TAG, "Scheduled background task '${task.goal}' for +${delayMs}ms")
        return task
    }

    fun getTasks(context: Context): List<IshaTask> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_TASKS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<IshaTask>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun updateTaskState(context: Context, taskId: String, newState: TaskState) {
        val current = getTasks(context).map {
            if (it.id == taskId) it.copy(state = newState) else it
        }
        saveTasks(context, current)
    }

    private fun saveTasks(context: Context, tasks: List<IshaTask>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_TASKS, gson.toJson(tasks)).apply()
    }
}

/** Backward compatibility alias for AuraTaskManager */
val AuraTaskManager = IshaTaskManager

