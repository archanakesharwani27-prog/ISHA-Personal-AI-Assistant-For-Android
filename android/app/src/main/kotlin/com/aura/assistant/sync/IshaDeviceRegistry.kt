package com.aura.assistant.sync

import android.content.Context
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.aura.assistant.auth.IshaAuthManager
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Metadata and live state for a device linked to the user's account.
 */
data class IshaDeviceInfo(
    val deviceId: String = "",
    val deviceName: String = "",
    val deviceAlias: String = "",
    val manufacturer: String = "",
    val model: String = "",
    val osVersion: String = "",
    val isOnline: Boolean = false,
    val batteryLevel: Int = -1,
    val isCharging: Boolean = false,
    val lastSeenAt: Long = 0L,
    val isCurrentDevice: Boolean = false
)

/**
 * Real-time Multi-Device Registry & Heartbeat Engine for ISHA.
 *
 * Tracks all physical devices (Phone A, Phone B, Tablet) logged into the same account:
 * 1. Generates and persists a stable device identity (UUID / Android ID).
 * 2. Periodically reports device telemetry (battery, charging status, online presence) to Cloud Firestore.
 * 3. Maintains real-time reactive StateFlow of all user devices.
 * 4. Resolves natural language device queries (e.g. "phone b", "dusre phone", "pixel", "redmi").
 */
object IshaDeviceRegistry {

    private const val TAG = "IshaDeviceRegistry"
    private const val PREFS_NAME = "isha_device_identity"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_ALIAS = "device_alias"

    private var prefs: SharedPreferences? = null
    private var cachedDeviceId: String = ""
    private var cachedDeviceAlias: String = ""

    private val _knownDevices = MutableStateFlow<List<IshaDeviceInfo>>(emptyList())
    val knownDevices: StateFlow<List<IshaDeviceInfo>> = _knownDevices.asStateFlow()

    private var devicesListener: ListenerRegistration? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var isInitialized = false

    @Synchronized
    fun init(context: Context) {
        if (isInitialized) return
        val app = context.applicationContext
        prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Retrieve or generate stable device ID
        var did = prefs?.getString(KEY_DEVICE_ID, null)
        if (did.isNullOrBlank()) {
            val androidId = try {
                Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
            } catch (e: Exception) {
                null
            }
            did = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
                "dev_" + androidId.take(12)
            } else {
                "dev_" + UUID.randomUUID().toString().replace("-", "").take(12)
            }
            prefs?.edit()?.putString(KEY_DEVICE_ID, did)?.apply()
        }
        cachedDeviceId = did

        // Default alias is Model name or saved alias
        cachedDeviceAlias = prefs?.getString(KEY_DEVICE_ALIAS, getDefaultDeviceAlias()) ?: getDefaultDeviceAlias()
        isInitialized = true

        Log.i(TAG, "Device Registry initialized. My Device ID: $cachedDeviceId ($cachedDeviceAlias)")

        // Register heartbeat and start observing devices
        registerHeartbeat(app)
        startObservingDevices(app)
    }

    fun getDeviceId(): String = cachedDeviceId

    fun getDeviceAlias(): String = cachedDeviceAlias

    fun setDeviceAlias(context: Context, newAlias: String) {
        val clean = newAlias.trim()
        if (clean.isNotBlank()) {
            cachedDeviceAlias = clean
            prefs?.edit()?.putString(KEY_DEVICE_ALIAS, clean)?.apply()
            registerHeartbeat(context.applicationContext)
        }
    }

    private fun getDefaultDeviceAlias(): String {
        val model = Build.MODEL ?: "Phone"
        val mfr = Build.MANUFACTURER ?: "Android"
        return "$mfr $model".trim()
    }

    /**
     * Publishes this device's online status, hardware info, and battery state to Firestore.
     */
    fun registerHeartbeat(context: Context) {
        scope.launch {
            try {
                val userId = IshaAuthManager.sessionState.value.uid
                if (userId.isBlank()) return@launch

                val firestore = FirebaseFirestore.getInstance()
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                val batteryLevel = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                val isCharging = bm?.isCharging == true

                val deviceData = hashMapOf<String, Any>(
                    "deviceId" to cachedDeviceId,
                    "deviceAlias" to cachedDeviceAlias,
                    "manufacturer" to (Build.MANUFACTURER ?: ""),
                    "model" to (Build.MODEL ?: ""),
                    "osVersion" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    "appVersion" to "2.0.0",
                    "isOnline" to true,
                    "batteryLevel" to batteryLevel,
                    "isCharging" to isCharging,
                    "lastSeenAt" to FieldValue.serverTimestamp()
                )

                firestore.collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(cachedDeviceId)
                    .set(deviceData, SetOptions.merge())
                    .addOnSuccessListener {
                        Log.d(TAG, "Heartbeat registered for device: $cachedDeviceId")
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Heartbeat update failed: ${e.message}")
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Error in registerHeartbeat: ${e.message}")
            }
        }
    }

    /**
     * Real-time listener for all user devices in `users/{userId}/devices`.
     */
    fun startObservingDevices(context: Context) {
        val userId = IshaAuthManager.sessionState.value.uid
        if (userId.isBlank()) return

        try {
            devicesListener?.remove()
            val firestore = FirebaseFirestore.getInstance()
            devicesListener = firestore.collection("users")
                .document(userId)
                .collection("devices")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Error observing user devices: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        val devicesList = snapshot.documents.mapNotNull { doc ->
                            try {
                                val did = doc.getString("deviceId") ?: doc.id
                                val alias = doc.getString("deviceAlias") ?: did
                                val mfr = doc.getString("manufacturer") ?: ""
                                val mdl = doc.getString("model") ?: ""
                                val os = doc.getString("osVersion") ?: ""
                                val online = doc.getBoolean("isOnline") ?: false
                                val battery = (doc.getLong("batteryLevel") ?: -1L).toInt()
                                val charging = doc.getBoolean("isCharging") ?: false
                                val lastSeen = doc.getTimestamp("lastSeenAt")?.toDate()?.time ?: 0L

                                IshaDeviceInfo(
                                    deviceId = did,
                                    deviceName = "$mfr $mdl".trim().ifBlank { alias },
                                    deviceAlias = alias,
                                    manufacturer = mfr,
                                    model = mdl,
                                    osVersion = os,
                                    isOnline = online,
                                    batteryLevel = battery,
                                    isCharging = charging,
                                    lastSeenAt = lastSeen,
                                    isCurrentDevice = (did == cachedDeviceId)
                                )
                            } catch (e: Exception) {
                                null
                            }
                        }
                        _knownDevices.value = devicesList
                        Log.i(TAG, "Active device count: ${devicesList.size} for user $userId")
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start observing devices: ${e.message}")
        }
    }

    /**
     * Resolves target device from natural language queries like:
     * "phone b", "phone 2", "dusra phone", "pixel", "samsung", "redmi".
     */
    fun resolveTargetDevice(query: String): IshaDeviceInfo? {
        val clean = query.trim().lowercase()
        val allDevices = _knownDevices.value
        val otherDevices = allDevices.filter { !it.isCurrentDevice }

        if (otherDevices.isEmpty()) return null

        // 1. If only 1 other device exists, any generic phrase routes to it
        if (otherDevices.size == 1 && (clean.contains("phone") || clean.contains("dusra") || clean.contains("other") || clean.isBlank())) {
            return otherDevices.first()
        }

        // 2. Exact alias match
        otherDevices.firstOrNull { it.deviceAlias.lowercase() == clean }?.let { return it }

        // 3. Substring match on alias, model, or manufacturer
        otherDevices.firstOrNull {
            clean.contains(it.deviceAlias.lowercase()) ||
            clean.contains(it.model.lowercase()) ||
            clean.contains(it.manufacturer.lowercase())
        }?.let { return it }

        // 4. "phone b" / "phone 2" heuristics
        if (clean.contains("phone b") || clean.contains("phone 2") || clean.contains("second phone")) {
            return otherDevices.firstOrNull()
        }

        return otherDevices.firstOrNull()
    }

    /**
     * Returns list of other devices (excluding current device).
     */
    fun getOtherDevices(): List<IshaDeviceInfo> {
        return _knownDevices.value.filter { !it.isCurrentDevice }
    }
}
