package com.aura.assistant.memory

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.google.gson.Gson
import java.util.UUID

// ── Entities ────────────────────────────────────────────────────────────

data class ExperienceRecord(
    val id: String = UUID.randomUUID().toString(),
    val taskId: String = "",
    val normalizedIntent: String,
    val userInput: String,
    val entities: String = "{}",
    val deviceProfile: String = "",
    val appPackage: String? = null,
    val screenContext: String? = null,
    val strategy: String = "DIRECT_TOOL",
    val tool: String,
    val arguments: String = "{}",
    val executionStatus: String,
    val verificationStatus: String,
    val failureReason: String? = null,
    val recoveryAction: String? = null,
    val latencyMs: Long = 0L,
    val userFeedback: String? = null, // "POSITIVE", "NEGATIVE", or user's exact corrective words
    val timestamp: Long = System.currentTimeMillis()
)

data class StrategyRanking(
    val id: String = UUID.randomUUID().toString(),
    val intent: String,
    val tool: String,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val avgLatencyMs: Long = 0L,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    val successRate: Float
        get() = if (successCount + failureCount == 0) 0.5f else successCount.toFloat() / (successCount + failureCount)
}

data class FailurePattern(
    val id: String = UUID.randomUUID().toString(),
    val tool: String,
    val errorSnippet: String,
    val rootCause: String,
    val occurrences: Int = 1,
    val lastSeen: Long = System.currentTimeMillis()
)

data class RecoveryPattern(
    val id: String = UUID.randomUUID().toString(),
    val failedTool: String,
    val fallbackTool: String,
    val condition: String,
    val successfulRecoveries: Int = 1
)

// ── DAOs ────────────────────────────────────────────────────────────────

class ExperienceDao(private val dbHelper: AuraExperienceDatabase.Helper) {

    fun insert(record: ExperienceRecord): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("id", record.id)
            put("task_id", record.taskId)
            put("normalized_intent", record.normalizedIntent)
            put("user_input", record.userInput)
            put("entities", record.entities)
            put("device_profile", record.deviceProfile)
            put("app_package", record.appPackage)
            put("screen_context", record.screenContext)
            put("strategy", record.strategy)
            put("tool", record.tool)
            put("arguments", record.arguments)
            put("execution_status", record.executionStatus)
            put("verification_status", record.verificationStatus)
            put("failure_reason", record.failureReason)
            put("recovery_action", record.recoveryAction)
            put("latency_ms", record.latencyMs)
            put("user_feedback", record.userFeedback)
            put("timestamp", record.timestamp)
        }
        return db.insertWithOnConflict("experience_records", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateFeedback(taskId: String, feedback: String, overridesSuccessToFail: Boolean): Int {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("user_feedback", feedback)
            if (overridesSuccessToFail) {
                put("verification_status", "FAILED_VERIFICATION")
                put("failure_reason", "User explicitly corrected: $feedback")
            }
        }
        if (taskId == "recent_task" || taskId.isBlank()) {
            // Update the most recent record in experience DB
            val recentIdQuery = "SELECT id FROM experience_records ORDER BY timestamp DESC LIMIT 1"
            var recentId: String? = null
            db.rawQuery(recentIdQuery, null).use { cursor ->
                if (cursor.moveToFirst()) {
                    recentId = cursor.getString(0)
                }
            }
            return if (recentId != null) {
                db.update("experience_records", values, "id = ?", arrayOf(recentId))
            } else {
                0
            }
        }
        return db.update("experience_records", values, "task_id = ? OR id = ?", arrayOf(taskId, taskId))
    }

    fun getRecentLessonsForPrompt(limit: Int = 15): List<ExperienceRecord> {
        val db = dbHelper.readableDatabase
        val records = mutableListOf<ExperienceRecord>()
        val query = """
            SELECT * FROM experience_records 
            ORDER BY CASE WHEN verification_status = 'FAILED_VERIFICATION' THEN 0 ELSE 1 END, timestamp DESC 
            LIMIT ?
        """.trimIndent()

        db.rawQuery(query, arrayOf(limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                records.add(cursorToRecord(cursor))
            }
        }
        return records
    }

    fun getRecordsForIntent(intent: String, limit: Int = 10): List<ExperienceRecord> {
        val db = dbHelper.readableDatabase
        val records = mutableListOf<ExperienceRecord>()
        db.query(
            "experience_records",
            null,
            "normalized_intent = ?",
            arrayOf(intent),
            null,
            null,
            "timestamp DESC",
            limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                records.add(cursorToRecord(cursor))
            }
        }
        return records
    }

    fun purgeStaleVerificationFailures(): Int {
        val db = dbHelper.writableDatabase
        return db.delete(
            "experience_records",
            "failure_reason LIKE '%action_dispatched_unverified%' OR failure_reason LIKE '%Verification check failed%'",
            null
        )
    }

    fun getHistoricalSuccessRates(): Map<String, Float> {
        val db = dbHelper.readableDatabase
        val rates = mutableMapOf<String, Float>()
        val query = """
            SELECT tool,
                   SUM(CASE WHEN verification_status = 'VERIFIED_SUCCESS' THEN 1 ELSE 0 END) as successes,
                   COUNT(*) as total
            FROM experience_records
            GROUP BY tool
        """.trimIndent()

        db.rawQuery(query, null).use { cursor ->
            while (cursor.moveToNext()) {
                val tool = cursor.getString(0)
                val successes = cursor.getInt(1)
                val total = cursor.getInt(2)
                rates[tool] = if (total > 0) successes.toFloat() / total else 0.5f
            }
        }
        return rates
    }

    private fun cursorToRecord(c: Cursor): ExperienceRecord {
        return ExperienceRecord(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            taskId = c.getString(c.getColumnIndexOrThrow("task_id")),
            normalizedIntent = c.getString(c.getColumnIndexOrThrow("normalized_intent")),
            userInput = c.getString(c.getColumnIndexOrThrow("user_input")),
            entities = c.getString(c.getColumnIndexOrThrow("entities")),
            deviceProfile = c.getString(c.getColumnIndexOrThrow("device_profile")),
            appPackage = c.getString(c.getColumnIndexOrThrow("app_package")),
            screenContext = c.getString(c.getColumnIndexOrThrow("screen_context")),
            strategy = c.getString(c.getColumnIndexOrThrow("strategy")),
            tool = c.getString(c.getColumnIndexOrThrow("tool")),
            arguments = c.getString(c.getColumnIndexOrThrow("arguments")),
            executionStatus = c.getString(c.getColumnIndexOrThrow("execution_status")),
            verificationStatus = c.getString(c.getColumnIndexOrThrow("verification_status")),
            failureReason = c.getString(c.getColumnIndexOrThrow("failure_reason")),
            recoveryAction = c.getString(c.getColumnIndexOrThrow("recovery_action")),
            latencyMs = c.getLong(c.getColumnIndexOrThrow("latency_ms")),
            userFeedback = c.getString(c.getColumnIndexOrThrow("user_feedback")),
            timestamp = c.getLong(c.getColumnIndexOrThrow("timestamp"))
        )
    }
}

// ── Database Manager ────────────────────────────────────────────────────

/**
 * Production Experience Database for AURA Agent Core v4.
 *
 * Implements persistent experience logging, user feedback correction,
 * strategy ranking, and dynamic prompt injection.
 * Fully inspectable via Android Studio Database Inspector (aura_experience.db).
 */
class AuraExperienceDatabase private constructor(context: Context) {

    companion object {
        private const val TAG = "AuraExperienceDB"
        private const val DB_NAME = "aura_experience.db"
        private const val DB_VERSION = 1

        @Volatile
        private var instance: AuraExperienceDatabase? = null

        fun getInstance(context: Context): AuraExperienceDatabase =
            instance ?: synchronized(this) {
                instance ?: AuraExperienceDatabase(context.applicationContext).also { instance = it }
            }
    }

    class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS experience_records (
                    id TEXT PRIMARY KEY,
                    task_id TEXT,
                    normalized_intent TEXT,
                    user_input TEXT,
                    entities TEXT,
                    device_profile TEXT,
                    app_package TEXT,
                    screen_context TEXT,
                    strategy TEXT,
                    tool TEXT,
                    arguments TEXT,
                    execution_status TEXT,
                    verification_status TEXT,
                    failure_reason TEXT,
                    recovery_action TEXT,
                    latency_ms INTEGER,
                    user_feedback TEXT,
                    timestamp INTEGER
                )
            """.trimIndent())

            db.execSQL("CREATE INDEX IF NOT EXISTS idx_exp_intent ON experience_records(normalized_intent)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_exp_tool ON experience_records(tool)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_exp_task ON experience_records(task_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_exp_verified ON experience_records(verification_status)")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS strategy_rankings (
                    id TEXT PRIMARY KEY,
                    intent TEXT,
                    tool TEXT,
                    success_count INTEGER,
                    failure_count INTEGER,
                    avg_latency_ms INTEGER,
                    last_updated INTEGER
                )
            """.trimIndent())

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS failure_patterns (
                    id TEXT PRIMARY KEY,
                    tool TEXT,
                    error_snippet TEXT,
                    root_cause TEXT,
                    occurrences INTEGER,
                    last_seen INTEGER
                )
            """.trimIndent())

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS recovery_patterns (
                    id TEXT PRIMARY KEY,
                    failed_tool TEXT,
                    fallback_tool TEXT,
                    condition TEXT,
                    successful_recoveries INTEGER
                )
            """.trimIndent())
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Future migrations
        }
    }

    private val helper = Helper(context)
    val experienceDao = ExperienceDao(helper)

    /**
     * Records execution outcome into Experience Database.
     */
    fun recordExecution(record: ExperienceRecord) {
        try {
            experienceDao.insert(record)
            Log.d(TAG, "💾 Recorded Experience: [${record.tool}] -> ${record.verificationStatus}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record experience", e)
        }
    }

    /**
     * Records explicit user feedback (e.g. user saying "Nahi gaya").
     */
    fun recordUserFeedback(taskId: String, feedbackText: String, isSuccess: Boolean) {
        try {
            val updated = experienceDao.updateFeedback(
                taskId = taskId,
                feedback = feedbackText,
                overridesSuccessToFail = !isSuccess
            )
            Log.d(TAG, "💬 Updated User Feedback for $taskId: \"$feedbackText\" (success=$isSuccess, rows=$updated)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record user feedback", e)
        }
    }

    /**
     * Formats lessons for system prompt injection.
     */
    fun getLessonsForPrompt(limit: Int = 15): String {
        return try {
            val records = experienceDao.getRecentLessonsForPrompt(limit)
            if (records.isEmpty()) return ""

            val sb = StringBuilder()
            sb.appendLine("\n📚 ISHA LEARNED EXPERIENCES & LESSONS (PERSISTENT DB — ADAPTIVE MEMORY):")
            for (r in records) {
                val icon = if (r.verificationStatus == "VERIFIED_SUCCESS") "✅" else "⚠️"
                val feedbackNote = if (!r.userFeedback.isNullOrBlank()) " (User said: \"${r.userFeedback}\")" else ""
                val reason = if (!r.failureReason.isNullOrBlank()) " -> Note: ${r.failureReason}" else ""
                sb.appendLine("$icon [${r.tool}] Intent: \"${r.normalizedIntent}\"$reason$feedbackNote")
            }
            sb.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to format lessons for prompt", e)
            ""
        }
    }

    fun purgeStaleVerificationFailures(): Int {
        return try {
            val count = experienceDao.purgeStaleVerificationFailures()
            Log.i(TAG, "Purged $count stale unverified records from experience DB")
            count
        } catch (e: Exception) {
            Log.w(TAG, "Error purging stale unverified records: ${e.message}")
            0
        }
    }
}
