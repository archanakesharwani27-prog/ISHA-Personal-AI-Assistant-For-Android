package com.aura.assistant.sync

import android.content.Context
import android.util.Log
import com.aura.assistant.ai.IshaContactEntry
import com.aura.assistant.ai.IshaContactMemoryManager
import com.aura.assistant.auth.IshaAuthManager
import com.aura.assistant.data.ChatMessage
import com.aura.assistant.data.ChatSession
import com.aura.assistant.data.IshaChatRepository
import com.aura.assistant.data.MessageRole
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Real-Time Multi-Device Cloud Synchronization Bridge for ISHA.
 *
 * Ensures:
 * 1. Phone A saved contacts and number history replicate instantly to Phone B.
 * 2. Conversations on Phone A appear on Phone B when logged into the same account.
 * 3. Offline-First: Works with local file vault; syncs transparently when internet is available.
 */
object IshaCloudSyncBridge {

    private const val TAG = "IshaCloudSyncBridge"
    private var contactsListener: ListenerRegistration? = null
    private var activeSessionListener: ListenerRegistration? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        val app = context.applicationContext
        isInitialized = true
        startContactsListener(app)

        scope.launch {
            IshaAuthManager.sessionState.collect { user ->
                if (user.isLoggedIn && user.uid.isNotBlank()) {
                    startContactsListener(app)
                }
            }
        }
    }

    /**
     * Pushes a locally created or updated contact to Cloud Firestore.
     */
    fun syncContactToCloud(context: Context, entry: IshaContactEntry) {
        val userId = IshaAuthManager.sessionState.value.uid
        if (userId.isBlank()) return

        scope.launch {
            try {
                val firestore = FirebaseFirestore.getInstance()
                val data = hashMapOf<String, Any>(
                    "id" to entry.id,
                    "name" to entry.name,
                    "normalizedName" to entry.normalizedName,
                    "primaryNumber" to entry.primaryNumber,
                    "previousNumbers" to entry.previousNumbers,
                    "note" to entry.note,
                    "createdAt" to entry.createdAt,
                    "updatedAt" to entry.updatedAt,
                    "syncedAt" to FieldValue.serverTimestamp()
                )

                firestore.collection("users")
                    .document(userId)
                    .collection("contacts")
                    .document(entry.normalizedName)
                    .set(data, SetOptions.merge())
                    .addOnSuccessListener {
                        Log.d(TAG, "Synced contact to cloud: ${entry.name}")
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Failed to sync contact ${entry.name}: ${e.message}")
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Cloud contact sync error", e)
            }
        }
    }

    /**
     * Listens for remote contact additions / updates and syncs them into local memory.
     */
    fun startContactsListener(context: Context) {
        val userId = IshaAuthManager.sessionState.value.uid
        if (userId.isBlank()) return

        try {
            contactsListener?.remove()
            val firestore = FirebaseFirestore.getInstance()

            contactsListener = firestore.collection("users")
                .document(userId)
                .collection("contacts")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Error in contacts listener: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null && !snapshot.isEmpty) {
                        for (doc in snapshot.documents) {
                            try {
                                val name = doc.getString("name") ?: ""
                                val primaryNumber = doc.getString("primaryNumber") ?: ""
                                val previousNumbers = (doc.get("previousNumbers") as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                                val note = doc.getString("note") ?: ""
                                val updatedAt = doc.getLong("updatedAt") ?: System.currentTimeMillis()

                                if (name.isNotBlank() && primaryNumber.isNotBlank()) {
                                    val localContact = IshaContactMemoryManager.getContactByName(context, name)
                                    // If contact doesn't exist or cloud version is newer, merge locally
                                    if (localContact == null || updatedAt > localContact.updatedAt) {
                                        IshaContactMemoryManager.rememberOrUpdateContact(
                                            context = context,
                                            rawName = name,
                                            rawNumber = primaryNumber,
                                            note = note
                                        )
                                        Log.i(TAG, "Merged remote contact from cloud: $name ($primaryNumber)")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Error parsing remote contact doc", e)
                            }
                        }
                    }
                }
            Log.i(TAG, "Started real-time contacts cloud listener for user: $userId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start contacts listener: ${e.message}")
        }
    }

    private var sessionsListener: ListenerRegistration? = null

    /**
     * Pushes an entire chat session and its messages to Cloud Firestore.
     */
    fun syncSessionToCloud(context: Context, session: ChatSession, messages: List<ChatMessage>) {
        val user = IshaAuthManager.sessionState.value
        val userId = user.uid
        if (userId.isBlank() || user.provider == "GUEST" || userId.startsWith("guest") || !user.isLoggedIn) return

        scope.launch {
            try {
                val firestore = FirebaseFirestore.getInstance()
                val sessionData = hashMapOf<String, Any>(
                    "id" to session.id,
                    "title" to session.title,
                    "createdAt" to session.createdAt,
                    "updatedAt" to session.updatedAt,
                    "syncedAt" to FieldValue.serverTimestamp(),
                    "messageCount" to messages.size,
                    "messages" to messages.map { msg ->
                        val item = hashMapOf<String, Any>(
                            "id" to msg.id,
                            "sessionId" to msg.sessionId,
                            "role" to msg.role.name,
                            "text" to msg.text,
                            "timestamp" to msg.timestamp
                        )
                        if (msg.toolName != null) item["toolName"] = msg.toolName
                        if (msg.toolArgs != null) item["toolArgs"] = msg.toolArgs
                        if (msg.toolResult != null) item["toolResult"] = msg.toolResult
                        item
                    }
                )

                firestore.collection("users")
                    .document(userId)
                    .collection("sessions")
                    .document(session.id)
                    .set(sessionData, SetOptions.merge())
                    .addOnSuccessListener {
                        Log.d(TAG, "Synced session to cloud: ${session.title} (${messages.size} msgs)")
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Failed syncing session ${session.id}: ${e.message}")
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Error in syncSessionToCloud", e)
            }
        }
    }

    /**
     * Pushes all local sessions to Cloud Firestore.
     */
    fun syncAllLocalSessionsToCloud(context: Context, repo: IshaChatRepository) {
        val user = IshaAuthManager.sessionState.value
        val userId = user.uid
        if (userId.isBlank() || user.provider == "GUEST" || userId.startsWith("guest") || !user.isLoggedIn) return

        scope.launch {
            try {
                val sanitizedExpected = IshaChatRepository.sanitizeUserId(userId)
                if (repo.getCurrentUserId() != sanitizedExpected) {
                    Log.w(TAG, "Skip syncAllLocalSessions: repo on ${repo.getCurrentUserId()} != expected $sanitizedExpected")
                    return@launch
                }
                val allSessions = repo.getAllSessions()
                Log.i(TAG, "Syncing ${allSessions.size} local sessions to cloud for user $userId")
                for (sess in allSessions) {
                    val msgs = repo.getMessages(sess.id)
                    if (msgs.isNotEmpty()) {
                        syncSessionToCloud(context, sess, msgs)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error in syncAllLocalSessionsToCloud", e)
            }
        }
    }

    /**
     * Stops observing cloud sessions when user logs out or switches to guest.
     */
    fun stopSessionsListener() {
        try {
            sessionsListener?.remove()
            sessionsListener = null
            Log.i(TAG, "Stopped cloud sessions listener")
        } catch (_: Exception) {}
    }

    /**
     * Listens in real-time to `users/{userId}/sessions` and updates the local chat repository.
     */
    fun startSessionsListener(context: Context, repo: IshaChatRepository, onSessionsChanged: () -> Unit) {
        val user = IshaAuthManager.sessionState.value
        val userId = user.uid
        if (userId.isBlank() || user.provider == "GUEST" || userId.startsWith("guest") || !user.isLoggedIn) {
            stopSessionsListener()
            return
        }

        try {
            sessionsListener?.remove()
            val firestore = FirebaseFirestore.getInstance()
            val expectedSanitized = IshaChatRepository.sanitizeUserId(userId)
            sessionsListener = firestore.collection("users")
                .document(userId)
                .collection("sessions")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Error observing cloud sessions: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (repo.getCurrentUserId() != expectedSanitized) {
                        Log.w(TAG, "Ignoring cloud session snapshot: repo is on ${repo.getCurrentUserId()} but expected $expectedSanitized")
                        return@addSnapshotListener
                    }
                    if (snapshot != null && !snapshot.isEmpty) {
                        scope.launch {
                            var hasUpdates = false
                            for (doc in snapshot.documents) {
                                try {
                                    val id = doc.getString("id") ?: doc.id
                                    val title = doc.getString("title") ?: "Chat"
                                    val createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
                                    val updatedAt = doc.getLong("updatedAt") ?: System.currentTimeMillis()

                                    val rawMessages = doc.get("messages") as? List<*> ?: emptyList<Any>()
                                    val chatMessages = rawMessages.mapNotNull { raw ->
                                        if (raw is Map<*, *>) {
                                            try {
                                                val msgId = raw["id"]?.toString() ?: UUID.randomUUID().toString()
                                                val sessId = raw["sessionId"]?.toString() ?: id
                                                val roleStr = raw["role"]?.toString() ?: "USER"
                                                val role = try { MessageRole.valueOf(roleStr) } catch (_: Exception) { MessageRole.USER }
                                                val text = raw["text"]?.toString() ?: ""
                                                val ts = (raw["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()
                                                val toolName = raw["toolName"]?.toString()?.ifBlank { null }
                                                val toolArgs = raw["toolArgs"]?.toString()?.ifBlank { null }
                                                val toolResult = raw["toolResult"]?.toString()?.ifBlank { null }

                                                ChatMessage(
                                                    id = msgId,
                                                    sessionId = sessId,
                                                    role = role,
                                                    text = text,
                                                    timestamp = ts,
                                                    toolName = toolName,
                                                    toolArgs = toolArgs,
                                                    toolResult = toolResult
                                                )
                                            } catch (_: Exception) {
                                                null
                                            }
                                        } else null
                                    }

                                    val session = ChatSession(
                                        id = id,
                                        title = title,
                                        createdAt = createdAt,
                                        updatedAt = updatedAt
                                    )

                                    if (repo.getCurrentUserId() == expectedSanitized) {
                                        val localMsgs = repo.getMessages(id)
                                        val localSession = repo.getAllSessions().find { it.id == id }
                                        val isNewer = updatedAt > (localSession?.updatedAt ?: 0L)
                                        val hasNewMsgs = chatMessages.size > localMsgs.size
                                        if (hasNewMsgs || (isNewer && chatMessages.size >= localMsgs.size && chatMessages != localMsgs)) {
                                            repo.upsertSessionAndMessages(session, chatMessages)
                                            hasUpdates = true
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Error parsing remote session doc", e)
                                }
                            }
                            if (hasUpdates && repo.getCurrentUserId() == expectedSanitized) {
                                Log.i(TAG, "Cloud sessions synced into local repository successfully")
                                onSessionsChanged()
                            }
                        }
                    }
                }
            Log.i(TAG, "Started real-time sessions cloud listener for user: $userId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start sessions listener: ${e.message}")
        }
    }

    fun deleteSessionFromCloud(context: Context, sessionId: String) {
        val user = IshaAuthManager.sessionState.value
        val userId = user.uid
        if (userId.isBlank() || user.provider == "GUEST" || userId.startsWith("guest") || !user.isLoggedIn) return

        scope.launch {
            try {
                FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(userId)
                    .collection("sessions")
                    .document(sessionId)
                    .delete()
            } catch (e: Exception) {
                Log.w(TAG, "Error deleting cloud session $sessionId", e)
            }
        }
    }

    /**
     * Pushes a chat message to Cloud Firestore.
     */
    fun syncChatMessageToCloud(context: Context, sessionId: String, message: ChatMessage) {
        val userId = IshaAuthManager.sessionState.value.uid
        if (userId.isBlank()) return

        scope.launch {
            try {
                val firestore = FirebaseFirestore.getInstance()
                val data = hashMapOf<String, Any>(
                    "id" to message.id,
                    "sessionId" to sessionId,
                    "role" to message.role.name,
                    "text" to message.text,
                    "timestamp" to message.timestamp,
                    "syncedAt" to FieldValue.serverTimestamp()
                )
                if (message.toolName != null) data["toolName"] = message.toolName
                if (message.toolArgs != null) data["toolArgs"] = message.toolArgs
                if (message.toolResult != null) data["toolResult"] = message.toolResult

                firestore.collection("users")
                    .document(userId)
                    .collection("chats")
                    .document(sessionId)
                    .collection("messages")
                    .document(message.id)
                    .set(data, SetOptions.merge())
            } catch (e: Exception) {
                Log.w(TAG, "Error syncing chat message to cloud", e)
            }
        }
    }
}
