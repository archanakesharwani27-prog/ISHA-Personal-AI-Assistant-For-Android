package com.aura.assistant.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "New Chat",
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
)

enum class MessageRole {
    USER,
    ASSISTANT,
    TOOL
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val role: MessageRole,
    var text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val toolName: String? = null,
    val toolArgs: String? = null,
    val toolResult: String? = null,
    var isStreaming: Boolean = false,
    val imageUri: String? = null
)

/**
 * Local persistent storage repository for conversations.
 * Stores sessions and chat message history in internal JSON files.
 */
class IshaChatRepository(private val context: Context, initialUserId: String = "guest") {

    companion object {
        private const val TAG = "IshaChatRepo"
        private const val SESSIONS_FILE = "chat_sessions.json"
        private const val CHATS_DIR = "isha_chats"
    }

    private val gson = Gson()
    @Volatile private var currentUserId: String = sanitizeUserId(initialUserId)

    private fun sanitizeUserId(uid: String): String {
        val clean = uid.trim().lowercase()
        return if (clean.isBlank()) "guest" else clean.replace(Regex("[^a-zA-Z0-9_]"), "_")
    }

    private fun getUserDir(): File {
        val dir = File(context.filesDir, "$CHATS_DIR/user_$currentUserId")
        if (!dir.exists()) {
            dir.mkdirs()
            // Auto-migrate legacy root sessions if this is the first user directory
            migrateLegacySessionsIfNeeded(dir)
        }
        return dir
    }

    private fun getSessionsFile(): File = File(getUserDir(), SESSIONS_FILE)

    private fun migrateLegacySessionsIfNeeded(targetUserDir: File) {
        try {
            val legacyRootDir = File(context.filesDir, CHATS_DIR)
            val legacySessionsFile = File(legacyRootDir, SESSIONS_FILE)
            if (legacySessionsFile.exists()) {
                val targetFile = File(targetUserDir, SESSIONS_FILE)
                if (!targetFile.exists()) {
                    legacySessionsFile.copyTo(targetFile, overwrite = true)
                    legacyRootDir.listFiles()?.forEach { f ->
                        if (f.name.startsWith("session_") && f.name.endsWith(".json")) {
                            f.copyTo(File(targetUserDir, f.name), overwrite = true)
                        }
                    }
                    Log.i(TAG, "Migrated legacy chats to user directory: ${targetUserDir.name}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Legacy migration error", e)
        }
    }

    @Synchronized
    fun switchUser(userId: String) {
        val clean = sanitizeUserId(userId)
        if (clean != currentUserId) {
            Log.i(TAG, "Switching chat partition: $currentUserId -> $clean")
            currentUserId = clean
            getUserDir() // ensure directory exists
        }
    }

    @Synchronized
    fun getCurrentUserId(): String = currentUserId

    @Synchronized
    fun getAllSessions(): MutableList<ChatSession> {
        val sessionsFile = getSessionsFile()
        return try {
            if (!sessionsFile.exists()) {
                val defaultSession = ChatSession(title = "New Chat")
                val list = mutableListOf(defaultSession)
                saveSessions(list)
                list
            } else {
                val json = sessionsFile.readText()
                val type = object : TypeToken<MutableList<ChatSession>>() {}.type
                val list: MutableList<ChatSession>? = gson.fromJson(json, type)
                if (list.isNullOrEmpty()) {
                    val defaultSession = ChatSession(title = "New Chat")
                    val newList = mutableListOf(defaultSession)
                    saveSessions(newList)
                    newList
                } else {
                    list.sortByDescending { it.updatedAt }
                    list
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading sessions", e)
            mutableListOf(ChatSession(title = "New Chat"))
        }
    }

    @Synchronized
    fun createSession(title: String = "New Chat"): ChatSession {
        val sessions = getAllSessions()
        val newSession = ChatSession(title = title)
        sessions.add(0, newSession)
        saveSessions(sessions)
        return newSession
    }

    @Synchronized
    fun renameSession(sessionId: String, newTitle: String) {
        val sessions = getAllSessions()
        val session = sessions.find { it.id == sessionId }
        if (session != null) {
            session.title = newTitle.trim()
            session.updatedAt = System.currentTimeMillis()
            saveSessions(sessions)
        }
    }

    @Synchronized
    fun deleteSession(sessionId: String) {
        val sessions = getAllSessions()
        sessions.removeAll { it.id == sessionId }
        if (sessions.isEmpty()) {
            sessions.add(ChatSession(title = "New Chat"))
        }
        saveSessions(sessions)

        val sessionMsgFile = File(getUserDir(), "session_$sessionId.json")
        if (sessionMsgFile.exists()) {
            sessionMsgFile.delete()
        }
    }

    @Synchronized
    fun clearAllSessions(): ChatSession {
        val defaultSession = ChatSession(title = "New Chat")
        saveSessions(mutableListOf(defaultSession))
        getUserDir().listFiles()?.forEach { file ->
            if (file.name.startsWith("session_")) {
                file.delete()
            }
        }
        return defaultSession
    }

    @Synchronized
    private fun saveSessions(sessions: List<ChatSession>) {
        try {
            getSessionsFile().writeText(gson.toJson(sessions))
        } catch (e: Exception) {
            Log.e(TAG, "Error saving sessions", e)
        }
    }

    @Synchronized
    fun getMessages(sessionId: String): MutableList<ChatMessage> {
        val file = File(getUserDir(), "session_$sessionId.json")
        return try {
            if (!file.exists()) {
                mutableListOf()
            } else {
                val json = file.readText()
                val type = object : TypeToken<MutableList<ChatMessage>>() {}.type
                gson.fromJson(json, type) ?: mutableListOf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading messages for session $sessionId", e)
            mutableListOf()
        }
    }

    @Synchronized
    fun saveMessages(sessionId: String, messages: List<ChatMessage>) {
        try {
            val file = File(getUserDir(), "session_$sessionId.json")
            file.writeText(gson.toJson(messages))

            // Update session timestamp
            val sessions = getAllSessions()
            sessions.find { it.id == sessionId }?.let { session ->
                session.updatedAt = System.currentTimeMillis()
                // Auto-title if it's currently "New Chat" and user sent first message
                if (session.title == "New Chat") {
                    val firstUserMsg = messages.firstOrNull { it.role == MessageRole.USER }
                    if (firstUserMsg != null) {
                        val preview = firstUserMsg.text.take(30).trim()
                        session.title = if (firstUserMsg.text.length > 30) "$preview..." else preview
                    }
                }
                saveSessions(sessions)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving messages for session $sessionId", e)
        }
    }

    /**
     * Cross-Session Context Recall: Searches prior conversations for keywords or topics.
     */
    @Synchronized
    fun searchPastConversations(query: String, maxResults: Int = 3): List<String> {
        val results = mutableListOf<String>()
        val terms = query.lowercase().split(Regex("[\\s,_?.!\\-]+")).filter { it.length > 2 }
        if (terms.isEmpty()) return emptyList()

        val allSessions = getAllSessions()
        for (session in allSessions) {
            val msgs = getMessages(session.id)
            for (msg in msgs) {
                val lower = msg.text.lowercase()
                if (terms.any { lower.contains(it) }) {
                    val prefix = if (msg.role == MessageRole.USER) "Boss" else "Isha"
                    results.add("[Session: ${session.title}] $prefix: \"${msg.text.take(120)}\"")
                    if (results.size >= maxResults) return results
                }
            }
        }
        return results
    }
}

/** Backward compatibility alias for AuraChatRepository */
typealias AuraChatRepository = IshaChatRepository

