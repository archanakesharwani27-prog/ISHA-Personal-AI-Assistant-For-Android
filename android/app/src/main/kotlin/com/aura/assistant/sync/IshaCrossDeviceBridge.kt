package com.aura.assistant.sync

import android.app.KeyguardManager
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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
        CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Unhandled error in cross-device coroutine — scope remains alive", e)
        }
    )
    private var isListening = false
    @Volatile private var isInitialized = false

    val ACTIONS_EXEMPT_FROM_UNLOCK: Set<String> = setOf(
        "toggle_flashlight", "flashlight", "torch", "turn_on_flashlight", "turn_off_flashlight",
        "battery", "battery_status", "get_battery", "get_battery_status", "set_volume", "volume", "lock_device",
        "play_siren", "sound_siren", "siren", "emergency_siren", "find_phone", "stop_siren", "silence_siren",
        "get_current_time", "time", "clock"
    )

    /**
     * Initializes the bridge and starts listening for commands sent to THIS device.
     */
    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true
        val app = context.applicationContext
        IshaAuthManager.init(app)
        IshaDeviceRegistry.init(app)
        startInboxListener(app)

        scope.launch {
            IshaAuthManager.sessionState.collect { user ->
                if (user.isLoggedIn && user.uid.isNotBlank()) {
                    isListening = false
                    startInboxListener(app)
                }
            }
        }
    }

    /**
     * Subscribes to `users/{userId}/devices/{myDeviceId}/inbox` for pending tasks.
     */
    fun startInboxListener(context: Context) {
        val app = context.applicationContext
        IshaAuthManager.init(app)
        IshaDeviceRegistry.init(app)

        val userId = IshaAuthManager.sessionState.value.uid
        if (userId.isBlank()) {
            Log.w(TAG, "Cannot start inbox listener: user not logged in yet")
            return
        }

        val myDeviceId = IshaDeviceRegistry.getDeviceId()
        if (myDeviceId.isBlank()) {
            Log.w(TAG, "Cannot start inbox listener: deviceId not generated")
            return
        }

        if (isListening) return

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
                        Log.w(TAG, "Inbox listener error: ${error.message} — scheduling reconnect in 5s")
                        isListening = false
                        scope.launch {
                            kotlinx.coroutines.delay(5000L)
                            startInboxListener(app)
                        }
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
            Log.i(TAG, "Started cross-device inbox listener for device: $myDeviceId (User: $userId)")
        } catch (e: Exception) {
            isListening = false
            Log.w(TAG, "Failed to start inbox listener: ${e.message}")
            scope.launch {
                kotlinx.coroutines.delay(5000L)
                startInboxListener(app)
            }
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
            val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            val isScreenOn = pm?.isInteractive ?: true
            val isLocked = km?.isKeyguardLocked ?: false

            // Use SCREEN_BRIGHT_WAKE_LOCK to turn screen ON if device is asleep
            val wakeLock = pm?.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                android.os.PowerManager.ON_AFTER_RELEASE,
                "isha:cross_device_cmd"
            )
            try {
                wakeLock?.acquire(20000L)
            } catch (_: Exception) {}

            // Auto-wake & unlock screen if phone is locked when receiving remote command
            var unlockedSuccessfully = true
            if (isLocked) {
                val a11y = IshaAccessibilityService.instance
                a11y?.performUnlockSwipe()
                kotlinx.coroutines.delay(800L)

                val pin = params["pin"]?.toString()
                    ?: params["password"]?.toString()
                    ?: getSavedDevicePin(context)
                val pattern = params["pattern"]?.toString()
                    ?: getSavedDevicePattern(context)

                if (!pin.isNullOrBlank()) {
                    saveDeviceCredentials(context, pin, null)
                    a11y?.enterPin(pin)
                } else if (!pattern.isNullOrBlank()) {
                    saveDeviceCredentials(context, null, pattern)
                    a11y?.unlockWithPattern(pattern)
                }

                // Explicit Keyguard Verification check with polling (wait for dismiss animation up to 2500ms)
                unlockedSuccessfully = pollKeyguardUnlocked(km, 2500L)
                if (!unlockedSuccessfully && (!pin.isNullOrBlank() || !pattern.isNullOrBlank())) {
                    Log.w(TAG, "Device remains locked after first attempt. Retrying swipe and credential...")
                    a11y?.performUnlockSwipe()
                    kotlinx.coroutines.delay(750L)
                    if (!pin.isNullOrBlank()) {
                        a11y?.enterPin(pin)
                    } else if (!pattern.isNullOrBlank()) {
                        a11y?.unlockWithPattern(pattern)
                    }
                    unlockedSuccessfully = pollKeyguardUnlocked(km, 2500L)
                    if (!unlockedSuccessfully) {
                        Log.w(TAG, "Device remains locked after retry unlock attempt.")
                    }
                }
            }

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

            // If action requires an active unlocked screen and the device is still locked, fail explicitly!
            val requiresUnlockedScreen = !ACTIONS_EXEMPT_FROM_UNLOCK.contains(action.lowercase())

            if (isLocked && !unlockedSuccessfully && requiresUnlockedScreen) {
                success = false
                val method = if (!params["pattern"]?.toString().isNullOrBlank() || !getSavedDevicePattern(context).isNullOrBlank()) "Pattern" else "PIN"
                executionResult = "Phone lock hai aur $method enter karne ke baad bhi unlock nahi hua, isliye '$action' execute nahi ho saki."
            } else {
                try {
                    when (action.lowercase()) {
                    "open_app" -> {
                        val pkgName = params["package_name"]?.toString() ?: ""
                        val rawAppName = params["app_name"]?.toString() ?: "app"
                        val appName = rawAppName
                            .replace(Regex("(?i)^(open|launch|kholo|chalao|start|run|app|application)\\s+"), "")
                            .replace(Regex("(?i)\\s+(app|application|kholo|chalao|karo|krdo)$"), "")
                            .replace(Regex("(?i)^(open|launch)\\s+"), "")
                            .replace(Regex("(?i)\\s+(app)$"), "")
                            .trim().ifBlank { rawAppName }

                        var launched = false
                        // 1. Try explicit or alias-resolved package name
                        val targetPkg = if (pkgName.isNotBlank()) pkgName else when {
                            appName.contains("chrome", ignoreCase = true) || appName.contains("browser", ignoreCase = true) -> "com.android.chrome"
                            appName.contains("whatsapp", ignoreCase = true) -> "com.whatsapp"
                            appName.contains("youtube", ignoreCase = true) -> "com.google.android.youtube"
                            appName.contains("play store", ignoreCase = true) || appName.contains("playstore", ignoreCase = true) -> "com.android.vending"
                            appName.contains("settings", ignoreCase = true) -> "com.android.settings"
                            appName.contains("camera", ignoreCase = true) -> "com.android.camera"
                            appName.contains("gallery", ignoreCase = true) || appName.contains("photos", ignoreCase = true) -> "com.google.android.apps.photos"
                            appName.contains("maps", ignoreCase = true) -> "com.google.android.apps.maps"
                            appName.contains("gmail", ignoreCase = true) || appName.contains("email", ignoreCase = true) -> "com.google.android.gm"
                            appName.contains("spotify", ignoreCase = true) -> "com.spotify.music"
                            appName.contains("telegram", ignoreCase = true) -> "org.telegram.messenger"
                            else -> com.aura.assistant.ai.IshaToolRegistry.resolveAppPackage(context, appName)
                        }

                        if (!targetPkg.isNullOrBlank()) {
                            val launchIntent = context.packageManager.getLaunchIntentForPackage(targetPkg)
                            if (launchIntent != null) {
                                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                context.startActivity(launchIntent)
                                launched = true
                            }
                        }

                        // 2. Fallback to package manager intent activities by label or package substring
                        if (!launched && appName.isNotBlank()) {
                            val packageMgr = context.packageManager
                            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
                            val apps = packageMgr.queryIntentActivities(mainIntent, 0)
                            val match = apps.firstOrNull { 
                                it.loadLabel(packageMgr).toString().contains(appName, ignoreCase = true) ||
                                it.activityInfo.packageName.contains(appName, ignoreCase = true)
                            }
                            if (match != null) {
                                val launchIntent = packageMgr.getLaunchIntentForPackage(match.activityInfo.packageName)
                                if (launchIntent != null) {
                                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                    context.startActivity(launchIntent)
                                    launched = true
                                }
                            }
                        }
                        if (launched) {
                            executionResult = "$appName opened successfully"
                        } else {
                            success = false
                            executionResult = "Could not find app $appName"
                        }
                    }

                    "toggle_flashlight", "flashlight", "torch", "turn_on_flashlight", "turn_off_flashlight" -> {
                        val state = params["state"]?.toString() ?: if (rawPrompt.contains("off") || rawPrompt.contains("band") || action.contains("off")) "off" else "on"
                        val turnOn = !(state.contains("off") || state.contains("false") || state.contains("band"))
                        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                        var targetCamId: String? = null
                        for (id in cm.cameraIdList) {
                            try {
                                val chars = cm.getCameraCharacteristics(id)
                                val hasFlash = chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                                if (hasFlash) {
                                    targetCamId = id
                                    break
                                }
                            } catch (_: Exception) {}
                        }
                        if (targetCamId == null) {
                            targetCamId = cm.cameraIdList.firstOrNull() ?: "0"
                        }
                        try {
                            cm.setTorchMode(targetCamId, turnOn)
                            executionResult = if (turnOn) "Flashlight turned on" else "Flashlight turned off"
                        } catch (e: Exception) {
                            success = false
                            executionResult = "Flashlight error: ${e.message}"
                        }
                    }

                    "play_siren", "sound_siren", "siren", "emergency_siren", "find_phone" -> {
                        val played = com.aura.assistant.media.IshaSirenManager.startSiren(context)
                        success = played
                        executionResult = if (played) "High-decibel siren is now playing at max volume with strobe light 🚨" else "Could not play siren"
                    }

                    "stop_siren", "silence_siren", "stop_alarm" -> {
                        val stopped = com.aura.assistant.media.IshaSirenManager.stopSiren(context)
                        success = stopped
                        executionResult = "Siren stopped successfully 🛑"
                    }

                    "toggle_hotspot", "hotspot" -> {
                        val enable = params["enable"]?.toString()?.toBooleanStrictOrNull()
                            ?: !(rawPrompt.contains("off") || rawPrompt.contains("band"))
                        val a11y = IshaAccessibilityService.instance
                        if (a11y != null) {
                            a11y.armHotspotToggle(enable)
                            // Wait up to 3500ms for accessibility service to locate switch and click it
                            var toggled = false
                            val checkStart = System.currentTimeMillis()
                            while (System.currentTimeMillis() - checkStart < 3500L) {
                                kotlinx.coroutines.delay(300L)
                                if (a11y.pendingHotspotToggle == null) {
                                    toggled = true
                                    break
                                }
                            }
                            if (toggled) {
                                executionResult = "Personal Hotspot ${if (enable) "ON" else "OFF"} kar diya hai Boss! 📶"
                                success = true
                            } else {
                                executionResult = "Hotspot settings kholi gayi hain, switch toggle trigger ho gaya hai."
                                success = true
                            }
                        } else {
                            val intent = Intent("android.settings.TETHER_SETTINGS").apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            try {
                                context.startActivity(intent)
                                executionResult = "Hotspot settings open kar di hai Boss"
                                success = true
                            } catch (_: Exception) {
                                success = false
                                executionResult = "Could not open hotspot settings"
                            }
                        }
                    }

                    "battery", "battery_status", "get_battery" -> {
                        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
                        val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                        val isCharging = bm?.isCharging == true
                        executionResult = "Battery level is $level%${if (isCharging) " (Charging ⚡)" else ""}"
                    }

                    "set_volume", "volume" -> {
                        val level = (params["level"]?.toString()?.toIntOrNull()) ?: 50
                        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val target = (level * maxVol) / 100
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
                        executionResult = "Volume set to $level%"
                    }

                    "play_youtube", "youtube", "play_song", "play_media", "open_youtube" -> {
                        val query = params["query"]?.toString()
                            ?: params["song"]?.toString()
                            ?: params["title"]?.toString()
                            ?: rawPrompt
                        val url = params["url"]?.toString() ?: ""
                        val targetUrl = if (url.isNotBlank()) url else {
                            val urlRegex = Regex("""https?://[^\s]+""")
                            urlRegex.find(rawPrompt)?.value ?: ""
                        }

                        if (targetUrl.isNotBlank()) {
                            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(targetUrl)).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            }
                            try {
                                intent.setPackage("com.google.android.youtube")
                                context.startActivity(intent)
                                executionResult = "YouTube link opened and playing"
                                success = true
                            } catch (_: Exception) {
                                intent.setPackage(null)
                                try {
                                    context.startActivity(intent)
                                    executionResult = "Link opened in media player/browser"
                                    success = true
                                } catch (e: Exception) {
                                    success = false
                                    executionResult = "Failed to open URL: ${e.message}"
                                }
                            }
                        } else {
                            val cleanQuery = query
                                .replace("youtube", "", ignoreCase = true)
                                .replace("song", "", ignoreCase = true)
                                .replace("play", "", ignoreCase = true)
                                .replace("open", "", ignoreCase = true)
                                .replace("karo", "", ignoreCase = true)
                                .replace("krdo", "", ignoreCase = true)
                                .replace("chalao", "", ignoreCase = true)
                                .trim()

                            val searchUrl = "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(cleanQuery, "UTF-8")
                            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(searchUrl)).apply {
                                setPackage("com.google.android.youtube")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            }
                            try {
                                context.startActivity(intent)
                                IshaAccessibilityService.instance?.armYouTubeAutoPlayFirstResult(cleanQuery)
                                executionResult = "YouTube launched for \"$cleanQuery\" with autoplay"
                                success = true
                            } catch (_: Exception) {
                                intent.setPackage(null)
                                try {
                                    context.startActivity(intent)
                                    executionResult = "YouTube opened in browser for \"$cleanQuery\""
                                    success = true
                                } catch (e: Exception) {
                                    success = false
                                    executionResult = "Failed to launch YouTube: ${e.message}"
                                }
                            }
                        }
                    }

                    "lock_device", "lock_phone", "lock" -> {
                        val a11y = IshaAccessibilityService.instance
                        if (a11y != null) {
                            val locked = a11y.performLockScreen()
                            success = locked
                            executionResult = if (locked) "Phone locked successfully" else "Lock screen not supported on this device"
                        } else {
                            success = false
                            executionResult = "Accessibility Service required to lock phone"
                        }
                    }

                    "unlock_device", "unlock_phone", "unlock", "wake_device", "wake" -> {
                        val a11y = IshaAccessibilityService.instance
                        a11y?.performUnlockSwipe()
                        kotlinx.coroutines.delay(800L)
                        val pin = params["pin"]?.toString() ?: params["password"]?.toString() ?: getSavedDevicePin(context)
                        val pattern = params["pattern"]?.toString() ?: getSavedDevicePattern(context)
                        if (!pin.isNullOrBlank()) {
                            saveDeviceCredentials(context, pin, null)
                            a11y?.enterPin(pin)
                        } else if (!pattern.isNullOrBlank()) {
                            saveDeviceCredentials(context, null, pattern)
                            a11y?.unlockWithPattern(pattern)
                        } else {
                            try {
                                val launchIntent = Intent(context, com.aura.assistant.MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                    putExtra("WAKE_REASON", "REMOTE_UNLOCK")
                                }
                                context.startActivity(launchIntent)
                            } catch (_: Exception) {}
                        }

                        // Poll verification (up to 2500ms for keyguard dismiss animation)
                        var unlocked = pollKeyguardUnlocked(km, 2500L)
                        if (!unlocked && (!pin.isNullOrBlank() || !pattern.isNullOrBlank())) {
                            Log.w(TAG, "unlock_device: Still locked after attempt 1. Retrying swipe and credentials...")
                            a11y?.performUnlockSwipe()
                            kotlinx.coroutines.delay(750L)
                            if (!pin.isNullOrBlank()) a11y?.enterPin(pin)
                            else if (!pattern.isNullOrBlank()) a11y?.unlockWithPattern(pattern)
                            unlocked = pollKeyguardUnlocked(km, 2500L)
                        }

                        if (unlocked) {
                            success = true
                            executionResult = if (!pin.isNullOrBlank()) "Phone unlocked with PIN 🔓"
                                              else if (!pattern.isNullOrBlank()) "Phone unlocked with Pattern 🔓"
                                              else "Phone screen wake aur swipe unlock ho gaya Boss! 🔓"
                        } else {
                            if (pin.isNullOrBlank() && pattern.isNullOrBlank()) {
                                success = true
                                executionResult = "Screen wake aur swipe attempt kiya gaya. Agar PIN/Pattern protected hai to PIN ya Pattern bataiye."
                            } else {
                                success = false
                                executionResult = "Phone unlock attempt fail ho gaya, keyguard abhi bhi locked hai. Kripya PIN ya Pattern check karein."
                            }
                        }
                    }

                    "install_store_app", "install_app" -> {
                        val query = params["query"]?.toString() ?: params["app_name"]?.toString() ?: rawPrompt
                        val cleanQuery = query.replace("play store", "", ignoreCase = true)
                            .replace("playstore", "", ignoreCase = true)
                            .replace("install", "", ignoreCase = true)
                            .replace("karo", "", ignoreCase = true)
                            .replace("krdo", "", ignoreCase = true)
                            .replace("se", "", ignoreCase = true)
                            .trim()
                        val marketUri = "market://search?q=" + java.net.URLEncoder.encode(cleanQuery, "UTF-8")
                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(marketUri)).apply {
                            setPackage("com.android.vending")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        try {
                            context.startActivity(intent)
                            executionResult = "Play Store opened to install \"$cleanQuery\""
                            success = true
                        } catch (_: Exception) {
                            val webUri = "https://play.google.com/store/search?q=" + java.net.URLEncoder.encode(cleanQuery, "UTF-8")
                            val webIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(webUri)).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(webIntent)
                            executionResult = "Play Store search opened in browser for \"$cleanQuery\""
                            success = true
                        }
                    }

                    "tap", "find_and_tap", "click" -> {
                        val a11y = IshaAccessibilityService.instance
                        if (a11y != null) {
                            val x = params["x"]?.toString()?.toFloatOrNull()
                            val y = params["y"]?.toString()?.toFloatOrNull()
                            val target = params["target"]?.toString() ?: params["text"]?.toString() ?: ""
                            if (x != null && y != null) {
                                val tapped = a11y.performClickCoordinate(x, y)
                                success = tapped
                                executionResult = if (tapped) "Tapped coordinate ($x, $y)" else "Failed to tap ($x, $y)"
                            } else if (target.isNotBlank()) {
                                val tapped = a11y.performClickByText(target)
                                success = tapped
                                executionResult = if (tapped) "Tapped on \"$target\"" else "Target \"$target\" not found on screen"
                            } else {
                                success = false
                                executionResult = "Missing tap coordinates or target text"
                            }
                        } else {
                            success = false
                            executionResult = "Accessibility Service not enabled on target device"
                        }
                    }

                    "open_url" -> {
                        val url = params["url"]?.toString() ?: rawPrompt
                        val targetUrl = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(targetUrl)).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                        executionResult = "URL opened: $targetUrl"
                        success = true
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
                        // Forward to local tool registry so all 80+ tools work remotely
                        try {
                            val jsonArgs = com.google.gson.JsonObject()
                            params.forEach { (k, v) ->
                                when (v) {
                                    is Number -> jsonArgs.addProperty(k, v)
                                    is Boolean -> jsonArgs.addProperty(k, v)
                                    else -> jsonArgs.addProperty(k, v.toString())
                                }
                            }
                            if (rawPrompt.isNotBlank() && !jsonArgs.has("prompt")) {
                                jsonArgs.addProperty("prompt", rawPrompt)
                            }
                            val localRes = com.aura.assistant.execution.ExecutionEngine.executeTool(context, action, jsonArgs)
                            val st = localRes.get("status")?.asString ?: "success"
                            val msg = localRes.get("message")?.asString ?: localRes.toString()
                            success = (st != "error" && st != "failed")
                            executionResult = msg
                        } catch (e: Exception) {
                            executionResult = "Command acknowledged: $action (${e.message})"
                        }
                    }
                }
            } catch (e: Exception) {
                success = false
                executionResult = "Execution failed: ${e.message}"
                Log.e(TAG, "Error executing incoming remote command", e)
            }
            }

            // 2. Report status back to initiator
            val finalStatus = if (success) "COMPLETED" else "FAILED"
            val updatePayload = hashMapOf<String, Any>(
                "status" to finalStatus,
                "result" to executionResult,
                "completedAt" to FieldValue.serverTimestamp()
            )
            try {
                docRef.set(updatePayload, SetOptions.merge())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update command status doc: ${e.message}")
            }
            Log.i(TAG, "Remote command $cmdId finished with status: $finalStatus ($executionResult)")
            try {
                if (wakeLock?.isHeld == true) wakeLock.release()
            } catch (_: Exception) {}
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
    ): RemoteExecutionResult = withTimeoutOrNull(25000L) {
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

    fun saveDeviceCredentials(context: Context, pin: String?, pattern: String?) {
        val p = context.getSharedPreferences("cross_device_creds", Context.MODE_PRIVATE)
        p.edit().apply {
            if (!pin.isNullOrBlank()) putString("saved_pin", pin)
            if (!pattern.isNullOrBlank()) putString("saved_pattern", pattern)
            apply()
        }
    }

    fun getSavedDevicePin(context: Context): String? {
        return context.getSharedPreferences("cross_device_creds", Context.MODE_PRIVATE).getString("saved_pin", null)
    }

    fun getSavedDevicePattern(context: Context): String? {
        return context.getSharedPreferences("cross_device_creds", Context.MODE_PRIVATE).getString("saved_pattern", null)
    }

    suspend fun pollKeyguardUnlocked(km: KeyguardManager?, maxWaitMs: Long = 2500L): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxWaitMs) {
            if (km?.isKeyguardLocked == false) {
                return true
            }
            kotlinx.coroutines.delay(200L)
        }
        return km?.isKeyguardLocked == false
    }
}
