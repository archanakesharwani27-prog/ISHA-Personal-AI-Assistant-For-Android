package com.aura.assistant.sync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.util.Log
import com.aura.assistant.IshaAccessibilityService
import com.aura.assistant.auth.IshaAuthManager
import com.aura.assistant.brain.OfflineReflexEngine
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Cross-Device Command Payload representation.
 */
data class RemoteCommand(
    val commandId: String = UUID.randomUUID().toString(),
    val senderDeviceId: String = "",
    val senderDeviceName: String = "",
    val targetDeviceId: String = "",
    val action: String = "",
    val params: Map<String, Any> = emptyMap(),
    val rawPrompt: String = "",
    val status: String = "PENDING", // PENDING, EXECUTING, COMPLETED, FAILED
    val result: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class RemoteExecutionResult(
    val success: Boolean,
    val targetDeviceName: String,
    val message: String
)

/**
 * High-performance Cross-Device Orchestration Engine for ISHA.
 *
 * Capabilities:
 *  1. Remote Command Dispatcher (Phone A -> Phone B):
 *     - "Phone B par WhatsApp open karo"
 *     - "Phone B par flashlight jalao"
 *     - "Phone A ka text copy karke Phone B par WhatsApp par Rahul ko bhej do"
 *  2. Inbox Listener:
 *     - Listens in real-time for commands targeted at THIS device.
 *     - Automatically executes via Accessibility Service, Offline Reflex, or System Intents.
 *     - Reports completion status back to the initiating device.
 *  3. Shared Cloud Clipboard:
 *     - Universal copy/paste bridge across all devices linked to the user account.
 */
object IshaCrossDeviceBridge {

    private const val TAG = "IshaCrossDeviceBridge"
    private var inboxListener: ListenerRegistration? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var isListening = false

    /**
     * Initializes the bridge and starts listening for commands sent to THIS device.
     */
    fun init(context: Context) {
        val app = context.applicationContext
        IshaDeviceRegistry.init(app)
        startInboxListener(app)
    }

    /**
     * Subscribes to `users/{userId}/devices/{myDeviceId}/inbox` for pending tasks.
     */
    fun startInboxListener(context: Context) {
        if (isListening) return
        val userId = IshaAuthManager.sessionState.value.uid
        if (userId.isBlank()) return

        val myDeviceId = IshaDeviceRegistry.getDeviceId()
        if (myDeviceId.isBlank()) return

        try {
            inboxListener?.remove()
            val firestore = FirebaseFirestore.getInstance()

            inboxListener = firestore.collection("users")
                .document(userId)
                .collection("devices")
                .document(myDeviceId)
                .collection("inbox")
                .whereEqualTo("status", "PENDING")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Inbox listener error: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null && !snapshot.isEmpty) {
                        for (doc in snapshot.documents) {
                            val cmdId = doc.getString("commandId") ?: doc.id
                            val senderName = doc.getString("senderDeviceName") ?: "Remote Device"
                            val action = doc.getString("action") ?: ""
                            val rawPrompt = doc.getString("rawPrompt") ?: ""
                            val params = (doc.get("params") as? Map<*, *>)?.mapKeys { it.key.toString() }?.mapValues { it.value ?: "" } ?: emptyMap()

                            Log.i(TAG, "Incoming remote command: [$action] from $senderName (ID: $cmdId)")
                            executeIncomingCommand(context, userId, myDeviceId, cmdId, senderName, action, params, rawPrompt)
                        }
                    }
                }
            isListening = true
            Log.i(TAG, "Started cross-device inbox listener for device: $myDeviceId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start inbox listener: ${e.message}")
        }
    }

    /**
     * Executes an incoming remote task on this local hardware.
     */
    private fun executeIncomingCommand(
        context: Context,
        userId: String,
        myDeviceId: String,
        cmdId: String,
        senderName: String,
        action: String,
        params: Map<String, Any>,
        rawPrompt: String
    ) {
        scope.launch {
            val firestore = FirebaseFirestore.getInstance()
            val docRef = firestore.collection("users")
                .document(userId)
                .collection("devices")
                .document(myDeviceId)
                .collection("inbox")
                .document(cmdId)

            // 1. Mark as EXECUTING
            docRef.update("status", "EXECUTING")

            var executionResult = "Executed successfully"
            var success = true

            try {
                when (action.lowercase()) {
                    "open_app" -> {
                        val pkgName = params["package_name"]?.toString() ?: ""
                        val appName = params["app_name"]?.toString() ?: "app"
                        if (pkgName.isNotBlank()) {
                            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkgName)
                            if (launchIntent != null) {
                                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(launchIntent)
                                executionResult = "$appName successfully opened"
                            } else {
                                success = false
                                executionResult = "Could not find package $pkgName"
                            }
                        } else {
                            OfflineReflexEngine.tryExecute(context, "open $appName")
                            executionResult = "$appName opened"
                        }
                    }

                    "toggle_flashlight" -> {
                        val state = params["state"]?.toString() ?: "on"
                        val turnOn = state.contains("on") || state.contains("true") || state.contains("jalao")
                        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                        val cameraId = cm.cameraIdList.firstOrNull()
                        if (cameraId != null) {
                            cm.setTorchMode(cameraId, turnOn)
                            executionResult = if (turnOn) "Flashlight turned on" else "Flashlight turned off"
                        } else {
                            success = false
                            executionResult = "Camera flash hardware unavailable"
                        }
                    }

                    "set_volume" -> {
                        val level = (params["level"]?.toString()?.toIntOrNull()) ?: 50
                        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val target = (level * maxVol) / 100
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
                        executionResult = "Volume set to $level%"
                    }

                    "chat_on_whatsapp", "send_whatsapp" -> {
                        val contact = params["contact"]?.toString() ?: ""
                        val message = params["message"]?.toString() ?: ""
                        if (contact.isNotBlank()) {
                            // Arm accessibility service for automated typing & sending
                            if (message.isNotBlank()) {
                                IshaAccessibilityService.instance?.armWhatsappTypeMessage(message)
                            }
                            // Open WhatsApp
                            val launchIntent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
                            if (launchIntent != null) {
                                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(launchIntent)
                                executionResult = "WhatsApp opened for $contact with message"
                            } else {
                                success = false
                                executionResult = "WhatsApp not installed"
                            }
                        } else {
                            success = false
                            executionResult = "Contact name required for WhatsApp"
                        }
                    }

                    "type_message" -> {
                        val text = params["text"]?.toString() ?: ""
                        if (text.isNotBlank()) {
                            IshaAccessibilityService.instance?.armWhatsappTypeMessage(text)
                            executionResult = "Armed text for typing: $text"
                        }
                    }

                    "copy_clipboard" -> {
                        val text = params["text"]?.toString() ?: ""
                        if (text.isNotBlank()) {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("ISHA Remote Clipboard", text))
                            executionResult = "Copied to clipboard: \"$text\""
                        }
                    }

                    else -> {
                        // Pass raw prompt to Offline Reflex Engine
                        if (rawPrompt.isNotBlank()) {
                            val reflexResult = OfflineReflexEngine.tryExecute(context, rawPrompt)
                            if (reflexResult != null) {
                                executionResult = reflexResult.naturalResponse
                            } else {
                                executionResult = "Command executed on target device"
                            }
                        } else {
                            executionResult = "Executed $action"
                        }
                    }
                }
            } catch (e: Exception) {
                success = false
                executionResult = "Execution failed: ${e.message}"
                Log.e(TAG, "Error executing incoming remote command", e)
            }

            // 2. Report status back to initiator
            val finalStatus = if (success) "COMPLETED" else "FAILED"
            val updatePayload = hashMapOf<String, Any>(
                "status" to finalStatus,
                "result" to executionResult,
                "completedAt" to FieldValue.serverTimestamp()
            )
            docRef.update(updatePayload)
            Log.i(TAG, "Remote command $cmdId finished with status: $finalStatus ($executionResult)")
        }
    }

    /**
     * Dispatches a remote command to another device and suspends until execution completes (or timeouts).
     */
    suspend fun dispatchRemoteCommand(
        context: Context,
        targetDeviceQuery: String,
        action: String,
        params: Map<String, Any>,
        rawPrompt: String
    ): RemoteExecutionResult = withTimeoutOrNull(15000L) {
        val app = context.applicationContext
        IshaDeviceRegistry.init(app)

        val targetDevice = IshaDeviceRegistry.resolveTargetDevice(targetDeviceQuery)
        if (targetDevice == null) {
            val otherDevices = IshaDeviceRegistry.getOtherDevices()
            val availableNames = otherDevices.joinToString(", ") { it.deviceAlias }
            return@withTimeoutOrNull RemoteExecutionResult(
                success = false,
                targetDeviceName = targetDeviceQuery,
                message = if (availableNames.isNotBlank()) {
                    "Dusra device nahi mila Boss. Available devices: $availableNames"
                } else {
                    "Aapke account se koi dusra device linked nahi hai Boss."
                }
            )
        }

        val userId = IshaAuthManager.sessionState.value.uid
        if (userId.isBlank()) {
            return@withTimeoutOrNull RemoteExecutionResult(
                success = false,
                targetDeviceName = targetDevice.deviceAlias,
                message = "Cross-device commands ke liye login required hai Boss."
            )
        }

        val myDeviceId = IshaDeviceRegistry.getDeviceId()
        val myDeviceName = IshaDeviceRegistry.getDeviceAlias()
        val commandId = "cmd_" + UUID.randomUUID().toString().replace("-", "").take(12)

        val cmdData = hashMapOf<String, Any>(
            "commandId" to commandId,
            "senderDeviceId" to myDeviceId,
            "senderDeviceName" to myDeviceName,
            "targetDeviceId" to targetDevice.deviceId,
            "action" to action,
            "params" to params,
            "rawPrompt" to rawPrompt,
            "status" to "PENDING",
            "createdAt" to FieldValue.serverTimestamp()
        )

        val firestore = FirebaseFirestore.getInstance()
        val targetDocRef = firestore.collection("users")
            .document(userId)
            .collection("devices")
            .document(targetDevice.deviceId)
            .collection("inbox")
            .document(commandId)

        // Write command to target device inbox
        targetDocRef.set(cmdData, SetOptions.merge())
        Log.i(TAG, "Dispatched remote command $commandId to ${targetDevice.deviceAlias} (${targetDevice.deviceId})")

        // Await completion via snapshot listener
        suspendCancellableCoroutine<RemoteExecutionResult> { cont ->
            var registration: ListenerRegistration? = null
            registration = targetDocRef.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    registration?.remove()
                    if (cont.isActive) {
                        cont.resume(RemoteExecutionResult(false, targetDevice.deviceAlias, "Network error: ${error.message}"))
                    }
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    val status = snapshot.getString("status") ?: "PENDING"
                    val res = snapshot.getString("result") ?: ""
                    if (status == "COMPLETED") {
                        registration?.remove()
                        if (cont.isActive) {
                            cont.resume(RemoteExecutionResult(true, targetDevice.deviceAlias, res.ifBlank { "Command executed successfully on ${targetDevice.deviceAlias}." }))
                        }
                    } else if (status == "FAILED") {
                        registration?.remove()
                        if (cont.isActive) {
                            cont.resume(RemoteExecutionResult(false, targetDevice.deviceAlias, res.ifBlank { "Execution failed on ${targetDevice.deviceAlias}." }))
                        }
                    }
                }
            }
            cont.invokeOnCancellation {
                registration.remove()
            }
        }
    } ?: RemoteExecutionResult(
        success = false,
        targetDeviceName = targetDeviceQuery,
        message = "Remote command timeout ho gaya Boss. Dusre device par network connection check karein."
    )

    /**
     * Universal Cloud Clipboard: Share text to cloud.
     */
    fun shareClipboard(context: Context, text: String) {
        val userId = IshaAuthManager.sessionState.value.uid
        if (userId.isBlank() || text.isBlank()) return

        scope.launch {
            try {
                val firestore = FirebaseFirestore.getInstance()
                val data = hashMapOf<String, Any>(
                    "text" to text,
                    "senderDeviceId" to IshaDeviceRegistry.getDeviceId(),
                    "senderDeviceName" to IshaDeviceRegistry.getDeviceAlias(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                firestore.collection("users")
                    .document(userId)
                    .collection("shared_clipboard")
                    .document("active")
                    .set(data, SetOptions.merge())
                Log.i(TAG, "Shared clipboard updated: \"${text.take(30)}...\"")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to share clipboard: ${e.message}")
            }
        }
    }

    /**
     * Universal Cloud Clipboard: Retrieve shared text.
     */
    suspend fun getSharedClipboard(): String? = suspendCancellableCoroutine { cont ->
        val userId = IshaAuthManager.sessionState.value.uid
        if (userId.isBlank()) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        try {
            val firestore = FirebaseFirestore.getInstance()
            firestore.collection("users")
                .document(userId)
                .collection("shared_clipboard")
                .document("active")
                .get()
                .addOnSuccessListener { doc ->
                    val text = doc.getString("text")
                    cont.resume(text)
                }
                .addOnFailureListener {
                    cont.resume(null)
                }
        } catch (e: Exception) {
            cont.resume(null)
        }
    }
}
