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
        IshaAuthManager.init(app)
        prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Retrieve or generate stable device ID with package isolation
        val pkgSuffix = if (app.packageName.contains("aura")) "_aura" else "_isha"
        val prefsKey = KEY_DEVICE_ID + pkgSuffix
        var did = prefs?.getString(prefsKey, null)
        if (did.isNullOrBlank()) {
            val androidId = try {
                Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
            } catch (e: Exception) {
                null
            }
            did = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
                "dev_" + androidId.take(10) + pkgSuffix
            } else {
                "dev_" + UUID.randomUUID().toString().replace("-", "").take(10) + pkgSuffix
            }
            prefs?.edit()?.putString(prefsKey, did)?.apply()
        }
        cachedDeviceId = did

        // Default alias is Model name or saved alias
        var savedAlias = prefs?.getString(KEY_DEVICE_ALIAS, null)
        if (savedAlias.isNullOrBlank() || savedAlias.equals("LAVA LAVA LEX402", ignoreCase = true) || savedAlias.equals("LEX402", ignoreCase = true)) {
            savedAlias = getDefaultDeviceAlias()
            prefs?.edit()?.putString(KEY_DEVICE_ALIAS, savedAlias)?.apply()
        }
        cachedDeviceAlias = savedAlias
        isInitialized = true

        Log.i(TAG, "Device Registry initialized. My Device ID: $cachedDeviceId ($cachedDeviceAlias)")

        // Register heartbeat and start observing devices
        registerHeartbeat(app)
        startObservingDevices(app)

        scope.launch {
            IshaAuthManager.sessionState.collect { user ->
                if (user.isLoggedIn && user.uid.isNotBlank()) {
                    registerHeartbeat(app)
                    startObservingDevices(app)
                }
            }
        }
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

    fun updateDeviceAlias(context: Context, targetDeviceId: String, newAlias: String) {
        val clean = newAlias.trim()
        if (clean.isBlank()) return
        if (targetDeviceId == cachedDeviceId) {
            setDeviceAlias(context, clean)
        } else {
            val userId = IshaAuthManager.sessionState.value.uid
            if (userId.isNotBlank()) {
                FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(userId)
                    .collection("devices")
                    .document(targetDeviceId)
                    .set(mapOf("deviceAlias" to clean), SetOptions.merge())
            }
        }
    }

    private fun getDefaultDeviceAlias(): String {
        val rawModel = Build.MODEL?.trim() ?: "Android Phone"
        val rawMfr = Build.MANUFACTURER?.trim() ?: ""
        val mfr = if (rawMfr.isNotBlank()) rawMfr.replaceFirstChar { it.uppercase() } else ""
        return if (rawModel.contains(mfr, ignoreCase = true)) {
            rawModel
        } else if (mfr.isNotBlank()) {
            "$mfr $rawModel"
        } else {
            rawModel
        }
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
                        val rawList = snapshot.documents.mapNotNull { doc ->
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

                                val isCurrent = (did == cachedDeviceId)
                                if (!isCurrent && !did.endsWith("_isha") && !did.endsWith("_aura")) {
                                    // Purge obsolete legacy registration from Firestore
                                    try {
                                        firestore.collection("users").document(userId).collection("devices").document(did).delete()
                                        Log.i(TAG, "Purged obsolete legacy device document: $did")
                                    } catch (_: Exception) {}
                                }

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
                                    isCurrentDevice = isCurrent
                                )
                            } catch (e: Exception) {
                                null
                            }
                        }

                        // Auto-disambiguate identical device aliases (e.g. two phones of exact same model)
                        val aliasCounts = rawList.groupBy { it.deviceAlias.lowercase() }
                        val disambiguatedList = rawList.map { dev ->
                            val count = aliasCounts[dev.deviceAlias.lowercase()]?.size ?: 0
                            if (count > 1) {
                                val shortId = dev.deviceId.removePrefix("dev_").take(4).ifBlank { dev.deviceId.takeLast(4) }
                                dev.copy(deviceAlias = "${dev.deviceAlias} ($shortId)")
                            } else {
                                dev
                            }
                        }

                        _knownDevices.value = disambiguatedList
                        Log.i(TAG, "Active device count: ${disambiguatedList.size} for user $userId")
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start observing devices: ${e.message}")
        }
    }

    /**
     * Resolves target device from natural language queries like:
     * "phone b", "phone 2", "dusra phone", "pixel", "samsung", "redmi", "shortId (2595)", "teesra phone".
     */
    fun resolveTargetDevice(query: String): IshaDeviceInfo? {
        val clean = query.trim().lowercase()
        val allDevices = _knownDevices.value
        val otherDevices = allDevices.filter { !it.isCurrentDevice }

        if (otherDevices.isEmpty()) return null

        val currentSuffix = if (cachedDeviceId.endsWith("_isha")) "_isha" else "_aura"
        // Prioritize devices with matching app suffix, online status, and latest heartbeat
        val sortedOtherDevices = otherDevices.sortedWith(
            compareByDescending<IshaDeviceInfo> { it.deviceId.endsWith(currentSuffix) }
                .thenByDescending { it.isOnline }
                .thenByDescending { it.lastSeenAt }
        )

        // 1. If only 1 other device exists, any cross-device request routes directly to it
        if (sortedOtherDevices.size == 1) {
            return sortedOtherDevices.first()
        }

        // 2. Exact alias match
        sortedOtherDevices.firstOrNull { it.deviceAlias.equals(clean, ignoreCase = true) }?.let { return it }

        // 3. Dynamic Substring, Token, Short-ID match across ANY Android device
        val cleanTokens = clean.split(Regex("[\\s,_\\-]+")).filter { it.length >= 2 }
        sortedOtherDevices.firstOrNull { dev ->
            val devAlias = dev.deviceAlias.lowercase()
            val devModel = dev.model.lowercase()
            val devMfr = dev.manufacturer.lowercase()
            val devName = dev.deviceName.lowercase()
            val devId = dev.deviceId.lowercase()
            val shortId = dev.deviceId.removePrefix("dev_").take(4).lowercase()

            clean.contains(devAlias) || clean.contains(devModel) || clean.contains(devMfr) || clean.contains(devName) ||
            devAlias.contains(clean) || devModel.contains(clean) || devMfr.contains(clean) || devName.contains(clean) ||
            (shortId.isNotBlank() && clean.contains(shortId)) || devId.contains(clean) ||
            cleanTokens.any { token ->
                devAlias.contains(token) || devModel.contains(token) || devMfr.contains(token) || devName.contains(token) || (shortId.isNotBlank() && token == shortId)
            }
        }?.let { return it }

        // 4. Positional/Ordinal resolution ("pehla phone", "dusra phone", "teesra phone", "first", "second", "third")
        if (clean.contains("pehla") || clean.contains("first") || clean.contains("1st") || clean.contains("phone 1")) {
            sortedOtherDevices.getOrNull(0)?.let { return it }
        }
        if (clean.contains("dusra") || clean.contains("second") || clean.contains("2nd") || clean.contains("phone 2") || clean.contains("dusre phone")) {
            sortedOtherDevices.getOrNull(1)?.let { return it } ?: sortedOtherDevices.getOrNull(0)?.let { return it }
        }
        if (clean.contains("teesra") || clean.contains("third") || clean.contains("3rd") || clean.contains("phone 3")) {
            sortedOtherDevices.getOrNull(2)?.let { return it }
        }
        if (clean.contains("chautha") || clean.contains("fourth") || clean.contains("4th") || clean.contains("phone 4")) {
            sortedOtherDevices.getOrNull(3)?.let { return it }
        }

        // 5. Default to first available active online device, or first registered device
        return sortedOtherDevices.firstOrNull { it.isOnline } ?: sortedOtherDevices.firstOrNull()
    }

    /**
     * Returns list of other devices (excluding current device).
     */
    fun getOtherDevices(): List<IshaDeviceInfo> {
        return _knownDevices.value.filter { !it.isCurrentDevice }
    }
}
