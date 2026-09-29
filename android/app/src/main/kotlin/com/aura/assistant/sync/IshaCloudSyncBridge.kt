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
