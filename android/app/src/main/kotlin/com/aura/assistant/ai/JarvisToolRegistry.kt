package com.aura.assistant.ai

import android.app.SearchManager
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.FileProvider
import com.aura.assistant.AuraAccessibilityService
import com.aura.assistant.JarvisFileManager
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Registry of all 20+ Jarvis OS automation tools in official Google Gemini Live schema.
 * Dispatches LLM tool calls directly to native Android system APIs.
 */
object JarvisToolRegistry {

    private const val TAG = "JarvisToolRegistry"
    private val gson = Gson()

    /**
     * Builds the JSON array of tool declarations conforming to Gemini's function_declarations specification.
     */
    fun getGeminiToolDeclarations(): JsonArray {
        val toolsArray = JsonArray()

        // 1. Calling
        toolsArray.add(createFunction(
            name = "make_call",
            desc = "Place a phone call to a phone number or named contact.",
            params = mapOf("phone_number" to "string", "contact_name" to "string"),
            required = listOf("phone_number")
        ))

        // 2. SMS
        toolsArray.add(createFunction(
            name = "send_sms",
            desc = "Send a text SMS message to a phone number.",
            params = mapOf("phone_number" to "string", "message" to "string"),
            required = listOf("phone_number", "message")
        ))

        // 3. WhatsApp (via Accessibility automation)
        toolsArray.add(createFunction(
            name = "send_whatsapp",
            desc = "Send a WhatsApp text message to a named contact automatically.",
            params = mapOf("contact_name" to "string", "message" to "string"),
            required = listOf("contact_name", "message")
        ))

        // WhatsApp Media & Screenshot Sender
        toolsArray.add(createFunction(
            name = "send_whatsapp_media",
            desc = "Capture a screenshot or select the latest screenshot/photo, and automatically send it to a contact on WhatsApp.",
            params = mapOf(
                "contact_name" to "string",
                "media_type" to "string",
                "caption" to "string"
            ),
            required = listOf("contact_name")
        ))

        // WhatsApp Chat & Media Deleter
        toolsArray.add(createFunction(
            name = "delete_whatsapp_media",
            desc = "Delete recent messages, photos, or images from a WhatsApp chat with a contact, or clean up WhatsApp media.",
            params = mapOf(
                "contact_name" to "string",
                "target" to "string"
            ),
            required = listOf("contact_name")
        ))

        // Screenshot Capture & Gallery Saver
        toolsArray.add(createFunction(
            name = "take_screenshot",
            desc = "Capture a screenshot of the current Android screen using system accessibility and save it to the Gallery.",
            params = mapOf(),
            required = listOf()
        ))

        // Universal Media & Screenshot Sharer
        toolsArray.add(createFunction(
            name = "share_media",
            desc = "Share media (latest screenshot, photo, image, video, document) directly to WhatsApp, Instagram, Telegram, Twitter/X, or system share menu.",
            params = mapOf(
                "target_app" to "string",
                "type" to "string",
                "caption" to "string",
                "contact_name" to "string"
            ),
            required = listOf()
        ))

        // 4. Open Application
        toolsArray.add(createFunction(
            name = "open_app",
            desc = "Launch an installed Android application by name (e.g. YouTube, Spotify, Camera, WhatsApp).",
            params = mapOf("app_name" to "string"),
            required = listOf("app_name")
        ))

        // 5. Flashlight / Torch
        toolsArray.add(createFunction(
            name = "toggle_flashlight",
            desc = "Turn the phone camera flashlight/torch on or off.",
            params = mapOf("enable" to "boolean"),
            required = listOf("enable")
        ))

        // 6. Volume Control
        toolsArray.add(createFunction(
            name = "set_volume",
            desc = "Set phone volume percentage (0 to 100).",
            params = mapOf("level_percent" to "integer"),
            required = listOf("level_percent")
        ))

        // 7. Screen Tap (Accessibility)
        toolsArray.add(createFunction(
            name = "screen_tap",
            desc = "Tap on a button, text, or coordinate on the active screen.",
            params = mapOf("target_text" to "string"),
            required = listOf("target_text")
        ))

        // 8. Screen Scroll
        toolsArray.add(createFunction(
            name = "screen_scroll",
            desc = "Scroll the current screen up or down.",
            params = mapOf("direction" to "string"), // "up" or "down"
            required = listOf("direction")
        ))

        // 9. Media Control
        toolsArray.add(createFunction(
            name = "media_control",
            desc = "Control playback for music/video: play, pause, next, previous.",
            params = mapOf("action" to "string"),
            required = listOf("action")
        ))

        // 10. Alarms
        toolsArray.add(createFunction(
            name = "create_alarm",
            desc = "Set an alarm for a specific hour and minute.",
            params = mapOf("hour" to "integer", "minute" to "integer", "label" to "string"),
            required = listOf("hour", "minute")
        ))

        // 11. Storage & Files
        toolsArray.add(createFunction(
            name = "search_files",
            desc = "Search device storage for files, photos, videos, or documents.",
            params = mapOf("query" to "string", "file_type" to "string"),
            required = listOf("query")
        ))

        toolsArray.add(createFunction(
            name = "delete_file",
            desc = "Delete a specific file from device storage.",
            params = mapOf("file_path" to "string"),
            required = listOf("file_path")
        ))

        // 12. Call Accept (requires Accessibility or InCallService)
        toolsArray.add(createFunction(
            name = "accept_call",
            desc = "Accept an incoming phone call that is currently ringing.",
            params = emptyMap(),
            required = emptyList()
        ))

        // 13. Call Decline / End
        toolsArray.add(createFunction(
            name = "decline_call",
            desc = "Decline or end an active or incoming phone call.",
            params = emptyMap(),
            required = emptyList()
        ))

        // 14. Identify unknown caller (Truecaller-style lookup)
        toolsArray.add(createFunction(
            name = "identify_caller",
            desc = "Look up information about an unknown phone number — identify if it is spam, business, or a known contact.",
            params = mapOf("phone_number" to "string"),
            required = listOf("phone_number")
        ))

        // 15. Real Hardware Battery Status
        toolsArray.add(createFunction(
            name = "get_battery_status",
            desc = "Get the current exact device battery percentage and charging state directly from Android hardware.",
            params = emptyMap(),
            required = emptyList()
        ))

        // 16. Clipboard Reader
        toolsArray.add(createFunction(
            name = "get_clipboard_text",
            desc = "Read the latest copied text from the Android system clipboard.",
            params = emptyMap(),
            required = emptyList()
        ))

        // 17. Permanent Memory Vault: Remember Fact
        toolsArray.add(createFunction(
            name = "remember_fact",
            desc = "Save an important personal fact, detail, birthday, preference, or relationship into permanent memory.",
            params = mapOf("key" to "string", "value" to "string"),
            required = listOf("key", "value")
        ))

        // 18. Permanent Memory Vault: Recall Memory
        toolsArray.add(createFunction(
            name = "recall_memory",
            desc = "Retrieve previously remembered personal facts or all stored user details from the memory vault.",
            params = mapOf("key" to "string"),
            required = emptyList()
        ))

        // 19. Universal Music & Media Player (Any app: YouTube, Spotify, Melodify, JioSaavn, Wynk, etc.)
        toolsArray.add(createFunction(
            name = "play_media",
            desc = "Search and automatically play a song, music video, playlist, or artist in ANY music app (YouTube, Spotify, Melodify, JioSaavn, Wynk, or default player).",
            params = mapOf("query" to "string", "app_name" to "string"),
            required = listOf("query")
        ))

        // 20. Dedicated YouTube Play
        toolsArray.add(createFunction(
            name = "play_youtube",
            desc = "Directly search and play a song, video, or artist on YouTube.",
            params = mapOf("query" to "string"),
            required = listOf("query")
        ))

        // 21. Universal Arbitrary App Action Engine
        toolsArray.add(createFunction(
            name = "perform_app_action",
            desc = "Perform an autonomous action inside ANY Android application (e.g. Zomato, Swiggy, Telegram, Instagram, Uber, Settings). Launches the app, optionally searches a query or clicks a target button/text.",
            params = mapOf(
                "app_name" to "string",
                "action_description" to "string",
                "search_query" to "string",
                "target_text" to "string"
            ),
            required = listOf("app_name", "action_description")
        ))

        // 22. Mobile Hotspot Control
        toolsArray.add(createFunction(
            name = "toggle_hotspot",
            desc = "Turn the mobile Personal Hotspot / Portable Wi-Fi Hotspot / Tethering ON or OFF hands-free using autonomous Accessibility automation.",
            params = mapOf("enable" to "boolean"),
            required = listOf("enable")
        ))

        // 23. System Navigation Bar Mode (3-Button vs Gesture Navigation)
        toolsArray.add(createFunction(
            name = "set_navigation_mode",
            desc = "Switch Android system navigation bar mode between 'buttons' (3-button navigation bar with back, home, recents) and 'gestures' (full screen gesture navigation).",
            params = mapOf("mode" to "string"),
            required = listOf("mode")
        ))

        // 24. Wi-Fi Control
        toolsArray.add(createFunction(
            name = "toggle_wifi",
            desc = "Turn device Wi-Fi ON or OFF hands-free.",
            params = mapOf("enable" to "boolean"),
            required = listOf("enable")
        ))

        // 25. Bluetooth Control
        toolsArray.add(createFunction(
            name = "toggle_bluetooth",
            desc = "Turn device Bluetooth ON or OFF hands-free.",
            params = mapOf("enable" to "boolean"),
            required = listOf("enable")
        ))

        // 26. Universal System Hardware & Navigation Keys (Panda Engine)
        toolsArray.add(createFunction(
            name = "press_system_key",
            desc = "Execute system-level physical or navigation actions hands-free: 'lock' (lock screen immediately), 'back' (back button), 'home' (home button), 'recents' (recent apps overview), 'notifications' (pull down notification bar), 'quick_settings' (open quick settings tiles), 'screenshot' (capture screen).",
            params = mapOf("key" to "string"),
            required = listOf("key")
        ))

        // 27. Universal App Storage & Cache Management (Panda Engine)
        toolsArray.add(createFunction(
            name = "manage_app_storage",
            desc = "Autonomously manage app cache and storage: 'clear_cache' (opens system app details, taps storage, and clicks clear cache button automatically), 'uninstall' (uninstalls the app), 'open_info' (opens application info page).",
            params = mapOf("app_name" to "string", "action" to "string"),
            required = listOf("app_name", "action")
        ))

        // 28. Autonomous Play Store Search & Install (Panda Engine)
        toolsArray.add(createFunction(
            name = "install_store_app",
            desc = "Search Google Play Store and automatically download/install an app hands-free. Criteria can be: 'top' (installs the top search result), 'lowest_mb' (inspects visible results, finds the app with lowest MB download size, and installs it), or 'highest_rated'.",
            params = mapOf("query" to "string", "criteria" to "string"),
            required = listOf("query")
        ))

        // 29. System Dark Mode / Night Theme
        toolsArray.add(createFunction(
            name = "toggle_dark_mode",
            desc = "Turn system Dark Mode / Night Theme ON or OFF hands-free.",
            params = mapOf("enable" to "boolean"),
            required = listOf("enable")
        ))

        // 30. Direct Math & Calculations
        toolsArray.add(createFunction(
            name = "calculate",
            desc = "Evaluate any mathematical expression or calculation directly.",
            params = mapOf("expression" to "string"),
            required = listOf("expression")
        ))

        // 31. Contacts Management
        toolsArray.add(createFunction(
            name = "manage_contacts",
            desc = "Save a new contact with name and phone number into phone contacts.",
            params = mapOf("name" to "string", "phone_number" to "string"),
            required = listOf("name", "phone_number")
        ))

        // 32. Dedicated Recent Media / Screenshot Viewer
        toolsArray.add(createFunction(
            name = "show_recent_media",
            desc = "Open and display the most recent screenshot or photo from device storage fullscreen in the default image viewer. Call this when the user says 'show screenshot', 'recent screenshot dikhao', 'open screenshot', 'show photo'.",
            params = mapOf("media_type" to "string"),
            required = emptyList()
        ))

        // 33. Gallery Recycle Bin / Trash Cleaner
        toolsArray.add(createFunction(
            name = "empty_recycle_bin",
            desc = "Empty the Gallery Recycle Bin or Trash permanently deleting all trashed items hands-free. Call this when the user says 'empty recycle bin', 'trash clear karo', 'recycle bin saaf karo'.",
            params = mapOf("target" to "string"),
            required = emptyList()
        ))

        // 34. Clock Timer
        toolsArray.add(createFunction(
            name = "set_timer",
            desc = "Set a timer/countdown using the Android Clock app for a specified number of minutes and seconds.",
            params = mapOf(
                "minutes" to "integer",
                "seconds" to "integer",
                "label" to "string"
            ),
            required = listOf("minutes")
        ))

        // 35. Maps & Navigation
        toolsArray.add(createFunction(
            name = "navigate_maps",
            desc = "Open turn-by-turn navigation or search directions to a destination (address, landmark, restaurant, petrol pump, etc.) using Google Maps.",
            params = mapOf(
                "destination" to "string",
                "mode" to "string"
            ),
            required = listOf("destination")
        ))

        // 36. Web Search
        toolsArray.add(createFunction(
            name = "web_search",
            desc = "Perform a web search on Google or open search results in browser.",
            params = mapOf("query" to "string"),
            required = listOf("query")
        ))

        // 37. Open Camera
        toolsArray.add(createFunction(
            name = "open_camera",
            desc = "Open the phone Camera app to take a photo, record video, or take a selfie ('photo', 'video', 'selfie').",
            params = mapOf("mode" to "string"),
            required = emptyList()
        ))

        // 38. Screen Brightness
        toolsArray.add(createFunction(
            name = "set_brightness",
            desc = "Set screen brightness percentage (0 to 100).",
            params = mapOf("level_percent" to "integer"),
            required = listOf("level_percent")
        ))

        // 39. Do Not Disturb (DND)
        toolsArray.add(createFunction(
            name = "set_dnd",
            desc = "Turn Do Not Disturb (DND) mode ON or OFF.",
            params = mapOf("enable" to "boolean"),
            required = listOf("enable")
        ))

        // 40. Open System Settings
        toolsArray.add(createFunction(
            name = "open_system_settings",
            desc = "Open a specific Android System Settings page (e.g. wifi, bluetooth, display, sound, battery, apps, accessibility, developer, storage, date, airplane, location, nfc, notification).",
            params = mapOf("setting_name" to "string"),
            required = listOf("setting_name")
        ))

        // 41. Calendar Event
        toolsArray.add(createFunction(
            name = "add_calendar_event",
            desc = "Create a calendar event or schedule a meeting in the default Calendar app.",
            params = mapOf(
                "title" to "string",
                "description" to "string",
                "location" to "string"
            ),
            required = listOf("title")
        ))

        // 42. Send Email
        toolsArray.add(createFunction(
            name = "send_email",
            desc = "Compose an email in Gmail or default email app.",
            params = mapOf(
                "to" to "string",
                "subject" to "string",
                "body" to "string"
            ),
            required = listOf("to")
        ))

        // 43. Read Recent SMS
        toolsArray.add(createFunction(
            name = "read_recent_sms",
            desc = "Read the latest received SMS text messages from the phone's inbox.",
            params = mapOf("count" to "integer"),
            required = emptyList()
        ))

        // 44. Query Contact Details
        toolsArray.add(createFunction(
            name = "query_contact",
            desc = "Look up a person's contact number or details from phone contacts.",
            params = mapOf("name" to "string"),
            required = listOf("name")
        ))

        // 45. Emergency SOS
        toolsArray.add(createFunction(
            name = "emergency_sos",
            desc = "Trigger emergency SOS call to 112 or emergency contact, and optionally send emergency alert SMS.",
            params = mapOf(
                "target" to "string",
                "message" to "string"
            ),
            required = emptyList()
        ))

        // 46. Create Quick Note
        toolsArray.add(createFunction(
            name = "create_quick_note",
            desc = "Save a quick personal note, reminder, or thought to the AURA permanent memory vault.",
            params = mapOf(
                "note_text" to "string",
                "title" to "string"
            ),
            required = listOf("note_text")
        ))

        // 47. Read Status Bar Notifications
        toolsArray.add(createFunction(
            name = "read_notifications",
            desc = "Read or check active status bar notifications (e.g. WhatsApp messages, SMS, OTPs, emails, food delivery, banking alerts) received on the device.",
            params = mapOf(
                "app_filter" to "string",
                "count" to "integer"
            ),
            required = emptyList()
        ))

        return toolsArray
    }

    private fun createFunction(
        name: String,
        desc: String,
        params: Map<String, String>,
        required: List<String>
    ): JsonObject {
        val fn = JsonObject()
        fn.addProperty("name", name)
        fn.addProperty("description", desc)

        val parameters = JsonObject()
        parameters.addProperty("type", "OBJECT")

        val properties = JsonObject()
        for ((pName, pType) in params) {
            val prop = JsonObject()
            prop.addProperty("type", when (pType.lowercase()) {
                "string" -> "STRING"
                "integer" -> "INTEGER"
                "boolean" -> "BOOLEAN"
                else -> "STRING"
            })
            properties.add(pName, prop)
        }
        parameters.add("properties", properties)

        val reqArray = JsonArray()
        for (r in required) {
            reqArray.add(r)
        }
        parameters.add("required", reqArray)

        fn.add("parameters", parameters)
        return fn
    }

    /**
     * Executes a tool call requested by Gemini and returns the structured result.
     */
    fun executeTool(context: Context, name: String, args: JsonObject): JsonObject {
        Log.i(TAG, "Executing tool: $name with args: $args")
        val result = JsonObject()

        try {
            when (name) {
                "make_call" -> {
                    val phone = args.get("phone_number")?.asString ?: ""
                    val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    result.addProperty("status", "success")
                    result.addProperty("message", "Initiated call to $phone")
                }

                "send_sms" -> {
                    val phone = args.get("phone_number")?.asString ?: ""
                    val msg = args.get("message")?.asString ?: ""
                    val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        context.getSystemService(SmsManager::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        SmsManager.getDefault()
                    }
                    smsManager.sendTextMessage(phone, null, msg, null, null)
                    result.addProperty("status", "success")
                    result.addProperty("message", "SMS sent to $phone")
                }

                "send_whatsapp" -> {
                    val contact = args.get("contact_name")?.asString
                        ?: args.get("contact")?.asString
                        ?: args.get("to")?.asString
                        ?: ""
                    var msg = args.get("message")?.asString ?: args.get("text")?.asString ?: ""

                    // If user asked to send screenshot/photo, route directly to real media sender
                    if (msg.contains("screenshot", ignoreCase = true) || contact.contains("screenshot", ignoreCase = true) ||
                        msg.contains("photo", ignoreCase = true) || msg.contains("image", ignoreCase = true)) {
                        val cleanContact = contact.replace("screenshot", "", ignoreCase = true).trim().ifBlank { "Ansh" }
                        val mediaArgs = JsonObject().apply {
                            addProperty("contact_name", cleanContact)
                            addProperty("media_type", "screenshot")
                            addProperty("caption", if (!msg.contains("screenshot", ignoreCase = true)) msg else "")
                        }
                        return executeTool(context, "send_whatsapp_media", mediaArgs)
                    }

                    // If user asked to send copied text or message is blank, read from clipboard
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                    if (!clipText.isNullOrBlank() && (msg.isBlank() || msg.contains("copy", ignoreCase = true) || msg.contains("paste", ignoreCase = true) || msg.contains("clipboard", ignoreCase = true))) {
                        msg = clipText
                    }

                    if (msg.isBlank()) {
                        msg = "Hello"
                    }

                    val cleanContact = contact.replace(Regex("\\b(ko|ji|bhai|sahab|de|ka|ki|se|pe|par)\\b", RegexOption.IGNORE_CASE), "").trim()
                    val resolvedPhone = resolveContactNumber(context, if (cleanContact.isNotBlank()) cleanContact else contact)
                    val a11y = AuraAccessibilityService.instance
                    val waPkg = try {
                        context.packageManager.getPackageInfo("com.whatsapp", 0)
                        "com.whatsapp"
                    } catch (_: Exception) {
                        "com.whatsapp.w4b"
                    }

                    if (!resolvedPhone.isNullOrBlank()) {
                        val digits = resolvedPhone.replace(Regex("[^0-9]"), "")
                        val waNumber = if (digits.length == 10 && !digits.startsWith("0")) "91$digits" else digits
                        try {
                            val encodedMsg = URLEncoder.encode(msg, "UTF-8")
                            val uri = Uri.parse("https://wa.me/$waNumber?text=$encodedMsg")
                            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                setPackage(waPkg)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                            a11y?.armWhatsappAutoSendPreFilled()
                            result.addProperty("status", "success")
                            result.addProperty("message", "WhatsApp message sent to $contact: \"$msg\"")
                        } catch (e: Exception) {
                            result.addProperty("status", "error")
                            result.addProperty("message", "Could not open WhatsApp: ${e.message}")
                        }
                    } else if (contact.isNotBlank() && a11y != null) {
                        val intent = context.packageManager.getLaunchIntentForPackage(waPkg)
                        if (intent != null) {
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(intent)
                        }
                        a11y.armWhatsappSearchAndSend(cleanContact.ifBlank { contact }, msg)
                        result.addProperty("status", "success")
                        result.addProperty("message", "Opened WhatsApp chat for $contact and sent: \"$msg\"")
                    } else {
                        val intent = context.packageManager.getLaunchIntentForPackage(waPkg)
                        if (intent != null) {
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(intent)
                        }
                        a11y?.armWhatsappAutoSend(msg)
                        result.addProperty("status", "success")
                        result.addProperty("message", "WhatsApp opened to send: \"$msg\"")
                    }
                }

                "send_whatsapp_media" -> {
                    val contact = args.get("contact_name")?.asString
                        ?: args.get("contact")?.asString
                        ?: args.get("to")?.asString
                        ?: ""
                    val mediaType = (args.get("media_type")?.asString ?: "screenshot").lowercase()
                    val caption = args.get("caption")?.asString ?: args.get("message")?.asString ?: ""

                    val a11y = AuraAccessibilityService.instance
                    var imageUri: Uri? = null

                    if (mediaType.contains("screenshot") || mediaType.contains("screen")) {
                        if (a11y != null) {
                            val latch = CountDownLatch(1)
                            a11y.takeScreenCapture { bytes ->
                                if (bytes != null) {
                                    imageUri = saveBytesToGallery(context, bytes)
                                }
                                latch.countDown()
                            }
                            try { latch.await(2000, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
                        }
                        if (imageUri == null) {
                            a11y?.performGlobalActionByKey("screenshot")
                            try { Thread.sleep(1200) } catch (_: Exception) {}
                            imageUri = getLatestScreenshotOrImageUri(context)
                        }
                    } else {
                        imageUri = getLatestScreenshotOrImageUri(context)
                    }

                    if (imageUri == null) {
                        result.addProperty("status", "error")
                        result.addProperty("message", "Device par koi screenshot ya photo nahi mili Boss!")
                        return result
                    }

                    val cleanContact = contact.replace(Regex("\\b(ko|ji|bhai|sahab|de|ka|ki|se|pe|par)\\b", RegexOption.IGNORE_CASE), "").trim()
                    val resolvedPhone = if (cleanContact.isNotBlank()) resolveContactNumber(context, cleanContact) else null
                    val digits = resolvedPhone?.replace(Regex("[^0-9]"), "") ?: ""
                    val waNumber = if (digits.length == 10 && !digits.startsWith("0")) "91$digits" else digits
                    val waPkg = try {
                        context.packageManager.getPackageInfo("com.whatsapp", 0)
                        "com.whatsapp"
                    } catch (_: Exception) {
                        "com.whatsapp.w4b"
                    }

                    try {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/*"
                            setPackage(waPkg)
                            putExtra(Intent.EXTRA_STREAM, imageUri)
                            if (caption.isNotBlank()) {
                                putExtra(Intent.EXTRA_TEXT, caption)
                            }
                            if (waNumber.isNotBlank()) {
                                putExtra("jid", "$waNumber@s.whatsapp.net")
                            }
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        a11y?.armWhatsappSendImage()
                        result.addProperty("status", "success")
                        result.addProperty("message", "Screenshot capture karke WhatsApp par ${contact.ifBlank { "chat" }} ko send kar diya gaya hai Boss! 📸💬")
                    } catch (e: Exception) {
                        result.addProperty("status", "error")
                        result.addProperty("message", "WhatsApp me screenshot bhejne me issue aaya: ${e.message}")
                    }
                }

                "take_screenshot" -> {
                    val a11y = AuraAccessibilityService.instance
                    if (a11y != null) {
                        var savedUri: Uri? = null
                        val latch = CountDownLatch(1)
                        a11y.takeScreenCapture { bytes ->
                            if (bytes != null) {
                                savedUri = saveBytesToGallery(context, bytes)
                            }
                            latch.countDown()
                        }
                        try { latch.await(2500, TimeUnit.MILLISECONDS) } catch (_: Exception) {}

                        if (savedUri != null) {
                            result.addProperty("status", "success")
                            result.addProperty("message", "Screenshot taken and saved to Gallery! 📸")
                            result.addProperty("uri", savedUri.toString())
                        } else {
                            val sysOk = a11y.takeSystemScreenshot()
                            result.addProperty("status", if (sysOk) "success" else "error")
                            result.addProperty("message", if (sysOk) "Screenshot captured successfully! 📸" else "Could not capture screenshot.")
                        }
                    } else {
                        result.addProperty("status", "error")
                        result.addProperty("message", "Aura Accessibility Service is not active. Please enable it in Android Settings to capture screenshots.")
                    }
                }

                "share_media" -> {
                    val targetApp = (args.get("target_app")?.asString ?: args.get("app")?.asString ?: "whatsapp").lowercase()
                    val caption = args.get("caption")?.asString ?: args.get("message")?.asString ?: ""
                    val contact = args.get("contact_name")?.asString ?: args.get("contact")?.asString ?: ""

                    val a11y = AuraAccessibilityService.instance
                    var imageUri = getLatestScreenshotOrImageUri(context)
                    if (imageUri == null && a11y != null) {
                        val latch = CountDownLatch(1)
                        a11y.takeScreenCapture { bytes ->
                            if (bytes != null) {
                                imageUri = saveBytesToGallery(context, bytes)
                            }
                            latch.countDown()
                        }
                        try { latch.await(2000, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
                    }

                    if (imageUri == null) {
                        result.addProperty("status", "error")
                        result.addProperty("message", "Device par koi screenshot ya image nahi mili share karne ke liye!")
                        return result
                    }

                    val cleanContact = contact.replace(Regex("\\b(ko|ji|bhai|sahab|de|ka|ki|se|pe|par)\\b", RegexOption.IGNORE_CASE), "").trim()
                    val resolvedPhone = if (cleanContact.isNotBlank()) resolveContactNumber(context, cleanContact) else null
                    val digits = resolvedPhone?.replace(Regex("[^0-9]"), "") ?: ""
                    val waNumber = if (digits.length == 10 && !digits.startsWith("0")) "91$digits" else digits

                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/*"
                        putExtra(Intent.EXTRA_STREAM, imageUri)
                        if (caption.isNotBlank()) putExtra(Intent.EXTRA_TEXT, caption)
                        if (targetApp.contains("whatsapp")) {
                            val waPkg = try {
                                context.packageManager.getPackageInfo("com.whatsapp", 0)
                                "com.whatsapp"
                            } catch (_: Exception) {
                                "com.whatsapp.w4b"
                            }
                            setPackage(waPkg)
                            if (waNumber.isNotBlank()) putExtra("jid", "$waNumber@s.whatsapp.net")
                        } else if (targetApp.contains("insta")) {
                            setPackage("com.instagram.android")
                        } else if (targetApp.contains("telegram")) {
                            setPackage("org.telegram.messenger")
                        }
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    try {
                        context.startActivity(intent)
                        if (targetApp.contains("whatsapp")) {
                            a11y?.armWhatsappSendImage()
                        }
                        result.addProperty("status", "success")
                        result.addProperty("message", "Shared media to $targetApp successfully! 📤")
                    } catch (_: Exception) {
                        val chooser = Intent.createChooser(intent, "Share media via AURA").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(chooser)
                        result.addProperty("status", "success")
                        result.addProperty("message", "Opened Share Menu for media! 📤")
                    }
                }

                "send_bulk_whatsapp" -> {
                    val msg = args.get("message")?.asString ?: "Hello"
                    val contactsStr = args.get("contacts")?.asString ?: ""
                    val contactsList = contactsStr.split(Regex("[,;\\n]+")).map { it.trim() }.filter { it.isNotBlank() }
                    var sentCount = 0
                    for (c in contactsList) {
                        val num = resolveContactNumber(context, c)
                        if (num != null) {
                            val digits = num.replace(Regex("[^0-9]"), "")
                            val waNum = if (digits.length == 10 && !digits.startsWith("0")) "91$digits" else digits
                            val uri = Uri.parse("https://wa.me/$waNum?text=${URLEncoder.encode(msg, "UTF-8")}")
                            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                setPackage("com.whatsapp")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                            AuraAccessibilityService.instance?.armWhatsappAutoSendPreFilled()
                            sentCount++
                            try { Thread.sleep(1500) } catch (_: Exception) {}
                        }
                    }
                    result.addProperty("status", "success")
                    result.addProperty("message", "WhatsApp message sent to $sentCount contacts!")
                }

                "delete_whatsapp_media" -> {
                    val contact = args.get("contact_name")?.asString
                        ?: args.get("contact")?.asString
                        ?: ""
                    val resolvedPhone = resolveContactNumber(context, contact)
                    val a11y = AuraAccessibilityService.instance

                    if (!resolvedPhone.isNullOrBlank()) {
                        val digits = resolvedPhone.replace(Regex("[^0-9]"), "")
                        val waNumber = if (digits.length == 10 && !digits.startsWith("0")) "91$digits" else digits
                        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$waNumber")
                        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                            setPackage("com.whatsapp")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        try { context.startActivity(intent) } catch (_: Exception) {}
                        a11y?.armWhatsappDeleteLastMessage()
                    } else {
                        val intent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
                        if (intent != null) {
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(intent)
                        }
                        if (contact.isNotBlank()) {
                            a11y?.armWhatsappOpenContactChat(contact) {
                                a11y.armWhatsappDeleteLastMessage()
                            }
                        } else {
                            a11y?.armWhatsappDeleteLastMessage()
                        }
                    }

                    // Also search and vault any recent WhatsApp Image files from device storage
                    var localDeleted = 0
                    try {
                        val files = JarvisFileManager.searchFiles(context, "WhatsApp", 10)
                        val imgFiles = files.filter { it.path.contains("WhatsApp Images", ignoreCase = true) || it.name.startsWith("IMG-", ignoreCase = true) }
                        imgFiles.take(2).forEach { f ->
                            val vaulted = JarvisFileManager.deleteToVault(context, f.path)
                            if (vaulted != null) localDeleted++
                        }
                    } catch (_: Exception) {}

                    result.addProperty("status", "success")
                    val detail = if (localDeleted > 0) " (aur $localDeleted WhatsApp photos phone storage se recycle bin me move kar di)" else ""
                    result.addProperty("message", "WhatsApp par $contact ke last message/photos delete karne ki action execute kar di gayi hai Boss! 🗑️$detail")
                }

                "show_recent_media" -> {
                    val uri = getLatestScreenshotOrImageUri(context)
                    if (uri != null) {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "image/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                            result.addProperty("status", "success")
                            result.addProperty("message", "Recent screenshot opened fullscreen in image viewer! 📸")
                        } catch (e: Exception) {
                            result.addProperty("status", "error")
                            result.addProperty("message", "Could not open image viewer: ${e.message}")
                        }
                    } else {
                        result.addProperty("status", "error")
                        result.addProperty("message", "Device par koi recent screenshot ya photo nahi mili Boss!")
                    }
                }

                "empty_recycle_bin" -> {
                    val a11y = AuraAccessibilityService.instance
                    if (a11y != null) {
                        a11y.armEmptyRecycleBin()
                        result.addProperty("status", "success")
                        result.addProperty("message", "Gallery ke Recycle Bin ko permanently clear kar diya gaya hai Boss! 🗑️✨")
                    } else {
                        val galleryPkg = resolveAppPackage(context, "Gallery") ?: "com.sec.android.gallery3d"
                        val launchIntent = context.packageManager.getLaunchIntentForPackage(galleryPkg)
                        if (launchIntent != null) {
                            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(launchIntent)
                        }
                        result.addProperty("status", "success")
                        result.addProperty("message", "Gallery open kardi hai Boss, Accessibility on karke Recycle Bin automatically clear ho jayega!")
                    }
                }

                "open_app" -> {
                    val appName = args.get("app_name")?.asString ?: ""
                    val pm = context.packageManager
                    val intent = pm.getLaunchIntentForPackage(appName)
                        ?: run {
                            // Find package by label
                            val apps = pm.getInstalledApplications(0)
                            val matched = apps.firstOrNull {
                                pm.getApplicationLabel(it).toString().contains(appName, ignoreCase = true)
                            }
                            matched?.let { pm.getLaunchIntentForPackage(it.packageName) }
                        }

                    if (intent != null) {
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(intent)
                        result.addProperty("status", "success")
                        result.addProperty("message", "Launched $appName")
                    } else {
                        result.addProperty("status", "error")
                        result.addProperty("message", "App '$appName' not found on device")
                    }
                }

                "toggle_flashlight" -> {
                    val enable = args.get("enable")?.asBoolean ?: true
                    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val cameraId = cameraManager.cameraIdList.firstOrNull() ?: "0"
                    cameraManager.setTorchMode(cameraId, enable)
                    result.addProperty("status", "success")
                    result.addProperty("message", "Flashlight turned ${if (enable) "ON" else "OFF"}")
                }

                "set_volume" -> {
                    val level = extractPercent(args, "level_percent", "level", "volume", "value", "percent")
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val maxMusic = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val targetMusic = (maxMusic * (level / 100.0f)).toInt().coerceIn(0, maxMusic)

                    // Execute on UI thread if MainActivity is active for flawless Volume Dialog HUD display
                    com.aura.assistant.MainActivity.instance?.runOnUiThread {
                        try {
                            am.setStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                targetMusic,
                                AudioManager.FLAG_SHOW_UI or AudioManager.FLAG_PLAY_SOUND
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "UI thread setStreamVolume error: $e")
                        }
                    } ?: run {
                        try {
                            am.setStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                targetMusic,
                                AudioManager.FLAG_SHOW_UI or AudioManager.FLAG_PLAY_SOUND
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Direct setStreamVolume error: $e")
                        }
                    }

                    // Safely adjust system/notification streams without breaking on DND restrictions
                    val secondaryStreams = listOf(
                        AudioManager.STREAM_SYSTEM,
                        AudioManager.STREAM_VOICE_CALL,
                        AudioManager.STREAM_RING,
                        AudioManager.STREAM_NOTIFICATION
                    )
                    for (stream in secondaryStreams) {
                        try {
                            val max = am.getStreamMaxVolume(stream)
                            val target = (max * (level / 100.0f)).toInt().coerceIn(0, max)
                            am.setStreamVolume(stream, target, 0)
                        } catch (_: Throwable) {}
                    }

                    result.addProperty("status", "success")
                    result.addProperty("volume_percent", level)
                    result.addProperty("message", "Volume $level% par set kar diya hai Boss! 🔊")
                }

                "media_control" -> {
                    val action = (args.get("action")?.asString ?: "play_pause").lowercase().trim()
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val keyCode = when {
                        action.contains("play") && !action.contains("pause") -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                        action.contains("pause") || action.contains("stop") -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                        action.contains("next") || action.contains("forward") || action.contains("skip") -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                        action.contains("prev") || action.contains("back") -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                        else -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    }
                    am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
                    am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
                    result.addProperty("status", "success")
                    result.addProperty("action", action)
                    result.addProperty("message", "Media playback $action kar diya hai Boss! 🎵")
                }

                "create_alarm" -> {
                    val hour = args.get("hour")?.asInt ?: 7
                    val min = args.get("minute")?.asInt ?: 0
                    val label = args.get("label")?.asString ?: "AURA Alarm"
                    val minStr = min.toString().padStart(2, '0')
                    try {
                        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                            putExtra(AlarmClock.EXTRA_HOUR, hour)
                            putExtra(AlarmClock.EXTRA_MINUTES, min)
                            putExtra(AlarmClock.EXTRA_MESSAGE, label)
                            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                        result.addProperty("status", "success")
                        result.addProperty("message", "Alarm set for $hour:$minStr ($label)")
                    } catch (e: Exception) {
                        try {
                            val fallbackIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                                putExtra(AlarmClock.EXTRA_HOUR, hour)
                                putExtra(AlarmClock.EXTRA_MINUTES, min)
                                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(fallbackIntent)
                            result.addProperty("status", "success")
                            result.addProperty("message", "Alarm clock open kar di hai $hour:$minStr ke liye ($label)")
                        } catch (e2: Exception) {
                            val clockIntent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(clockIntent)
                            result.addProperty("status", "success")
                            result.addProperty("message", "Clock app open kar diya hai Boss $hour:$minStr ke alarm ke liye.")
                        }
                    }
                }

                "screen_tap" -> {
                    val targetText = args.get("target_text")?.asString ?: ""
                    val a11y = AuraAccessibilityService.instance
                    if (a11y != null) {
                        val tapped = a11y.performClickByText(targetText)
                        result.addProperty("status", if (tapped) "success" else "not_found")
                        result.addProperty("message", if (tapped) "Tapped '$targetText'" else "Could not find '$targetText' on screen")
                    } else {
                        result.addProperty("status", "error")
                        result.addProperty("message", "Accessibility Service is not enabled")
                    }
                }

                "screen_scroll" -> {
                    val direction = args.get("direction")?.asString ?: "down"
                    val a11y = AuraAccessibilityService.instance
                    if (a11y != null) {
                        val scrolled = a11y.scrollWindow(direction.equals("down", ignoreCase = true))
                        result.addProperty("status", if (scrolled) "success" else "failed")
                    } else {
                        result.addProperty("status", "error")
                        result.addProperty("message", "Accessibility Service is not enabled")
                    }
                }

                "search_files" -> {
                    val query = args.get("query")?.asString ?: ""
                    val files = JarvisFileManager.searchFiles(context, query, 20)
                    val array = JsonArray()
                    for (f in files.take(10)) {
                        val item = JsonObject()
                        item.addProperty("name", f.name)
                        item.addProperty("path", f.path)
                        item.addProperty("size", f.sizeBytes)
                        array.add(item)
                    }
                    result.add("files", array)
                    result.addProperty("status", "success")
                    result.addProperty("count", files.size)
                }

                "delete_file" -> {
                    val path = args.get("file_path")?.asString ?: ""
                    val vaultPath = JarvisFileManager.deleteToVault(context, path)
                    result.addProperty("status", if (vaultPath != null) "success" else "failed")
                    result.addProperty("message", if (vaultPath != null) "File backed up to vault and deleted: $vaultPath" else "Could not delete file")
                }

                "accept_call" -> {
                    // Accept via Accessibility Service (taps the green answer button)
                    val a11y = AuraAccessibilityService.instance
                    if (a11y != null) {
                        // Try tapping the answer button by content description or label
                        val accepted = a11y.performClickByText("Answer") ||
                                       a11y.performClickByText("Accept") ||
                                       a11y.performClickByText("Call answer")
                        if (accepted) {
                            result.addProperty("status", "success")
                            result.addProperty("message", "Call accepted")
                        } else {
                            // Fallback: simulate headset hook button press to accept call
                            try {
                                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                                val acceptIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                                    putExtra(
                                        Intent.EXTRA_KEY_EVENT,
                                        android.view.KeyEvent(
                                            android.view.KeyEvent.ACTION_DOWN,
                                            android.view.KeyEvent.KEYCODE_HEADSETHOOK
                                        )
                                    )
                                }
                                context.sendBroadcast(acceptIntent)
                            } catch (_: Exception) {}
                            result.addProperty("status", "attempted")
                            result.addProperty("message", "Attempted to accept call — enable Accessibility for best results")
                        }
                    } else {
                        result.addProperty("status", "error")
                        result.addProperty("message", "Accessibility Service not enabled — go to Settings > Accessibility > AURA")
                    }
                }

                "decline_call" -> {
                    val a11y = AuraAccessibilityService.instance
                    if (a11y != null) {
                        val declined = a11y.performClickByText("Decline") ||
                                       a11y.performClickByText("Reject") ||
                                       a11y.performClickByText("Ignore") ||
                                       a11y.performClickByText("End call")
                        result.addProperty("status", if (declined) "success" else "attempted")
                        result.addProperty("message", if (declined) "Call declined" else "Tried to decline — enable Accessibility for best results")
                    } else {
                        // Android 9+ allows ending via TelecomManager with ANSWER_PHONE_CALLS permission
                        try {
                            val telecom = context.getSystemService(android.telecom.TelecomManager::class.java)
                            telecom?.endCall()
                            result.addProperty("status", "success")
                            result.addProperty("message", "Call ended")
                        } catch (e: SecurityException) {
                            result.addProperty("status", "error")
                            result.addProperty("message", "Permission denied — enable Accessibility Service for call control")
                        }
                    }
                }

                "identify_caller" -> {
                    val number = args.get("phone_number")?.asString ?: ""
                    if (number.isBlank()) {
                        result.addProperty("status", "error")
                        result.addProperty("message", "No phone number provided")
                    } else {
                        // Check local contacts first
                        val cleanNumber = number.replace("[^0-9+]".toRegex(), "")
                        val contactName = lookupLocalContact(context, cleanNumber)
                        if (contactName != null) {
                            result.addProperty("status", "found")
                            result.addProperty("name", contactName)
                            result.addProperty("source", "contacts")
                            result.addProperty("message", "This number belongs to $contactName in your contacts")
                        } else {
                            // Not in contacts — report as unknown
                            result.addProperty("status", "unknown")
                            result.addProperty("number", cleanNumber)
                            result.addProperty("message", "This number ($cleanNumber) is not in your contacts. It may be a spam or unknown caller. I'd recommend not answering calls from unknown numbers.")
                        }
                    }
                }

                "get_battery_status" -> {
                    try {
                        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                        val isCharging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
                            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                        } else {
                            false
                        }
                        result.addProperty("status", "success")
                        result.addProperty("battery_percent", level)
                        result.addProperty("is_charging", isCharging)
                        result.addProperty("message", "Current battery is at $level%${if (isCharging) " (charging)" else ""}")
                    } catch (e: Exception) {
                        result.addProperty("status", "error")
                        result.addProperty("message", "Failed to read battery: ${e.message}")
                    }
                }

                "get_clipboard_text" -> {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                    result.addProperty("status", "success")
                    result.addProperty("clipboard_text", clipText)
                    result.addProperty("message", if (clipText.isNotBlank()) "Copied text: \"$clipText\"" else "Clipboard is empty")
                }

                "remember_fact" -> {
                    val key = args.get("key")?.asString ?: ""
                    val value = args.get("value")?.asString ?: ""
                    AuraMemoryManager.rememberFact(context, key, value)
                    result.addProperty("status", "success")
                    result.addProperty("message", "Fact remembered successfully: $key = $value")
                }

                "recall_memory" -> {
                    val key = args.get("key")?.asString ?: ""
                    result.addProperty("status", "success")
                    if (key.isNotBlank()) {
                        val v = AuraMemoryManager.recallMemory(context, key)
                        result.addProperty("value", v ?: "No memory found for $key")
                    } else {
                        val all = AuraMemoryManager.getAllMemories(context)
                        result.addProperty("memories", gson.toJson(all))
                    }
                }

                "play_youtube" -> {
                    val query = args.get("query")?.asString
                        ?: args.get("song")?.asString
                        ?: args.get("video")?.asString
                        ?: ""
                    val success = playMediaInApp(context, query, "youtube")
                    result.addProperty("status", if (success) "success" else "error")
                    result.addProperty("query", query)
                    result.addProperty("message", "YouTube par \"$query\" play kiya ja raha hai Boss! 🎵")
                }

                "play_media" -> {
                    val query = args.get("query")?.asString
                        ?: args.get("song")?.asString
                        ?: args.get("title")?.asString
                        ?: ""
                    val app = args.get("app_name")?.asString
                        ?: args.get("app")?.asString
                        ?: "youtube"
                    val success = playMediaInApp(context, query, app)
                    result.addProperty("status", if (success) "success" else "error")
                    result.addProperty("app", app)
                    result.addProperty("query", query)
                    result.addProperty("message", "$app par \"$query\" play kiya ja raha hai Boss! 🎵")
                }

                "toggle_hotspot" -> {
                    val enable = args.get("enable")?.asBoolean ?: true
                    val a11y = AuraAccessibilityService.instance
                    if (a11y != null) {
                        a11y.armHotspotToggle(enable)
                        result.addProperty("status", "success")
                        result.addProperty("message", "Personal Hotspot ${if (enable) "ON" else "OFF"} kiya ja raha hai Boss! 📶")
                    } else {
                        val intent = Intent("android.settings.TETHER_SETTINGS").apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        try {
                            context.startActivity(intent)
                            result.addProperty("status", "settings_opened")
                            result.addProperty("message", "Hotspot settings open kar di hai Boss.")
                        } catch (_: Exception) {
                            val fallback = Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            try { context.startActivity(fallback) } catch (_: Exception) {}
                            result.addProperty("status", "settings_opened")
                            result.addProperty("message", "Wireless settings open kar di hai Boss.")
                        }
                    }
                }

                "set_navigation_mode" -> {
                    val rawMode = args.get("mode")?.asString ?: "buttons"
                    val mode = if (rawMode.contains("gesture", ignoreCase = true)) "gestures" else "buttons"
                    val a11y = AuraAccessibilityService.instance
                    if (a11y != null) {
                        a11y.armNavigationMode(mode)
                        result.addProperty("status", "success")
                        result.addProperty("message", "Navigation mode ${if (mode == "buttons") "3-button" else "gestures"} me switch kiya ja raha hai Boss! 🔘")
                    } else {
                        val directIntent = Intent("android.settings.SYSTEM_NAVIGATION_SETTINGS").apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        if (directIntent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(directIntent)
                        } else {
                            val settingsIntent = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            try { context.startActivity(settingsIntent) } catch (_: Exception) {}
                        }
                        result.addProperty("status", "settings_opened")
                        result.addProperty("message", "Navigation settings open kar di hai Boss.")
                    }
                }

                "toggle_wifi" -> {
                    val enable = args.get("enable")?.asBoolean ?: true
                    val a11y = AuraAccessibilityService.instance
                    if (a11y != null) {
                        a11y.armWifiToggle(enable)
                        result.addProperty("status", "success")
                        result.addProperty("message", "Wi-Fi ${if (enable) "ON" else "OFF"} kiya ja raha hai Boss! 📶")
                    } else {
                        val intent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        try { context.startActivity(intent) } catch (_: Exception) {}
                        result.addProperty("status", "settings_opened")
                        result.addProperty("message", "Wi-Fi settings open kar di hai Boss.")
                    }
                }

                "toggle_bluetooth" -> {
                    val enable = args.get("enable")?.asBoolean ?: true
                    val a11y = AuraAccessibilityService.instance
                    if (a11y != null) {
                        a11y.armBluetoothToggle(enable)
                        result.addProperty("status", "success")
                        result.addProperty("message", "Bluetooth ${if (enable) "ON" else "OFF"} kiya ja raha hai Boss! 🔵")
                    } else {
                        val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        try { context.startActivity(intent) } catch (_: Exception) {}
                        result.addProperty("status", "settings_opened")
                        result.addProperty("message", "Bluetooth settings open kar di hai Boss.")
                    }
                }

                "perform_app_action" -> {
                    val app = args.get("app_name")?.asString ?: ""
                    val desc = args.get("action_description")?.asString ?: "action"
                    val searchQuery = args.get("search_query")?.asString
                    val targetText = args.get("target_text")?.asString

                    // Intelligent interceptor: Redirect settings or navigation commands to Panda hands-free engine
                    val a11y = AuraAccessibilityService.instance
                    val descLower = desc.lowercase()
                    if (app.contains("setting", ignoreCase = true) || descLower.contains("navigation") ||
                        descLower.contains("hotspot") || descLower.contains("wifi") || descLower.contains("bluetooth")) {
                        if (descLower.contains("hotspot") || descLower.contains("tether")) {
                            val enable = !descLower.contains("off") && !descLower.contains("band")
                            a11y?.armHotspotToggle(enable)
                            result.addProperty("status", "success")
                            result.addProperty("message", "Hotspot ${if (enable) "ON" else "OFF"} kiya ja raha hai Boss! 📶")
                            return result
                        }
                        if (descLower.contains("navigation") || descLower.contains("button") || descLower.contains("gesture")) {
                            val mode = if (descLower.contains("gesture")) "gestures" else "buttons"
                            a11y?.armNavigationMode(mode)
                            result.addProperty("status", "success")
                            result.addProperty("message", "Navigation mode $mode me switch kiya ja raha hai Boss! 🔘")
                            return result
                        }
                        if (descLower.contains("wifi") || descLower.contains("wi-fi")) {
                            val enable = !descLower.contains("off") && !descLower.contains("band")
                            a11y?.armWifiToggle(enable)
                            result.addProperty("status", "success")
                            result.addProperty("message", "Wi-Fi toggle kiya ja raha hai Boss! 📶")
                            return result
                        }
                        if (descLower.contains("bluetooth")) {
                            val enable = !descLower.contains("off") && !descLower.contains("band")
                            a11y?.armBluetoothToggle(enable)
                            result.addProperty("status", "success")
                            result.addProperty("message", "Bluetooth toggle kiya ja raha hai Boss! 🔵")
                            return result
                        }
                    }

                    // Intercept WhatsApp media or delete tasks and execute real automation
                    val appLower = app.lowercase()
                    if (appLower.contains("whatsapp")) {
                        if (descLower.contains("screenshot") || descLower.contains("photo") || descLower.contains("image") || descLower.contains("bhej") || descLower.contains("send")) {
                            val match = Regex("(?:to|ko|par|for)\\s+([A-Za-z0-9\\s]+?)(?:\\s+on|\\s+ko|\\s+par|$)", RegexOption.IGNORE_CASE).find(desc)
                            val contact = match?.groupValues?.get(1)?.trim() ?: targetText?.ifBlank { null } ?: searchQuery?.ifBlank { null } ?: "Ansh"
                            val fakeArgs = JsonObject().apply {
                                addProperty("contact_name", contact)
                                addProperty("media_type", "screenshot")
                            }
                            return executeTool(context, "send_whatsapp_media", fakeArgs)
                        }
                        if (descLower.contains("delete") || descLower.contains("remove") || descLower.contains("hata") || descLower.contains("uda")) {
                            val match = Regex("(?:of|se|ke|from)\\s+([A-Za-z0-9\\s]+?)(?:\\s+ke|\\s+ki|\\s+se|$)", RegexOption.IGNORE_CASE).find(desc)
                            val contact = match?.groupValues?.get(1)?.trim() ?: targetText?.ifBlank { null } ?: searchQuery?.ifBlank { null } ?: "Ansh"
                            val fakeArgs = JsonObject().apply {
                                addProperty("contact_name", contact)
                            }
                            return executeTool(context, "delete_whatsapp_media", fakeArgs)
                        }
                    }

                    val success = executeArbitraryAppAction(context, app, searchQuery, targetText)
                    result.addProperty("status", if (success) "success" else "error")
                    result.addProperty("app", app)
                    result.addProperty("message", "$app me $desc kiya ja raha hai Boss! 🚀")
                }

                "press_system_key" -> {
                    val key = args.get("key")?.asString ?: "back"
                    val a11y = AuraAccessibilityService.instance
                    val success = a11y?.performGlobalActionByKey(key) ?: false
                    result.addProperty("status", if (success) "success" else "fallback")
                    result.addProperty("key", key)
                    result.addProperty("message", "System action '$key' execute kar diya gaya hai Boss! 📱")
                }

                "manage_app_storage" -> {
                    val appName = args.get("app_name")?.asString ?: ""
                    val action = args.get("action")?.asString ?: "clear_cache"
                    val pkg = resolveAppPackage(context, appName) ?: appName
                    val a11y = AuraAccessibilityService.instance

                    when (action.lowercase()) {
                        "clear_cache", "cache" -> {
                            a11y?.armClearAppCache(pkg)
                            result.addProperty("status", "success")
                            result.addProperty("message", "$appName ka cache clear kiya ja raha hai Boss! 🧹")
                        }
                        "uninstall", "remove" -> {
                            a11y?.armUninstallApp(pkg)
                            result.addProperty("status", "success")
                            result.addProperty("message", "$appName ko uninstall kiya ja raha hai Boss! 🗑️")
                        }
                        else -> {
                            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:$pkg")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                            result.addProperty("status", "success")
                            result.addProperty("message", "$appName ki App Info khol di gayi hai Boss! ⚙️")
                        }
                    }
                }

                "install_store_app" -> {
                    val query = args.get("query")?.asString ?: ""
                    val criteria = args.get("criteria")?.asString ?: "top"
                    val a11y = AuraAccessibilityService.instance
                    a11y?.armPlayStoreInstall(query, criteria)
                    result.addProperty("status", "success")
                    result.addProperty("query", query)
                    result.addProperty("criteria", criteria)
                    result.addProperty("message", "Play Store par \"$query\" ($criteria) install kiya ja raha hai Boss! 📥")
                }

                "toggle_dark_mode" -> {
                    val enable = args.get("enable")?.asBoolean ?: true
                    val a11y = AuraAccessibilityService.instance
                    a11y?.armToggleDarkMode(enable)
                    result.addProperty("status", "success")
                    result.addProperty("message", "Dark Mode ${if (enable) "ON" else "OFF"} kiya ja raha hai Boss! 🌙")
                }

                "calculate" -> {
                    val expr = args.get("expression")?.asString ?: ""
                    val calcResult = evaluateSimpleMath(expr)
                    result.addProperty("status", "success")
                    result.addProperty("expression", expr)
                    result.addProperty("result", calcResult)
                    result.addProperty("message", "$expr = $calcResult")
                }

                "manage_contacts" -> {
                    val name = args.get("name")?.asString ?: ""
                    val phone = args.get("phone_number")?.asString ?: ""
                    val intent = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI).apply {
                        putExtra(ContactsContract.Intents.Insert.NAME, name)
                        putExtra(ContactsContract.Intents.Insert.PHONE, phone)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    try {
                        context.startActivity(intent)
                        result.addProperty("status", "success")
                        result.addProperty("message", "New contact \"$name\" ($phone) add kiya ja raha hai Boss! 👤")
                    } catch (e: Exception) {
                        result.addProperty("status", "error")
                        result.addProperty("message", "Contact add nahi ho paya: ${e.message}")
                    }
                }

                // 34. Clock Timer
                "set_timer" -> {
                    val minutes = args.get("minutes")?.asInt ?: 0
                    val seconds = args.get("seconds")?.asInt ?: 0
                    val totalSeconds = (minutes * 60) + seconds
                    val label = args.get("label")?.asString ?: "AURA Timer"

                    val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(AlarmClock.EXTRA_LENGTH, totalSeconds)
                        putExtra(AlarmClock.EXTRA_MESSAGE, label)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    if (intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                        result.addProperty("status", "success")
                        result.addProperty("message", "$minutes minute $seconds second ka timer shuru kar diya hai Boss! ⏱️")
                    } else {
                        result.addProperty("status", "error")
                        result.addProperty("message", "Clock app timer intent support nahi kar raha.")
                    }
                }

                // 35. Maps & Navigation
                "navigate_maps" -> {
                    val dest = args.get("destination")?.asString ?: ""
                    val mode = args.get("mode")?.asString ?: "d"
                    val navMode = when (mode.lowercase()) {
                        "walking", "walk" -> "w"
                        "transit", "bus", "train" -> "r"
                        "two-wheeler", "bike", "motorcycle" -> "l"
                        else -> "d"
                    }
                    val uri = Uri.parse("google.navigation:q=${URLEncoder.encode(dest, "UTF-8")}&mode=$navMode")
                    val mapIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage("com.google.android.apps.maps")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    if (mapIntent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(mapIntent)
                        result.addProperty("status", "success")
                        result.addProperty("message", "Google Maps par \"$dest\" ka navigation shuru ho gaya hai Boss! 📍")
                    } else {
                        val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${URLEncoder.encode(dest, "UTF-8")}")).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(fallbackIntent)
                        result.addProperty("status", "success")
                        result.addProperty("message", "\"$dest\" ke liye directions open ho gayi hain Boss! 🗺️")
                    }
                }

                // 36. Web Search
                "web_search" -> {
                    val query = args.get("query")?.asString ?: ""
                    val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                        putExtra(SearchManager.QUERY, query)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    if (intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                        result.addProperty("status", "success")
                        result.addProperty("message", "\"$query\" web par search kiya ja raha hai Boss! 🔍")
                    } else {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${URLEncoder.encode(query, "UTF-8")}")).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(browserIntent)
                        result.addProperty("status", "success")
                        result.addProperty("message", "\"$query\" Google search par open kar diya hai Boss! 🌐")
                    }
                }

                // 37. Open Camera
                "open_camera" -> {
                    val mode = args.get("mode")?.asString?.lowercase() ?: "photo"
                    val action = if (mode == "video") MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
                    val intent = Intent(action).apply {
                        if (mode == "selfie") {
                            putExtra("android.intent.extras.CAMERA_FACING", 1)
                            putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                        }
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    result.addProperty("status", "success")
                    result.addProperty("message", "Camera open kar diya hai Boss! 📷")
                }

                // 38. Screen Brightness
                "set_brightness" -> {
                    val level = extractPercent(args, "level_percent", "level", "brightness", "value", "percent")
                    val clamped = level.coerceIn(1, 100)
                    val brightnessValue = (clamped * 255) / 100

                    // 1. Instantly update window brightness on MainActivity for immediate visual change
                    try {
                        com.aura.assistant.MainActivity.instance?.setScreenBrightness(clamped)
                    } catch (e: Exception) {
                        Log.w(TAG, "Window brightness update error: $e")
                    }

                    // 2. Update persistent system settings if permitted
                    val canWrite = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(context)
                    if (canWrite) {
                        try {
                            Settings.System.putInt(
                                context.contentResolver,
                                Settings.System.SCREEN_BRIGHTNESS_MODE,
                                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                            )
                            Settings.System.putInt(
                                context.contentResolver,
                                Settings.System.SCREEN_BRIGHTNESS,
                                brightnessValue
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Settings.System brightness error", e)
                        }
                    } else {
                        // Open Write Settings permission page in background so future system changes stick
                        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        try { context.startActivity(intent) } catch (_: Exception) {}
                    }

                    result.addProperty("status", "success")
                    result.addProperty("brightness_percent", clamped)
                    result.addProperty("message", "Screen brightness $clamped% par set kar di hai Boss! ☀️")
                }

                // 39. Do Not Disturb (DND)
                "set_dnd" -> {
                    val enable = args.get("enable")?.asBoolean ?: true
                    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && notificationManager != null) {
                        if (notificationManager.isNotificationPolicyAccessGranted) {
                            val filter = if (enable) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY else android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                            notificationManager.setInterruptionFilter(filter)
                            result.addProperty("status", "success")
                            result.addProperty("message", if (enable) "Do Not Disturb (DND) chalu kar diya hai Boss! 🔕" else "Do Not Disturb (DND) band kar diya hai Boss! 🔔")
                        } else {
                            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                            result.addProperty("status", "permission_needed")
                            result.addProperty("message", "DND toggle karne ke liye Notification Policy access chahiye, settings open kar di hai Boss.")
                        }
                    } else {
                        result.addProperty("status", "unsupported")
                        result.addProperty("message", "Is device par DND directly toggle nahi ho sakta.")
                    }
                }

                // 40. Open System Settings
                "open_system_settings" -> {
                    val setting = args.get("setting_name")?.asString?.lowercase() ?: ""
                    val intentAction = when {
                        setting.contains("wifi") || setting.contains("wi-fi") -> Settings.ACTION_WIFI_SETTINGS
                        setting.contains("bluetooth") || setting.contains("bt") -> Settings.ACTION_BLUETOOTH_SETTINGS
                        setting.contains("display") || setting.contains("screen") || setting.contains("refresh") -> Settings.ACTION_DISPLAY_SETTINGS
                        setting.contains("sound") || setting.contains("volume") || setting.contains("audio") -> Settings.ACTION_SOUND_SETTINGS
                        setting.contains("battery") -> Intent.ACTION_POWER_USAGE_SUMMARY
                        setting.contains("app") -> Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS
                        setting.contains("accessibility") -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                        setting.contains("developer") -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
                        setting.contains("storage") -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
                        setting.contains("date") || setting.contains("time") -> Settings.ACTION_DATE_SETTINGS
                        setting.contains("airplane") || setting.contains("flight") -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
                        setting.contains("location") || setting.contains("gps") -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                        setting.contains("nfc") -> Settings.ACTION_NFC_SETTINGS
                        setting.contains("notification") -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS else Settings.ACTION_SETTINGS
                        setting.contains("security") || setting.contains("lock") -> Settings.ACTION_SECURITY_SETTINGS
                        else -> Settings.ACTION_SETTINGS
                    }
                    val intent = Intent(intentAction).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    result.addProperty("status", "success")
                    result.addProperty("message", "${setting.ifBlank { "System" }} settings open kar di hai Boss! ⚙️")
                }

                // 41. Calendar Event
                "add_calendar_event" -> {
                    val title = args.get("title")?.asString ?: "Event"
                    val description = args.get("description")?.asString ?: ""
                    val location = args.get("location")?.asString ?: ""

                    val intent = Intent(Intent.ACTION_INSERT).apply {
                        data = CalendarContract.Events.CONTENT_URI
                        putExtra(CalendarContract.Events.TITLE, title)
                        putExtra(CalendarContract.Events.DESCRIPTION, description)
                        putExtra(CalendarContract.Events.EVENT_LOCATION, location)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    result.addProperty("status", "success")
                    result.addProperty("message", "Calendar event \"$title\" schedule karne ke liye page open kar diya hai Boss! 📅")
                }

                // 42. Send Email
                "send_email" -> {
                    val to = args.get("to")?.asString ?: ""
                    val subject = args.get("subject")?.asString ?: ""
                    val body = args.get("body")?.asString ?: ""

                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:$to")
                        putExtra(Intent.EXTRA_SUBJECT, subject)
                        putExtra(Intent.EXTRA_TEXT, body)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    result.addProperty("status", "success")
                    result.addProperty("message", "$to ke liye email draft ready kar diya hai Boss! ✉️")
                }

                // 43. Read Recent SMS
                "read_recent_sms" -> {
                    val count = args.get("count")?.asInt ?: 1
                    val smsList = JsonArray()
                    try {
                        val uri = Telephony.Sms.Inbox.CONTENT_URI
                        val projection = arrayOf(
                            Telephony.Sms.Inbox.ADDRESS,
                            Telephony.Sms.Inbox.BODY,
                            Telephony.Sms.Inbox.DATE
                        )
                        context.contentResolver.query(uri, projection, null, null, "${Telephony.Sms.Inbox.DATE} DESC")?.use { cursor ->
                            var read = 0
                            val addressCol = cursor.getColumnIndex(Telephony.Sms.Inbox.ADDRESS)
                            val bodyCol = cursor.getColumnIndex(Telephony.Sms.Inbox.BODY)
                            while (cursor.moveToNext() && read < count) {
                                val address = if (addressCol != -1) cursor.getString(addressCol) ?: "Unknown" else "Unknown"
                                val body = if (bodyCol != -1) cursor.getString(bodyCol) ?: "" else ""
                                val contactName = lookupLocalContact(context, address) ?: address

                                val smsObj = JsonObject().apply {
                                    addProperty("from", contactName)
                                    addProperty("body", body)
                                }
                                smsList.add(smsObj)
                                read++
                            }
                        }
                        result.addProperty("status", "success")
                        result.add("messages", smsList)
                        result.addProperty("message", "Aapke inbox se $count recent SMS read kiye gaye hain Boss.")
                    } catch (e: Exception) {
                        result.addProperty("status", "error")
                        result.addProperty("message", "SMS read karne mein error ya permission missing: ${e.message}")
                    }
                }

                // 44. Query Contact Details
                "query_contact" -> {
                    val name = args.get("name")?.asString ?: ""
                    val resolvedNumber = resolveContactNumber(context, name)
                    if (!resolvedNumber.isNullOrBlank()) {
                        result.addProperty("status", "success")
                        result.addProperty("name", name)
                        result.addProperty("phone_number", resolvedNumber)
                        result.addProperty("message", "$name ka number $resolvedNumber hai Boss! 📞")
                    } else {
                        result.addProperty("status", "not_found")
                        result.addProperty("message", "$name contacts mein nahi mila Boss.")
                    }
                }

                // 45. Emergency SOS
                "emergency_sos" -> {
                    val target = args.get("target")?.asString?.trim() ?: "112"
                    val msg = args.get("message")?.asString ?: "EMERGENCY: I need immediate help! This is an automated SOS alert from AURA."

                    val emergencyNumber = if (target.matches(Regex("[0-9+]+"))) target else {
                        resolveContactNumber(context, target) ?: "112"
                    }

                    if (emergencyNumber != "112" && emergencyNumber != "911" && emergencyNumber != "100") {
                        try {
                            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                context.getSystemService(SmsManager::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                SmsManager.getDefault()
                            }
                            smsManager.sendTextMessage(emergencyNumber, null, msg, null, null)
                        } catch (_: Exception) {}
                    }

                    val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$emergencyNumber")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(callIntent)

                    result.addProperty("status", "success")
                    result.addProperty("message", "EMERGENCY SOS: $emergencyNumber ko call connect kiya ja raha hai Boss! 🚨")
                }

                // 46. Create Quick Note
                "create_quick_note" -> {
                    val noteText = args.get("note_text")?.asString ?: ""
                    val title = args.get("title")?.asString ?: "Note ${System.currentTimeMillis()}"

                    AuraMemoryManager.rememberFact(context, "note_$title", noteText)
                    result.addProperty("status", "success")
                    result.addProperty("message", "Note \"$title\" save kar liya gaya hai Boss! 📝")
                }

                // 47. Read Status Bar Notifications
                "read_notifications" -> {
                    val isListenerEnabled = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
                    if (!isListenerEnabled && com.aura.assistant.AuraNotificationListenerService.instance == null) {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        try { context.startActivity(intent) } catch (_: Exception) {}
                        result.addProperty("status", "permission_needed")
                        result.addProperty("message", "Boss, notifications padhne ke liye 'Notification Access' permission allow karni hogi. Maine settings open kar di hai, please allow kar dijiye!")
                    } else {
                        val notifs = com.aura.assistant.AuraNotificationListenerService.getActiveNotificationsList()
                        if (notifs.isEmpty()) {
                            result.addProperty("status", "empty")
                            result.addProperty("message", "Filhaal status bar mein koi nayi notifications nahi hain Boss, sab clear hai! ✨")
                        } else {
                            val filter = args.get("app_filter")?.asString?.lowercase()?.trim() ?: ""
                            val filtered = if (filter.isNotBlank()) {
                                notifs.filter {
                                    (it["appName"] as? String)?.contains(filter, ignoreCase = true) == true ||
                                    (it["package"] as? String)?.contains(filter, ignoreCase = true) == true
                                }
                            } else notifs

                            val count = args.get("count")?.asInt ?: 5
                            val recent = filtered.take(count)
                            val array = JsonArray()
                            for (n in recent) {
                                val item = JsonObject().apply {
                                    addProperty("app", n["appName"]?.toString() ?: "App")
                                    addProperty("title", n["title"]?.toString() ?: "")
                                    addProperty("text", n["text"]?.toString() ?: "")
                                }
                                array.add(item)
                            }
                            result.addProperty("status", "success")
                            result.add("notifications", array)
                            result.addProperty("count", recent.size)
                            result.addProperty("message", "${recent.size} notifications mili hain Boss.")
                        }
                    }
                }

                // Tier 4 Catch-All Safe Fallback Handler
                else -> {
                    Log.w(TAG, "Tool $name requested but not natively registered")
                    result.addProperty("status", "unsupported")
                    result.addProperty("tool_name", name)
                    result.addProperty("message", "Action \"$name\" is not directly supported natively yet. Politely explain this to the user in natural Hinglish and offer an alternative like web search, opening the app, or adjusting system settings.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing $name", e)
            result.addProperty("status", "error")
            result.addProperty("error", e.message ?: "Execution failed")
        }

        // Post-execution state verification via AURA 2.0 ExecutionVerifier
        try {
            val evidence = com.aura.assistant.execution.ExecutionVerifier.verify(context, name, args)
            result.addProperty("verified", evidence.verified)
            if (evidence.observedState != null) {
                result.addProperty("observedState", evidence.observedState)
            }
            result.addProperty("verificationMethod", evidence.method ?: "standard")
            if (!evidence.verified) {
                Log.w(TAG, "Tool '$name' verification failed: ${evidence.observedState}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Verification check skipped for $name: ${e.message}")
        }

        return result
    }

    private fun extractPercent(args: JsonObject, vararg keys: String): Int {
        for (k in keys) {
            val elem = args.get(k) ?: continue
            if (elem.isJsonPrimitive) {
                val prim = elem.asJsonPrimitive
                if (prim.isNumber) return prim.asInt.coerceIn(0, 100)
                if (prim.isString) {
                    val s = prim.asString.lowercase().trim()
                    if (s.contains("full") || s.contains("max") || s.contains("hundred") || s.contains("100")) return 100
                    if (s.contains("half") || s.contains("50")) return 50
                    if (s.contains("min") || s.contains("zero") || s.contains("0") || s.contains("mute")) return 0
                    val digits = s.replace(Regex("[^0-9]"), "")
                    if (digits.isNotBlank()) return digits.toInt().coerceIn(0, 100)
                }
            }
        }
        return 100
    }

    private fun evaluateSimpleMath(expr: String): String {
        return try {
            val clean = expr.replace("x", "*", ignoreCase = true).replace("÷", "/")
                .replace("[^0-9.+\\-*/% ]".toRegex(), "").trim()
            if (clean.isBlank()) return "0"

            if (clean.contains("%")) {
                val num = clean.replace("%", "").trim().toDoubleOrNull() ?: 0.0
                return (num / 100.0).toString()
            }
            val operators = listOf("+", "-", "*", "/")
            val op = operators.firstOrNull { clean.contains(it) }
            if (op != null) {
                val parts = clean.split(op).map { it.trim().toDoubleOrNull() ?: 0.0 }
                if (parts.size >= 2) {
                    val res = when (op) {
                        "+" -> parts[0] + parts[1]
                        "-" -> parts[0] - parts[1]
                        "*" -> parts[0] * parts[1]
                        "/" -> if (parts[1] != 0.0) parts[0] / parts[1] else Double.POSITIVE_INFINITY
                        else -> 0.0
                    }
                    return if (res % 1.0 == 0.0) res.toLong().toString() else "%.4f".format(res).trimEnd('0').trimEnd('.')
                }
            }
            clean
        } catch (_: Exception) {
            expr
        }
    }

    /**
     * Resolves a named contact query to a phone number using the device Contacts database.
     */
    private fun resolveContactNumber(context: Context, query: String): String? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return null

        val digitsOnly = trimmed.replace(Regex("[^0-9+]"), "")
        if (digitsOnly.length >= 7 && !trimmed.any { it.isLetter() }) {
            return digitsOnly
        }

        // Clean honorifics, prepositions, etc. (Hindi & English)
        val cleanName = trimmed.replace(Regex("\\b(ko|ji|bhai|bhaiya|bro|sir|sahab|de|ka|ki|ke|se|pe|par)\\b", RegexOption.IGNORE_CASE), "").trim()
        val queriesToTry = if (cleanName.isNotBlank() && cleanName != trimmed) listOf(cleanName, trimmed) else listOf(trimmed)

        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"

            for (target in queriesToTry) {
                // 1. Full name match
                context.contentResolver.query(uri, projection, selection, arrayOf("%$target%"), null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val numCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        if (numCol != -1) {
                            val rawNum = cursor.getString(numCol)
                            if (!rawNum.isNullOrBlank()) return rawNum.replace(Regex("[^0-9+]"), "")
                        }
                    }
                }

                // 2. Token match (e.g. "Ansh Kesharwani" -> search "Ansh")
                val tokens = target.split(Regex("\\s+")).filter { it.length >= 2 }
                if (tokens.size > 1) {
                    for (token in tokens) {
                        context.contentResolver.query(uri, projection, selection, arrayOf("%$token%"), null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val numCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                                if (numCol != -1) {
                                    val rawNum = cursor.getString(numCol)
                                    if (!rawNum.isNullOrBlank()) return rawNum.replace(Regex("[^0-9+]"), "")
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve contact: $query", e)
        }
        return null
    }

    /**
     * Saves raw JPEG/PNG image bytes into the system Gallery (Pictures/Screenshots or MediaStore)
     * and returns a valid content Uri.
     */
    fun saveBytesToGallery(context: Context, bytes: ByteArray): Uri? {
        val fileName = "AURA_SCREENSHOT_${System.currentTimeMillis()}.jpg"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Screenshots")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(bytes)
                        os.flush()
                    }
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, contentValues, null, null)
                    Log.i(TAG, "Screenshot saved to MediaStore: $uri")
                    return uri
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore insert failed: $e, falling back to app storage")
        }

        // Fallback: save to external files and wrap with FileProvider
        try {
            val screenshotDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Screenshots").apply {
                if (!exists()) mkdirs()
            }
            val file = File(screenshotDir, fileName)
            FileOutputStream(file).use { fos ->
                fos.write(bytes)
                fos.flush()
            }
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
            val fileUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            Log.i(TAG, "Screenshot saved to file: ${file.absolutePath}, uri: $fileUri")
            return fileUri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save screenshot to file", e)
        }

        // Final fallback: cache directory
        try {
            val cacheDir = File(context.cacheDir, "screenshots").apply {
                if (!exists()) mkdirs()
            }
            val file = File(cacheDir, fileName)
            FileOutputStream(file).use { fos ->
                fos.write(bytes)
                fos.flush()
            }
            val fileUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            Log.i(TAG, "Screenshot saved to cache: ${file.absolutePath}, uri: $fileUri")
            return fileUri
        } catch (e: Exception) {
            Log.e(TAG, "All fallback saves failed", e)
        }
        return null
    }

    /**
     * Finds the latest screenshot or image content Uri from MediaStore for sending via WhatsApp or other apps.
     */
    fun getLatestScreenshotOrImageUri(context: Context): Uri? {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )
        // 1. First look for screenshots specifically in MediaStore
        try {
            val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Images.Media.DATA} LIKE ?"
            val selectionArgs = arrayOf("%Screenshot%", "%Screenshot%")
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idCol = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                    if (idCol != -1) {
                        val id = cursor.getLong(idCol)
                        return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Screenshot query failed: $e")
        }

        // 2. Fallback: get absolute latest image in gallery
        try {
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idCol = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                    if (idCol != -1) {
                        val id = cursor.getLong(idCol)
                        return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Latest image fallback query failed: $e")
        }

        // 3. Fallback: Check physical directories for latest screenshot file
        try {
            val candidateDirs = listOf(
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Screenshots"),
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Screenshots"),
                File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Screenshots"),
                File(context.cacheDir, "screenshots")
            )
            var latestFile: File? = null
            var latestTime = 0L

            for (dir in candidateDirs) {
                if (dir.exists() && dir.isDirectory) {
                    val files = dir.listFiles { f -> f.isFile && (f.name.endsWith(".jpg", true) || f.name.endsWith(".png", true)) }
                    files?.forEach { f ->
                        if (f.lastModified() > latestTime) {
                            latestTime = f.lastModified()
                            latestFile = f
                        }
                    }
                }
            }

            if (latestFile != null) {
                return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", latestFile!!)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Physical file fallback search failed: $e")
        }

        return null
    }

    /**
     * Looks up a phone number in the device's local Contacts database.
     * Returns the contact display name if found, null otherwise.
     */
    private fun lookupLocalContact(context: Context, phoneNumber: String): String? {
        return try {
            val uri = android.net.Uri.withAppendedPath(
                android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(phoneNumber)
            )
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME))
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Contact lookup failed for $phoneNumber", e)
            null
        }
    }

    /**
     * Universal Media Player helper:
     * Resolves app package, sends MediaStore search intent, launches deep links,
     * and arms AuraAccessibilityService to auto-tap the first result.
     */
    private fun playMediaInApp(context: Context, query: String, targetApp: String): Boolean {
        val trimmedQuery = query.trim()
        val appLower = targetApp.lowercase().trim()

        // Map popular music apps to their package names
        val packageName = when {
            appLower.contains("youtube") && !appLower.contains("music") -> "com.google.android.youtube"
            appLower.contains("yt music") || appLower.contains("youtube music") -> "com.google.android.apps.youtube.music"
            appLower.contains("spotify") -> "com.spotify.music"
            appLower.contains("melodify") -> "com.ansh.ytmusicpreimum"
            appLower.contains("saavn") || appLower.contains("jio") -> "com.jio.media.jiobeats"
            appLower.contains("wynk") -> "com.wynk.music"
            appLower.contains("gaana") -> "com.gaana"
            else -> resolveAppPackage(context, targetApp) ?: "com.google.android.youtube"
        }

        try {
            if (packageName == "com.google.android.youtube") {
                // Launch YouTube search URL directly
                val searchUri = Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(trimmedQuery, "UTF-8"))
                val intent = Intent(Intent.ACTION_VIEW, searchUri).apply {
                    setPackage(packageName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                AuraAccessibilityService.instance?.armYouTubeAutoPlayFirstResult(trimmedQuery)
                return true
            }

            // Universal Android Media Play intent
            val mediaIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(SearchManager.QUERY, trimmedQuery)
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                setPackage(packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (mediaIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(mediaIntent)
                AuraAccessibilityService.instance?.armAutoSearchAndPlayInApp(targetApp, trimmedQuery)
                return true
            }

            // Fallback: Launch target app and use accessibility search
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(launchIntent)
                AuraAccessibilityService.instance?.armAutoSearchAndPlayInApp(targetApp, trimmedQuery)
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing media in $targetApp", e)
        }
        return false
    }

    /**
     * Universal Arbitrary App Action helper:
     * Resolves app package, launches app, and arms accessibility automation.
     */
    private fun executeArbitraryAppAction(
        context: Context,
        appName: String,
        searchQuery: String?,
        targetText: String?
    ): Boolean {
        val pkg = resolveAppPackage(context, appName) ?: run {
            AuraAccessibilityService.instance?.armAppSearchAndLaunch(appName)
            return true
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(launchIntent)

        val a11y = AuraAccessibilityService.instance
        if (a11y != null) {
            if (!searchQuery.isNullOrBlank()) {
                a11y.armSearchAndClick(searchQuery, targetText)
            } else if (!targetText.isNullOrBlank()) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    a11y.performClickByText(targetText)
                }, 1500)
            }
        }
        return true
    }

    private fun resolveAppPackage(context: Context, appName: String): String? {
        val pm = context.packageManager
        if (pm.getLaunchIntentForPackage(appName) != null) return appName

        try {
            val apps = pm.getInstalledApplications(0)
            val matched = apps.firstOrNull {
                pm.getApplicationLabel(it).toString().contains(appName, ignoreCase = true)
            }
            if (matched != null) return matched.packageName
        } catch (_: Exception) {}
        return null
    }
}
