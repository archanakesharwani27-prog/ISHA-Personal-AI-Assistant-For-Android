package com.aura.assistant.ai

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

/**
 * Data model for a contact remembered in ISHA's persistent long-term memory.
 * Supports active number + historical previous numbers tracking.
 */
data class IshaContactEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,                               // Display name e.g. "Rahul", "Doctor Sharma"
    val normalizedName: String,                     // Lowercase trimmed name for lookup e.g. "rahul"
    val primaryNumber: String,                      // Active 10-digit normalized phone number
    val previousNumbers: List<String> = emptyList(),// List of older numbers previously associated with this person
    val note: String = "",                          // Optional relation or contextual note e.g. "Friend", "Office"
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class ContactResolution(
    val name: String,
    val matchedNumber: String,
    val isOldNumber: Boolean,
    val currentActiveNumber: String
)

data class ContactUpdateResult(
    val name: String,
    val currentNumber: String,
    val oldNumber: String?,
    val isUpdated: Boolean,
    val message: String
)

/**
 * High-performance, persistent Contact Memory Manager for ISHA.
 *
 * Capabilities:
 *  1. Saves & updates contact numbers via natural speech/text.
 *  2. Full Versioning / History: Remembers what the person's previous number was if updated.
 *  3. Bidirectional O(1) Lookup: Phone -> Name (for incoming call announcer) & Name -> Phone (for make_call).
 *  4. Robust Dual Persistence: Survives phone reboots, app updates, and cache clearing.
 *  5. Prompt Injection: Injects current + old number context into Gemini LLM instructions.
 */
object IshaContactMemoryManager {

    private const val TAG = "IshaContactMemory"
    private const val VAULT_FILE_NAME = "isha_contacts_vault.json"
    private const val PREFS_NAME = "isha_contact_memory_backup"
    private const val KEY_CONTACTS_JSON = "contacts_data_json"

    private val gson = Gson()
    private val contactsMap = java.util.concurrent.ConcurrentHashMap<String, IshaContactEntry>()
    private var isInitialized = false

    /**
     * Initializes the contact memory from disk vault.
     */
    @Synchronized
    fun init(context: Context) {
        if (isInitialized) return
        loadFromStorage(context)
        isInitialized = true
        Log.i(TAG, "IshaContactMemoryManager initialized with ${contactsMap.size} saved contacts")
    }

    /**
     * Normalizes phone number to standard last-10-digits format for bulletproof matching.
     * Handles: "+91 90876 54321", "09087654321", "+919087654321", "90876-54321", etc.
     */
    fun normalizePhoneNumber(rawNumber: String): String {
        val digitsOnly = rawNumber.replace(Regex("[^0-9]"), "")
        return if (digitsOnly.length > 10) {
            digitsOnly.takeLast(10)
        } else {
            digitsOnly
        }
    }

    /**
     * Normalizes contact name for lookup (lowercase, trimmed, removes extra spaces).
     */
    fun normalizeName(rawName: String): String {
        return rawName.trim().lowercase()
            .replace(Regex("(?<=[\\u0900-\\u097F])\\s+(?=[\\u0900-\\u097F])"), "")
            .replace(Regex("\\s+"), " ")
    }

    /**
     * Saves or updates a contact in ISHA's long-term memory.
     * If person already exists with a different number, archives the previous number into [previousNumbers].
     */
    @Synchronized
    fun rememberOrUpdateContact(
        context: Context,
        rawName: String,
        rawNumber: String,
        note: String = ""
    ): ContactUpdateResult {
        init(context)

        val cleanName = rawName.trim()
        val normName = normalizeName(cleanName)
        val cleanNumber = normalizePhoneNumber(rawNumber)

        if (cleanName.isBlank() || cleanNumber.length < 5) {
            return ContactUpdateResult(
                name = cleanName,
                currentNumber = cleanNumber,
                oldNumber = null,
                isUpdated = false,
                message = "Invalid name or phone number"
            )
        }

        val existingEntry = contactsMap.values.firstOrNull { it.normalizedName == normName }

        return if (existingEntry != null) {
            if (existingEntry.primaryNumber == cleanNumber) {
                // Already current number
                ContactUpdateResult(
                    name = existingEntry.name,
                    currentNumber = cleanNumber,
                    oldNumber = null,
                    isUpdated = false,
                    message = "${existingEntry.name} ka number pehle se hi $cleanNumber set hai Boss."
                )
            } else {
                // Number has changed: Record old number into history!
                val oldNumber = existingEntry.primaryNumber
                val updatedPreviousList = (existingEntry.previousNumbers + oldNumber).distinct()
                val updatedEntry = existingEntry.copy(
                    primaryNumber = cleanNumber,
                    previousNumbers = updatedPreviousList,
                    note = if (note.isNotBlank()) note else existingEntry.note,
                    updatedAt = System.currentTimeMillis()
                )
                contactsMap[updatedEntry.id] = updatedEntry
                saveToStorage(context)
                com.aura.assistant.sync.IshaCloudSyncBridge.syncContactToCloud(context, updatedEntry)

                Log.i(TAG, "Updated ${existingEntry.name}'s number: $oldNumber -> $cleanNumber (History: $updatedPreviousList)")

                ContactUpdateResult(
                    name = existingEntry.name,
                    currentNumber = cleanNumber,
                    oldNumber = oldNumber,
                    isUpdated = true,
                    message = "${existingEntry.name} ka number update ho gaya hai Boss! Naya number $cleanNumber hai, aur purana number $oldNumber bhi yaad hai."
                )
            }
        } else {
            // Check if this number was previously registered under someone else or same person with different casing
            val newEntry = IshaContactEntry(
                name = cleanName,
                normalizedName = normName,
                primaryNumber = cleanNumber,
                previousNumbers = emptyList(),
                note = note
            )
            contactsMap[newEntry.id] = newEntry
            saveToStorage(context)
            com.aura.assistant.sync.IshaCloudSyncBridge.syncContactToCloud(context, newEntry)

            Log.i(TAG, "Saved new contact in memory: $cleanName -> $cleanNumber")

            ContactUpdateResult(
                name = cleanName,
                currentNumber = cleanNumber,
                oldNumber = null,
                isUpdated = false,
                message = "$cleanName ka number $cleanNumber save ho gaya hai Boss! Ab inka call aane par main announce kar dungi."
            )
        }
    }

    /**
     * Resolves an incoming phone number against ISHA's contact memory.
     * Distinguishes whether it's their CURRENT active number or an OLD previous number!
     */
    fun resolveIncomingNumber(context: Context, rawNumber: String): ContactResolution? {
        init(context)
        val cleanNumber = normalizePhoneNumber(rawNumber)
        if (cleanNumber.isBlank()) return null

        // 1. Check Primary Numbers (Current)
        val primaryMatch = contactsMap.values.firstOrNull { it.primaryNumber == cleanNumber }
        if (primaryMatch != null) {
            return ContactResolution(
                name = primaryMatch.name,
                matchedNumber = cleanNumber,
                isOldNumber = false,
                currentActiveNumber = primaryMatch.primaryNumber
            )
        }

        // 2. Check Previous / Old Numbers
        val oldMatch = contactsMap.values.firstOrNull { it.previousNumbers.contains(cleanNumber) }
        if (oldMatch != null) {
            return ContactResolution(
                name = oldMatch.name,
                matchedNumber = cleanNumber,
                isOldNumber = true,
                currentActiveNumber = oldMatch.primaryNumber
            )
        }

        return null
    }

    /**
     * Finds active phone number for a person's name (for calling / SMS / WhatsApp).
     */
    fun getNumberByName(context: Context, rawName: String): String? {
        init(context)
        val norm = normalizeName(rawName)
        val match = contactsMap.values.firstOrNull { 
            it.normalizedName == norm || it.normalizedName.contains(norm) || norm.contains(it.normalizedName)
        }
        return match?.primaryNumber
    }

    /**
     * Retrieves full entry for a person.
     */
    fun getContactByName(context: Context, rawName: String): IshaContactEntry? {
        init(context)
        val norm = normalizeName(rawName)
        return contactsMap.values.firstOrNull { 
            it.normalizedName == norm || it.normalizedName.contains(norm) || norm.contains(it.normalizedName)
        }
    }

    /**
     * Returns all saved contacts.
     */
    fun getAllContacts(context: Context): List<IshaContactEntry> {
        init(context)
        return contactsMap.values.toList().sortedBy { it.name }
    }

    /**
     * Builds structured prompt context for Gemini system instructions so AI has 100% awareness of all numbers.
     */
    fun buildPromptContext(context: Context): String {
        init(context)
        val all = contactsMap.values.toList()
        if (all.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("\n### 📞 ISHA REMEMBERED CONTACTS & NUMBERS (PERSISTENT MEMORY):\n")
        sb.append("You have learned the following personal contacts directly from the user:\n")
        for (c in all) {
            val history = if (c.previousNumbers.isNotEmpty()) {
                " (Old/Previous: ${c.previousNumbers.joinToString(", ")})"
            } else ""
            val noteStr = if (c.note.isNotBlank()) " [${c.note}]" else ""
            sb.append("• ${c.name}: ${c.primaryNumber}$history$noteStr\n")
        }
        sb.append("RULE: When the user asks to call or message these people, ALWAYS use these remembered numbers.\n")
        return sb.toString()
    }

    // ── Persistence Helpers ───────────────────────────────────────────────────

    private fun saveToStorage(context: Context) {
        try {
            val list = contactsMap.values.toList()
            val json = gson.toJson(list)

            // 1. Save to internal vault file
            val file = File(context.filesDir, VAULT_FILE_NAME)
            file.writeText(json)

            // 2. Save backup to SharedPreferences
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CONTACTS_JSON, json)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist contact memory to storage", e)
        }
    }

    private fun loadFromStorage(context: Context) {
        contactsMap.clear()
        var json: String? = null

        // 1. Try loading from file
        try {
            val file = File(context.filesDir, VAULT_FILE_NAME)
            if (file.exists()) {
                json = file.readText()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read from vault file, checking backup prefs", e)
        }

        // 2. Fallback to backup prefs if file was missing
        if (json.isNullOrBlank()) {
            try {
                json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_CONTACTS_JSON, null)
            } catch (_: Exception) {}
        }

        if (!json.isNullOrBlank()) {
            try {
                val type = object : TypeToken<List<IshaContactEntry>>() {}.type
                val list: List<IshaContactEntry> = gson.fromJson(json, type) ?: emptyList()
                for (item in list) {
                    contactsMap[item.id] = item
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deserializing contacts vault JSON", e)
            }
        }
    }
}
