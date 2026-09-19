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
 * Local persistent storage repository for ChatGPT-style conversations.
 * Stores sessions and chat message history in internal JSON files.
 */
class AuraChatRepository(context: Context) {

    companion object {
        private const val TAG = "AuraChatRepo"
        private const val SESSIONS_FILE = "chat_sessions.json"
        private const val CHATS_DIR = "aura_chats"
    }

    private val gson = Gson()
    private val rootDir = File(context.filesDir, CHATS_DIR).apply { if (!exists()) mkdirs() }
    private val sessionsFile = File(rootDir, SESSIONS_FILE)

    @Synchronized
    fun getAllSessions(): MutableList<ChatSession> {
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

        val sessionMsgFile = File(rootDir, "session_$sessionId.json")
        if (sessionMsgFile.exists()) {
            sessionMsgFile.delete()
        }
    }

    @Synchronized
    fun clearAllSessions(): ChatSession {
        val defaultSession = ChatSession(title = "New Chat")
        saveSessions(mutableListOf(defaultSession))
        rootDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("session_")) {
                file.delete()
            }
        }
        return defaultSession
    }

    @Synchronized
    private fun saveSessions(sessions: List<ChatSession>) {
        try {
            sessionsFile.writeText(gson.toJson(sessions))
        } catch (e: Exception) {
            Log.e(TAG, "Error saving sessions", e)
        }
    }

    @Synchronized
    fun getMessages(sessionId: String): MutableList<ChatMessage> {
        val file = File(rootDir, "session_$sessionId.json")
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
            val file = File(rootDir, "session_$sessionId.json")
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
}
