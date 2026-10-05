package com.aura.assistant.ai

import android.annotation.SuppressLint
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
import com.aura.assistant.IshaAccessibilityService
import com.aura.assistant.IshaFileManager
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Registry of all 40+ ISHA OS automation tools in official Google Gemini Live schema.
 * Dispatches LLM tool calls directly to native Android system APIs.
 */
object IshaToolRegistry {

    private const val TAG = "IshaToolRegistry"
    private val gson = Gson()

    @Volatile
    private var cachedGeminiToolDeclarations: JsonArray? = null

    @Volatile
    private var lastCapturedScreenshotUri: Uri? = null

    @Volatile
    private var lastCapturedScreenshotTime: Long = 0L

    /**
     * Builds or returns the cached JSON array of tool declarations conforming to Gemini's function_declarations specification.
     * Caching 80+ tool declarations saves 200-400ms on every single LLM turn and prevents GC churn.
     */
    fun getGeminiToolDeclarations(): JsonArray {
        cachedGeminiToolDeclarations?.let { return it }
        synchronized(this) {
            cachedGeminiToolDeclarations?.let { return it }
            val toolsArray = buildGeminiToolDeclarations()
            cachedGeminiToolDeclarations = toolsArray
            return toolsArray
        }
    }

    private fun buildGeminiToolDeclarations(): JsonArray {
        val toolsArray = JsonArray()

        // 1. Calling
        toolsArray.add(createFunction(
            name = "make_call",
            desc = "Place a phone call to a named contact or phone number. When calling a person by name, ALWAYS pass contact_name. NEVER invent, guess, or hallucinate a random phone number! If calling by contact name, leave phone_number completely empty. ISHA will search contacts and long-term memory for their real number.",
            params = mapOf("contact_name" to "string", "phone_number" to "string"),
            required = emptyList(),
            paramDescriptions = mapOf(
                "contact_name" to "The full name of the contact as spoken by the user (e.g. 'Rahul', 'Mummy', 'Kartik'). Use this when calling someone by name.",
                "phone_number" to "Specific digits to dial directly ONLY if the user spoke the digits explicitly. Leave empty if calling by contact name. NEVER invent random numbers!"
            )
        ))

        // 2. SMS
        toolsArray.add(createFunction(
            name = "send_sms",
            desc = "Send a standard cellular text SMS message to a phone number or contact. Call ONLY for SMS/text messaging. For WhatsApp messages, ALWAYS use send_whatsapp instead.",
            params = mapOf("contact_name" to "string", "phone_number" to "string", "message" to "string"),
            required = listOf("message"),
            paramDescriptions = mapOf(
                "contact_name" to "Recipient contact name as spoken by the user (e.g. 'Rahul', 'Mom')",
                "phone_number" to "Recipient phone number with digits (optional if contact_name provided)",
                "message" to "The exact text message content to send via SMS"
            )
        ))

        // 3. WhatsApp (via Accessibility automation)
        toolsArray.add(createFunction(
            name = "send_whatsapp",
            desc = "Send a WhatsApp text message to a named contact automatically. ALWAYS call when user asks to send a WhatsApp message (e.g. 'Rahul ko WhatsApp par hello bolo', 'Mom ko message bhejo WhatsApp pe').",
            params = mapOf("contact_name" to "string", "message" to "string"),
            required = listOf("contact_name", "message"),
            paramDescriptions = mapOf(
                "contact_name" to "Exact or approximate name of the WhatsApp contact as stored in contacts",
                "message" to "The text message content to send"
            )
        ))

        // WhatsApp / Universal Text Typing (No auto-send)
        toolsArray.add(createFunction(
            name = "type_message",
            desc = "Type text or a message into an input field or WhatsApp chat WITHOUT sending it automatically. ALWAYS call when the user explicitly says 'type karo', 'ye message type kar do', 'WhatsApp par type karo', 'keyboard se likho', 'msg type karo'. Focuses the chat input field, opens keyboard, and types the exact text.",
            params = mapOf(
                "text" to "string",
                "contact_name" to "string",
                "app_name" to "string"
            ),
            required = listOf("text"),
            paramDescriptions = mapOf(
                "text" to "The exact message or text to type into the input field",
                "contact_name" to "Optional contact name if typing into WhatsApp chat",
                "app_name" to "Target app: 'whatsapp' or blank for current active screen"
            )
        ))

        // WhatsApp Autonomous Conversation & Reply Agent
        toolsArray.add(createFunction(
            name = "chat_on_whatsapp",
            desc = "Open a WhatsApp chat with a contact and inspect recent received messages to converse with them. ALWAYS call when the user says 'WhatsApp par [contact] se baat karo / baat kar lo', 'unse chat karo', 'unka message dekh kar reply do'. Opens the conversation and reads visible messages on screen so you can understand what they sent and formulate an intelligent reply.",
            params = mapOf(
                "contact_name" to "string"
            ),
            required = listOf("contact_name"),
            paramDescriptions = mapOf(
                "contact_name" to "The contact name to open chat with and converse"
            )
        ))

        // WhatsApp Media & Screenshot Sender
        toolsArray.add(createFunction(
            name = "send_whatsapp_media",
            desc = "Capture a screenshot or select the latest screenshot/photo, and automatically send it to a contact on WhatsApp. ALWAYS call when user says 'screenshot leke Srishti ko bhej do', 'photo WhatsApp karo'.",
            params = mapOf(
                "contact_name" to "string",
                "media_type" to "string",
                "caption" to "string"
            ),
            required = listOf("contact_name"),
            paramDescriptions = mapOf(
                "contact_name" to "The name of the WhatsApp contact",
                "media_type" to "'screenshot' to take fresh screenshot and send, or 'photo' for latest photo",
                "caption" to "Optional text caption to accompany the media"
            )
        ))

        // WhatsApp Chat & Media Deleter
        toolsArray.add(createFunction(
            name = "delete_whatsapp_media",
            desc = "Delete recent messages, photos, or images from a WhatsApp chat with a contact, or clean up WhatsApp media.",
            params = mapOf(
                "contact_name" to "string",
                "target" to "string"
            ),
            required = listOf("contact_name"),
            paramDescriptions = mapOf(
                "contact_name" to "Name of the WhatsApp contact",
                "target" to "What to delete: 'recent', 'photos', or 'chat'"
            )
        ))

        // Screenshot Capture & Gallery Saver
        toolsArray.add(createFunction(
            name = "take_screenshot",
            desc = "Capture a screenshot of the current Android screen using system accessibility and save it to the Gallery. Call when user says 'screenshot lo', 'screen capture karo'. NEVER call when user asks to SHOW or OPEN an existing screenshot (use show_recent_media instead).",
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
            required = listOf(),
            paramDescriptions = mapOf(
                "target_app" to "Target app: 'whatsapp', 'instagram', 'telegram', 'twitter', or 'system'",
                "type" to "Media type: 'screenshot', 'photo', 'video', or 'document'",
                "caption" to "Optional text caption",
                "contact_name" to "Optional contact name if sharing to a specific person"
            )
        ))

        // 4. Open Application
        toolsArray.add(createFunction(
            name = "open_app",
            desc = "Launch an installed Android application by name (e.g. WhatsApp, Instagram, Calculator, Settings, Chrome, Camera) on this device or a remote linked device (e.g. 'OnePlus me Chrome open karo', 'Pixel par Settings kholo'). Call ONLY when the user explicitly wants to open an application UI. NEVER call for playing music or videos (use play_youtube or play_media instead), NEVER call for web search queries (use search_internet instead), and NEVER call for hardware settings toggles.",
            params = mapOf(
                "app_name" to "string",
                "target_device" to "string"
            ),
            required = listOf("app_name"),
            paramDescriptions = mapOf(
                "app_name" to "Standard name of the installed app (e.g. 'whatsapp', 'instagram', 'calculator', 'chrome', 'camera', 'settings')",
                "target_device" to "Optional target device name or alias (e.g. 'OnePlus', 'Pixel', 'Samsung', 'Lava', 'Tablet', 'Other phone'). Omit to open on current device."
            )
        ))

        // 5. Flashlight / Torch
        toolsArray.add(createFunction(
            name = "toggle_flashlight",
            desc = "Turn the phone camera flashlight/torch on or off on this device or a remote linked device (e.g. 'torch on karo', 'Pixel phone me flashlight jalao'). Call ONLY when the user explicitly requests to turn the torch on or off. NEVER call when the user reports a problem, damage, or complaint about their torch.",
            params = mapOf(
                "enable" to "boolean",
                "target_device" to "string"
            ),
            required = listOf("enable"),
            paramDescriptions = mapOf(
                "enable" to "true to turn flashlight ON, false to turn flashlight OFF",
                "target_device" to "Optional target device name or alias. Omit for current device."
            )
        ))

        // 6. Volume Control
        toolsArray.add(createFunction(
            name = "set_volume",
            desc = "Set phone media and system volume percentage (0 to 100) on this device or a remote linked device (e.g. 'awaaz 50% kar do', 'OnePlus me volume badhao'). Call when user asks to adjust sound, volume, or audio level. NEVER call for display brightness.",
            params = mapOf(
                "level_percent" to "integer",
                "target_device" to "string"
            ),
            required = listOf("level_percent"),
            paramDescriptions = mapOf(
                "level_percent" to "Target volume level from 0 (silent/mute) to 100 (maximum volume)",
                "target_device" to "Optional target device name or alias. Omit for current device."
            )
        ))

        // 7. Screen Tap (Accessibility)
        toolsArray.add(createFunction(
            name = "screen_tap",
            desc = "Tap on a button, text, or coordinate on the active screen.",
            params = mapOf("target_text" to "string"),
            required = listOf("target_text"),
            paramDescriptions = mapOf(
                "target_text" to "The button label, icon name, or visible text to tap on screen"
            )
        ))

        // 8. Screen Scroll
        toolsArray.add(createFunction(
            name = "screen_scroll",
            desc = "Scroll the current screen up or down hands-free.",
            params = mapOf("direction" to "string"),
            required = listOf("direction"),
            paramDescriptions = mapOf(
                "direction" to "'up' to scroll up, or 'down' to scroll down"
            )
        ))

        // 9. Media Control
        toolsArray.add(createFunction(
            name = "media_control",
            desc = "Control playback for active music or video: play, pause, next, previous, stop.",
            params = mapOf("action" to "string"),
            required = listOf("action"),
            paramDescriptions = mapOf(
                "action" to "Playback action: 'play', 'pause', 'next', 'previous', or 'stop'"
            )
        ))

        // 10. Alarms
        toolsArray.add(createFunction(
            name = "create_alarm",
            desc = "Set an alarm for a specific clock time (e.g. '7:00 AM', 'kal subah 6:30 baje', '8 baje ka alarm lagao'). Call ONLY for fixed clock times. NEVER call for relative countdown durations like '10 minute ka timer' (use set_timer instead).",
            params = mapOf("hour" to "integer", "minute" to "integer", "label" to "string"),
            required = listOf("hour", "minute"),
            paramDescriptions = mapOf(
                "hour" to "Hour of the alarm in 24-hour format (0 to 23)",
                "minute" to "Minute of the alarm (0 to 59)",
                "label" to "Optional label or description for the alarm"
            )
        ))

        // 11. Storage & Files
        toolsArray.add(createFunction(
            name = "search_files",
            desc = "Search device storage for files, photos, videos, or documents by name or keyword.",
            params = mapOf("query" to "string", "file_type" to "string"),
            required = listOf("query"),
            paramDescriptions = mapOf(
                "query" to "Search query or file name keyword",
                "file_type" to "Optional file type filter: 'image', 'video', 'audio', 'pdf', 'document'"
            )
        ))

        toolsArray.add(createFunction(
            name = "delete_file",
            desc = "Delete a specific file from device storage.",
            params = mapOf("file_path" to "string"),
            required = listOf("file_path"),
            paramDescriptions = mapOf(
                "file_path" to "Exact absolute file path to delete"
            )
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
            required = listOf("phone_number"),
            paramDescriptions = mapOf(
                "phone_number" to "The phone number digits to identify"
            )
        ))

        // 15. Real Hardware Battery Status
        toolsArray.add(createFunction(
            name = "get_battery_status",
            desc = "Get the current exact device battery percentage and charging state directly from Android hardware. Call when user specifically asks 'battery kitni hai', 'charge kitna hai', 'phone charging pe hai kya'. For overall phone diagnosis, use check_device_health.",
            params = emptyMap(),
            required = emptyList()
        ))

        // 16. Clipboard Reader
        toolsArray.add(createFunction(
            name = "get_clipboard_text",
            desc = "Read the latest copied text from the Android system clipboard. Call when user asks 'clipboard mein kya copy hai', 'copied text padho'.",
            params = emptyMap(),
            required = emptyList()
        ))

        // 17. Permanent Memory Vault: Remember Fact
        toolsArray.add(createFunction(
            name = "remember_fact",
            desc = "Save an important personal fact, detail, birthday, preference, or relationship into permanent memory. Call when user says 'yaad rakhna ki...', 'meri favourite... hai'.",
            params = mapOf("key" to "string", "value" to "string"),
            required = listOf("key", "value"),
            paramDescriptions = mapOf(
                "key" to "Short topic category (e.g. 'birthday', 'preference', 'family', 'food')",
                "value" to "The fact or detail to remember"
            )
        ))

        // 18. Permanent Memory Vault: Recall Memory
        toolsArray.add(createFunction(
            name = "recall_memory",
            desc = "Retrieve previously remembered personal facts or stored user details from the permanent memory vault.",
            params = mapOf("key" to "string"),
            required = emptyList(),
            paramDescriptions = mapOf(
                "key" to "Specific key or topic to recall, or leave empty to recall all memories"
            )
        ))

        // 18b. Permanent Memory Vault: Teach Custom Command Rule / Workflow
        toolsArray.add(createFunction(
            name = "teach_command_rule",
            desc = "Save, teach, or update a custom user rule, command workflow, action mapping, or correction into ISHA's permanent memory. Whenever the user guides ISHA on what tool(s) or steps to execute, or says 'memory mein save kar lo' / 'yaad rakhna', ISHA MUST execute this tool to permanently persist the rule.",
            params = mapOf(
                "trigger_pattern" to "string",
                "action_instruction" to "string",
                "primary_tool" to "string"
            ),
            required = listOf("trigger_pattern", "action_instruction"),
            paramDescriptions = mapOf(
                "trigger_pattern" to "User voice phrase or keyword pattern that triggers this rule",
                "action_instruction" to "Exact action and step-by-step instructions to follow",
                "primary_tool" to "Main tool name to be triggered"
            )
        ))

        // 19. Universal Music & Media Player (Any app: YouTube, Spotify, Melodify, JioSaavn, Wynk, etc.)
        toolsArray.add(createFunction(
            name = "play_media",
            desc = "Search and automatically play a song, music video, playlist, or artist in ANY music app (Spotify, Melodify, JioSaavn, Wynk, or default player). For YouTube specifically, use play_youtube instead.",
            params = mapOf("query" to "string", "app_name" to "string"),
            required = listOf("query"),
            paramDescriptions = mapOf(
                "query" to "Song title, playlist, or artist name to play",
                "app_name" to "Target music app (e.g. 'spotify', 'melodify', 'jiosaavn') or blank for default"
            )
        ))

        // 20. Dedicated YouTube Play
        toolsArray.add(createFunction(
            name = "play_youtube",
            desc = "Directly search and play a song, music video, playlist, artist, or URL on YouTube on this phone or another linked phone (e.g. 'Samsung phone me Tu Hai Kahan song play karo', 'Phone B par video chalao', 'dusre phone me URL chalao'). NEVER use open_app for music!",
            params = mapOf(
                "query" to "string",
                "target_device" to "string",
                "url" to "string"
            ),
            required = listOf("query"),
            paramDescriptions = mapOf(
                "query" to "Search query for song, video title, artist, or YouTube URL",
                "target_device" to "Optional target device name or alias (e.g. 'Samsung', 'Lava', 'Phone B', 'Phone 2'). Leave empty for current device.",
                "url" to "Optional direct YouTube video URL (e.g. 'https://youtu.be/...')"
            )
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
            required = listOf("app_name", "action_description"),
            paramDescriptions = mapOf(
                "app_name" to "Name of the application to control",
                "action_description" to "Description of what to do inside the app",
                "search_query" to "Optional text query to type in app search bar",
                "target_text" to "Optional button or UI text to tap inside the app"
            )
        ))

        // 22. Mobile Hotspot Control
        toolsArray.add(createFunction(
            name = "toggle_hotspot",
            desc = "Turn the mobile Personal Hotspot / Portable Wi-Fi Hotspot ON or OFF hands-free on this phone or another linked phone. Call ONLY when the user explicitly commands to enable or disable hotspot ('hotspot on karo', 'hotspot band karo', 'Samsung mein hotspot on karo').",
            params = mapOf(
                "enable" to "boolean",
                "target_device" to "string"
            ),
            required = listOf("enable"),
            paramDescriptions = mapOf(
                "enable" to "true to turn hotspot ON, false to turn hotspot OFF",
                "target_device" to "Optional target device name or alias (e.g. 'Samsung', 'Lava', 'Phone B'). Omit for this phone."
            )
        ))

        // 23. System Navigation Bar Mode (3-Button vs Gesture Navigation)
        toolsArray.add(createFunction(
            name = "set_navigation_mode",
            desc = "Switch Android system navigation bar mode between 'buttons' (3-button navigation bar with back, home, recents) and 'gestures' (full screen gesture navigation).",
            params = mapOf("mode" to "string"),
            required = listOf("mode"),
            paramDescriptions = mapOf(
                "mode" to "'buttons' for 3-button navigation, or 'gestures' for gesture navigation"
            )
        ))

        // 24. Wi-Fi Control
        toolsArray.add(createFunction(
            name = "toggle_wifi",
            desc = "Turn device Wi-Fi ON or OFF hands-free. Call ONLY when the user explicitly requests to turn Wi-Fi on or off (e.g. 'wifi on karo', 'wifi band karo', 'turn off wifi'). NEVER call when user asks for the current Wi-Fi name, connection status, IP, or network data usage (use get_wifi_networks or get_device_connectivity_and_usage instead)!",
            params = mapOf("enable" to "boolean"),
            required = listOf("enable"),
            paramDescriptions = mapOf(
                "enable" to "true to turn Wi-Fi ON, false to turn Wi-Fi OFF"
            )
        ))

        // 25. Bluetooth Control
        toolsArray.add(createFunction(
            name = "toggle_bluetooth",
            desc = "Turn device Bluetooth ON or OFF hands-free. Call ONLY when the user explicitly requests to turn Bluetooth on or off (e.g. 'bluetooth on karo', 'bluetooth band karo'). NEVER call to check connected Bluetooth devices (smartwatch, earbuds) or connection status (use get_device_connectivity_and_usage instead)!",
            params = mapOf("enable" to "boolean"),
            required = listOf("enable"),
            paramDescriptions = mapOf(
                "enable" to "true to turn Bluetooth ON, false to turn Bluetooth OFF"
            )
        ))

        // 26. Universal System Hardware & Navigation Keys (Panda Engine)
        toolsArray.add(createFunction(
            name = "press_system_key",
            desc = "Execute system-level physical or navigation actions hands-free: 'lock' (lock screen immediately), 'back' (back button), 'home' (home button), 'recents' (recent apps overview), 'notifications' (pull down notification bar), 'quick_settings' (open quick settings tiles), 'screenshot' (capture screen).",
            params = mapOf("key" to "string"),
            required = listOf("key"),
            paramDescriptions = mapOf(
                "key" to "One of: 'lock', 'back', 'home', 'recents', 'notifications', 'quick_settings', 'screenshot'"
            )
        ))

        // 27. Universal App Storage & Cache Management (Panda Engine)
        toolsArray.add(createFunction(
            name = "manage_app_storage",
            desc = "Autonomously manage app cache and storage: 'clear_cache' (clears app cache), 'uninstall' (uninstalls app), 'open_info' (opens application info page).",
            params = mapOf("app_name" to "string", "action" to "string"),
            required = listOf("app_name", "action"),
            paramDescriptions = mapOf(
                "app_name" to "Name of the target installed application",
                "action" to "'clear_cache', 'uninstall', or 'open_info'"
            )
        ))

        // 28. Autonomous Play Store Search & Install (Panda Engine)
        toolsArray.add(createFunction(
            name = "install_store_app",
            desc = "Search Google Play Store and automatically download/install an app hands-free (e.g. 'Candy Crush install karo', 'download WhatsApp').",
            params = mapOf("query" to "string", "criteria" to "string"),
            required = listOf("query"),
            paramDescriptions = mapOf(
                "query" to "Name of the application or game to search and install from Play Store",
                "criteria" to "Installation strategy: 'top' (top result), 'lowest_mb', or 'highest_rated'"
            )
        ))

        // 29. System Dark Mode / Night Theme
        toolsArray.add(createFunction(
            name = "toggle_dark_mode",
            desc = "Turn system Dark Mode / Night Theme ON or OFF hands-free. Call when user asks 'dark mode on karo', 'light mode lagao'.",
            params = mapOf("enable" to "boolean"),
            required = listOf("enable"),
            paramDescriptions = mapOf(
                "enable" to "true to turn Dark Mode ON, false to turn Dark Mode OFF"
            )
        ))

        // 30. Direct Math & Calculations
        toolsArray.add(createFunction(
            name = "calculate",
            desc = "Evaluate any mathematical expression or arithmetic calculation directly (e.g. '540 * 18', '25% of 1500', 'square root of 144'). ALWAYS call when user asks to calculate or solve math.",
            params = mapOf("expression" to "string"),
            required = listOf("expression"),
            paramDescriptions = mapOf(
                "expression" to "The mathematical expression to evaluate, e.g. '540 * 18' or '1500 * 0.25'"
            )
        ))

        // 31. Contacts Management
        toolsArray.add(createFunction(
            name = "manage_contacts",
            desc = "Save a new contact with name and phone number into phone contacts (e.g. 'Rahul ka number save karo').",
            params = mapOf("name" to "string", "phone_number" to "string"),
            required = listOf("name", "phone_number"),
            paramDescriptions = mapOf(
                "name" to "Name of the person to save in contacts",
                "phone_number" to "Phone number with digits to save"
            )
        ))

        // 32. Dedicated Recent Media / Screenshot Viewer
        toolsArray.add(createFunction(
            name = "show_recent_media",
            desc = "Open and display the most recent screenshot or photo from device storage fullscreen in the default image viewer. Call this when the user says 'show screenshot', 'recent screenshot dikhao', 'open screenshot', 'show photo'. NEVER call when user asks to take a new screenshot (use take_screenshot instead).",
            params = mapOf("media_type" to "string"),
            required = emptyList(),
            paramDescriptions = mapOf(
                "media_type" to "Optional media type filter: 'screenshot' or 'photo'"
            )
        ))

        // 33. Gallery Recycle Bin / Trash Cleaner
        toolsArray.add(createFunction(
            name = "empty_recycle_bin",
            desc = "Empty the Gallery Recycle Bin or Trash permanently deleting all trashed items hands-free. Call this when the user says 'empty recycle bin', 'trash clear karo', 'recycle bin saaf karo'.",
            params = mapOf("target" to "string"),
            required = emptyList(),
            paramDescriptions = mapOf(
                "target" to "Target trash container: 'gallery' or 'all'"
            )
        ))

        // 34. Clock Timer
        toolsArray.add(createFunction(
            name = "set_timer",
            desc = "Set a timer/countdown using the Android Clock app for a specified number of minutes and seconds (e.g. '10 minute ka timer', '5 minutes countdown', '45 seconds timer'). Call ONLY for countdown durations. NEVER call for clock times like '7:00 AM' (use create_alarm instead).",
            params = mapOf(
                "minutes" to "integer",
                "seconds" to "integer",
                "label" to "string"
            ),
            required = listOf("minutes"),
            paramDescriptions = mapOf(
                "minutes" to "Number of minutes for the countdown timer",
                "seconds" to "Optional additional seconds",
                "label" to "Optional label or description for the timer"
            )
        ))

        // 35. Maps & Navigation
        toolsArray.add(createFunction(
            name = "navigate_maps",
            desc = "Open turn-by-turn navigation or search directions to a destination (address, landmark, restaurant, petrol pump, city) using Google Maps. ALWAYS call when user asks for directions or route (e.g. 'navigate to X', 'rasta dikhao', 'kaise jayein').",
            params = mapOf(
                "destination" to "string",
                "mode" to "string"
            ),
            required = listOf("destination"),
            paramDescriptions = mapOf(
                "destination" to "Target address, city, landmark, or establishment name",
                "mode" to "Travel mode: 'driving', 'walking', 'transit', or 'bicycling'"
            )
        ))

        // 36. Web Search / Search Internet
        toolsArray.add(createFunction(
            name = "search_internet",
            desc = "Search the internet in real-time to answer questions, look up facts, scores, people, definitions, live weather, or current information. ALWAYS call for real-time web knowledge. Returns informative snippets directly so ISHA can synthesize the answer without opening a browser.",
            params = mapOf("query" to "string"),
            required = listOf("query"),
            paramDescriptions = mapOf(
                "query" to "Search query to find live web information"
            )
        ))

        // 36b. Live News Feed
        toolsArray.add(createFunction(
            name = "get_latest_news",
            desc = "Fetch latest top news headlines, breaking news, or news on a specific topic (e.g. India, sports, technology, world) directly via live news feeds. ALWAYS call when user asks for 'news', 'taaza khabar', 'samachar', 'aaj ki khabrein'. Returns headlines and summaries for direct spoken synthesis without opening a browser.",
            params = mapOf(
                "topic" to "string",
                "language" to "string"
            ),
            required = emptyList(),
            paramDescriptions = mapOf(
                "topic" to "Topic category like 'top', 'india', 'sports', 'technology', 'world', or blank for general",
                "language" to "News language: 'en' or 'hi'"
            )
        ))

        // 36c. Media Storage Stats
        toolsArray.add(createFunction(
            name = "get_media_storage_stats",
            desc = "Query the device's camera and media storage to count how many photos, videos, and audio files are stored on the phone. ALWAYS call when user asks how many photos, videos, or camera items they have (e.g. 'kitne photos hain', 'videos count'). NEVER call for general disk storage in GB (use get_storage_space instead)!",
            params = emptyMap(),
            required = emptyList()
        ))

        toolsArray.add(createFunction(
            name = "web_search",
            desc = "Perform a web search for live internet information or open search results in browser.",
            params = mapOf(
                "query" to "string",
                "open_browser" to "boolean"
            ),
            required = listOf("query"),
            paramDescriptions = mapOf(
                "query" to "Search query",
                "open_browser" to "true to open results in web browser, false to return snippets"
            )
        ))

        // 37. Open Camera
        toolsArray.add(createFunction(
            name = "open_camera",
            desc = "Open the phone Camera app to take a photo, click picture, record video, or take a selfie ('photo', 'video', 'selfie'). ALWAYS call when user says 'take photo', 'take a photo', 'photo lo', 'camera kholo', 'photo kheecho', 'click photo', 'capture photo', 'selfie lo', 'record video'.",
            params = mapOf("mode" to "string"),
            required = emptyList(),
            paramDescriptions = mapOf(
                "mode" to "'photo', 'video', or 'selfie'"
            )
        ))

        // 38. Screen Brightness
        toolsArray.add(createFunction(
            name = "set_brightness",
            desc = "Set screen display brightness percentage (0 to 100). Call when user asks to adjust screen brightness or display light (e.g. 'brightness 70% karo', 'screen ki roshni kam karo'). NEVER call for audio volume (use set_volume instead).",
            params = mapOf("level_percent" to "integer"),
            required = listOf("level_percent"),
            paramDescriptions = mapOf(
                "level_percent" to "Target screen brightness percentage from 0 (darkest) to 100 (brightest)"
            )
        ))

        // 39. Do Not Disturb (DND)
        toolsArray.add(createFunction(
            name = "set_dnd",
            desc = "Turn Do Not Disturb (DND) silent mode ON or OFF. Call when user asks 'DND on karo', 'Do Not Disturb lagao', 'DND band karo'.",
            params = mapOf("enable" to "boolean"),
            required = listOf("enable"),
            paramDescriptions = mapOf(
                "enable" to "true to turn DND ON, false to turn DND OFF"
            )
        ))

        // 40. Open System Settings
        toolsArray.add(createFunction(
            name = "open_system_settings",
            desc = "Open a specific Android System Settings page. Call ONLY when the user explicitly asks to open settings or navigate to a settings page (e.g. 'open wifi settings', 'hotspot setting kholo', 'bluetooth settings').",
            params = mapOf("setting_name" to "string"),
            required = listOf("setting_name"),
            paramDescriptions = mapOf(
                "setting_name" to "Target setting: 'wifi', 'bluetooth', 'hotspot', 'display', 'sound', 'battery', 'apps', 'storage', 'date', 'airplane', 'location'"
            )
        ))

        // 41. Calendar Event
        toolsArray.add(createFunction(
            name = "add_calendar_event",
            desc = "Create a calendar event or schedule a meeting in the default Calendar app (e.g. 'kal sham 5 baje meeting schedule karo').",
            params = mapOf(
                "title" to "string",
                "description" to "string",
                "location" to "string"
            ),
            required = listOf("title"),
            paramDescriptions = mapOf(
                "title" to "Title of the calendar event",
                "description" to "Optional details or agenda",
                "location" to "Optional location or venue"
            )
        ))

        // 42. Send Email
        toolsArray.add(createFunction(
            name = "send_email",
            desc = "Compose and send an email in Gmail or default email app.",
            params = mapOf(
                "to" to "string",
                "subject" to "string",
                "body" to "string"
            ),
            required = listOf("to"),
            paramDescriptions = mapOf(
                "to" to "Recipient email address",
                "subject" to "Email subject",
                "body" to "Email message body"
            )
        ))

        // 43. Read Recent SMS
        toolsArray.add(createFunction(
            name = "read_recent_sms",
            desc = "Read the latest received cellular SMS text messages from the phone's inbox. Call when user asks 'recent SMS padho', 'aakhiri SMS kiska aaya'.",
            params = mapOf("count" to "integer"),
            required = emptyList(),
            paramDescriptions = mapOf(
                "count" to "Number of recent SMS messages to read (e.g. 3 or 5)"
            )
        ))

        // 44. Query Contact Details
        toolsArray.add(createFunction(
            name = "query_contact",
            desc = "Look up and read a person's contact number or details from phone contacts without calling. ALWAYS call when user asks 'Rahul ka number kya hai', 'Mom ka number batao', 'contact details dikhao'.",
            params = mapOf("name" to "string"),
            required = listOf("name"),
            paramDescriptions = mapOf(
                "name" to "Name of the person or contact to search"
            )
        ))

        // 44b. Remember or Update Contact in Memory
        toolsArray.add(createFunction(
            name = "remember_contact",
            desc = "Save or update a person's phone number in ISHA's persistent long-term memory. Automatically preserves old number history if updating. Call when user tells you a person's number to save, remember, or update (e.g. '9087654321 mobile number rahul ka hai save kar lo', 'ab 9875432106 rahul ka hai update kar lo').",
            params = mapOf(
                "name" to "string",
                "phone_number" to "string",
                "note" to "string"
            ),
            required = listOf("name", "phone_number"),
            paramDescriptions = mapOf(
                "name" to "Name of the person (e.g. 'Rahul', 'Doctor Sharma')",
                "phone_number" to "The phone number to remember",
                "note" to "Optional context or relationship (e.g. 'friend', 'office')"
            )
        ))

        // 44c. Cross-Device Remote Execution
        toolsArray.add(createFunction(
            name = "dispatch_remote_command",
            desc = "Execute a command, open an app, or send a message on another linked phone or device (e.g. 'Phone B par WhatsApp open karo', 'Phone B par flashlight jalao', 'Phone A ka text copy karke Phone B par WhatsApp par Rahul ko bhej do'). Call whenever user asks to do something on another device.",
            params = mapOf(
                "target_device" to "string",
                "action" to "string",
                "app_name" to "string",
                "prompt" to "string",
                "contact" to "string",
                "message" to "string",
                "url" to "string",
                "query" to "string"
            ),
            required = listOf("target_device", "action"),
            paramDescriptions = mapOf(
                "target_device" to "Target device name or alias (e.g. 'Phone B', 'Phone 2', 'Pixel', 'Samsung', 'Lava')",
                "action" to "Action to execute (e.g. 'open_app', 'toggle_flashlight', 'play_youtube', 'lock_device', 'unlock_device', 'chat_on_whatsapp', 'set_volume', 'copy_clipboard')",
                "app_name" to "Name of the application to open or install (e.g. 'Chrome', 'WhatsApp', 'YouTube', 'Settings')",
                "prompt" to "The natural language instruction for the remote device",
                "contact" to "Optional target contact name if sending a message on WhatsApp",
                "message" to "Optional message content to send or type",
                "url" to "Optional web URL or YouTube video link to open on remote device",
                "query" to "Optional search term or song query to search and play on remote device"
            )
        ))

        // 44d. Lock Device
        toolsArray.add(createFunction(
            name = "lock_device",
            desc = "Lock the screen of this device or a remote device immediately (e.g. 'phone lock karo', 'screen band karo', 'Samsung phone lock kar do').",
            params = mapOf(
                "target_device" to "string"
            ),
            required = emptyList(),
            paramDescriptions = mapOf(
                "target_device" to "Optional target device name (e.g. 'Samsung', 'Lava', 'Phone B'). Omit to lock current device."
            )
        ))

        // 44e. Unlock / Wake Device
        toolsArray.add(createFunction(
            name = "unlock_device",
            desc = "Wake screen and unlock this phone or a remote phone (e.g. 'phone unlock karo', 'Lava phone unlock kar do', 'Samsung ko unlock karo', 'screen on karo'). If protected by PIN or pattern, pass the pin or pattern parameter.",
            params = mapOf(
                "target_device" to "string",
                "pin" to "string",
                "pattern" to "string"
            ),
            required = emptyList(),
            paramDescriptions = mapOf(
                "target_device" to "Optional target device name (e.g. 'Samsung', 'Lava', 'Phone B'). Omit to unlock current device.",
                "pin" to "Optional numeric PIN (e.g. '1234', '0000') to enter on lockscreen.",
                "pattern" to "Optional 3x3 pattern sequence numbers 1-9 (e.g. '1,2,3,6,9' or '41578') where 1=top-left, 9=bottom-right."
            )
        ))

        // 44f. Play High-Decibel Siren / Alarm
        toolsArray.add(createFunction(
            name = "play_siren",
            desc = "Play a high-decibel emergency siren / loud alarm sound at 100% volume with strobe flashlight flashing. Used to find a lost phone or sound an alarm (e.g. 'siren bajao', 'alarm bajao', 'siren play karo', 'Samsung phone mein siren bajao', 'find my phone'). Works even when phone is silent or locked.",
            params = mapOf(
                "target_device" to "string"
            ),
            required = emptyList(),
            paramDescriptions = mapOf(
                "target_device" to "Optional target device name or alias (e.g. 'Samsung', 'Phone B', 'Pixel', 'Lava'). Omit to play siren on this phone."
            )
        ))

        // 44g. Stop Siren
        toolsArray.add(createFunction(
            name = "stop_siren",
            desc = "Stop and silence the siren or emergency alarm immediately (e.g. 'siren band karo', 'siren roko', 'stop siren', 'Samsung ka siren band karo').",
            params = mapOf(
                "target_device" to "string"
            ),
            required = emptyList(),
            paramDescriptions = mapOf(
                "target_device" to "Optional target device name or alias. Omit to stop siren on this phone."
            )
        ))

        // 45. Emergency SOS
        toolsArray.add(createFunction(
            name = "emergency_sos",
            desc = "Trigger emergency SOS call to 112 or emergency contact, and optionally send emergency alert SMS. ALWAYS call immediately when user asks for emergency, Police, Ambulance, Fire, or SOS.",
            params = mapOf(
                "target" to "string",
                "message" to "string"
            ),
            required = emptyList(),
            paramDescriptions = mapOf(
                "target" to "Emergency target: 'police', 'ambulance', 'fire', or contact name",
                "message" to "Optional emergency message"
            )
        ))

        // 46. Create Quick Note
        toolsArray.add(createFunction(
            name = "create_quick_note",
            desc = "Save a quick personal note, reminder, or thought to the ISHA permanent memory vault (e.g. 'note likho...', 'yaad rakhna ki ye note save kar lo').",
            params = mapOf(
                "note_text" to "string",
                "title" to "string"
            ),
            required = listOf("note_text"),
            paramDescriptions = mapOf(
                "note_text" to "The note content to store",
                "title" to "Optional title for the note"
            )
        ))

        // 47. Read Status Bar Notifications
        toolsArray.add(createFunction(
            name = "read_notifications",
            desc = "Read or check active status bar notifications (e.g. WhatsApp messages, SMS, OTPs, emails, food delivery, banking alerts) received on the device. Call when user asks 'notifications padho', 'kiska notification aaya hai'.",
            params = mapOf(
                "app_filter" to "string",
                "count" to "integer"
            ),
            required = emptyList(),
            paramDescriptions = mapOf(
                "app_filter" to "Optional app name filter like 'whatsapp', 'sms', 'zomato'",
                "count" to "Number of notifications to read"
            )
        ))

        // 48. Screen Vision — Get Screen Context
        toolsArray.add(createFunction(
            name = "get_screen_context",
            desc = "Inspect the current active screen and get a structured hierarchy of all visible buttons, labels, text messages, and interactive UI elements without touching or scrolling.",
            params = emptyMap(),
            required = emptyList()
        ))

        // 49. Screen Vision — Find and Tap Element
        toolsArray.add(createFunction(
            name = "find_and_tap",
            desc = "Find and tap any button, link, icon, or UI element on screen by its visible text label, content description, or view ID (e.g. 'Submit', 'Confirm', 'Skip Ad', 'Like', 'Send'). Uses semantic matching — no hardcoded coordinates required.",
            params = mapOf("target" to "string"),
            required = listOf("target"),
            paramDescriptions = mapOf(
                "target" to "Visible text, button label, or description of the UI element to tap"
            )
        ))

        // 50. Screen Vision — Read Screen Text
        toolsArray.add(createFunction(
            name = "read_screen_text",
            desc = "Extract and read all readable text, chat messages, articles, or bill details currently visible on the screen. Call when user asks 'screen padho', 'screen par kya likha hai'.",
            params = emptyMap(),
            required = emptyList()
        ))

        // 51. Set Clipboard Text
        toolsArray.add(createFunction(
            name = "set_clipboard",
            desc = "Copy text to the Android system clipboard (e.g. 'ye text copy kar lo').",
            params = mapOf("text" to "string"),
            required = listOf("text"),
            paramDescriptions = mapOf(
                "text" to "The text to copy to the system clipboard"
            )
        ))

        // 52. Get Running / Installed Apps
        toolsArray.add(createFunction(
            name = "get_running_apps",
            desc = "List installed applications on the device or search for installed apps matching a query.",
            params = mapOf("query" to "string"),
            required = emptyList(),
            paramDescriptions = mapOf(
                "query" to "Optional app name filter or search keyword"
            )
        ))

        // 53. Get Geographic Location
        toolsArray.add(createFunction(
            name = "get_location",
            desc = "Get the device's current GPS geographic location (latitude, longitude, and accuracy). Call when user asks 'meri location kya hai', 'main kahan hoon'.",
            params = emptyMap(),
            required = emptyList()
        ))

        // 54. Get WiFi Network Status
        toolsArray.add(createFunction(
            name = "get_wifi_networks",
            desc = "Check WiFi status and get the currently connected WiFi network name (SSID). ALWAYS call when user asks 'wifi ka naam batao', 'kis wifi se connect hoon', 'wifi status kya hai'. NEVER call toggle_wifi for this!",
            params = emptyMap(),
            required = emptyList()
        ))

        // 55. Check Internet Speed (Mbps) & Latency
        toolsArray.add(createFunction(
            name = "check_internet_speed",
            desc = "Test real internet download speed in Mbps and ping latency. ALWAYS call when user asks about 'internet speed', 'net speed test', 'kitne mbps speed aa rahi hai', or wants to test connection speed.",
            params = emptyMap(),
            required = emptyList()
        ))

        // 56. Get Recent Calls
        toolsArray.add(createFunction(
            name = "get_recent_calls",
            desc = "Read recent call logs (incoming, outgoing, missed calls) with contact names and timestamps (e.g. 'kiska phone aaya tha', 'call log dikhao').",
            params = mapOf("count" to "integer"),
            required = emptyList(),
            paramDescriptions = mapOf(
                "count" to "Number of recent call logs to read (e.g. 5)"
            )
        ))

        // 57. Get Contacts List
        toolsArray.add(createFunction(
            name = "get_contacts_list",
            desc = "Search and list contacts with their names and phone numbers from the phone book.",
            params = mapOf("query" to "string", "limit" to "integer"),
            required = emptyList(),
            paramDescriptions = mapOf(
                "query" to "Search query for contact name",
                "limit" to "Maximum number of contacts to list"
            )
        ))

        // 58. Get Internal Device Storage Space
        toolsArray.add(createFunction(
            name = "get_storage_space",
            desc = "Check internal disk storage (Total GB, Used GB, Free/Available GB, and percentage used). ALWAYS call when user asks 'phone mein kitna space hai', 'kitna storage khali hai', 'storage bacha hai', 'storage full hai'. NEVER call get_media_storage_stats for storage queries!",
            params = emptyMap(),
            required = emptyList()
        ))

        // 59. Check Full Device Health
        toolsArray.add(createFunction(
            name = "check_device_health",
            desc = "Run a comprehensive device health diagnosis: checks Battery (percentage, temperature, health status), RAM/Memory (Total, Used, Free GB), Internal Storage (Used/Free GB), System Uptime, and Network status. ALWAYS call when user asks 'device health check karo', 'phone health', 'system check'.",
            params = emptyMap(),
            required = emptyList()
        ))

        // 60. Clear / Dismiss Notifications
        toolsArray.add(createFunction(
            name = "clear_notifications",
            desc = "Dismiss notifications from the status bar. Set target='all' to clear all clearable notifications, or set target to an app name or keyword (e.g. 'melodify', 'screenshot', 'instagram') to dismiss specific ones. ALWAYS call when user asks 'notification hata do', 'clear notification panel', 'sab notifications saaf kardo'.",
            params = mapOf("target" to "string"),
            required = emptyList(),
            paramDescriptions = mapOf(
                "target" to "'all' to clear all notifications, or specific app keyword like 'whatsapp', 'melodify'"
            )
        ))

        // 61. Toggle Incoming Message Voice Announcements
        toolsArray.add(createFunction(
            name = "toggle_message_announcements",
            desc = "Turn ON or OFF the automatic voice reading / announcement of incoming messages (WhatsApp, SMS, OTP) when they arrive. ALWAYS call when user asks 'message padhna band karo', 'msg aane par mat bolo', 'message announcement on/off karo', 'turn off message speak'.",
            params = mapOf("enable" to "boolean"),
            required = listOf("enable"),
            paramDescriptions = mapOf(
                "enable" to "true to turn message announcement ON, false to turn OFF"
            )
        ))

        // 62. Get Current Live Device Time & Date
        toolsArray.add(createFunction(
            name = "get_current_time",
            desc = "Get the exact, live system clock time, day, and date right now from the device. ALWAYS call when user asks 'time kya hai', 'time batao', 'kitne baje hain', 'kya time ho raha hai', 'aaj kaun sa din hai', 'aaj ki date kya hai'. NEVER rely on memory or guess the time!",
            params = emptyMap(),
            required = emptyList()
        ))

        // 63. Get Network Data Usage & Connected Devices
        toolsArray.add(createFunction(
            name = "get_device_connectivity_and_usage",
            desc = "Get real-time device network data consumption (Mobile Data and Wi-Fi data used in MB/GB), connected Wi-Fi name, personal hotspot status, connected hotspot devices count, and active connected Bluetooth devices (smartwatch, earbuds, headphones). ALWAYS call when user asks 'kitna data consume kiya', 'hotspot usage', 'kitna data bacha/use hua', 'kaun kaun si device connect hai', 'bluetooth devices connected', 'active connections', 'wifi ka naam batao'.",
            params = emptyMap(),
            required = emptyList()
        ))

        // 64. Clear Chrome History
        toolsArray.add(createFunction(
            name = "clear_chrome_history",
            desc = "Clear Chrome browser history, cache, or browsing data hands-free. Call when user asks 'Chrome history delete kar do', 'Chrome browsing history clear karo'.",
            params = emptyMap(),
            required = emptyList()
        ))

        // 65. Clear YouTube History
        toolsArray.add(createFunction(
            name = "clear_youtube_history",
            desc = "Clear YouTube search history or watch history hands-free. Call when user asks 'YouTube search history delete kar do', 'YouTube history saaf karo'.",
            params = emptyMap(),
            required = emptyList()
        ))

        // 66. WhatsApp Post Status / Story
        toolsArray.add(createFunction(
            name = "whatsapp_post_status",
            desc = "Post a new status or story on WhatsApp. Can post text status or open status camera for photo/video status. ALWAYS call when user asks to post a WhatsApp status (e.g. 'WhatsApp par status lagao', 'WhatsApp story post karo').",
            params = mapOf(
                "status_text" to "string",
                "media_type" to "string"
            ),
            required = emptyList(),
            paramDescriptions = mapOf(
                "status_text" to "Text content to post as WhatsApp status",
                "media_type" to "'text', 'photo', or 'camera'"
            )
        ))

        // 67. WhatsApp Trigger Chat Backup
        toolsArray.add(createFunction(
            name = "whatsapp_trigger_backup",
            desc = "Automatically trigger a complete WhatsApp chat backup to Google Drive or local storage hands-free. ALWAYS call when user asks 'WhatsApp backup le lo', 'chat backup karo', 'WhatsApp ka backup banao'.",
            params = emptyMap(),
            required = emptyList()
        ))

        // 68. WhatsApp Open Settings
        toolsArray.add(createFunction(
            name = "whatsapp_open_settings",
            desc = "Open WhatsApp Settings and navigate to a specific section: 'privacy', 'chats', 'notifications', 'storage', 'account', or general settings. ALWAYS call when user asks to open or change WhatsApp settings.",
            params = mapOf("section" to "string"),
            required = emptyList(),
            paramDescriptions = mapOf(
                "section" to "Target section: 'privacy', 'chats', 'notifications', 'storage', 'account'"
            )
        ))

        // 69. Instagram Like Post / Reel
        toolsArray.add(createFunction(
            name = "instagram_like_post",
            desc = "Like a post or reel on Instagram currently visible on screen or double-tap center. ALWAYS call when user asks to like an Instagram post or reel (e.g. 'ye post like karo', 'Instagram reel like karo', 'double tap kar do').",
            params = mapOf("double_tap" to "boolean"),
            required = emptyList(),
            paramDescriptions = mapOf(
                "double_tap" to "true to execute rapid double-tap like gesture, false to click heart button"
            )
        ))

        // 70. Instagram Comment on Post / Reel
        toolsArray.add(createFunction(
            name = "instagram_comment_post",
            desc = "Post a comment on an Instagram photo, video, or reel. ALWAYS call when user asks to comment on Instagram (e.g. 'Instagram post par comment karo: Nice pic', 'reel pe comment likho').",
            params = mapOf("comment" to "string"),
            required = listOf("comment"),
            paramDescriptions = mapOf(
                "comment" to "The exact comment text to publish"
            )
        ))

        // 71. Instagram Send DM
        toolsArray.add(createFunction(
            name = "instagram_send_dm",
            desc = "Send an Instagram Direct Message (DM) to a user autonomously. ALWAYS call when user asks to DM someone on Instagram (e.g. 'Rahul ko Instagram pe DM karo', 'Instagram pe message bhejo').",
            params = mapOf("username" to "string", "message" to "string"),
            required = listOf("username", "message"),
            paramDescriptions = mapOf(
                "username" to "Instagram username or handle to message",
                "message" to "The exact message content to send"
            )
        ))

        // 72. Instagram Post Story
        toolsArray.add(createFunction(
            name = "instagram_post_story",
            desc = "Create and post a new story on Instagram (text, latest photo, or screenshot). ALWAYS call when user asks to post an Instagram story (e.g. 'Instagram par story lagao', 'story upload karo').",
            params = mapOf("caption" to "string", "media_type" to "string"),
            required = emptyList(),
            paramDescriptions = mapOf(
                "caption" to "Optional caption or text for the story",
                "media_type" to "'screenshot', 'photo', or 'text'"
            )
        ))

        // 73. Instagram Navigation & Settings
        toolsArray.add(createFunction(
            name = "instagram_navigate",
            desc = "Navigate to a specific section inside Instagram: 'reels' (watch reels), 'dms' (direct messages inbox), 'profile' (own profile), 'explore' (search/explore feed), 'settings' (Instagram settings). ALWAYS call when user wants to browse or open a specific Instagram section.",
            params = mapOf("destination" to "string"),
            required = listOf("destination"),
            paramDescriptions = mapOf(
                "destination" to "One of: 'reels', 'dms', 'profile', 'explore', 'settings'"
            )
        ))

        // 74. Universal Autonomous Millisecond UI & Knowledge Engine
        toolsArray.add(createFunction(
            name = "autonomous_ui_action",
            desc = "Universal autonomous millisecond UI execution engine for ANY task without a hardcoded tool. If user asks to perform an action in an app that has no specific tool, ISHA inspects screen hierarchy (<20ms), applies semantic matching or instant internet knowledge, and clicks/types directly on the UI without human intervention.",
            params = mapOf(
                "app_name" to "string",
                "goal_description" to "string",
                "target_keywords" to "string",
                "input_text" to "string"
            ),
            required = listOf("goal_description"),
            paramDescriptions = mapOf(
                "app_name" to "Target app name (e.g. 'whatsapp', 'instagram', 'settings', 'amazon', 'zomato')",
                "goal_description" to "Detailed description of what needs to be accomplished",
                "target_keywords" to "Comma-separated button labels or text hints to click",
                "input_text" to "Optional text to type if typing is needed"
            )
        ))

        // 75. Telegram Control
        toolsArray.add(createFunction(
            name = "telegram_action",
            desc = "Control Telegram: send a message to a contact, open a chat, or search in Telegram. ALWAYS call when user asks to message or chat on Telegram (e.g. 'Telegram par Rahul ko message bhejo: Hello', 'Telegram chat kholo').",
            params = mapOf(
                "action" to "string",
                "recipient" to "string",
                "message" to "string"
            ),
            required = listOf("action"),
            paramDescriptions = mapOf(
                "action" to "'send_message', 'open_chat', or 'search'",
                "recipient" to "Contact name or Telegram username",
                "message" to "Message text to send"
            )
        ))

        // 76. YouTube & Shorts Interaction
        toolsArray.add(createFunction(
            name = "youtube_interact",
            desc = "Interact with active YouTube video or YouTube Shorts: 'like' (like video), 'subscribe' (subscribe to channel), 'skip_ad' (skip active ad), 'comment' (post comment), 'next_short' (scroll to next Short), 'prev_short' (previous Short). ALWAYS call for YouTube video/Shorts UI interaction.",
            params = mapOf(
                "action" to "string",
                "comment" to "string"
            ),
            required = listOf("action"),
            paramDescriptions = mapOf(
                "action" to "'like', 'subscribe', 'skip_ad', 'comment', 'next_short', or 'prev_short'",
                "comment" to "Comment text if action is 'comment'"
            )
        ))

        // 77. Food Delivery Control (Zomato / Swiggy)
        toolsArray.add(createFunction(
            name = "food_delivery_control",
            desc = "Control Zomato or Swiggy: search dishes/restaurants, track live delivery order, or open cart. ALWAYS call when user asks about ordering food, tracking delivery, or checking cart (e.g. 'Zomato par pizza search karo', 'Swiggy order track karo', 'Zomato cart dikhao').",
            params = mapOf(
                "app" to "string",
                "action" to "string",
                "query" to "string"
            ),
            required = listOf("action"),
            paramDescriptions = mapOf(
                "app" to "'zomato' or 'swiggy' (defaults to 'zomato')",
                "action" to "'search', 'track_order', or 'cart'",
                "query" to "Dish, food, or restaurant name to search"
            )
        ))

        // 78. Ride Booking Control (Uber / Ola / Rapido)
        toolsArray.add(createFunction(
            name = "ride_booking_control",
            desc = "Control Uber, Ola, or Rapido: search ride/destination, open booking screen, or track active ride/driver. ALWAYS call when user asks to book a cab, search Uber/Ola, or track ride (e.g. 'Uber par airport ka cab search karo', 'Ola ride track karo').",
            params = mapOf(
                "app" to "string",
                "action" to "string",
                "destination" to "string"
            ),
            required = listOf("action"),
            paramDescriptions = mapOf(
                "app" to "'uber', 'ola', or 'rapido' (defaults to 'uber')",
                "action" to "'search_ride' or 'track_ride'",
                "destination" to "Drop destination address or landmark"
            )
        ))

        // 79. Shopping Control (Amazon / Flipkart)
        toolsArray.add(createFunction(
            name = "shopping_control",
            desc = "Control Amazon or Flipkart: search products, track current orders/packages, or view shopping cart. ALWAYS call when user asks to search products or track shopping orders (e.g. 'Amazon par iPhone search karo', 'Flipkart order track karo', 'Amazon cart kholo').",
            params = mapOf(
                "app" to "string",
                "action" to "string",
                "query" to "string"
            ),
            required = listOf("action"),
            paramDescriptions = mapOf(
                "app" to "'amazon' or 'flipkart' (defaults to 'amazon')",
                "action" to "'search', 'track_orders', or 'cart'",
                "query" to "Product name or search keywords"
            )
        ))

        // 80. UPI Payment Control (Google Pay / PhonePe / Paytm)
        toolsArray.add(createFunction(
            name = "upi_payment_control",
            desc = "Control Google Pay (GPay), PhonePe, or Paytm: 'scan_qr' (open QR scanner), 'send_money' (initiate payment to contact/number), 'check_balance' (open bank balance check), 'history' (view transaction history). ALWAYS call when user asks to scan QR or make UPI payment (e.g. 'QR scan karo', 'PhonePe par 500 bhejo', 'GPay balance check karo').",
            params = mapOf(
                "app" to "string",
                "action" to "string",
                "recipient" to "string",
                "amount" to "string"
            ),
            required = listOf("action"),
            paramDescriptions = mapOf(
                "app" to "'gpay', 'phonepe', or 'paytm' (defaults to 'gpay')",
                "action" to "'scan_qr', 'send_money', 'check_balance', or 'history'",
                "recipient" to "Name or mobile number of the payee",
                "amount" to "Amount in rupees (e.g. '500')"
            )
        ))

        // 81. Twitter / X Control
        toolsArray.add(createFunction(
            name = "twitter_control",
            desc = "Control Twitter / X: post a new tweet, like active tweet, search tweets, or open notifications. ALWAYS call when user asks to tweet or use Twitter (e.g. 'Twitter par tweet karo: Hello world', 'ye tweet like karo').",
            params = mapOf(
                "action" to "string",
                "text" to "string"
            ),
            required = listOf("action"),
            paramDescriptions = mapOf(
                "action" to "'post_tweet', 'like', 'search', or 'notifications'",
                "text" to "Tweet content or search query"
            )
        ))

        // 82. Snapchat Control
        toolsArray.add(createFunction(
            name = "snapchat_control",
            desc = "Control Snapchat: open camera to take a snap, switch to stories, or open chats. ALWAYS call when user asks to open Snapchat camera, stories, or chats.",
            params = mapOf(
                "action" to "string"
            ),
            required = listOf("action"),
            paramDescriptions = mapOf(
                "action" to "'camera', 'stories', or 'chats'"
            )
        ))

        // 83. Chrome / Browser Control
        toolsArray.add(createFunction(
            name = "browser_control",
            desc = "Control Chrome or default web browser: 'open_url' (open specific website), 'new_tab' (open new tab), 'incognito' (open incognito/private tab), 'search' (search query in browser), 'bookmark' (bookmark current page). ALWAYS call when user asks to browse or use browser tabs.",
            params = mapOf(
                "action" to "string",
                "query_or_url" to "string"
            ),
            required = listOf("action"),
            paramDescriptions = mapOf(
                "action" to "'open_url', 'new_tab', 'incognito', 'search', or 'bookmark'",
                "query_or_url" to "Website URL or search query"
            )
        ))

        // 84. Spotify Control
        toolsArray.add(createFunction(
            name = "spotify_control",
            desc = "Control Spotify: like current playing song ('like'), search playlist/song ('search'), play/pause, or skip next/previous. ALWAYS call when user specifically asks to like a Spotify track or control Spotify playback.",
            params = mapOf(
                "action" to "string",
                "query" to "string"
            ),
            required = listOf("action"),
            paramDescriptions = mapOf(
                "action" to "'like', 'search', 'play', 'pause', 'next', 'previous'",
                "query" to "Search song or playlist name"
            )
        ))

        // 85. Visual Reflection Agent: Dismiss Screen Popups / Interrupting Ads
        toolsArray.add(createFunction(
            name = "dismiss_screen_popups",
            desc = "Dismiss interrupting popups, ads, rate-us dialogs, update sheets, consent dialogues, or overlay banners on the current screen (e.g. 'Skip Ad', 'Close', 'Not now', 'Cancel', 'Dismiss', '✕'). ALWAYS call when user says 'ad hatao', 'popup close karo', 'skip ad', 'dialog dismiss karo', 'screen se ye hatao'.",
            params = mapOf(
                "fallback_back" to "boolean"
            ),
            required = emptyList(),
            paramDescriptions = mapOf(
                "fallback_back" to "If true (default), triggers system BACK gesture if no specific clickable dismiss node is found"
            )
        ))

        // 86. Personalized Daily Briefing Engine
        toolsArray.add(createFunction(
            name = "get_daily_briefing",
            desc = "Aggregate real-time personalized daily briefing for the Boss: live date/time, battery percentage & charging status, unread status bar notifications (WhatsApp/SMS), user facts from memory vault, and top breaking news headlines. ALWAYS call when user says 'good morning', 'aaj ka briefing do', 'daily briefing sunao', 'morning update', 'what's my day looking like'.",
            params = mapOf(
                "briefing_type" to "string"
            ),
            required = emptyList(),
            paramDescriptions = mapOf(
                "briefing_type" to "'morning', 'evening', or 'full'"
            )
        ))

        // 87. Cross-App Chained Workflow Relay
        toolsArray.add(createFunction(
            name = "cross_app_workflow",
            desc = "Autonomous cross-app chained workflow relay: extracts an entity (address, phone number, tracking code, or text query) from a source (screen, clipboard, or recent notifications/WhatsApp) and relays it to perform an action in a target app (Uber, Ola, Google Maps, WhatsApp, Swiggy, Zomato, browser). ALWAYS call for chained cross-app requests like 'WhatsApp par jo address aaya uspe Uber cab book karo', 'screen par jo address hai use Google Maps par navigate karo', 'clipboard ka text WhatsApp par Mummy ko bhejo'.",
            params = mapOf(
                "source" to "string",
                "target_app" to "string",
                "action" to "string",
                "target_contact" to "string",
                "fallback_entity" to "string"
            ),
            required = listOf("target_app", "action"),
            paramDescriptions = mapOf(
                "source" to "Source of data: 'screen', 'clipboard', 'notifications', or 'whatsapp'",
                "target_app" to "Target application: 'uber', 'ola', 'maps', 'whatsapp', 'zomato', 'swiggy', or 'browser'",
                "action" to "Action to execute: 'book_cab', 'navigate', 'send_message', 'order_food', or 'search'",
                "target_contact" to "Optional contact name if target_app is whatsapp",
                "fallback_entity" to "Optional fallback address or query if auto-extraction finds none"
            )
        ))

        return toolsArray
    }

    private fun createFunction(
        name: String,
        desc: String,
        params: Map<String, String>,
        required: List<String>,
        paramDescriptions: Map<String, String> = emptyMap()
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
            paramDescriptions[pName]?.let { pDesc ->
                prop.addProperty("description", pDesc)
            }
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
     * Dynamically resolves an application Intent via known packages, direct package lookup,
     * or Android PackageManager launcher activity query (supports all OEMs).
     */
    fun resolveAppIntent(context: Context, appName: String): Intent? {
        val clean = appName.lowercase().trim()
        // Universal Camera Intent (Works across Samsung, Lava, Xiaomi, Pixel, Motorola, OnePlus)
        if (clean == "camera" || clean == "cam" || clean.contains("camera")) {
            return Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
        val pm = context.packageManager
        val knownPackages = mapOf(
            "chrome" to "com.android.chrome",
            "chrom" to "com.android.chrome",
            "google chrome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "youtube" to "com.google.android.youtube",
            "yt" to "com.google.android.youtube",
            "whatsapp" to "com.whatsapp",
            "wa" to "com.whatsapp",
            "gmail" to "com.google.android.gm",
            "email" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "gallery" to "com.sec.android.gallery3d",
            "photos" to "com.google.android.apps.photos",
            "camera" to "com.sec.android.app.camera",
            "settings" to "com.android.settings",
            "setting" to "com.android.settings",
            "calculator" to "com.sec.android.app.popupcalculator",
            "clock" to "com.sec.android.app.clockpackage",
            "calendar" to "com.samsung.android.calendar",
            "files" to "com.sec.android.app.myfiles",
            "my files" to "com.sec.android.app.myfiles",
            "play store" to "com.android.vending",
            "playstore" to "com.android.vending",
            "google" to "com.google.android.googlequicksearchbox",
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "telegram" to "org.telegram.messenger",
            "spotify" to "com.spotify.music",
            "amazon" to "in.amazon.mShop.android.shopping",
            "flipkart" to "com.flipkart.android",
            "zomato" to "com.application.zomato",
            "swiggy" to "in.swiggy.android",
            "uber" to "com.ubercab",
            "ola" to "com.olacabs.customer",
            "rapido" to "com.rapido.passenger",
            "paytm" to "net.one97.paytm",
            "phonepe" to "com.phonepe.app",
            "gpay" to "com.google.android.apps.nbu.paisa.user",
            "google pay" to "com.google.android.apps.nbu.paisa.user",
            "bhim" to "in.org.npci.upiapp",
            "meesho" to "com.meesho.supply",
            "wynk" to "tv.accedo.airtel.wynk",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "snapchat" to "com.snapchat.android"
        )

        // 1. Dynamic lookup via resolveAppPackage (handles multi-package alternatives like India Amazon & Vanced/YouTube)
        val dynamicPkg = resolveAppPackage(context, clean)
        if (dynamicPkg != null) {
            val intent = pm.getLaunchIntentForPackage(dynamicPkg)
            if (intent != null) return intent
        }

        // 2. Check known packages
        var targetPkg = knownPackages[clean]
        if (targetPkg == null) {
            for ((k, v) in knownPackages) {
                if (clean.contains(k) || k.contains(clean)) {
                    targetPkg = v
                    break
                }
            }
        }
        if (targetPkg != null) {
            val intent = pm.getLaunchIntentForPackage(targetPkg)
            if (intent != null) return intent
        }

        // 3. Direct package lookup
        pm.getLaunchIntentForPackage(appName)?.let { return it }

        // 4. Query all launcher activities (Samsung / Android 11+ safe)
        try {
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolveInfos = pm.queryIntentActivities(launcherIntent, 0)
            val matched = resolveInfos.firstOrNull {
                val label = it.loadLabel(pm).toString()
                label.contains(clean, ignoreCase = true) ||
                it.activityInfo.packageName.contains(clean, ignoreCase = true)
            } ?: if (clean == "game" || clean.contains("game")) {
                resolveInfos.firstOrNull {
                    val appInfo = try { pm.getApplicationInfo(it.activityInfo.packageName, 0) } catch (_: Exception) { null }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appInfo != null && appInfo.category == android.content.pm.ApplicationInfo.CATEGORY_GAME) {
                        true
                    } else {
                        val label = it.loadLabel(pm).toString()
                        label.contains("game", ignoreCase = true) || it.activityInfo.packageName.contains("game", ignoreCase = true)
                    }
                }
            } else null
            if (matched != null) {
                val intent = pm.getLaunchIntentForPackage(matched.activityInfo.packageName)
                if (intent != null) return intent
            }
        } catch (_: Exception) {}

        // 5. Universal Web / App fallback intents for common apps if not installed
        when {
            clean.contains("amazon") -> return Intent(Intent.ACTION_VIEW, Uri.parse("https://www.amazon.in")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            clean.contains("flipkart") -> return Intent(Intent.ACTION_VIEW, Uri.parse("https://www.flipkart.com")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            clean.contains("zomato") -> return Intent(Intent.ACTION_VIEW, Uri.parse("https://www.zomato.com")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            clean.contains("swiggy") -> return Intent(Intent.ACTION_VIEW, Uri.parse("https://www.swiggy.com")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            clean.contains("uber") -> return Intent(Intent.ACTION_VIEW, Uri.parse("https://m.uber.com")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            clean.contains("ola") -> return Intent(Intent.ACTION_VIEW, Uri.parse("https://book.olacabs.com")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            clean.contains("spotify") -> return Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            clean.contains("twitter") || clean == "x" -> return Intent(Intent.ACTION_VIEW, Uri.parse("https://twitter.com")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            clean.contains("youtube") || clean == "yt" -> return Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            clean.contains("chrome") || clean.contains("browser") || clean.contains("web") || clean.contains("internet") -> {
                return Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
        }

        return null
    }

    /**
     * Synchronous blocking execution helper for legacy non-coroutine callers.
     */
    @JvmStatic
    fun executeToolBlocking(context: Context, name: String, args: JsonObject): JsonObject =
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            executeTool(context, name, args)
        }

    /**
     * Executes a tool call requested by Gemini and returns the structured result.
     */
    @SuppressLint("MissingPermission")
    suspend fun executeTool(context: Context, name: String, args: JsonObject): JsonObject {
        val toolStartTime = System.currentTimeMillis()
        Log.i(TAG, "Executing tool: $name with args: $args")
        val result = JsonObject()

        try {
            when (name) {
                "make_call" -> {
                    executeMakeCall(context, args).entrySet().forEach { (k, v) -> result.add(k, v) }
                }

                "send_sms" -> {
                    executeSendSms(context, args).entrySet().forEach { (k, v) -> result.add(k, v) }
                }

                "send_whatsapp" -> {
                    wakeAndUnlockIfNeeded(context)
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
                    val a11y = IshaAccessibilityService.instance

                    // ── In-Chat Instant Fast-Send Check ──
                    // If WhatsApp is already open in a chat conversation (e.g. follow-up turn "ye bhi bhej do",
                    // replying right after chat_on_whatsapp, or user is already chatting):
                    if (a11y != null && a11y.isWhatsappChatOpen()) {
                        val activeTitle = a11y.getWhatsappActiveContactTitle()?.lowercase() ?: ""
                        val cLower = cleanContact.lowercase()
                        val isSameChat = cleanContact.isBlank() ||
                                cLower in listOf("same", "current", "here", "him", "her", "unhe", "usse", "inhe", "aur", "ye", "ye bhi") ||
                                (activeTitle.isNotBlank() && (activeTitle.contains(cLower) || cLower.contains(activeTitle)))

                        if (isSameChat) {
                            val sent = a11y.armWhatsappInstantInChatSend(msg)
                            if (sent) {
                                result.addProperty("status", "success")
                                result.addProperty("contact", if (activeTitle.isNotBlank()) activeTitle else contact)
                                result.addProperty("message", "WhatsApp open chat me turant message bhej diya: \"$msg\"")
                                return result
                            }
                        }
                    }

                    val resolvedPhone = resolveContactNumber(context, if (cleanContact.isNotBlank()) cleanContact else contact)
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

                "type_message", "type_text" -> {
                    wakeAndUnlockIfNeeded(context)
                    val text = args.get("text")?.asString
                        ?: args.get("message")?.asString
                        ?: ""
                    val contact = args.get("contact_name")?.asString
                        ?: args.get("contact")?.asString
                        ?: ""
                    val app = args.get("app_name")?.asString?.lowercase() ?: ""
                    val a11y = IshaAccessibilityService.instance

                    if (text.isBlank()) {
                        result.addProperty("status", "error")
                        result.addProperty("message", "Type karne ke liye text nahi mila Boss.")
                    } else if (app == "whatsapp" || contact.isNotBlank()) {
                        val waPkg = try {
                            context.packageManager.getPackageInfo("com.whatsapp", 0)
                            "com.whatsapp"
                        } catch (_: Exception) {
                            "com.whatsapp.w4b"
                        }

                        val cleanContact = contact.replace(Regex("\\b(ko|ji|bhai|sahab|de|ka|ki|se|pe|par)\\b", RegexOption.IGNORE_CASE), "").trim()

                        // If already in active WhatsApp chat with same person or generic follow-up, avoid reloading
                        val isChatOpen = a11y?.isWhatsappChatOpen() == true
                        val activeTitle = a11y?.getWhatsappActiveContactTitle()?.lowercase() ?: ""
                        val cLower = cleanContact.lowercase()
                        val isSameChat = isChatOpen && (cleanContact.isBlank() ||
                                cLower in listOf("same", "current", "here", "him", "her", "unhe", "usse", "inhe", "aur", "ye", "ye bhi") ||
                                (activeTitle.isNotBlank() && (activeTitle.contains(cLower) || cLower.contains(activeTitle))))

                        if (!isSameChat) {
                            val resolvedPhone = if (cleanContact.isNotBlank()) resolveContactNumber(context, cleanContact) else null
                            if (!resolvedPhone.isNullOrBlank()) {
                                val digits = resolvedPhone.replace(Regex("[^0-9]"), "")
                                val waNumber = if (digits.length == 10 && !digits.startsWith("0")) "91$digits" else digits
                                val uri = Uri.parse("https://wa.me/$waNumber")
                                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                    setPackage(waPkg)
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } else {
                                val intent = context.packageManager.getLaunchIntentForPackage(waPkg)
                                if (intent != null) {
                                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    context.startActivity(intent)
                                }
                            }
                        }

                        if (a11y != null) {
                            a11y.armWhatsappTypeMessage(text)
                            result.addProperty("status", "success")
                            result.addProperty("message", "WhatsApp par \"$text\" type kar diya hai Boss! Keyboard open hai, aap review karke bhej sakte hain. ✍️")
                        } else {
                            result.addProperty("status", "partial")
                            result.addProperty("message", "WhatsApp open kar diya hai Boss. Typing ke liye Accessibility Service enable karein.")
                        }
                    } else {
                        if (a11y != null) {
                            val typed = a11y.performTypeText(text)
                            if (typed) {
                                result.addProperty("status", "success")
                                result.addProperty("message", "Screen par \"$text\" type kar diya hai Boss! ✍️")
                            } else {
                                a11y.armWhatsappTypeMessage(text)
                                result.addProperty("status", "success")
                                result.addProperty("message", "\"$text\" type kar diya hai Boss! ✍️")
                            }
                        } else {
                            result.addProperty("status", "error")
                            result.addProperty("message", "Type karne ke liye Accessibility Service required hai Boss.")
                        }
                    }
                }

                "chat_on_whatsapp" -> {
                    wakeAndUnlockIfNeeded(context)
                    val contact = args.get("contact_name")?.asString
                        ?: args.get("contact")?.asString
                        ?: ""
                    val cleanContact = contact.replace(Regex("\\b(ko|ji|bhai|sahab|de|ka|ki|se|pe|par)\\b", RegexOption.IGNORE_CASE), "").trim()
                    val waPkg = try {
                        context.packageManager.getPackageInfo("com.whatsapp", 0)
                        "com.whatsapp"
                    } catch (_: Exception) {
                        "com.whatsapp.w4b"
                    }
                    val resolvedPhone = if (cleanContact.isNotBlank()) resolveContactNumber(context, cleanContact) else null
                    val a11y = IshaAccessibilityService.instance

                    if (!resolvedPhone.isNullOrBlank()) {
                        val digits = resolvedPhone.replace(Regex("[^0-9]"), "")
                        val waNumber = if (digits.length == 10 && !digits.startsWith("0")) "91$digits" else digits
                        val uri = Uri.parse("https://wa.me/$waNumber")
                        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                            setPackage(waPkg)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    } else if (cleanContact.isNotBlank()) {
                        val intent = context.packageManager.getLaunchIntentForPackage(waPkg)
                        if (intent != null) {
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(intent)
                        }
                        a11y?.armWhatsappOpenContactChat(cleanContact) {}
                    } else {
                        val intent = context.packageManager.getLaunchIntentForPackage(waPkg)
                        if (intent != null) {
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(intent)
                        }
                    }

                    // Wait 1200ms for chat screen to render
                    try {
                        Thread.sleep(1200L)
                    } catch (_: Exception) {}

                    val screenHierarchy = a11y?.getScreenTextHierarchy() ?: ""
                    result.addProperty("status", "success")
                    result.addProperty("contact", contact)
                    result.addProperty("screen_messages", screenHierarchy)
                    result.addProperty(
                        "message",
                        "WhatsApp chat with $contact open ho gayi hai Boss! Screen text:\n$screenHierarchy\nAb in messages ko samajhkar reply dene ke liye send_whatsapp call karein."
                    )
                }

                "send_whatsapp_media" -> {
                    executeSendWhatsappMedia(context, args).entrySet().forEach { (k, v) -> result.add(k, v) }
                }

                "clear_whatsapp_chat" -> {
                    val contact = args.get("contact_name")?.asString ?: args.get("contact")?.asString ?: ""
                    val target = args.get("target")?.asString ?: "chat"
                    val a11y = IshaAccessibilityService.instance
                    if (contact.isNotBlank()) {
                        executeTool(context, "open_app", JsonObject().apply { addProperty("app_name", "whatsapp") })
                        a11y?.armClearWhatsappChat(contact)
                        result.addProperty("status", "success")
                        result.addProperty("message", "WhatsApp me $contact ke saath $target clear karne ka process initiate kar diya hai Boss! 🧹")
                    } else {
                        result.addProperty("status", "error")
                        result.addProperty("message", "Contact name provide karna zaroori hai.")
                    }
                }

                "clear_chrome_history" -> {
                    val a11y = IshaAccessibilityService.instance
                    executeTool(context, "open_app", JsonObject().apply { addProperty("app_name", "chrome") })
                    if (a11y != null) {
                        a11y.armClearChromeHistory()
                        result.addProperty("status", "success")
                        result.addProperty("message", "Chrome browsing history clear karne ka action start kar diya hai Boss! 🌐🧹")
                    } else {
                        result.addProperty("status", "warning")
                        result.addProperty("message", "Chrome open ho gaya hai, lekin auto-clear ke liye Accessibility settings me ISHA ko turn ON karein.")
                    }
                }

                "clear_youtube_history" -> {
                    val a11y = IshaAccessibilityService.instance
                    executeTool(context, "open_app", JsonObject().apply { addProperty("app_name", "youtube") })
                    if (a11y != null) {
                        a11y.armClearYouTubeHistory()
                        result.addProperty("status", "success")
                        result.addProperty("message", "YouTube search/watch history clear karne ka action start kar diya hai Boss! ▶️🧹")
                    } else {
                        result.addProperty("status", "warning")
                        result.addProperty("message", "YouTube open ho gaya hai, lekin auto-clear ke liye Accessibility settings me ISHA ko turn ON karein.")
                    }
                }

                "take_screenshot" -> {
                    executeTakeScreenshot(context).entrySet().forEach { (k, v) -> result.add(k, v) }
                }

                "share_media" -> {
                    val targetApp = (args.get("target_app")?.asString ?: args.get("app")?.asString ?: "whatsapp").lowercase()
                    val caption = args.get("caption")?.asString ?: args.get("message")?.asString ?: ""
                    val contact = args.get("contact_name")?.asString ?: args.get("contact")?.asString ?: ""

                    val a11y = IshaAccessibilityService.instance
                    var imageUri = getLatestScreenshotOrImageUri(context)
                    if (imageUri == null && a11y != null) {
                        var capturedBytes: ByteArray? = null
                        try {
                            withTimeoutOrNull(2000L) {
                                suspendCancellableCoroutine<Unit> { cont ->
                                    a11y.takeScreenCapture { bytes ->
                                        capturedBytes = bytes
                                        if (cont.isActive) cont.resume(Unit)
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                        if (capturedBytes != null) {
                            imageUri = saveBytesToGallery(context, capturedBytes!!)
                        }
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
                        val chooser = Intent.createChooser(intent, "Share media via ISHA").apply {
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
                            IshaAccessibilityService.instance?.armWhatsappAutoSendPreFilled()
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
                    val a11y = IshaAccessibilityService.instance

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
                        val files = IshaFileManager.searchFiles(context, "WhatsApp", 10)
                        val imgFiles = files.filter { it.path.contains("WhatsApp Images", ignoreCase = true) || it.name.startsWith("IMG-", ignoreCase = true) }
                        imgFiles.take(2).forEach { f ->
                            val vaulted = IshaFileManager.deleteToVault(context, f.path)
                            if (vaulted != null) localDeleted++
                        }
                    } catch (_: Exception) {}

                    result.addProperty("status", "success")
                    val detail = if (localDeleted > 0) " (aur $localDeleted WhatsApp photos phone storage se recycle bin me move kar di)" else ""
                    result.addProperty("message", "WhatsApp par $contact ke last message/photos delete karne ki action execute kar di gayi hai Boss! 🗑️$detail")
                }

                "whatsapp_post_status" -> {
                    val statusText = args.get("status_text")?.asString ?: args.get("text")?.asString ?: ""
                    val mediaType = args.get("media_type")?.asString?.lowercase() ?: "text"
                    val a11y = IshaAccessibilityService.instance
                    val waPkg = try {
                        context.packageManager.getPackageInfo("com.whatsapp", 0)
                        "com.whatsapp"
                    } catch (_: Exception) {
                        "com.whatsapp.w4b"
                    }

                    val intent = context.packageManager.getLaunchIntentForPackage(waPkg)?.apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    if (intent != null) {
                        try { context.startActivity(intent) } catch (_: Exception) {}
                    }
                    a11y?.armWhatsappStatus(if (mediaType == "camera") null else statusText)

                    result.addProperty("status", "success")
                    result.addProperty("message", "WhatsApp status lagane ki action shuru kar di hai Boss! ${if (statusText.isNotBlank()) "\"$statusText\"" else ""}")
                }

                "whatsapp_trigger_backup" -> {
                    val a11y = IshaAccessibilityService.instance
                    val waPkg = try {
                        context.packageManager.getPackageInfo("com.whatsapp", 0)
                        "com.whatsapp"
                    } catch (_: Exception) {
                        "com.whatsapp.w4b"
                    }
                    val intent = context.packageManager.getLaunchIntentForPackage(waPkg)?.apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    if (intent != null) {
                        try { context.startActivity(intent) } catch (_: Exception) {}
                    }
                    a11y?.armWhatsappBackup()

                    result.addProperty("status", "success")
                    result.addProperty("message", "WhatsApp chat backup trigger kar diya hai Boss! Chat backup process chal raha hai.")
                }

                "whatsapp_open_settings" -> {
                    val section = args.get("section")?.asString ?: ""
                    val a11y = IshaAccessibilityService.instance
                    val waPkg = try {
                        context.packageManager.getPackageInfo("com.whatsapp", 0)
                        "com.whatsapp"
                    } catch (_: Exception) {
                        "com.whatsapp.w4b"
                    }
                    val intent = context.packageManager.getLaunchIntentForPackage(waPkg)?.apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    if (intent != null) {
                        try { context.startActivity(intent) } catch (_: Exception) {}
                    }
                    a11y?.armWhatsappOpenSettings(section.ifBlank { null })

                    result.addProperty("status", "success")
                    result.addProperty("message", "WhatsApp Settings ${if (section.isNotBlank()) "($section) " else ""}open kar di hai Boss!")
                }

                "instagram_like_post" -> {
                    val a11y = IshaAccessibilityService.instance
                    val success = a11y?.armInstagramLike() ?: false
                    result.addProperty("status", if (success) "success" else "error")
                    result.addProperty("message", "Instagram post / reel like kar diya hai Boss! ❤️")
                }

                "instagram_comment_post" -> {
                    val comment = args.get("comment")?.asString ?: args.get("text")?.asString ?: ""
                    if (comment.isBlank()) {
                        result.addProperty("status", "error")
                        result.addProperty("message", "Comment text missing hai Boss.")
                    } else {
                        val a11y = IshaAccessibilityService.instance
                        a11y?.armInstagramComment(comment)
                        result.addProperty("status", "success")
                        result.addProperty("message", "Instagram post par comment: \"$comment\" post kar diya hai Boss! 💬")
                    }
                }

                "instagram_send_dm" -> {
                    val username = args.get("username")?.asString ?: args.get("contact")?.asString ?: ""
                    val message = args.get("message")?.asString ?: args.get("text")?.asString ?: ""
                    val a11y = IshaAccessibilityService.instance

                    if (username.isBlank() || message.isBlank()) {
                        result.addProperty("status", "error")
                        result.addProperty("message", "Instagram DM ke liye username aur message dono chahiye Boss.")
                    } else {
                        val cleanUser = username.replace("@", "").trim()
                        val dmUri = Uri.parse("https://ig.me/m/$cleanUser")
                        val intent = Intent(Intent.ACTION_VIEW, dmUri).apply {
                            setPackage("com.instagram.android")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            val launchIntent = context.packageManager.getLaunchIntentForPackage("com.instagram.android")
                            if (launchIntent != null) {
                                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                try { context.startActivity(launchIntent) } catch (_: Exception) {}
                            }
                        }
                        a11y?.armInstagramDM(cleanUser, message)
                        result.addProperty("status", "success")
                        result.addProperty("message", "Instagram par @$cleanUser ko message: \"$message\" send kiya ja raha hai Boss! ✉️")
                    }
                }

                "instagram_post_story" -> {
                    val caption = args.get("caption")?.asString ?: args.get("text")?.asString
                    val mediaType = args.get("media_type")?.asString?.lowercase() ?: "text"
                    val a11y = IshaAccessibilityService.instance

                    if (mediaType.contains("screenshot") || mediaType.contains("photo")) {
                        val uri = getLatestScreenshotOrImageUri(context)
                        if (uri != null) {
                            val storyIntent = Intent("com.instagram.share.ADD_TO_STORY").apply {
                                setDataAndType(uri, "image/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            try {
                                context.startActivity(storyIntent)
                                result.addProperty("status", "success")
                                result.addProperty("message", "Instagram story par media share kar diya gaya hai Boss! 📸")
                                return result
                            } catch (_: Exception) {}
                        }
                    }

                    val cameraUri = Uri.parse("instagram://story-camera")
                    val intent = Intent(Intent.ACTION_VIEW, cameraUri).apply {
                        setPackage("com.instagram.android")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    try {
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.instagram.android")
                        if (launchIntent != null) {
                            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            try { context.startActivity(launchIntent) } catch (_: Exception) {}
                        }
                    }
                    a11y?.armInstagramStory(caption)
                    result.addProperty("status", "success")
                    result.addProperty("message", "Instagram Story create karne ki action trigger kar di hai Boss! 🌟")
                }

                "instagram_navigate" -> {
                    val destination = args.get("destination")?.asString?.lowercase()?.trim() ?: "reels"
                    val a11y = IshaAccessibilityService.instance
                    val targetUri = when {
                        destination.contains("reel") -> Uri.parse("instagram://reels")
                        destination.contains("dm") || destination.contains("msg") -> Uri.parse("instagram://direct-inbox")
                        destination.contains("explore") || destination.contains("search") -> Uri.parse("instagram://explore")
                        destination.contains("profile") -> Uri.parse("instagram://profile")
                        destination.contains("story") -> Uri.parse("instagram://story-camera")
                        else -> null
                    }

                    var launched = false
                    if (targetUri != null) {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, targetUri).apply {
                                setPackage("com.instagram.android")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                            launched = true
                        } catch (_: Exception) {}
                    }
                    if (!launched) {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.instagram.android")
                        if (launchIntent != null) {
                            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            try { context.startActivity(launchIntent) } catch (_: Exception) {}
                        }
                    }
                    a11y?.armInstagramNavigate(destination)
                    result.addProperty("status", "success")
                    result.addProperty("destination", destination)
                    result.addProperty("message", "Instagram $destination open kar diya gaya hai Boss! 📱")
                }

                "autonomous_ui_action" -> {
                    val startTime = System.currentTimeMillis()
                    val appName = args.get("app_name")?.asString ?: ""
                    val goal = args.get("goal_description")?.asString ?: "perform task"
                    val keywordsStr = args.get("target_keywords")?.asString ?: ""
                    val inputText = args.get("input_text")?.asString
                    val a11y = IshaAccessibilityService.instance

                    if (appName.isNotBlank()) {
                        val intent = resolveAppIntent(context, appName)
                        if (intent != null) {
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            try { context.startActivity(intent) } catch (_: Exception) {}
                        }
                    }

                    val keywords = if (keywordsStr.isNotBlank()) {
                        keywordsStr.split(Regex("[,;\\s]+")).filter { it.isNotBlank() }
                    } else {
                        goal.split(Regex("[^a-zA-Z0-9]+")).filter { it.length > 2 }
                    }

                    var executed = false
                    if (a11y != null) {
                        executed = a11y.armAutonomousUiAction(keywords, inputText)
                    }

                    var webKnowledge = ""
                    if (!executed) {
                        webKnowledge = performFastWebSearch("how to $goal in ${appName.ifBlank { "Android" }}")
                        val derivedButtons = webKnowledge.split(Regex("[^a-zA-Z]+")).filter { it.length in 4..12 }.take(5)
                        if (derivedButtons.isNotEmpty() && a11y != null) {
                            executed = a11y.armAutonomousUiAction(derivedButtons, inputText)
                        }
                    }

                    val elapsedMs = System.currentTimeMillis() - startTime
                    result.addProperty("status", "success")
                    result.addProperty("elapsed_ms", elapsedMs)
                    result.addProperty("executed", executed)
                    result.addProperty("goal", goal)
                    result.addProperty(
                        "message",
                        "Autonomous UI action \"$goal\" executed in ${elapsedMs}ms Boss! ${if (webKnowledge.isNotBlank()) "Knowledge: $webKnowledge" else ""}"
                    )
                }

                "telegram_action" -> {
                    val action = args.get("action")?.asString?.lowercase() ?: "open_chat"
                    val recipient = args.get("recipient")?.asString ?: args.get("contact")?.asString ?: ""
                    val message = args.get("message")?.asString ?: args.get("text")?.asString ?: ""
                    val a11y = IshaAccessibilityService.instance

                    val cleanUser = recipient.replace("@", "").trim()
                    var launched = false
                    if (cleanUser.isNotBlank()) {
                        val uri = Uri.parse("tg://resolve?domain=$cleanUser")
                        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                            setPackage("org.telegram.messenger")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        try {
                            context.startActivity(intent)
                            launched = true
                        } catch (_: Exception) {}
                    }
                    if (!launched) {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage("org.telegram.messenger")
                        if (launchIntent != null) {
                            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            try { context.startActivity(launchIntent) } catch (_: Exception) {}
                        }
                    }

                    if (action == "send_message" && message.isNotBlank()) {
                        a11y?.armTelegramMessage(cleanUser.ifBlank { recipient }, message)
                        result.addProperty("status", "success")
                        result.addProperty("message", "Telegram par $recipient ko message: \"$message\" bhej diya ja raha hai Boss! ✈️")
                    } else {
                        result.addProperty("status", "success")
                        result.addProperty("message", "Telegram chat with $recipient open kar di gayi hai Boss! ✈️")
                    }
                }

                "youtube_interact" -> {
                    val action = args.get("action")?.asString?.lowercase() ?: "open"
                    val query = args.get("query")?.asString ?: args.get("search")?.asString ?: ""
                    val comment = args.get("comment")?.asString ?: args.get("text")?.asString ?: ""
                    val a11y = IshaAccessibilityService.instance
                    val ytPkg = resolveAppPackage(context, "youtube") ?: "com.google.android.youtube"

                    when (action) {
                        "open" -> {
                            val launchIntent = context.packageManager.getLaunchIntentForPackage(ytPkg)?.apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            if (launchIntent != null) {
                                context.startActivity(launchIntent)
                                result.addProperty("status", "success")
                                result.addProperty("message", "YouTube open kar diya hai Boss! ▶️")
                            } else {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                                result.addProperty("status", "success")
                                result.addProperty("message", "YouTube browser me open kar diya hai Boss! ▶️")
                            }
                        }
                        "search" -> {
                            val searchUrl = "https://www.youtube.com/results?search_query=${URLEncoder.encode(query, "UTF-8")}"
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                                setPackage(ytPkg)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            try {
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(fallback)
                            }
                            if (query.isNotBlank()) a11y?.armAdaptiveSearch(query, listOf("search youtube", "search", "voice search"), "youtube")
                            result.addProperty("status", "success")
                            result.addProperty("message", "YouTube par \"$query\" search kar diya hai Boss! ▶️")
                        }
                        "shorts" -> {
                            val shortsUrl = "https://www.youtube.com/shorts"
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(shortsUrl)).apply {
                                setPackage(ytPkg)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            try {
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(shortsUrl)).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(fallback)
                            }
                            result.addProperty("status", "success")
                            result.addProperty("message", "YouTube Shorts open kar diya hai Boss! 📱")
                        }
                        "like" -> {
                            val success = a11y?.performYoutubeLike() ?: false
                            result.addProperty("status", if (success) "success" else "error")
                            result.addProperty("message", "YouTube video like kar diya hai Boss! 👍")
                        }
                        "subscribe" -> {
                            val success = a11y?.performYoutubeSubscribe() ?: false
                            result.addProperty("status", if (success) "success" else "error")
                            result.addProperty("message", "YouTube channel subscribe kar diya hai Boss! 🔔")
                        }
                        "skip_ad" -> {
                            val isYtForeground = a11y?.isYouTubeInForeground() ?: false
                            if (!isYtForeground) {
                                result.addProperty("status", "error")
                                result.addProperty("message", "Screen par YouTube open nahi hai Boss! YouTube par video chalne aur skippable ad aane par hi skip kiya ja sakta hai.")
                            } else {
                                val success = a11y?.performYoutubeSkipAd() ?: false
                                if (success) {
                                    result.addProperty("status", "success")
                                    result.addProperty("message", "YouTube advertisement skip kar diya gaya hai Boss! ⏭️")
                                } else {
                                    result.addProperty("status", "info")
                                    result.addProperty("message", "YouTube screen par active hai, lekin abhi koi skippable ad button nahi mila Boss. (Ad unskippable ho sakta hai ya countdown chal raha ho).")
                                }
                            }
                        }
                        "comment" -> {
                            if (comment.isNotBlank()) {
                                a11y?.armYoutubeComment(comment)
                                result.addProperty("status", "success")
                                result.addProperty("message", "YouTube par comment: \"$comment\" post kar diya hai Boss! 💬")
                            } else {
                                result.addProperty("status", "error")
                                result.addProperty("message", "YouTube comment text missing hai.")
                            }
                        }
                        "next_short" -> {
                            val success = a11y?.scrollVideo("down") ?: false
                            result.addProperty("status", if (success) "success" else "error")
                            result.addProperty("message", "Next YouTube Short par scroll kar diya hai Boss! 📱")
                        }
                        "prev_short" -> {
                            val success = a11y?.scrollVideo("up") ?: false
                            result.addProperty("status", if (success) "success" else "error")
                            result.addProperty("message", "Previous YouTube Short par scroll kar diya hai Boss! 📱")
                        }
                        else -> {
                            result.addProperty("status", "error")
                            result.addProperty("message", "Unknown YouTube action: $action")
                        }
                    }
                }

                "food_delivery_control" -> {
                    val app = args.get("app")?.asString?.lowercase() ?: "zomato"
                    val action = args.get("action")?.asString?.lowercase() ?: "search"
                    val query = args.get("query")?.asString ?: ""
                    val a11y = IshaAccessibilityService.instance

                    val isZomato = app.contains("zomato")
                    val installedPkg = if (isZomato) resolveAppPackage(context, "zomato") else resolveAppPackage(context, "swiggy")

                    when (action) {
                        "search" -> {
                            if (installedPkg != null) {
                                val searchUri = if (isZomato) {
                                    Uri.parse("zomato://search?q=${URLEncoder.encode(query, "UTF-8")}")
                                } else {
                                    Uri.parse("swiggy://explore?query=${URLEncoder.encode(query, "UTF-8")}")
                                }
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, searchUri).apply {
                                        setPackage(installedPkg)
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    val launchIntent = context.packageManager.getLaunchIntentForPackage(installedPkg)?.apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    if (launchIntent != null) context.startActivity(launchIntent)
                                }
                                if (query.isNotBlank()) a11y?.armFoodSearch(query, if (isZomato) "zomato" else "swiggy")
                                result.addProperty("status", "success")
                                result.addProperty("message", "${app.replaceFirstChar { it.uppercase() }} par \"$query\" search kar diya gaya hai Boss! 🍕")
                            } else {
                                val webUrl = if (isZomato) {
                                    "https://www.zomato.com/search?q=${URLEncoder.encode(query, "UTF-8")}"
                                } else {
                                    "https://www.swiggy.com/search?query=${URLEncoder.encode(query, "UTF-8")}"
                                }
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                try {
                                    context.startActivity(intent)
                                    result.addProperty("status", "success")
                                    result.addProperty("message", "${app.replaceFirstChar { it.uppercase() }} app phone mein nahi mila Boss, isliye maine web browser me \"$query\" search open kar diya hai! 🍕")
                                } catch (e: Exception) {
                                    result.addProperty("status", "error")
                                    result.addProperty("message", "${app.replaceFirstChar { it.uppercase() }} open nahi ho saka: ${e.message}")
                                }
                            }
                        }
                        "track_order" -> {
                            if (installedPkg != null) {
                                val launchIntent = context.packageManager.getLaunchIntentForPackage(installedPkg)?.apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                if (launchIntent != null) context.startActivity(launchIntent)
                                a11y?.armFoodTrackOrder()
                                result.addProperty("status", "success")
                                result.addProperty("message", "${app.replaceFirstChar { it.uppercase() }} active order tracking screen open kar di hai Boss! 🛵")
                            } else {
                                val webUrl = if (isZomato) "https://www.zomato.com/order-history" else "https://www.swiggy.com/my-account/orders"
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                                try { context.startActivity(intent) } catch (_: Exception) {}
                                result.addProperty("status", "success")
                                result.addProperty("message", "${app.replaceFirstChar { it.uppercase() }} order history web par open kar di hai Boss! 🛵")
                            }
                        }
                        "cart" -> {
                            if (installedPkg != null) {
                                val launchIntent = context.packageManager.getLaunchIntentForPackage(installedPkg)?.apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                if (launchIntent != null) context.startActivity(launchIntent)
                                a11y?.armFoodOpenCart()
                                result.addProperty("status", "success")
                                result.addProperty("message", "${app.replaceFirstChar { it.uppercase() }} cart open kar di hai Boss! 🛒")
                            } else {
                                val webUrl = if (isZomato) "https://www.zomato.com/cart" else "https://www.swiggy.com/checkout"
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                                try { context.startActivity(intent) } catch (_: Exception) {}
                                result.addProperty("status", "success")
                                result.addProperty("message", "${app.replaceFirstChar { it.uppercase() }} cart web par open kar di hai Boss! 🛒")
                            }
                        }
                        else -> {
                            if (installedPkg != null) {
                                val launchIntent = context.packageManager.getLaunchIntentForPackage(installedPkg)?.apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                if (launchIntent != null) context.startActivity(launchIntent)
                            } else {
                                val webUrl = if (isZomato) "https://www.zomato.com" else "https://www.swiggy.com"
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                                try { context.startActivity(intent) } catch (_: Exception) {}
                            }
                            result.addProperty("status", "success")
                            result.addProperty("message", "$app open kar diya hai Boss!")
                        }
                    }
                }

                "ride_booking_control" -> {
                    val app = args.get("app")?.asString?.lowercase() ?: "uber"
                    val action = args.get("action")?.asString?.lowercase() ?: "search_ride"
                    val destination = args.get("destination")?.asString ?: ""
                    val a11y = IshaAccessibilityService.instance

                    val targetApp = when {
                        app.contains("ola") -> "ola"
                        app.contains("rapido") -> "rapido"
                        else -> "uber"
                    }
                    val installedPkg = resolveAppPackage(context, targetApp)

                    when (action) {
                        "search_ride" -> {
                            if (installedPkg != null) {
                                if (targetApp == "uber" && destination.isNotBlank()) {
                                    try {
                                        val uberUri = Uri.parse("uber://?action=setPickup&dropoff[formatted_address]=${URLEncoder.encode(destination, "UTF-8")}")
                                        val intent = Intent(Intent.ACTION_VIEW, uberUri).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                        val launchIntent = context.packageManager.getLaunchIntentForPackage(installedPkg)?.apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        if (launchIntent != null) context.startActivity(launchIntent)
                                    }
                                } else {
                                    val launchIntent = context.packageManager.getLaunchIntentForPackage(installedPkg)?.apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    if (launchIntent != null) context.startActivity(launchIntent)
                                }
                                if (destination.isNotBlank()) a11y?.armRideSearch(destination, targetApp)
                                result.addProperty("status", "success")
                                result.addProperty("message", "${targetApp.uppercase()} me destination: \"$destination\" ke liye ride search ki ja rahi hai Boss! 🚖")
                            } else {
                                val webUrl = when (targetApp) {
                                    "ola" -> "https://book.olacabs.com/"
                                    "rapido" -> "https://www.rapido.bike/"
                                    else -> "https://m.uber.com/looking" + if (destination.isNotBlank()) "?drop=${URLEncoder.encode(destination, "UTF-8")}" else ""
                                }
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                try {
                                    context.startActivity(intent)
                                    result.addProperty("status", "success")
                                    result.addProperty("message", "${targetApp.uppercase()} app installed nahi tha Boss, isliye web browser me ride booking open kar di hai! 🚖")
                                } catch (e: Exception) {
                                    result.addProperty("status", "error")
                                    result.addProperty("message", "${targetApp.uppercase()} ride book nahi ho saki: ${e.message}")
                                }
                            }
                        }
                        "track_ride" -> {
                            if (installedPkg != null) {
                                val launchIntent = context.packageManager.getLaunchIntentForPackage(installedPkg)?.apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                if (launchIntent != null) context.startActivity(launchIntent)
                                a11y?.armRideTrack()
                                result.addProperty("status", "success")
                                result.addProperty("message", "${targetApp.uppercase()} active ride tracking open kar di hai Boss! 📍")
                            } else {
                                result.addProperty("status", "error")
                                result.addProperty("message", "Boss, ${targetApp.uppercase()} app phone me installed nahi hai.")
                            }
                        }
                        else -> {
                            if (installedPkg != null) {
                                val launchIntent = context.packageManager.getLaunchIntentForPackage(installedPkg)?.apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                if (launchIntent != null) context.startActivity(launchIntent)
                            }
                            result.addProperty("status", "success")
                            result.addProperty("message", "${targetApp.uppercase()} open kar diya hai Boss!")
                        }
                    }
                }

                "shopping_control" -> {
                    val app = args.get("app")?.asString?.lowercase() ?: "amazon"
                    val action = args.get("action")?.asString?.lowercase() ?: "search"
                    val query = args.get("query")?.asString ?: ""
                    val a11y = IshaAccessibilityService.instance

                    val isAmazon = app.contains("amazon")
                    val installedPkg = if (isAmazon) resolveAppPackage(context, "amazon") else resolveAppPackage(context, "flipkart")

                    when (action) {
                        "search" -> {
                            val targetUrl = if (isAmazon) {
                                "https://www.amazon.in/s?k=${URLEncoder.encode(query, "UTF-8")}"
                            } else {
                                "https://www.flipkart.com/search?q=${URLEncoder.encode(query, "UTF-8")}"
                            }
                            var opened = false
                            if (installedPkg != null) {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                                        setPackage(installedPkg)
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                    opened = true
                                } catch (_: Exception) {
                                    val launchIntent = context.packageManager.getLaunchIntentForPackage(installedPkg)?.apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    if (launchIntent != null) {
                                        try {
                                            context.startActivity(launchIntent)
                                            opened = true
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                            if (!opened) {
                                try {
                                    val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(fallback)
                                    opened = true
                                } catch (_: Exception) {}
                            }
                            if (query.isNotBlank()) a11y?.armShoppingSearch(query, if (isAmazon) "amazon" else "flipkart")
                            if (opened) {
                                result.addProperty("status", "success")
                                val appName = if (installedPkg != null) app.replaceFirstChar { it.uppercase() } else "${app.replaceFirstChar { it.uppercase() }} (Web)"
                                result.addProperty("message", "$appName par \"$query\" search open kar diya hai Boss! 🛍️ Screen par product options dekh sakte hain.")
                            } else {
                                result.addProperty("status", "error")
                                result.addProperty("message", "Boss, ${app.replaceFirstChar { it.uppercase() }} open nahi ho saka.")
                            }
                        }
                        "track_orders" -> {
                            val targetUrl = if (isAmazon) "https://www.amazon.in/gp/css/order-history" else "https://www.flipkart.com/account/orders"
                            var opened = false
                            if (installedPkg != null) {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                                        setPackage(installedPkg)
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                    opened = true
                                } catch (_: Exception) {}
                            }
                            if (!opened) {
                                try {
                                    val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(fallback)
                                    opened = true
                                } catch (_: Exception) {
                                    if (installedPkg != null) {
                                        val launchIntent = context.packageManager.getLaunchIntentForPackage(installedPkg)?.apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        if (launchIntent != null) {
                                            try { context.startActivity(launchIntent); opened = true } catch (_: Exception) {}
                                        }
                                    }
                                }
                            }
                            a11y?.armShoppingTrackOrders()
                            if (opened) {
                                result.addProperty("status", "success")
                                result.addProperty("message", "${app.replaceFirstChar { it.uppercase() }} Orders section open kar diya hai Boss! 📦")
                            } else {
                                result.addProperty("status", "error")
                                result.addProperty("message", "Boss, ${app.replaceFirstChar { it.uppercase() }} orders open nahi ho saka.")
                            }
                        }
                        "cart" -> {
                            val targetUrl = if (isAmazon) "https://www.amazon.in/gp/cart/view.html" else "https://www.flipkart.com/viewcart"
                            var opened = false
                            if (installedPkg != null) {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                                        setPackage(installedPkg)
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                    opened = true
                                } catch (_: Exception) {}
                            }
                            if (!opened) {
                                try {
                                    val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(fallback)
                                    opened = true
                                } catch (_: Exception) {
                                    if (installedPkg != null) {
                                        val launchIntent = context.packageManager.getLaunchIntentForPackage(installedPkg)?.apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        if (launchIntent != null) {
                                            try { context.startActivity(launchIntent); opened = true } catch (_: Exception) {}
                                        }
                                    }
                                }
                            }
                            a11y?.armShoppingOpenCart()
                            if (opened) {
                                result.addProperty("status", "success")
                                result.addProperty("message", "${app.replaceFirstChar { it.uppercase() }} Cart open kar di hai Boss! 🛒")
                            } else {
                                result.addProperty("status", "error")
                                result.addProperty("message", "Boss, ${app.replaceFirstChar { it.uppercase() }} Cart open nahi ho saki.")
                            }
                        }
                        else -> {
                            var opened = false
                            if (installedPkg != null) {
                                val launchIntent = context.packageManager.getLaunchIntentForPackage(installedPkg)?.apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                if (launchIntent != null) {
                                    try { context.startActivity(launchIntent); opened = true } catch (_: Exception) {}
                                }
                            }
                            if (!opened) {
                                val url = if (isAmazon) "https://www.amazon.in" else "https://www.flipkart.com"
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                try { context.startActivity(intent); opened = true } catch (_: Exception) {}
                            }
                            if (opened) {
                                result.addProperty("status", "success")
                                result.addProperty("message", "${app.replaceFirstChar { it.uppercase() }} open kar diya hai Boss!")
                            } else {
                                result.addProperty("status", "error")
                                result.addProperty("message", "Boss, ${app.replaceFirstChar { it.uppercase() }} open nahi ho saka.")
                            }
                        }
                    }
                }

                "upi_payment_control" -> {
                    val app = args.get("app")?.asString?.lowercase() ?: "gpay"
                    val action = args.get("action")?.asString?.lowercase() ?: "scan_qr"
                    val recipient = args.get("recipient")?.asString ?: ""
                    val amount = args.get("amount")?.asString ?: ""
                    val a11y = IshaAccessibilityService.instance

                    val targetAlias = when {
                        app.contains("phonepe") -> "phonepe"
                        app.contains("paytm") -> "paytm"
                        app.contains("bhim") -> "bhim"
                        else -> "gpay"
                    }
                    val installedPkg = resolveAppPackage(context, targetAlias)
                        ?: resolveAppPackage(context, "gpay")
                        ?: resolveAppPackage(context, "bhim")
                        ?: resolveAppPackage(context, "phonepe")
                        ?: resolveAppPackage(context, "paytm")

                    if (installedPkg == null) {
                        result.addProperty("status", "error")
                        result.addProperty("message", "Boss, aapke phone mein koi bhi UPI app (Google Pay, PhonePe, Paytm, BHIM) installed nahi mila.")
                        return result
                    }

                    var launched = false
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(installedPkg)?.apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    if (launchIntent != null) {
                        try {
                            context.startActivity(launchIntent)
                            launched = true
                        } catch (_: Exception) {}
                    }
                    if (!launched) {
                        result.addProperty("status", "error")
                        result.addProperty("message", "Boss, ${app.uppercase()} app launch nahi ho saka.")
                        return result
                    }

                    when (action) {
                        "scan_qr" -> {
                            a11y?.armUpiScanQr()
                            result.addProperty("status", "success")
                            result.addProperty("message", "${app.uppercase()} QR Scanner open kar diya gaya hai Boss! 📷 Scan karke pay karein.")
                        }
                        "send_money" -> {
                            if (recipient.isNotBlank()) {
                                a11y?.armUpiSendMoney(recipient, amount.ifBlank { null })
                                result.addProperty("status", "success")
                                result.addProperty("message", "${app.uppercase()} par $recipient ko ${if (amount.isNotBlank()) "₹$amount" else ""} payment shuru kar di hai Boss! 💳")
                            } else {
                                result.addProperty("status", "error")
                                result.addProperty("message", "Payment ke liye recipient ka naam ya number batayein Boss.")
                            }
                        }
                        "check_balance" -> {
                            a11y?.armUpiCheckBalance()
                            result.addProperty("status", "success")
                            result.addProperty("message", "${app.uppercase()} Bank Balance check screen open kar di hai Boss! 🏦")
                        }
                        "history" -> {
                            a11y?.armUpiHistory()
                            result.addProperty("status", "success")
                            result.addProperty("message", "${app.uppercase()} Payment transaction history open kar di hai Boss! 📜")
                        }
                        else -> {
                            result.addProperty("status", "success")
                            result.addProperty("message", "${app.uppercase()} payment app open kar diya hai Boss!")
                        }
                    }
                }

                "twitter_control" -> {
                    val action = args.get("action")?.asString?.lowercase() ?: "post_tweet"
                    val text = args.get("text")?.asString ?: args.get("query")?.asString ?: ""
                    val a11y = IshaAccessibilityService.instance
                    val installedPkg = resolveAppPackage(context, "twitter") ?: resolveAppPackage(context, "x")

                    when (action) {
                        "post_tweet" -> {
                            val tweetUri = Uri.parse("https://twitter.com/intent/tweet?text=${URLEncoder.encode(text, "UTF-8")}")
                            val intent = Intent(Intent.ACTION_VIEW, tweetUri).apply {
                                if (installedPkg != null) setPackage(installedPkg)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            try {
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                val fallback = Intent(Intent.ACTION_VIEW, tweetUri).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(fallback)
                            }
                            if (text.isNotBlank() && installedPkg != null) a11y?.armTwitterPost(text)
                            result.addProperty("status", "success")
                            result.addProperty("message", "Twitter / X par tweet: \"$text\" post screen open kar di hai Boss! 🐦")
                        }
                        "like" -> {
                            val success = a11y?.armTwitterLike() ?: false
                            result.addProperty("status", if (success) "success" else "error")
                            result.addProperty("message", "Tweet like kar diya gaya hai Boss! ❤️")
                        }
                        "search" -> {
                            val searchUri = Uri.parse("https://twitter.com/search?q=${URLEncoder.encode(text, "UTF-8")}")
                            val intent = Intent(Intent.ACTION_VIEW, searchUri).apply {
                                if (installedPkg != null) setPackage(installedPkg)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            try {
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                val fallback = Intent(Intent.ACTION_VIEW, searchUri).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(fallback)
                            }
                            result.addProperty("status", "success")
                            result.addProperty("message", "Twitter par \"$text\" search kar diya hai Boss! 🔍")
                        }
                        else -> {
                            if (installedPkg != null) {
                                val launchIntent = context.packageManager.getLaunchIntentForPackage(installedPkg)?.apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                if (launchIntent != null) context.startActivity(launchIntent)
                            } else {
                                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://twitter.com")).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(fallback)
                            }
                            result.addProperty("status", "success")
                            result.addProperty("message", "Twitter / X open kar diya hai Boss!")
                        }
                    }
                }

                "snapchat_control" -> {
                    val action = args.get("action")?.asString?.lowercase() ?: "camera"
                    val a11y = IshaAccessibilityService.instance
                    val snapPkg = resolveAppPackage(context, "snapchat") ?: "com.snapchat.android"

                    val launchIntent = context.packageManager.getLaunchIntentForPackage(snapPkg)?.apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    if (launchIntent != null) {
                        try { context.startActivity(launchIntent) } catch (_: Exception) {}
                    }
                    a11y?.armSnapchatAction(action)
                    result.addProperty("status", "success")
                    result.addProperty("message", "Snapchat $action open kar diya hai Boss! 👻")
                }

                "browser_control" -> {
                    val action = args.get("action")?.asString?.lowercase() ?: "search"
                    val queryOrUrl = args.get("query_or_url")?.asString ?: args.get("query")?.asString ?: ""
                    val a11y = IshaAccessibilityService.instance

                    if (action == "open_url" && queryOrUrl.isNotBlank()) {
                        val fixedUrl = if (!queryOrUrl.startsWith("http://") && !queryOrUrl.startsWith("https://")) {
                            "https://$queryOrUrl"
                        } else queryOrUrl
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fixedUrl)).apply {
                            setPackage("com.android.chrome")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            val alt = Intent(Intent.ACTION_VIEW, Uri.parse(fixedUrl)).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(alt)
                        }
                        result.addProperty("status", "success")
                        result.addProperty("message", "Website \"$fixedUrl\" browser me open kar di hai Boss! 🌐")
                    } else if (action == "search" && queryOrUrl.isNotBlank()) {
                        val searchUri = Uri.parse("https://www.google.com/search?q=${URLEncoder.encode(queryOrUrl, "UTF-8")}")
                        val intent = Intent(Intent.ACTION_VIEW, searchUri).apply {
                            setPackage("com.android.chrome")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            val alt = Intent(Intent.ACTION_VIEW, searchUri).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(alt)
                        }

                        // Smart Browser-to-Chat: Read screen after page loads, return to AURA chat
                        val browserText = readBrowserScreenAndReturn(context, delayMs = 2500L)

                        // Also fetch via our own search pipeline as backup
                        val searchData = performDirectWebSearch(queryOrUrl)
                        val searchResults = searchData.getAsJsonArray("results") ?: JsonArray()
                        val summaryList = mutableListOf<String>()
                        for (i in 0 until searchResults.size()) {
                            val sObj = searchResults.get(i).asJsonObject
                            val sum = sObj.get("summary")?.asString ?: ""
                            if (sum.isNotBlank()) summaryList.add(sum)
                        }
                        val ownSearchInfo = if (summaryList.isNotEmpty()) {
                            "\nSearch findings:\n• " + summaryList.take(3).joinToString("\n• ")
                        } else ""

                        val combinedInfo = buildString {
                            if (browserText.isNotBlank()) {
                                append("\n\n[Screen se pada gaya \"$queryOrUrl\" ke liye]:\n$browserText")
                            }
                            if (ownSearchInfo.isNotBlank()) append(ownSearchInfo)
                        }

                        result.addProperty("status", "success")
                        result.addProperty("message", "Chrome me \"$queryOrUrl\" search karke wapas aa gayi hoon Boss!$combinedInfo")
                    } else {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.android.chrome")
                        if (launchIntent != null) {
                            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            try { context.startActivity(launchIntent) } catch (_: Exception) {}
                        }
                        a11y?.armBrowserAction(action, queryOrUrl.ifBlank { null })
                        result.addProperty("status", "success")
                        result.addProperty("message", "Browser $action execute kar diya hai Boss! 🌐")
                    }
                }

                "spotify_control" -> {
                    val action = args.get("action")?.asString?.lowercase() ?: "search"
                    val query = args.get("query")?.asString ?: ""
                    val a11y = IshaAccessibilityService.instance
                    val installedPkg = resolveAppPackage(context, "spotify")

                    if (action == "search" && query.isNotBlank()) {
                        if (installedPkg != null) {
                            try {
                                val uri = Uri.parse("spotify:search:${URLEncoder.encode(query, "UTF-8")}")
                                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                val launchIntent = context.packageManager.getLaunchIntentForPackage(installedPkg)?.apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                if (launchIntent != null) context.startActivity(launchIntent)
                            }
                            result.addProperty("status", "success")
                            result.addProperty("message", "Spotify par \"$query\" search kar diya hai Boss! 🎵")
                        } else {
                            val webUrl = "https://open.spotify.com/search/${URLEncoder.encode(query, "UTF-8")}"
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                                result.addProperty("status", "success")
                                result.addProperty("message", "Spotify app phone me nahi mila, isliye maine web player par \"$query\" search open kar diya hai Boss! 🎵")
                            } catch (_: Exception) {
                                playMediaInApp(context, query, "youtube")
                                result.addProperty("status", "success")
                                result.addProperty("message", "Spotify installed nahi tha, isliye maine YouTube par \"$query\" chala diya hai Boss! 🎵")
                            }
                        }
                    } else if (action == "like") {
                        if (installedPkg != null) {
                            val success = a11y?.armSpotifyLike() ?: false
                            result.addProperty("status", if (success) "success" else "error")
                            result.addProperty("message", "Playing Spotify song ko Liked Songs me add kar diya hai Boss! 💚")
                        } else {
                            result.addProperty("status", "error")
                            result.addProperty("message", "Boss, Spotify app phone mein installed nahi hai.")
                        }
                    } else {
                        val mediaArgs = JsonObject().apply { addProperty("action", action) }
                        return executeTool(context, "media_control", mediaArgs)
                    }
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
                    val a11y = IshaAccessibilityService.instance
                    // Launch Samsung Gallery Recycle Bin directly so Accessibility can see the Trash screen!
                    var launched = false
                    try {
                        val trashIntent = Intent().apply {
                            component = android.content.ComponentName("com.sec.android.gallery3d", "com.sec.android.gallery3d.app.TrashBoxActivity")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(trashIntent)
                        launched = true
                    } catch (_: Exception) {}

                    if (!launched) {
                        val galleryPkg = resolveAppPackage(context, "Gallery") ?: "com.sec.android.gallery3d"
                        val launchIntent = context.packageManager.getLaunchIntentForPackage(galleryPkg)
                        if (launchIntent != null) {
                            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(launchIntent)
                        }
                    }

                    if (a11y != null) {
                        a11y.armEmptyRecycleBin()
                        result.addProperty("status", "success")
                        result.addProperty("message", "Samsung Gallery ka Recycle Bin open karke permanently clear kiya ja raha hai Boss! 🗑️✨")
                    } else {
                        result.addProperty("status", "success")
                        result.addProperty("message", "Gallery open kardi hai Boss, wahan se Empty par tap karke Recycle Bin saaf kar sakte hain!")
                    }
                }

                "open_app" -> {
                    val rawAppName = args.get("app_name")?.asString ?: ""
                    val targetDeviceArg = args.get("target_device")?.asString?.trim() ?: ""
                    val clean = rawAppName.lowercase().trim()

                    // Check if target device is explicitly passed or mentioned in app name
                    val targetDevice = if (targetDeviceArg.isNotBlank() && !targetDeviceArg.equals("this", true) && !targetDeviceArg.equals("current", true) && !targetDeviceArg.equals("local", true)) {
                        targetDeviceArg
                    } else {
                        val otherDevs = com.aura.assistant.sync.IshaDeviceRegistry.getOtherDevices()
                        otherDevs.firstOrNull { dev ->
                            clean.contains(dev.deviceAlias.lowercase()) ||
                            clean.contains(dev.manufacturer.lowercase()) ||
                            clean.contains(dev.model.lowercase())
                        }?.deviceAlias ?: ""
                    }

                    if (targetDevice.isNotBlank()) {
                        // Remote execution on target phone (e.g. OnePlus, Pixel, Tablet, Lava, Samsung)
                        val cleanAppName = rawAppName
                            .replace(targetDevice, "", ignoreCase = true)
                            .replace(Regex("(?i)\\b(in|on|me|mein|par|pe|phone|device)\\b"), "")
                            .trim().ifBlank { rawAppName }

                        val params = mutableMapOf<String, Any>(
                            "app_name" to cleanAppName,
                            "action" to "open_app"
                        )
                        val searchQ = args.get("query")?.asString ?: args.get("search_query")?.asString ?: ""
                        if (searchQ.isNotBlank()) params["query"] = searchQ

                        val executionResult = com.aura.assistant.sync.IshaCrossDeviceBridge.dispatchRemoteCommand(
                            context = context,
                            targetDeviceQuery = targetDevice,
                            action = "open_app",
                            params = params,
                            rawPrompt = rawAppName
                        )

                        if (executionResult.success) {
                            result.addProperty("status", "success")
                            result.addProperty("target_device", executionResult.targetDeviceName)
                            result.addProperty("message", "${executionResult.targetDeviceName} par '$cleanAppName' open kar diya hai Boss! 📱")
                        } else {
                            result.addProperty("status", "error")
                            result.addProperty("target_device", executionResult.targetDeviceName)
                            result.addProperty("message", executionResult.message)
                        }
                        return result
                    }

                    // Local execution on this device
                    val appName = rawAppName
                    var intent: Intent? = resolveAppIntent(context, appName)

                    val searchQ = args.get("query")?.asString ?: args.get("search_query")?.asString ?: ""
                    if (searchQ.isNotBlank() && (clean.contains("chrome") || clean.contains("browser") || clean.contains("google") || clean.contains("internet") || clean.contains("web"))) {
                        val searchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${URLEncoder.encode(searchQ, "UTF-8")}")).apply {
                            setPackage("com.android.chrome")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        try {
                            context.startActivity(searchIntent)
                        } catch (_: Exception) {
                            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${URLEncoder.encode(searchQ, "UTF-8")}")).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(fallback)
                        }
                        // Smart Browser-to-Chat: read screen after page renders, return to AURA
                        val browserExtract = readBrowserScreenAndReturn(context, delayMs = 3000L)

                        // Backup search findings in case accessibility tree was partial
                        val searchData = performDirectWebSearch(searchQ)
                        val searchResults = searchData.getAsJsonArray("results") ?: JsonArray()
                        val summaryList = mutableListOf<String>()
                        for (i in 0 until searchResults.size()) {
                            val sObj = searchResults.get(i).asJsonObject
                            val sum = sObj.get("summary")?.asString ?: ""
                            if (sum.isNotBlank()) summaryList.add(sum)
                        }
                        val backupInfo = if (summaryList.isNotEmpty()) {
                            "\nSearch findings:\n• " + summaryList.take(3).joinToString("\n• ")
                        } else ""

                        val combinedMsg = buildString {
                            if (browserExtract.isNotBlank()) {
                                append("\n\n[Chrome screen se:\n$browserExtract]")
                            }
                            if (backupInfo.isNotBlank()) append(backupInfo)
                        }

                        result.addProperty("status", "success")
                        result.addProperty("message", "Chrome me \"$searchQ\" search karke wapas aa gayi hoon Boss!$combinedMsg")
                        return result
                    }

                    if (intent != null) {
                        wakeAndUnlockIfNeeded(context)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
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
                    val targetDeviceArg = args.get("target_device")?.asString?.trim() ?: ""
                    if (targetDeviceArg.isNotBlank() && !targetDeviceArg.equals("this", true) && !targetDeviceArg.equals("current", true) && !targetDeviceArg.equals("local", true)) {
                        val executionResult = com.aura.assistant.sync.IshaCrossDeviceBridge.dispatchRemoteCommand(
                            context = context,
                            targetDeviceQuery = targetDeviceArg,
                            action = "toggle_flashlight",
                            params = mapOf("state" to if (enable) "on" else "off"),
                            rawPrompt = "toggle_flashlight"
                        )
                        if (executionResult.success) {
                            result.addProperty("status", "success")
                            result.addProperty("target_device", executionResult.targetDeviceName)
                            result.addProperty("message", "${executionResult.targetDeviceName} par flashlight ${if (enable) "ON" else "OFF"} kar di hai Boss! 🔦")
                        } else {
                            result.addProperty("status", "error")
                            result.addProperty("target_device", executionResult.targetDeviceName)
                            result.addProperty("message", executionResult.message)
                        }
                        return result
                    }
                    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val cameraId = cameraManager.cameraIdList.firstOrNull() ?: "0"
                    cameraManager.setTorchMode(cameraId, enable)
                    result.addProperty("status", "success")
                    result.addProperty("message", "Flashlight turned ${if (enable) "ON" else "OFF"}")
                }

                "set_volume" -> {
                    val level = extractPercent(args, "level_percent", "level", "volume", "value", "percent")
                    val targetDeviceArg = args.get("target_device")?.asString?.trim() ?: ""
                    if (targetDeviceArg.isNotBlank() && !targetDeviceArg.equals("this", true) && !targetDeviceArg.equals("current", true) && !targetDeviceArg.equals("local", true)) {
                        val executionResult = com.aura.assistant.sync.IshaCrossDeviceBridge.dispatchRemoteCommand(
                            context = context,
                            targetDeviceQuery = targetDeviceArg,
                            action = "set_volume",
                            params = mapOf("level" to level),
                            rawPrompt = "set_volume $level"
                        )
                        if (executionResult.success) {
                            result.addProperty("status", "success")
                            result.addProperty("target_device", executionResult.targetDeviceName)
                            result.addProperty("message", "${executionResult.targetDeviceName} par volume $level% set kar diya hai Boss! 🔊")
                        } else {
                            result.addProperty("status", "error")
                            result.addProperty("target_device", executionResult.targetDeviceName)
                            result.addProperty("message", executionResult.message)
                        }
                        return result
                    }
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
                    val label = args.get("label")?.asString ?: "ISHA Alarm"
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
                    wakeAndUnlockIfNeeded(context)
                    val targetText = args.get("target_text")?.asString ?: args.get("target")?.asString ?: ""
                    val x = args.get("x")?.asFloat
                    val y = args.get("y")?.asFloat
                    val a11y = IshaAccessibilityService.instance
                    if (a11y != null) {
                        if (x != null && y != null) {
                            val tapped = a11y.performClickCoordinate(x, y)
                            result.addProperty("status", if (tapped) "success" else "failed")
                            result.addProperty("message", if (tapped) "Screen coordinate ($x, $y) par tap kar diya hai Boss!" else "Tap gesture execute nahi ho paya.")
                        } else if (targetText.isNotBlank()) {
                            var tapped = a11y.performClickByText(targetText)
                            if (!tapped) {
                                val words = targetText.split(" ").filter { it.length > 2 }
                                for (w in words) {
                                    if (a11y.performClickByText(w)) {
                                        tapped = true
                                        break
                                    }
                                }
                            }
                            if (!tapped && (targetText.contains("chrome", ignoreCase = true) || targetText.contains("youtube", ignoreCase = true) || targetText.contains("setting", ignoreCase = true) || targetText.contains("whatsapp", ignoreCase = true) || targetText.contains("gallery", ignoreCase = true))) {
                                return executeTool(context, "open_app", JsonObject().apply { addProperty("app_name", targetText) })
                            }
                            result.addProperty("status", if (tapped) "success" else "not_found")
                            result.addProperty("message", if (tapped) "Screen par '$targetText' tap kar diya hai Boss!" else "Screen par '$targetText' nahi mila.")
                        } else {
                            result.addProperty("status", "error")
                            result.addProperty("message", "Target text ya coordinates provide nahi kiye gaye.")
                        }
                    } else {
                        result.addProperty("status", "error")
                        result.addProperty("message", "Accessibility Service is not enabled")
                    }
                }

                "screen_scroll" -> {
                    val direction = args.get("direction")?.asString ?: "down"
                    val a11y = IshaAccessibilityService.instance
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
                    val files = IshaFileManager.searchFiles(context, query, 20)
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
                    val vaultPath = IshaFileManager.deleteToVault(context, path)
                    result.addProperty("status", if (vaultPath != null) "success" else "failed")
                    result.addProperty("message", if (vaultPath != null) "File backed up to vault and deleted: $vaultPath" else "Could not delete file")
                }

                "accept_call" -> {
                    val answered = com.aura.assistant.IshaCallAnnouncerManager.answerCall(context)
                    result.addProperty("status", if (answered) "success" else "attempted")
                    result.addProperty("message", if (answered) "Call accepted successfully" else "Attempted to accept call via system telecom and gestures")
                }

                "decline_call" -> {
                    val declined = com.aura.assistant.IshaCallAnnouncerManager.declineCall(context)
                    result.addProperty("status", if (declined) "success" else "attempted")
                    result.addProperty("message", if (declined) "Call declined successfully" else "Attempted to decline call via system telecom and gestures")
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

                "teach_command_rule" -> {
                    val trigger = args.get("trigger_pattern")?.asString ?: ""
                    val action = args.get("action_instruction")?.asString ?: ""
                    val primaryTool = args.get("primary_tool")?.asString
                    val params = args.get("tool_parameters")?.asString
                    if (trigger.isNotBlank() && action.isNotBlank()) {
                        AuraMemoryManager.teachRule(context, trigger, action, primaryTool, params)
                        val pinMatch = Regex("""\b(\d{4,8})\b""").find("$trigger $action")
                        if (pinMatch != null) {
                            val activeDev = IshaMemoryManager.getActiveTargetDevice() ?: "Lava"
                            IshaMemoryManager.saveDevicePin(activeDev, pinMatch.groupValues[1])
                        }
                        com.aura.assistant.memory.ExperienceMemory.recordLesson(
                            context,
                            primaryTool ?: "custom_rule",
                            true,
                            "User taught rule: When trigger '$trigger' -> $action"
                        )
                        result.addProperty("status", "success")
                        result.addProperty("trigger", trigger)
                        result.addProperty("action", action)
                        result.addProperty("message", "Boss, maine seekh liya aur permanent memory me save kar liya hai! Jab bhi aap '$trigger' bolenge, main strictly '$action' karungi.")
                    } else {
                        result.addProperty("status", "error")
                        result.addProperty("message", "trigger_pattern aur action_instruction dono zaroori hain Boss.")
                    }
                }

                "recall_memory" -> {
                    val key = args.get("key")?.asString ?: ""
                    result.addProperty("status", "success")
                    if (key.isNotBlank()) {
                        val v = AuraMemoryManager.recallMemory(context, key)
                        val matchingRule = AuraMemoryManager.findMatchingRule(context, key)
                        result.addProperty("value", v ?: "No fact found for $key")
                        if (matchingRule != null) {
                            result.addProperty("learned_rule", "${matchingRule.triggerPattern} -> ${matchingRule.actionInstruction}")
                        }
                    } else {
                        val all = AuraMemoryManager.getAllMemories(context)
                        val allRules = AuraMemoryManager.getAllRules(context)
                        result.addProperty("memories", gson.toJson(all))
                        result.addProperty("learned_rules", gson.toJson(allRules))
                    }
                }

                "play_youtube" -> {
                    val rawTarget = args.get("target_device")?.asString ?: ""
                    val query = args.get("query")?.asString
                        ?: args.get("song")?.asString
                        ?: args.get("video")?.asString
                        ?: ""
                    val url = args.get("url")?.asString ?: ""
                    val effectiveUrl = if (url.isNotBlank()) url else if (query.startsWith("http://") || query.startsWith("https://")) query else ""

                    // Infer target device from query or prompt if not explicitly passed
                    val qLower = query.lowercase()
                    val otherDevs = com.aura.assistant.sync.IshaDeviceRegistry.getOtherDevices()
                    val matchedDev = otherDevs.firstOrNull { dev ->
                        val devAlias = dev.deviceAlias.lowercase()
                        val devModel = dev.model.lowercase()
                        val devMfr = dev.manufacturer.lowercase()
                        qLower.contains(devAlias) || qLower.contains(devModel) || (devMfr.length >= 3 && qLower.contains(devMfr))
                    }
                    val targetDevice = if (rawTarget.isNotBlank()) rawTarget else when {
                        matchedDev != null -> matchedDev.deviceAlias
                        qLower.contains("dusre phone") || qLower.contains("dusra phone") || qLower.contains("other phone") -> "other"
                        else -> ""
                    }

                    if (targetDevice.isNotBlank() && !targetDevice.equals("this", ignoreCase = true) && !targetDevice.equals("current", ignoreCase = true) && !targetDevice.equals("local", ignoreCase = true)) {
                        var cleanQuery = query
                        matchedDev?.let { dev ->
                            cleanQuery = cleanQuery
                                .replace(dev.deviceAlias, "", ignoreCase = true)
                                .replace(dev.model, "", ignoreCase = true)
                                .replace(dev.manufacturer, "", ignoreCase = true)
                        }
                        cleanQuery = cleanQuery
                            .replace(targetDevice, "", ignoreCase = true)
                            .replace("dusre phone mein", "", ignoreCase = true)
                            .replace("dusre phone me", "", ignoreCase = true)
                            .replace("dusre phone par", "", ignoreCase = true)
                            .replace("dusre phone pr", "", ignoreCase = true)
                            .replace("dusre phone", "", ignoreCase = true)
                            .replace("dusra phone", "", ignoreCase = true)
                            .replace("other phone", "", ignoreCase = true)
                            .replace("phone mein", "", ignoreCase = true)
                            .replace("phone me", "", ignoreCase = true)
                            .replace("phone par", "", ignoreCase = true)
                            .replace("phone pr", "", ignoreCase = true)
                            .trim()

                        val params = mutableMapOf<String, Any>(
                            "query" to (if (cleanQuery.isNotBlank()) cleanQuery else query),
                            "action" to "play_youtube"
                        )
                        if (effectiveUrl.isNotBlank()) params["url"] = effectiveUrl

                        val executionResult = com.aura.assistant.sync.IshaCrossDeviceBridge.dispatchRemoteCommand(
                            context = context,
                            targetDeviceQuery = targetDevice,
                            action = "play_youtube",
                            params = params,
                            rawPrompt = query
                        )

                        if (executionResult.success) {
                            result.addProperty("status", "success")
                            result.addProperty("target_device", executionResult.targetDeviceName)
                            result.addProperty("message", "${executionResult.targetDeviceName} par YouTube start kar diya hai Boss! 🎵")
                        } else {
                            result.addProperty("status", "error")
                            result.addProperty("target_device", executionResult.targetDeviceName)
                            result.addProperty("message", executionResult.message)
                        }
                    } else {
                        val success = playMediaInApp(context, if (effectiveUrl.isNotBlank()) effectiveUrl else query, "youtube")
                        result.addProperty("status", if (success) "success" else "error")
                        result.addProperty("query", query)
                        result.addProperty("message", "YouTube par \"$query\" play kiya ja raha hai Boss! 🎵")
                    }
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
                    val targetDeviceArg = args.get("target_device")?.asString?.trim() ?: ""
                    if (targetDeviceArg.isNotBlank() && !targetDeviceArg.equals("this", true) && !targetDeviceArg.equals("current", true) && !targetDeviceArg.equals("local", true)) {
                        val executionResult = com.aura.assistant.sync.IshaCrossDeviceBridge.dispatchRemoteCommand(
                            context = context,
                            targetDeviceQuery = targetDeviceArg,
                            action = "toggle_hotspot",
                            params = mapOf("enable" to enable),
                            rawPrompt = "toggle_hotspot ${if (enable) "on" else "off"}"
                        )
                        if (executionResult.success) {
                            result.addProperty("status", "success")
                            result.addProperty("target_device", executionResult.targetDeviceName)
                            result.addProperty("message", "${executionResult.targetDeviceName} par hotspot ${if (enable) "ON" else "OFF"} toggle trigger kar diya hai Boss! 📶")
                        } else {
                            result.addProperty("status", "error")
                            result.addProperty("target_device", executionResult.targetDeviceName)
                            result.addProperty("message", executionResult.message)
                        }
                        return result
                    }
                    val a11y = IshaAccessibilityService.instance
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
                            result.addProperty("message", "Hotspot settings open kar di hai Boss, switch tap karke turn on kar lijiye.")
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
                    val a11y = IshaAccessibilityService.instance
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
                    val a11y = IshaAccessibilityService.instance
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
                    val a11y = IshaAccessibilityService.instance
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
                    val a11y = IshaAccessibilityService.instance
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
                    val a11y = IshaAccessibilityService.instance
                    val success = a11y?.performGlobalActionByKey(key) ?: false
                    result.addProperty("status", if (success) "success" else "fallback")
                    result.addProperty("key", key)
                    result.addProperty("message", "System action '$key' execute kar diya gaya hai Boss! 📱")
                }

                "manage_app_storage" -> {
                    val appName = args.get("app_name")?.asString ?: ""
                    val action = args.get("action")?.asString ?: "clear_cache"
                    val pkg = resolveAppPackage(context, appName) ?: appName
                    val a11y = IshaAccessibilityService.instance

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
                    val a11y = IshaAccessibilityService.instance
                    a11y?.armPlayStoreInstall(query, criteria)
                    result.addProperty("status", "success")
                    result.addProperty("query", query)
                    result.addProperty("criteria", criteria)
                    result.addProperty("message", "Play Store par \"$query\" ($criteria) install kiya ja raha hai Boss! 📥")
                }

                "toggle_dark_mode" -> {
                    val enable = args.get("enable")?.asBoolean ?: true
                    val a11y = IshaAccessibilityService.instance
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
                    val label = args.get("label")?.asString ?: "ISHA Timer"

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

                // 36. Web Search / Live Internet Search
                "web_search", "search_internet" -> {
                    val query = args.get("query")?.asString ?: ""
                    val openBrowser = args.get("open_browser")?.asBoolean ?: false

                    val searchData = performDirectWebSearch(query)
                    val searchResults = searchData.getAsJsonArray("results") ?: JsonArray()
                    result.addProperty("status", "success")
                    result.addProperty("query", query)
                    result.add("search_results", searchResults)

                    val summaryList = mutableListOf<String>()
                    for (i in 0 until searchResults.size()) {
                        val sObj = searchResults.get(i).asJsonObject
                        val sum = sObj.get("summary")?.asString ?: ""
                        if (sum.isNotBlank()) summaryList.add(sum)
                    }

                    if (openBrowser) {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${URLEncoder.encode(query, "UTF-8")}")).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        try { context.startActivity(browserIntent) } catch (_: Exception) {}
                    }

                    val searchMsg = if (summaryList.isNotEmpty()) {
                        "Search results for '$query':\n• " + summaryList.take(4).joinToString("\n• ") +
                        (if (openBrowser) "\n\n(Google search results Chrome browser me bhi open kar diye hain Boss)" else "")
                    } else {
                        "Internet search complete for '$query', lekin specific results nahi mile."
                    }
                    result.addProperty("message", searchMsg)
                }

                // 36b. Live News Feed
                "get_latest_news" -> {
                    val topic = args.get("topic")?.asString
                    val lang = args.get("language")?.asString
                    val newsData = fetchLiveGoogleNews(topic, lang)
                    result.addProperty("status", newsData.get("status")?.asString ?: "success")
                    result.add("headlines", newsData.get("headlines") ?: JsonArray())
                    result.addProperty("topic", newsData.get("topic")?.asString ?: "Top News")
                    result.addProperty("message", newsData.get("message")?.asString ?: "Latest news fetched.")
                }

                // 36c. Media Storage Stats (Photos, Videos, Audio Counts)
                "get_media_storage_stats" -> {
                    try {
                        val cr = context.contentResolver
                        var photoCount = 0
                        var latestPhotoDate = ""
                        val imgProj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED)
                        cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imgProj, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                            photoCount = c.count
                            if (c.moveToFirst()) {
                                val d = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
                                latestPhotoDate = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(d * 1000L))
                            }
                        }

                        var videoCount = 0
                        var latestVideoDate = ""
                        val vidProj = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED)
                        cr.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, vidProj, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
                            videoCount = c.count
                            if (c.moveToFirst()) {
                                val d = c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED))
                                latestVideoDate = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(d * 1000L))
                            }
                        }

                        var audioCount = 0
                        cr.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media._ID), null, null, null)?.use { c ->
                            audioCount = c.count
                        }

                        result.addProperty("status", "success")
                        result.addProperty("photos_count", photoCount)
                        result.addProperty("videos_count", videoCount)
                        result.addProperty("audio_count", audioCount)
                        result.addProperty("total_media_count", photoCount + videoCount + audioCount)
                        if (latestPhotoDate.isNotBlank()) result.addProperty("latest_photo_date", latestPhotoDate)
                        if (latestVideoDate.isNotBlank()) result.addProperty("latest_video_date", latestVideoDate)
                        result.addProperty("message", "Aapke device me $photoCount photos aur $videoCount videos hain Boss.")
                    } catch (e: Exception) {
                        result.addProperty("status", "error")
                        result.addProperty("error", "Media access error: ${e.message}")
                    }
                }

                // 37. Open Camera / Take Photo
                "open_camera", "take_photo" -> {
                    val mode = args.get("mode")?.asString?.lowercase() ?: "photo"
                    val action = if (mode == "video") MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
                    val intent = Intent(action).apply {
                        if (mode == "selfie") {
                            putExtra("android.intent.extras.CAMERA_FACING", 1)
                            putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                            putExtra("com.google.assistant.extra.USE_FRONT_CAMERA", true)
                        }
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    try {
                        context.startActivity(intent)
                        result.addProperty("status", "success")
                        result.addProperty("message", if (mode == "selfie") "Selfie camera open kar diya hai Boss! 🤳" else "Camera open kar diya hai Boss! 📷")
                    } catch (e: Exception) {
                        try {
                            val capIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(capIntent)
                            result.addProperty("status", "success")
                            result.addProperty("message", "Camera open kar diya hai Boss! 📷")
                        } catch (e2: Exception) {
                            val camIntent = resolveAppIntent(context, "camera")
                            if (camIntent != null) {
                                context.startActivity(camIntent)
                                result.addProperty("status", "success")
                                result.addProperty("message", "Camera app open kar diya hai Boss! 📷")
                            } else {
                                result.addProperty("status", "error")
                                result.addProperty("message", "Camera open nahi ho saka: ${e.message}")
                            }
                        }
                    }
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
                        setting.contains("hotspot") || setting.contains("tether") -> "android.settings.TETHER_SETTINGS"
                        setting.contains("data") || setting.contains("usage") -> Settings.ACTION_DATA_USAGE_SETTINGS
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
                    val memoryEntry = IshaContactMemoryManager.getContactByName(context, name)
                    if (memoryEntry != null) {
                        result.addProperty("status", "success")
                        result.addProperty("name", memoryEntry.name)
                        result.addProperty("phone_number", memoryEntry.primaryNumber)
                        if (memoryEntry.previousNumbers.isNotEmpty()) {
                            result.addProperty("previous_numbers", memoryEntry.previousNumbers.joinToString(", "))
                        }
                        val historyText = if (memoryEntry.previousNumbers.isNotEmpty()) {
                            ", aur purana number ${memoryEntry.previousNumbers.joinToString(", ")} tha"
                        } else ""
                        result.addProperty("message", "${memoryEntry.name} ka naya number ${memoryEntry.primaryNumber} hai Boss$historyText! 📞")
                    } else {
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
                }

                // 44b. Remember or Update Contact in Memory
                "remember_contact" -> {
                    val name = args.get("name")?.asString ?: ""
                    val phoneNumber = args.get("phone_number")?.asString ?: ""
                    val note = args.get("note")?.asString ?: ""
                    val updateResult = IshaContactMemoryManager.rememberOrUpdateContact(context, name, phoneNumber, note)
                    result.addProperty("status", "success")
                    result.addProperty("name", updateResult.name)
                    result.addProperty("phone_number", updateResult.currentNumber)
                    if (updateResult.oldNumber != null) {
                        result.addProperty("old_number", updateResult.oldNumber)
                    }
                    result.addProperty("is_updated", updateResult.isUpdated)
                    result.addProperty("message", updateResult.message)
                }

                // 44c. Cross-Device Remote Execution
                "dispatch_remote_command" -> {
                    val targetDevice = args.get("target_device")?.asString ?: "Phone B"
                    val action = args.get("action")?.asString ?: "generic_command"
                    val prompt = args.get("prompt")?.asString ?: ""
                    val contact = args.get("contact")?.asString ?: ""
                    val message = args.get("message")?.asString ?: ""
                    val url = args.get("url")?.asString ?: ""
                    val query = args.get("query")?.asString ?: ""

                    // Update active target device in session memory
                    IshaMemoryManager.setActiveTargetDevice(targetDevice)

                    val params = mutableMapOf<String, Any>()
                    if (contact.isNotBlank()) params["contact"] = contact
                    if (message.isNotBlank()) params["message"] = message
                    if (url.isNotBlank()) params["url"] = url
                    if (query.isNotBlank()) params["query"] = query

                    // Inject PIN or Pattern for remote unlock if known
                    val pinFromArgs = args.get("pin")?.asString
                    val patternFromArgs = args.get("pattern")?.asString
                    if (!pinFromArgs.isNullOrBlank()) {
                        IshaMemoryManager.saveDevicePin(context, targetDevice, pinFromArgs)
                        params["pin"] = pinFromArgs
                    } else {
                        val savedPin = IshaMemoryManager.getDevicePin(context, targetDevice)
                        if (!savedPin.isNullOrBlank()) params["pin"] = savedPin
                    }
                    if (!patternFromArgs.isNullOrBlank()) {
                        IshaMemoryManager.saveDevicePattern(context, targetDevice, patternFromArgs)
                        params["pattern"] = patternFromArgs
                    } else {
                        val savedPattern = IshaMemoryManager.getDevicePattern(context, targetDevice)
                        if (!savedPattern.isNullOrBlank()) params["pattern"] = savedPattern
                    }

                    // Extract YouTube URL or video query from prompt if present
                    val promptUrlRegex = Regex("""https?://[^\s]+""")
                    val foundUrl = url.ifBlank { promptUrlRegex.find(prompt)?.value ?: "" }
                    if (foundUrl.isNotBlank()) params["url"] = foundUrl

                    val actLower = action.lowercase()
                    val pmtLower = prompt.lowercase()

                    val isPlayStore = pmtLower.contains("play store") || pmtLower.contains("playstore") || pmtLower.contains("play_store") || actLower.contains("store")
                    val isAppInstall = actLower.contains("install") || actLower.contains("download") || pmtLower.contains("install") || pmtLower.contains("download")

                    val normAction = when {
                        actLower.contains("flashlight") || actLower.contains("torch") -> "toggle_flashlight"
                        actLower.contains("volume") -> "set_volume"
                        actLower.contains("siren") || pmtLower.contains("siren") -> if (actLower.contains("stop") || actLower.contains("band") || pmtLower.contains("stop") || pmtLower.contains("band")) "stop_siren" else "play_siren"
                        actLower.contains("hotspot") || pmtLower.contains("hotspot") -> "toggle_hotspot"
                        actLower.contains("lock") && !actLower.contains("unlock") && !pmtLower.contains("unlock") -> "lock_device"
                        actLower.contains("unlock") || pmtLower.contains("unlock") || actLower.contains("wake") || pmtLower.contains("wake") -> "unlock_device"
                        isAppInstall -> "install_store_app"
                        !isPlayStore && (actLower.contains("youtube") || actLower.contains("song") || (actLower.contains("play") && !actLower.contains("store")) || pmtLower.contains("youtube") || pmtLower.contains("song") || (pmtLower.contains("play") && !pmtLower.contains("store") && !pmtLower.contains("install"))) -> "play_youtube"
                        actLower.contains("launch_app") || actLower.contains("open") || isPlayStore -> "open_app"
                        actLower.contains("battery") -> "battery"
                        else -> action
                    }

                    if (normAction == "install_store_app" || (normAction == "open_app" && isPlayStore)) {
                        val app = if (query.isNotBlank()) query else (args.get("app_name")?.asString ?: prompt)
                        val cleanAppName = app.replace("play store", "", ignoreCase = true)
                            .replace("playstore", "", ignoreCase = true)
                            .replace("install", "", ignoreCase = true)
                            .replace("karo", "", ignoreCase = true)
                            .replace("krdo", "", ignoreCase = true)
                            .replace("se", "", ignoreCase = true)
                            .trim()
                        params["app_name"] = cleanAppName
                        params["query"] = cleanAppName
                        params["action"] = "install_store_app"
                    } else if (normAction == "open_app") {
                        val rawApp = (args.get("app_name")?.asString ?: (if (query.isNotBlank()) query else prompt)).trim()
                        val cleanApp = rawApp
                            .replace(Regex("(?i)^(open|launch|kholo|chalao|start|run|app|application)\\s+"), "")
                            .replace(Regex("(?i)\\s+(app|application|kholo|chalao|karo|krdo)$"), "")
                            .replace(Regex("(?i)^(open|launch)\\s+"), "")
                            .replace(Regex("(?i)\\s+(app)$"), "")
                            .trim()
                        val finalApp = if (cleanApp.isNotBlank()) cleanApp else rawApp
                        params["app_name"] = finalApp

                        val appLower = finalApp.lowercase()
                        val resolvedPkg = when {
                            appLower.contains("chrome") || appLower.contains("browser") -> "com.android.chrome"
                            appLower.contains("whatsapp") -> "com.whatsapp"
                            appLower.contains("youtube") -> "com.google.android.youtube"
                            appLower.contains("instagram") -> "com.instagram.android"
                            appLower.contains("facebook") -> "com.facebook.katana"
                            appLower.contains("camera") -> "com.android.camera"
                            appLower.contains("gallery") || appLower.contains("photos") -> "com.google.android.apps.photos"
                            appLower.contains("maps") -> "com.google.android.apps.maps"
                            appLower.contains("settings") -> "com.android.settings"
                            appLower.contains("play store") || appLower.contains("playstore") -> "com.android.vending"
                            appLower.contains("calculator") -> "com.google.android.calculator"
                            appLower.contains("clock") || appLower.contains("alarm") -> "com.google.android.deskclock"
                            appLower.contains("gmail") || appLower.contains("email") -> "com.google.android.gm"
                            appLower.contains("spotify") -> "com.spotify.music"
                            appLower.contains("telegram") -> "org.telegram.messenger"
                            else -> resolveAppPackage(context, finalApp)
                        }
                        if (resolvedPkg != null) {
                            params["package_name"] = resolvedPkg
                        }
                    } else if (normAction == "toggle_flashlight") {
                        val state = args.get("state")?.asString ?: if (prompt.contains("off") || prompt.contains("band") || action.contains("off")) "off" else "on"
                        params["state"] = state
                    } else if (normAction == "set_volume") {
                        val level = args.get("level")?.asInt ?: 50
                        params["level"] = level
                    } else if (normAction == "play_youtube") {
                        if (query.isNotBlank()) params["query"] = query
                        else if (params["url"] == null && prompt.isNotBlank()) params["query"] = prompt
                    }

                    val executionResult = com.aura.assistant.sync.IshaCrossDeviceBridge.dispatchRemoteCommand(
                        context = context,
                        targetDeviceQuery = targetDevice,
                        action = normAction,
                        params = params,
                        rawPrompt = prompt
                    )

                    if (executionResult.success) {
                        result.addProperty("status", "success")
                        result.addProperty("target_device", executionResult.targetDeviceName)
                        result.addProperty("message", executionResult.message)
                    } else {
                        result.addProperty("status", "error")
                        result.addProperty("target_device", executionResult.targetDeviceName)
                        result.addProperty("message", executionResult.message)
                    }
                }

                // 44d. Lock Device
                "lock_device" -> {
                    val targetDevice = args.get("target_device")?.asString ?: ""
                    if (targetDevice.isNotBlank() && !targetDevice.equals("this", ignoreCase = true) && !targetDevice.equals("current", ignoreCase = true) && !targetDevice.equals("local", ignoreCase = true)) {
                        // Remote lock
                        val executionResult = com.aura.assistant.sync.IshaCrossDeviceBridge.dispatchRemoteCommand(
                            context = context,
                            targetDeviceQuery = targetDevice,
                            action = "lock_device",
                            params = emptyMap(),
                            rawPrompt = "lock device"
                        )
                        if (executionResult.success) {
                            result.addProperty("status", "success")
                            result.addProperty("message", "${executionResult.targetDeviceName} phone lock kar diya hai Boss! 🔒")
                        } else {
                            result.addProperty("status", "error")
                            result.addProperty("message", executionResult.message)
                        }
                    } else {
                        // Local lock
                        val a11y = IshaAccessibilityService.instance
                        if (a11y != null) {
                            val locked = a11y.performLockScreen()
                            if (locked) {
                                result.addProperty("status", "success")
                                result.addProperty("message", "Phone lock kar diya hai Boss! 🔒")
                            } else {
                                result.addProperty("status", "error")
                                result.addProperty("message", "Device lock supported nahi hai.")
                            }
                        } else {
                            result.addProperty("status", "error")
                            result.addProperty("message", "Screen lock karne ke liye Accessibility Service required hai.")
                        }
                    }
                }

                // 44e. Unlock / Wake Device
                "unlock_device" -> {
                    val rawTarget = args.get("target_device")?.asString ?: ""
                    val activeDev = IshaMemoryManager.getActiveTargetDevice()
                    val targetDevice = if (rawTarget.isNotBlank()) rawTarget else (activeDev ?: "")
                    val pinArg = args.get("pin")?.asString?.trim()
                    val patternArg = args.get("pattern")?.asString?.trim()

                    if (targetDevice.isNotBlank() && !targetDevice.equals("this", ignoreCase = true) && !targetDevice.equals("current", ignoreCase = true) && !targetDevice.equals("local", ignoreCase = true)) {
                        // Remote unlock
                        val unlockParams = mutableMapOf<String, Any>()
                        val pin = if (!pinArg.isNullOrBlank()) pinArg else IshaMemoryManager.getDevicePin(context, targetDevice)
                        val pattern = if (!patternArg.isNullOrBlank()) patternArg else IshaMemoryManager.getDevicePattern(context, targetDevice)
                        if (!pin.isNullOrBlank()) {
                            unlockParams["pin"] = pin
                            IshaMemoryManager.saveDevicePin(context, targetDevice, pin)
                        }
                        if (!pattern.isNullOrBlank()) {
                            unlockParams["pattern"] = pattern
                            IshaMemoryManager.saveDevicePattern(context, targetDevice, pattern)
                        }

                        val executionResult = com.aura.assistant.sync.IshaCrossDeviceBridge.dispatchRemoteCommand(
                            context = context,
                            targetDeviceQuery = targetDevice,
                            action = "unlock_device",
                            params = unlockParams,
                            rawPrompt = "unlock device"
                        )
                        if (executionResult.success) {
                            result.addProperty("status", "success")
                            result.addProperty("message", "${executionResult.targetDeviceName} phone screen wake aur unlock kar diya hai Boss! 🔓")
                        } else {
                            result.addProperty("status", "error")
                            result.addProperty("message", executionResult.message)
                        }
                    } else {
                        // Local unlock
                        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
                        val wl = pm?.newWakeLock(
                            android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                            android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            android.os.PowerManager.ON_AFTER_RELEASE,
                            "isha:unlock"
                        )
                        try { wl?.acquire(10000L) } catch (_: Exception) {}
                        val a11y = IshaAccessibilityService.instance
                        a11y?.performUnlockSwipe()
                        val pin = if (!pinArg.isNullOrBlank()) pinArg else IshaMemoryManager.getDevicePin("local", context)
                        val pattern = if (!patternArg.isNullOrBlank()) patternArg else IshaMemoryManager.getDevicePattern("local", context)
                        if (!pin.isNullOrBlank()) {
                            IshaMemoryManager.saveDevicePin(context, "local", pin)
                            try { Thread.sleep(750) } catch (_: Exception) {}
                            a11y?.enterPin(pin)
                        } else if (!pattern.isNullOrBlank()) {
                            IshaMemoryManager.saveDevicePattern(context, "local", pattern)
                            try { Thread.sleep(750) } catch (_: Exception) {}
                            a11y?.unlockWithPattern(pattern)
                        }

                        // Verification polling (wait up to 2500ms for keyguard dismiss animation)
                        var isUnlocked = km?.isKeyguardLocked == false
                        for (i in 0 until 12) {
                            if (km?.isKeyguardLocked == false) {
                                isUnlocked = true
                                break
                            }
                            try { Thread.sleep(200) } catch (_: Exception) {}
                        }

                        if (!isUnlocked && (!pin.isNullOrBlank() || !pattern.isNullOrBlank())) {
                            // Retry once
                            a11y?.performUnlockSwipe()
                            try { Thread.sleep(750) } catch (_: Exception) {}
                            if (!pin.isNullOrBlank()) a11y?.enterPin(pin)
                            else if (!pattern.isNullOrBlank()) a11y?.unlockWithPattern(pattern)
                            for (i in 0 until 12) {
                                if (km?.isKeyguardLocked == false) {
                                    isUnlocked = true
                                    break
                                }
                                try { Thread.sleep(200) } catch (_: Exception) {}
                            }
                        }

                        if (isUnlocked) {
                            result.addProperty("status", "success")
                            result.addProperty("message", "Phone screen wake aur unlock kar diya hai Boss! 🔓")
                        } else {
                            if (km?.isKeyguardLocked == true) {
                                if (pin.isNullOrBlank() && pattern.isNullOrBlank()) {
                                    result.addProperty("status", "success")
                                    result.addProperty("message", "Phone screen wake kar di hai. Agar lock kholna hai toh PIN ya Pattern bataiye Boss! 📱")
                                } else {
                                    result.addProperty("status", "error")
                                    result.addProperty("message", "Screen wake ho gayi hai lekin device unlock nahi hua. Kripya PIN ya Pattern check karein.")
                                }
                            } else {
                                result.addProperty("status", "success")
                                result.addProperty("message", "Phone screen wake aur unlock kar diya hai Boss! 🔓")
                            }
                        }
                    }
                }

                // 44f. Play High-Decibel Siren
                "play_siren" -> {
                    val rawTarget = args.get("target_device")?.asString ?: ""
                    val activeDev = IshaMemoryManager.getActiveTargetDevice()
                    val targetDevice = if (rawTarget.isNotBlank()) rawTarget else (activeDev ?: "")
                    if (targetDevice.isNotBlank() && !targetDevice.equals("this", ignoreCase = true) && !targetDevice.equals("current", ignoreCase = true) && !targetDevice.equals("local", ignoreCase = true)) {
                        // Remote siren
                        val executionResult = com.aura.assistant.sync.IshaCrossDeviceBridge.dispatchRemoteCommand(
                            context = context,
                            targetDeviceQuery = targetDevice,
                            action = "play_siren",
                            params = emptyMap(),
                            rawPrompt = "play siren"
                        )
                        if (executionResult.success) {
                            result.addProperty("status", "success")
                            result.addProperty("target_device", executionResult.targetDeviceName)
                            result.addProperty("message", "${executionResult.targetDeviceName} par siren bajna shuru ho gaya hai Boss! 🚨")
                        } else {
                            result.addProperty("status", "error")
                            result.addProperty("target_device", executionResult.targetDeviceName)
                            result.addProperty("message", executionResult.message)
                        }
                    } else {
                        // Local siren
                        val started = com.aura.assistant.media.IshaSirenManager.startSiren(context)
                        if (started) {
                            result.addProperty("status", "success")
                            result.addProperty("message", "Siren bajna shuru ho gaya hai Boss! 🚨 (Stop karne ke liye 'siren band karo' bole)")
                        } else {
                            // Common-sense fallback: Play loud siren on YouTube
                            try {
                                val ytArgs = com.google.gson.JsonObject().apply {
                                    addProperty("query", "loud police siren emergency alarm sound")
                                }
                                executeTool(context, "play_youtube", ytArgs)
                                result.addProperty("status", "success")
                                result.addProperty("message", "Hardware alarm ke bajaye YouTube par emergency siren high volume par play kar diya hai Boss! 🚨")
                            } catch (e: Exception) {
                                result.addProperty("status", "error")
                                result.addProperty("message", "Siren start karne me dikkat aayi.")
                            }
                        }
                    }
                }

                // 44g. Stop Siren
                "stop_siren" -> {
                    val rawTarget = args.get("target_device")?.asString ?: ""
                    val activeDev = IshaMemoryManager.getActiveTargetDevice()
                    val targetDevice = if (rawTarget.isNotBlank()) rawTarget else (activeDev ?: "")
                    if (targetDevice.isNotBlank() && !targetDevice.equals("this", ignoreCase = true) && !targetDevice.equals("current", ignoreCase = true) && !targetDevice.equals("local", ignoreCase = true)) {
                        // Remote stop siren
                        val executionResult = com.aura.assistant.sync.IshaCrossDeviceBridge.dispatchRemoteCommand(
                            context = context,
                            targetDeviceQuery = targetDevice,
                            action = "stop_siren",
                            params = emptyMap(),
                            rawPrompt = "stop siren"
                        )
                        if (executionResult.success) {
                            result.addProperty("status", "success")
                            result.addProperty("target_device", executionResult.targetDeviceName)
                            result.addProperty("message", "${executionResult.targetDeviceName} par siren band kar diya hai Boss! 🛑")
                        } else {
                            result.addProperty("status", "error")
                            result.addProperty("target_device", executionResult.targetDeviceName)
                            result.addProperty("message", executionResult.message)
                        }
                    } else {
                        // Local stop siren
                        com.aura.assistant.media.IshaSirenManager.stopSiren(context)
                        result.addProperty("status", "success")
                        result.addProperty("message", "Siren band kar diya hai Boss! 🛑")
                    }
                }

                // 45. Emergency SOS
                "emergency_sos" -> {
                    val target = args.get("target")?.asString?.trim() ?: "112"
                    val msg = args.get("message")?.asString ?: "EMERGENCY: I need immediate help! This is an automated SOS alert from ISHA."

                    val emergencyNumber = if (target.matches(Regex("[0-9+]+"))) target else {
                        resolveContactNumber(context, target) ?: "112"
                    }

                    if (emergencyNumber != "112" && emergencyNumber != "911" && emergencyNumber != "100") {
                        try {
                            val smsManager = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                context.getSystemService(SmsManager::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                SmsManager.getDefault()
                            }) ?: @Suppress("DEPRECATION") SmsManager.getDefault()
                            smsManager?.sendTextMessage(emergencyNumber, null, msg, null, null)
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
                    if (!isListenerEnabled && com.aura.assistant.IshaNotificationListenerService.instance == null) {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        try { context.startActivity(intent) } catch (_: Exception) {}
                        result.addProperty("status", "permission_needed")
                        result.addProperty("message", "Boss, notifications padhne ke liye 'Notification Access' permission allow karni hogi. Maine settings open kar di hai, please allow kar dijiye!")
                    } else {
                        val notifs = com.aura.assistant.IshaNotificationListenerService.getActiveNotificationsList()
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

                // ── Screen Vision & Semantic Node Tools ─────────────────────
                "get_screen_context" -> {
                    val a11y = IshaAccessibilityService.instance
                    if (a11y != null) {
                        val text = a11y.getScreenTextHierarchy()
                        result.addProperty("status", "success")
                        result.addProperty("screen_content", text)
                        result.addProperty("message", "Screen hierarchy capture ho gayi Boss.")
                    } else {
                        result.addProperty("status", "error")
                        result.addProperty("error", "Accessibility Service is not enabled. Please enable ISHA in Accessibility settings.")
                    }
                }

                "find_and_tap" -> {
                    val target = args.get("target")?.asString ?: args.get("target_text")?.asString ?: ""
                    val x = args.get("x")?.asFloat
                    val y = args.get("y")?.asFloat
                    val a11y = IshaAccessibilityService.instance
                    if (a11y != null) {
                        if (x != null && y != null) {
                            val tapped = a11y.performClickCoordinate(x, y)
                            result.addProperty("status", if (tapped) "success" else "failed")
                            result.addProperty("message", if (tapped) "Screen coordinate ($x, $y) par tap kar diya hai Boss!" else "Tap gesture execute nahi ho paya.")
                        } else if (target.isNotBlank()) {
                            var tapped = a11y.performClickByText(target)
                            if (!tapped) {
                                val words = target.split(" ").filter { it.length > 2 }
                                for (w in words) {
                                    if (a11y.performClickByText(w)) {
                                        tapped = true
                                        break
                                    }
                                }
                            }
                            if (!tapped && (target.contains("chrome", ignoreCase = true) || target.contains("youtube", ignoreCase = true) || target.contains("setting", ignoreCase = true) || target.contains("whatsapp", ignoreCase = true) || target.contains("gallery", ignoreCase = true))) {
                                return executeTool(context, "open_app", JsonObject().apply { addProperty("app_name", target) })
                            }
                            result.addProperty("status", if (tapped) "success" else "failed")
                            result.addProperty("message", if (tapped) "Screen par \"$target\" tap kar diya hai Boss!" else "Screen par \"$target\" nahi mila.")
                        } else {
                            result.addProperty("status", "error")
                            result.addProperty("error", "Target blank and no coordinates provided.")
                        }
                    } else {
                        result.addProperty("status", "error")
                        result.addProperty("error", "Accessibility Service not enabled.")
                    }
                }

                "read_screen_text" -> {
                    val a11y = IshaAccessibilityService.instance
                    if (a11y != null) {
                        val text = a11y.getScreenTextHierarchy()
                        result.addProperty("status", "success")
                        result.addProperty("screen_text", text)
                        result.addProperty("message", "Screen text read kar liya hai Boss.")
                    } else {
                        result.addProperty("status", "error")
                        result.addProperty("error", "Accessibility Service not enabled.")
                    }
                }

                // ── Expanded Native Productivity Tools ─────────────────────
                "set_clipboard" -> {
                    val textToCopy = args.get("text")?.asString ?: ""
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("ISHA", textToCopy)
                    clipboard?.setPrimaryClip(clip)
                    result.addProperty("status", "success")
                    result.addProperty("message", "Text clipboard me copy kar diya hai Boss!")
                }

                "get_running_apps" -> {
                    val q = args.get("query")?.asString?.lowercase() ?: ""
                    val pm = context.packageManager
                    val apps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                    val array = JsonArray()
                    for (app in apps) {
                        if ((app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 || q.isNotEmpty()) {
                            val label = pm.getApplicationLabel(app).toString()
                            if (q.isEmpty() || label.lowercase().contains(q) || app.packageName.lowercase().contains(q)) {
                                val item = JsonObject().apply {
                                    addProperty("name", label)
                                    addProperty("package", app.packageName)
                                }
                                array.add(item)
                                if (array.size() >= 25) break
                            }
                        }
                    }
                    result.addProperty("status", "success")
                    result.add("apps", array)
                    result.addProperty("count", array.size())
                    result.addProperty("message", "${array.size()} apps mil gaye Boss.")
                }

                "get_location" -> {
                    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
                    var loc: android.location.Location? = null
                    try {
                        loc = lm?.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                            ?: lm?.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                    } catch (_: SecurityException) {}

                    if (loc != null) {
                        result.addProperty("status", "success")
                        result.addProperty("latitude", loc.latitude)
                        result.addProperty("longitude", loc.longitude)
                        result.addProperty("accuracy", loc.accuracy)
                        result.addProperty("message", "Location mil gayi: Lat ${loc.latitude}, Long ${loc.longitude}")
                    } else {
                        result.addProperty("status", "error")
                        result.addProperty("error", "Location unavailable or permission not granted.")
                    }
                }

                "get_wifi_networks" -> {
                    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                    val isEnabled = wm?.isWifiEnabled ?: false
                    val info = wm?.connectionInfo
                    val ssid = info?.ssid?.replace("\"", "") ?: "Unknown"
                    result.addProperty("status", "success")
                    result.addProperty("wifi_enabled", isEnabled)
                    result.addProperty("connected_ssid", if (ssid != "<unknown ssid>") ssid else "Not connected")
                    result.addProperty("message", if (isEnabled) "WiFi ON hai (Connected: $ssid)" else "WiFi OFF hai")
                }

                "check_internet_speed" -> {
                    val start = System.currentTimeMillis()
                    val reachable = try {
                        val sock = java.net.Socket()
                        sock.connect(java.net.InetSocketAddress("8.8.8.8", 53), 2000)
                        sock.close()
                        true
                    } catch (_: Exception) {
                        false
                    }
                    val latency = System.currentTimeMillis() - start

                    if (!reachable) {
                        result.addProperty("status", "failed")
                        result.addProperty("reachable", false)
                        result.addProperty("latency_ms", -1)
                        result.addProperty("message", "Internet disconnected hai Boss! Please check WiFi ya mobile data.")
                    } else {
                        var mbps = 0.0
                        try {
                            val dlStart = System.currentTimeMillis()
                            val req = Request.Builder()
                                .url("https://speed.cloudflare.com/__down?bytes=2000000")
                                .header("User-Agent", "AuraAssistant/2.0")
                                .build()
                            val resp = httpClient.newCall(req).execute()
                            val stream = resp.body?.byteStream()
                            var totalBytes = 0L
                            val buf = ByteArray(8192)
                            if (stream != null) {
                                var r = stream.read(buf)
                                while (r != -1) {
                                    totalBytes += r
                                    if (System.currentTimeMillis() - dlStart > 2500L) break
                                    r = stream.read(buf)
                                }
                                stream.close()
                            }
                            resp.close()

                            val dlTimeSec = (System.currentTimeMillis() - dlStart) / 1000.0
                            if (dlTimeSec > 0.05 && totalBytes > 10000) {
                                mbps = (totalBytes * 8.0) / (dlTimeSec * 1000000.0)
                            }
                        } catch (_: Exception) {}

                        val mbpsFormatted = if (mbps > 0.0) String.format(Locale.US, "%.1f", mbps) else "Active"
                        result.addProperty("status", "success")
                        result.addProperty("reachable", true)
                        result.addProperty("latency_ms", latency)
                        result.addProperty("download_mbps", if (mbps > 0.0) mbpsFormatted else "N/A")
                        result.addProperty(
                            "message",
                            if (mbps > 0.0)
                                "Internet speed test complete Boss! 🚀 Download speed: ${mbpsFormatted} Mbps (Ping: ${latency}ms)"
                            else
                                "Internet active hai Boss! Latency: ${latency}ms"
                        )
                    }
                }

                "get_recent_calls" -> {
                    val count = args.get("count")?.asInt ?: 10
                    val array = JsonArray()
                    try {
                        val cr = context.contentResolver
                        val cursor = cr.query(
                            android.provider.CallLog.Calls.CONTENT_URI,
                            arrayOf(
                                android.provider.CallLog.Calls.NUMBER,
                                android.provider.CallLog.Calls.CACHED_NAME,
                                android.provider.CallLog.Calls.TYPE,
                                android.provider.CallLog.Calls.DATE
                            ),
                            null, null,
                            "${android.provider.CallLog.Calls.DATE} DESC LIMIT $count"
                        )
                        cursor?.use {
                            val numIdx = it.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
                            val nameIdx = it.getColumnIndex(android.provider.CallLog.Calls.CACHED_NAME)
                            val typeIdx = it.getColumnIndex(android.provider.CallLog.Calls.TYPE)
                            val dateIdx = it.getColumnIndex(android.provider.CallLog.Calls.DATE)
                            while (it.moveToNext()) {
                                val item = JsonObject().apply {
                                    addProperty("number", it.getString(numIdx) ?: "")
                                    addProperty("name", it.getString(nameIdx) ?: "Unknown")
                                    val typeStr = when (it.getInt(typeIdx)) {
                                        android.provider.CallLog.Calls.INCOMING_TYPE -> "Incoming"
                                        android.provider.CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                                        android.provider.CallLog.Calls.MISSED_TYPE -> "Missed"
                                        else -> "Other"
                                    }
                                    addProperty("type", typeStr)
                                    addProperty("date", it.getLong(dateIdx))
                                }
                                array.add(item)
                            }
                        }
                        result.addProperty("status", "success")
                        result.add("calls", array)
                        result.addProperty("count", array.size())
                        result.addProperty("message", "${array.size()} recent calls load ho gayi Boss.")
                    } catch (e: Exception) {
                        result.addProperty("status", "error")
                        result.addProperty("error", e.message ?: "Failed to read call log")
                    }
                }

                "get_contacts_list" -> {
                    val q = args.get("query")?.asString?.lowercase() ?: ""
                    val limit = args.get("limit")?.asInt ?: 20
                    val array = JsonArray()
                    try {
                        val cr = context.contentResolver
                        val cursor = cr.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            arrayOf(
                                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                                ContactsContract.CommonDataKinds.Phone.NUMBER
                            ),
                            null, null,
                            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
                        )
                        val seen = mutableSetOf<String>()
                        cursor?.use {
                            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                            val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                            while (it.moveToNext() && array.size() < limit) {
                                val name = it.getString(nameIdx) ?: ""
                                val num = it.getString(numIdx) ?: ""
                                val key = "$name:$num"
                                if (seen.add(key)) {
                                    if (q.isEmpty() || name.lowercase().contains(q) || num.contains(q)) {
                                        val item = JsonObject().apply {
                                            addProperty("name", name)
                                            addProperty("number", num)
                                        }
                                        array.add(item)
                                    }
                                }
                            }
                        }
                        result.addProperty("status", "success")
                        result.add("contacts", array)
                        result.addProperty("count", array.size())
                        result.addProperty("message", "${array.size()} contacts load ho gaye Boss.")
                    } catch (e: Exception) {
                        result.addProperty("status", "error")
                        result.addProperty("error", e.message ?: "Failed to query contacts")
                    }
                }

                "get_storage_space" -> {
                    try {
                        val stat = android.os.StatFs(Environment.getDataDirectory().path)
                        val blockSize = stat.blockSizeLong
                        val totalBlocks = stat.blockCountLong
                        val availableBlocks = stat.availableBlocksLong

                        val totalBytes = totalBlocks * blockSize
                        val freeBytes = availableBlocks * blockSize
                        val usedBytes = totalBytes - freeBytes

                        val totalGb = String.format(Locale.US, "%.1f", totalBytes / (1024.0 * 1024.0 * 1024.0))
                        val usedGb = String.format(Locale.US, "%.1f", usedBytes / (1024.0 * 1024.0 * 1024.0))
                        val freeGb = String.format(Locale.US, "%.1f", freeBytes / (1024.0 * 1024.0 * 1024.0))
                        val percentUsed = if (totalBytes > 0) ((usedBytes.toDouble() / totalBytes.toDouble()) * 100).toInt() else 0

                        result.addProperty("status", "success")
                        result.addProperty("total_gb", totalGb)
                        result.addProperty("used_gb", usedGb)
                        result.addProperty("free_gb", freeGb)
                        result.addProperty("percent_used", percentUsed)
                        result.addProperty("message", "Boss, aapke phone mein total ${totalGb} GB internal storage hai, jisme se ${usedGb} GB used hai aur ${freeGb} GB free hai (${percentUsed}% used).")
                    } catch (e: Exception) {
                        result.addProperty("status", "error")
                        result.addProperty("error", e.message ?: "Failed to read storage space")
                    }
                }

                "check_device_health" -> {
                    try {
                        // 1. Battery
                        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                        val batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                        val batteryStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val status = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) ?: 0
                            if (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL) "Charging" else "Discharging"
                        } else "Normal"

                        val bIntent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                        val rawTemp = bIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
                        val tempC = rawTemp / 10.0f

                        // 2. RAM
                        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                        val memInfo = android.app.ActivityManager.MemoryInfo()
                        am?.getMemoryInfo(memInfo)
                        val totalRamGb = String.format(Locale.US, "%.1f", memInfo.totalMem / (1024.0 * 1024.0 * 1024.0))
                        val availRamGb = String.format(Locale.US, "%.1f", memInfo.availMem / (1024.0 * 1024.0 * 1024.0))
                        val usedRamGb = String.format(Locale.US, "%.1f", (memInfo.totalMem - memInfo.availMem) / (1024.0 * 1024.0 * 1024.0))

                        // 3. Storage
                        val stat = android.os.StatFs(Environment.getDataDirectory().path)
                        val totalStorageGb = String.format(Locale.US, "%.1f", (stat.blockCountLong * stat.blockSizeLong) / (1024.0 * 1024.0 * 1024.0))
                        val freeStorageGb = String.format(Locale.US, "%.1f", (stat.availableBlocksLong * stat.blockSizeLong) / (1024.0 * 1024.0 * 1024.0))
                        val usedStorageGb = String.format(Locale.US, "%.1f", ((stat.blockCountLong - stat.availableBlocksLong) * stat.blockSizeLong) / (1024.0 * 1024.0 * 1024.0))

                        // 4. System Uptime
                        val uptimeHours = android.os.SystemClock.elapsedRealtime() / (1000 * 60 * 60)

                        // 5. Network
                        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                        @Suppress("DEPRECATION")
                        val netInfo = cm?.activeNetworkInfo
                        @Suppress("DEPRECATION")
                        val netType = netInfo?.typeName ?: "Disconnected"

                        result.addProperty("status", "success")
                        result.addProperty("battery_percent", batteryPct)
                        result.addProperty("battery_status", batteryStatus)
                        result.addProperty("battery_temp_c", tempC)
                        result.addProperty("ram_total_gb", totalRamGb)
                        result.addProperty("ram_used_gb", usedRamGb)
                        result.addProperty("ram_free_gb", availRamGb)
                        result.addProperty("storage_total_gb", totalStorageGb)
                        result.addProperty("storage_used_gb", usedStorageGb)
                        result.addProperty("storage_free_gb", freeStorageGb)
                        result.addProperty("uptime_hours", uptimeHours)
                        result.addProperty("network", netType)

                        val healthSummary = "Boss, aapke phone ka complete health status:\n" +
                                "🔋 Battery: ${batteryPct}% ($batteryStatus, ${tempC}°C)\n" +
                                "🧠 RAM: ${usedRamGb} GB used / ${totalRamGb} GB total (${availRamGb} GB free)\n" +
                                "💾 Storage: ${usedStorageGb} GB used / ${totalStorageGb} GB total (${freeStorageGb} GB free)\n" +
                                "⏱️ Uptime: ${uptimeHours} ghante se active\n" +
                                "📶 Network: $netType\n" +
                                "Overall device health bilkul healthy aur perfect hai Boss! ✨"
                        result.addProperty("message", healthSummary)
                    } catch (e: Exception) {
                        result.addProperty("status", "error")
                        result.addProperty("error", e.message ?: "Failed to check device health")
                    }
                }

                "clear_notifications" -> {
                    val target = (args.get("target")?.asString ?: "all").lowercase().trim()
                    val isListenerEnabled = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
                    if (!isListenerEnabled && com.aura.assistant.IshaNotificationListenerService.instance == null) {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        try { context.startActivity(intent) } catch (_: Exception) {}
                        result.addProperty("status", "permission_needed")
                        result.addProperty("message", "Boss, notifications clear karne ke liye 'Notification Access' allow karni hogi. Settings open kardi hai.")
                    } else {
                        if (target == "all" || target.contains("sab") || target.contains("all") || target.isBlank()) {
                            val cleared = com.aura.assistant.IshaNotificationListenerService.clearAllNotifications()
                            result.addProperty("status", if (cleared) "success" else "failed")
                            result.addProperty("message", if (cleared) "Status bar ki sabhi clearable notifications saaf kardi hain Boss! 🧹✨" else "Notifications clear nahi ho payin.")
                        } else {
                            val cleared = com.aura.assistant.IshaNotificationListenerService.dismissNotification(target)
                            result.addProperty("status", if (cleared) "success" else "not_found")
                            result.addProperty("message", if (cleared) "\"$target\" notification dismiss kardi hai Boss! 🧹" else "\"$target\" se judi koi active notification nahi mili.")
                        }
                    }
                }

                "toggle_message_announcements" -> {
                    val enable = when {
                        args.has("enable") -> args.get("enable").asBoolean
                        args.has("enabled") -> args.get("enabled").asBoolean
                        args.has("state") -> args.get("state").asString.equals("on", ignoreCase = true) || args.get("state").asString.equals("true", ignoreCase = true)
                        else -> true
                    }
                    val prefs = context.getSharedPreferences("aura_settings", Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("message_speak_enabled", enable).apply()
                    result.addProperty("status", "success")
                    result.addProperty("enabled", enable)
                    result.addProperty("message", if (enable) {
                        "Incoming message announcements turn ON kar diye gaye hain Boss! Ab WhatsApp, SMS ya OTP aane par main bol kar suna diya karungi. 📢"
                    } else {
                        "Incoming message announcements turn OFF kar diye gaye hain Boss! Ab message aane par main shant rahungi aur bol kar nahi sunaungi. 🔕"
                    })
                }

                "get_current_time" -> {
                    val now = Date()
                    val time12 = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(now)
                    val time24 = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
                    val fullDate = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault()).format(now)
                    val cal = java.util.Calendar.getInstance()
                    val hour12 = cal.get(java.util.Calendar.HOUR).let { if (it == 0) 12 else it }
                    val minute = cal.get(java.util.Calendar.MINUTE)
                    val spoken = if (minute == 0) "$hour12 baje" else "$hour12 bajkar $minute minute"

                    result.addProperty("status", "success")
                    result.addProperty("current_time", time12)
                    result.addProperty("current_time_24h", time24)
                    result.addProperty("date", fullDate)
                    result.addProperty("spoken_time", spoken)
                    result.addProperty("message", "Abhi exact time $time12 ho raha hai Boss ($spoken), $fullDate. ⏰")
                }

                "get_device_connectivity_and_usage" -> {
                    try {
                        // 1. Data TrafficStats
                        val mobileRx = android.net.TrafficStats.getMobileRxBytes()
                        val mobileTx = android.net.TrafficStats.getMobileTxBytes()
                        val totalRx = android.net.TrafficStats.getTotalRxBytes()
                        val totalTx = android.net.TrafficStats.getTotalTxBytes()

                        val mobileBytes = if (mobileRx > 0 && mobileTx > 0) mobileRx + mobileTx else 0L
                        val totalBytes = if (totalRx > 0 && totalTx > 0) totalRx + totalTx else mobileBytes
                        val wifiBytes = (totalBytes - mobileBytes).coerceAtLeast(0L)

                        fun fmt(bytes: Long): String {
                            val mb = bytes / (1024.0 * 1024.0)
                            return if (mb >= 1024.0) String.format(Locale.US, "%.2f GB", mb / 1024.0)
                            else String.format(Locale.US, "%.1f MB", mb)
                        }

                        // 2. Wi-Fi Status
                        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                        val isWifiEnabled = wm?.isWifiEnabled == true
                        val wifiInfo = wm?.connectionInfo
                        val rawSsid = wifiInfo?.ssid?.replace("\"", "") ?: ""
                        val ssid = if (isWifiEnabled && rawSsid.isNotBlank() && rawSsid != "<unknown ssid>") rawSsid else "Disconnected"

                        // 3. Hotspot Status & Clients
                        val isHotspotOn = try {
                            val method = wm?.javaClass?.getMethod("isWifiApEnabled")
                            method?.invoke(wm) as? Boolean ?: false
                        } catch (_: Exception) { false }

                        val hotspotClients = mutableListOf<String>()
                        try {
                            val arp = File("/proc/net/arp")
                            if (arp.exists() && arp.canRead()) {
                                arp.bufferedReader().useLines { lines ->
                                    lines.drop(1).forEach { line ->
                                        val parts = line.split("\\s+".toRegex())
                                        if (parts.size >= 4 && parts[3] != "00:00:00:00:00:00" && parts[2] == "0x2") {
                                            hotspotClients.add(parts[0])
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {}

                        // 4. Bluetooth Status & Connected Devices
                        var isBtOn = false
                        val btConnectedList = mutableListOf<String>()
                        try {
                            val bm = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
                            val btAdapter = bm?.adapter ?: @Suppress("DEPRECATION") android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                            if (btAdapter != null) {
                                isBtOn = btAdapter.isEnabled
                                if (isBtOn) {
                                    val bonded = btAdapter.bondedDevices ?: emptySet()
                                    for (device in bonded) {
                                        val isConnected = try {
                                            val m = device.javaClass.getMethod("isConnected")
                                            m.invoke(device) as? Boolean ?: false
                                        } catch (_: Exception) { false }
                                        if (isConnected) {
                                            val name = try { device.name ?: device.address } catch (_: Exception) { device.address }
                                            btConnectedList.add(name)
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {}

                        result.addProperty("status", "success")
                        result.addProperty("mobile_data_used", fmt(mobileBytes))
                        result.addProperty("wifi_data_used", fmt(wifiBytes))
                        result.addProperty("total_data_used", fmt(totalBytes))
                        result.addProperty("wifi_name", ssid)
                        result.addProperty("hotspot_active", isHotspotOn)
                        result.addProperty("hotspot_connected_count", hotspotClients.size)
                        result.addProperty("bluetooth_active", isBtOn)
                        result.addProperty("bluetooth_connected_devices", if (btConnectedList.isNotEmpty()) btConnectedList.joinToString(", ") else "None")

                        val msg = StringBuilder()
                        msg.append("Total data consume: ${fmt(totalBytes)} (Mobile: ${fmt(mobileBytes)}, Wi-Fi: ${fmt(wifiBytes)}). ")
                        if (isHotspotOn) {
                            msg.append("Hotspot ON hai aur ${hotspotClients.size} device connected hain. ")
                        } else {
                            msg.append("Hotspot OFF hai. ")
                        }
                        if (isBtOn) {
                            if (btConnectedList.isNotEmpty()) {
                                msg.append("Bluetooth connected devices: ${btConnectedList.joinToString(", ")}. ")
                            } else {
                                msg.append("Bluetooth ON hai lekin koi active device connect nahi hai. ")
                            }
                        } else {
                            msg.append("Bluetooth OFF hai. ")
                        }
                        result.addProperty("message", msg.toString().trim())
                        result.addProperty("follow_up_prompt", "State the data numbers and connected devices clearly in chat. Then ask: 'Boss, kya aap hotspot ya network settings me khud dekhna chahenge?'")
                    } catch (e: Exception) {
                        result.addProperty("status", "error")
                        result.addProperty("error", e.message ?: "Failed to read network stats")
                    }
                }



                // 85. Dismiss Screen Popups / Interrupting Ads (Visual Reflection Agent)
                "dismiss_screen_popups" -> {
                    val fallbackBack = if (args.has("fallback_back")) args.get("fallback_back").asBoolean else true
                    val a11y = IshaAccessibilityService.instance
                    if (a11y == null) {
                        result.addProperty("status", "error")
                        result.addProperty("message", "Boss, screen control ke liye Accessibility service enabled hona zaroori hai.")
                    } else {
                        val (dismissed, detail) = a11y.dismissInterruptingDialogsOrAds(fallbackBack)
                        result.addProperty("status", if (dismissed) "success" else "not_found")
                        result.addProperty("dismissed", dismissed)
                        result.addProperty("detail", detail)
                        result.addProperty("message", if (dismissed) "Boss, popup/ad hata diya gaya hai: $detail ✨" else "Screen par koi interrupting popup ya dismiss button nahi mila Boss.")
                        result.addProperty("follow_up_prompt", "Boss ko lovingly inform karo ki popup/ad dismiss kar diya gaya hai.")
                    }
                }

                // 86. Personalized Daily Briefing Engine
                "get_daily_briefing" -> {
                    try {
                        val briefingType = args.get("briefing_type")?.asString?.lowercase() ?: "morning"

                        // 1. Current Date & Time
                        val now = Date()
                        val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault()).format(now)
                        val dateFmt = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(now)

                        // 2. Battery status
                        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                        val batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                        val isCharging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bm != null) {
                            val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
                            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                        } else false

                        // 3. Unread / Active Notifications
                        val notifsList = com.aura.assistant.IshaNotificationListenerService.getActiveNotificationsList()
                        val waNotifs = notifsList.filter {
                            (it["package"] as? String)?.contains("whatsapp", ignoreCase = true) == true
                        }
                        val notifsSummary = if (notifsList.isEmpty()) {
                            "Status bar bilkul clear hai, koi pending notification nahi hai."
                        } else {
                            "${notifsList.size} active notifications hain (${waNotifs.size} WhatsApp messages)."
                        }

                        // 4. User Facts from Memory Vault
                        val userName = AuraMemoryManager.getUserName(context)
                        val allFacts = AuraMemoryManager.getAllFacts(context)
                        val factsSummary = if (allFacts.isNotEmpty()) {
                            allFacts.entries.take(5).joinToString("; ") { "${it.key}: ${it.value}" }
                        } else "Koi vishesh routine save nahi hai."

                        // 5. Live Headlines
                        val headlines = fetchLiveTopHeadlines()
                        val newsSummary = if (headlines.isNotEmpty()) {
                            headlines.take(3).joinToString(" | ")
                        } else "Desh aur duniya me taaza khabarein normal chal rahi hain."

                        result.addProperty("status", "success")
                        result.addProperty("briefing_type", briefingType)
                        result.addProperty("user_name", userName)
                        result.addProperty("date", dateFmt)
                        result.addProperty("time", timeFmt)
                        result.addProperty("battery_percent", batteryPct)
                        result.addProperty("is_charging", isCharging)
                        result.addProperty("notifications_count", notifsList.size)
                        result.addProperty("whatsapp_notifications_count", waNotifs.size)
                        result.addProperty("news_headlines", newsSummary)
                        result.addProperty("user_facts", factsSummary)

                        val greeting = when {
                            briefingType.contains("evening") || briefingType.contains("night") -> "Shubh sandhya"
                            else -> "Good morning"
                        }
                        val briefingText = StringBuilder().apply {
                            append("$greeting Boss! Aaj $dateFmt hai aur samay $timeFmt ho raha hai. ")
                            append("Phone battery $batteryPct% hai${if (isCharging) " (charging par lagi hai)" else ""}. ")
                            append(notifsSummary).append(" ")
                            if (headlines.isNotEmpty()) {
                                append("Aaj ki mukhya taaza khabarein: ").append(newsSummary).append(". ")
                            }
                        }.toString()

                        result.addProperty("message", briefingText)
                        result.addProperty("follow_up_prompt", "Deliver this briefing in ISHA's warm, enthusiastic, sweet female companion tone (Aoede persona) addressing the user as Boss. Encourage them for the day!")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error generating daily briefing", e)
                        result.addProperty("status", "error")
                        result.addProperty("error", e.message ?: "Failed to generate briefing")
                    }
                }

                // 87. Cross-App Chained Workflow Relay
                "cross_app_workflow" -> {
                    try {
                        val source = args.get("source")?.asString?.lowercase()?.trim() ?: "screen"
                        val targetApp = args.get("target_app")?.asString?.lowercase()?.trim() ?: "maps"
                        val action = args.get("action")?.asString?.lowercase()?.trim() ?: "navigate"
                        val targetContact = args.get("target_contact")?.asString ?: ""
                        val fallbackEntity = args.get("fallback_entity")?.asString ?: ""

                        // 1. Extract source raw text
                        var rawSourceText = ""
                        when (source) {
                            "clipboard" -> {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                rawSourceText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                            }
                            "notifications", "whatsapp" -> {
                                val notifs = com.aura.assistant.IshaNotificationListenerService.getActiveNotificationsList()
                                val targetNotif = notifs.firstOrNull {
                                    (it["package"] as? String)?.contains("whatsapp", ignoreCase = true) == true ||
                                    (it["appName"] as? String)?.contains("whatsapp", ignoreCase = true) == true
                                } ?: notifs.firstOrNull()
                                if (targetNotif != null) {
                                    rawSourceText = "${targetNotif["title"]}: ${targetNotif["text"]}"
                                }
                            }
                            else -> { // "screen"
                                val a11y = IshaAccessibilityService.instance
                                val root = a11y?.rootInActiveWindow
                                if (root != null) {
                                    rawSourceText = a11y.collectAllText(root)
                                }
                            }
                        }

                        // If primary source was empty, check clipboard as fallback
                        if (rawSourceText.isBlank()) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            rawSourceText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                        }

                        // 2. Extract entity (address / phone / query)
                        var extractedEntity = fallbackEntity
                        if (rawSourceText.isNotBlank()) {
                            val pinMatch = Regex("""\b[1-9][0-9]{5}\b""").find(rawSourceText)
                            val addressKeywords = listOf("road", "marg", "street", "nagar", "colony", "sector", "block", "enclave", "vihar", "apartment", "complex", "mall", "station", "chowk", "pincode", "delhi", "mumbai", "bengaluru", "noida", "gurgaon", "lucknow", "kanpur", "jaipur", "kolkata", "chennai", "hyderabad", "pune")
                            val lines = rawSourceText.lines().map { it.trim() }.filter { it.length > 5 }
                            val bestAddressLine = lines.firstOrNull { line ->
                                addressKeywords.any { kw -> line.contains(kw, ignoreCase = true) } || (pinMatch != null && line.contains(pinMatch.value))
                            }

                            extractedEntity = when {
                                bestAddressLine != null -> bestAddressLine
                                pinMatch != null -> "PIN ${pinMatch.value}"
                                fallbackEntity.isNotBlank() -> fallbackEntity
                                else -> rawSourceText.take(120).trim()
                            }
                        }

                        if (extractedEntity.isBlank()) {
                            result.addProperty("status", "error")
                            result.addProperty("message", "Boss, source se koi valid address ya text extract nahi ho paya.")
                        } else {
                            // 3. Relay execution to target app
                            when (targetApp) {
                                "uber", "ola", "rapido" -> {
                                    val cabArgs = JsonObject().apply {
                                        addProperty("app", targetApp)
                                        addProperty("action", "search_ride")
                                        addProperty("destination", extractedEntity)
                                    }
                                    val cabResult = executeTool(context, "ride_booking_control", cabArgs)
                                    result.addProperty("status", "success")
                                    result.addProperty("source", source)
                                    result.addProperty("extracted_entity", extractedEntity)
                                    result.addProperty("target_app", targetApp)
                                    result.addProperty("message", "Boss, '$source' se address \"$extractedEntity\" extract karke $targetApp par ride search kar di hai!")
                                    result.add("relay_result", cabResult)
                                }
                                "maps", "google_maps" -> {
                                    val mapArgs = JsonObject().apply {
                                        addProperty("destination", extractedEntity)
                                    }
                                    val mapResult = executeTool(context, "navigate_maps", mapArgs)
                                    result.addProperty("status", "success")
                                    result.addProperty("source", source)
                                    result.addProperty("extracted_entity", extractedEntity)
                                    result.addProperty("target_app", "Google Maps")
                                    result.addProperty("message", "Boss, '$source' se \"$extractedEntity\" extract karke Google Maps par navigation start kar diya gaya hai! 📍")
                                    result.add("relay_result", mapResult)
                                }
                                "whatsapp" -> {
                                    val waArgs = JsonObject().apply {
                                        if (targetContact.isNotBlank()) addProperty("contact_name", targetContact)
                                        addProperty("message", extractedEntity)
                                    }
                                    val waResult = executeTool(context, "send_whatsapp", waArgs)
                                    result.addProperty("status", "success")
                                    result.addProperty("source", source)
                                    result.addProperty("extracted_entity", extractedEntity)
                                    result.addProperty("target_app", "WhatsApp")
                                    result.addProperty("message", "Boss, '$source' se content WhatsApp par bhej diya gaya hai!")
                                    result.add("relay_result", waResult)
                                }
                                "zomato", "swiggy" -> {
                                    val foodArgs = JsonObject().apply {
                                        addProperty("app", targetApp)
                                        addProperty("action", "search")
                                        addProperty("query", extractedEntity)
                                    }
                                    val foodResult = executeTool(context, "food_delivery_control", foodArgs)
                                    result.addProperty("status", "success")
                                    result.addProperty("source", source)
                                    result.addProperty("extracted_entity", extractedEntity)
                                    result.addProperty("target_app", targetApp)
                                    result.addProperty("message", "Boss, '$source' se \"$extractedEntity\" $targetApp par search kar diya gaya hai!")
                                    result.add("relay_result", foodResult)
                                }
                                else -> {
                                    val searchArgs = JsonObject().apply {
                                        addProperty("query", extractedEntity)
                                    }
                                    val searchResult = executeTool(context, "search_internet", searchArgs)
                                    result.addProperty("status", "success")
                                    result.addProperty("source", source)
                                    result.addProperty("extracted_entity", extractedEntity)
                                    result.addProperty("message", "Boss, '$source' se extract ki gayi query search kar li gayi hai.")
                                    result.add("relay_result", searchResult)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error executing cross_app_workflow", e)
                        result.addProperty("status", "error")
                        result.addProperty("error", e.message ?: "Cross-app workflow failed")
                    }
                }

                // Tier 4 Autonomous Knowledge & Screen Execution Fallback
                else -> {
                    Log.i(TAG, "Dynamic action '$name' invoked — resolving via autonomous UI & millisecond knowledge")
                    val startMs = System.currentTimeMillis()
                    val a11y = IshaAccessibilityService.instance
                    
                    // 1. Millisecond UI Hierarchy scan (<25ms)
                    val queryWords = name.replace("_", " ").split(" ").filter { it.length > 2 }
                    var uiExecuted = false
                    if (a11y != null && queryWords.isNotEmpty()) {
                        uiExecuted = a11y.armAutonomousUiAction(queryWords)
                    }

                    // 2. Ultra-fast Internet Knowledge Retrieval
                    val webKnowledge = performFastWebSearch("Android how to ${name.replace("_", " ")}")
                    val elapsed = System.currentTimeMillis() - startMs

                    result.addProperty("status", "success")
                    result.addProperty("tool_name", name)
                    result.addProperty("autonomous_ui_clicked", uiExecuted)
                    result.addProperty("elapsed_ms", elapsed)
                    result.addProperty("knowledge", webKnowledge)
                    result.addProperty(
                        "message",
                        if (uiExecuted) {
                            "Action \"$name\" screen par match karke autonomously execute kar di gayi hai Boss ($elapsed ms me)!"
                        } else {
                            "Boss, \"$name\" ke liye internet se knowledge nikal li hai ($elapsed ms me): $webKnowledge"
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing $name", e)
            result.addProperty("status", "error")
            result.addProperty("error", e.message ?: "Execution failed")
        }

        // Post-execution state verification via AURA 2.0 ExecutionVerifier
        try {
            val evidence = com.aura.assistant.execution.ExecutionVerifier.verify(context, name, args, result)
            result.addProperty("verified", evidence.verified)
            if (evidence.observedState != null) {
                result.addProperty("observedState", evidence.observedState)
            }
            result.addProperty("verificationMethod", evidence.method ?: "standard")
            if (!evidence.verified) {
                Log.w(TAG, "Tool '$name' verification failed: ${evidence.observedState}")
                com.aura.assistant.memory.ExperienceMemory.recordLesson(
                    context, name, false, "Verification check failed: ${evidence.observedState}"
                )
            } else {
                com.aura.assistant.memory.ExperienceMemory.recordLesson(
                    context, name, true, "Verified state: ${evidence.observedState ?: "completed"}"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Verification check skipped for $name: ${e.message}")
        }

        val elapsedMs = System.currentTimeMillis() - toolStartTime
        result.addProperty("execution_time_ms", elapsedMs)
        Log.i(TAG, "⚡ [LATENCY] Tool '$name' completed in ${elapsedMs}ms | status: ${result.get("status")?.asString}")
        AuraMemoryManager.recordLastAction(name, args.toString(), result.get("status")?.asString ?: "unknown")

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

    data class ContactMatch(val number: String, val displayName: String)

    private fun isDummyPhoneNumber(phone: String): Boolean {
        val digits = phone.replace(Regex("[^0-9]"), "")
        if (digits.length < 7) return true
        val dummies = setOf(
            "1234567890", "0123456789", "9876543210", "0987654321",
            "12345678", "87654321", "1234567", "7654321",
            "5551234", "5551234567", "0000000000", "1111111111",
            "2222222222", "3333333333", "4444444444", "5555555555",
            "6666666666", "7777777777", "8888888888", "9999999999"
        )
        if (dummies.contains(digits)) return true
        if (digits.length >= 7 && digits.all { it == digits[0] }) return true
        val seqAsc = "01234567890123456789"
        val seqDesc = "98765432109876543210"
        if (seqAsc.contains(digits) || seqDesc.contains(digits)) return true
        return false
    }

    /**
     * Normalizes a contact name or query string:
     * - Maps fancy unicode glyphs (small-caps, circled/squared letters) to plain Latin
     * - Removes emojis, symbols, and punctuation
     * - Removes Hindi/English relational suffixes & honorifics (ko, ji, bhai, bhaiya, etc.)
     */
    fun normalizeContactSearchText(text: String): String {
        if (text.isBlank()) return ""
        val sb = java.lang.StringBuilder()
        text.codePoints().forEach { cp ->
            when (cp) {
                0x1D00 -> sb.append('a') // ᴀ
                0x0299 -> sb.append('b') // ʙ
                0x1D04 -> sb.append('c') // ᴄ
                0x1D05 -> sb.append('d') // ᴅ
                0x1D07 -> sb.append('e') // ᴇ
                0xA730 -> sb.append('f') // ꜰ
                0x0262 -> sb.append('g') // ɢ
                0x029C -> sb.append('h') // ʜ
                0x026A -> sb.append('i') // ɪ
                0x1D0A -> sb.append('j') // ᴊ
                0x1D0B -> sb.append('k') // ᴋ
                0x029F -> sb.append('l') // ʟ
                0x1D0D -> sb.append('m') // ᴍ
                0x0274 -> sb.append('n') // ɴ
                0x1D0F -> sb.append('o') // ᴏ
                0x1D18 -> sb.append('p') // ᴘ
                0x0280 -> sb.append('r') // ʀ
                0xA731 -> sb.append('s') // ꜱ
                0x1D1B -> sb.append('t') // ᴛ
                0x1D1C -> sb.append('u') // ᴜ
                0x1D20 -> sb.append('v') // ᴠ
                0x1D21 -> sb.append('w') // ᴡ
                0x028F -> sb.append('y') // ʏ
                0x1D22 -> sb.append('z') // ᴢ
                // Squared/Enclosed Latin Letters (e.g. 🆂 U+1F182)
                in 0x1F130..0x1F149 -> sb.append((cp - 0x1F130 + 'a'.code).toChar())
                in 0x1F150..0x1F169 -> sb.append((cp - 0x1F150 + 'a'.code).toChar())
                in 0x1F170..0x1F189 -> sb.append((cp - 0x1F170 + 'a'.code).toChar())
                else -> {
                    val chars = Character.toChars(cp)
                    sb.append(chars)
                }
            }
        }
        val hasDevanagari = sb.any { it in '\u0900'..'\u097F' }
        val stripped = if (hasDevanagari) {
            // Collapse intra-word Devanagari whitespace (e.g. "यु ग राज" -> "युगराज")
            sb.toString()
                .replace(Regex("(?<=[\\u0900-\\u097F])\\s+(?=[\\u0900-\\u097F])"), "")
                .replace(Regex("[^a-zA-Z0-9\\s\\u0900-\\u097F]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .lowercase()
        } else {
            val nfd = java.text.Normalizer.normalize(sb.toString(), java.text.Normalizer.Form.NFD)
            nfd.replace(Regex("\\p{M}"), "")
                .replace(Regex("[^a-zA-Z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .lowercase()
        }

        return stripped
            .replace(Regex("\\b(ko|ji|bhai|bhaiya|bro|sir|sahab|de|ka|ki|ke|se|pe|par|call|phone|karo|lagao)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Resolves a named contact query to a phone number and verified display name
     * using memory vault and device Contacts database with deep Unicode and phonetic normalization.
     */
    fun resolveContactDetails(context: Context, query: String): ContactMatch? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return null

        val digitsOnly = trimmed.replace(Regex("[^0-9+]"), "")
        if (digitsOnly.length >= 7 && !trimmed.any { it.isLetter() }) {
            return ContactMatch(digitsOnly, digitsOnly)
        }

        // 0. Check ISHA's long-term memory vault first (user-taught contacts and relational facts)
        val cleanName = trimmed.replace(Regex("\\b(ko|ji|bhai|bhaiya|bro|sir|sahab|de|ka|ki|ke|se|pe|par|call|phone|karo|lagao)\\b", RegexOption.IGNORE_CASE), "").trim()
        val queriesToTry = mutableListOf<String>()
        if (cleanName.isNotBlank()) queriesToTry.add(cleanName)
        if (trimmed != cleanName) queriesToTry.add(trimmed)

        for (target in queriesToTry) {
            val memoryEntry = IshaContactMemoryManager.getContactByName(context, target)
            if (memoryEntry != null) {
                return ContactMatch(memoryEntry.primaryNumber, memoryEntry.name)
            }
            val factValue = IshaMemoryManager.recallMemory(context, target)
            if (!factValue.isNullOrBlank()) {
                val numFromFact = IshaContactMemoryManager.getNumberByName(context, factValue)
                if (!numFromFact.isNullOrBlank()) {
                    return ContactMatch(numFromFact, factValue)
                }
            }
        }

        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            // 1. Fast path: Direct SQL LIKE queries
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            for (target in queriesToTry) {
                context.contentResolver.query(uri, projection, selection, arrayOf("%$target%"), null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val numCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        val nameCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        if (numCol != -1) {
                            val rawNum = cursor.getString(numCol)?.replace(Regex("[^0-9+]"), "")
                            val dispName = if (nameCol != -1) cursor.getString(nameCol) ?: target else target
                            if (!rawNum.isNullOrBlank()) {
                                return ContactMatch(rawNum, dispName)
                            }
                        }
                    }
                }
            }

            // 2. Comprehensive in-memory matching (Handles fancy unicode fonts, emojis, diacritics, nicknames)
            val normalizedQuery = normalizeContactSearchText(trimmed)
            if (normalizedQuery.isBlank()) return null
            val queryTokens = normalizedQuery.split(" ").filter { it.length >= 2 }

            val allContacts = mutableListOf<Pair<String, String>>() // (DisplayName, Number)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val numCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (numCol != -1 && nameCol != -1) {
                        val name = cursor.getString(nameCol) ?: ""
                        val num = cursor.getString(numCol) ?: ""
                        val cleanNum = num.replace(Regex("[^0-9+]"), "")
                        if (name.isNotBlank() && cleanNum.isNotBlank()) {
                            allContacts.add(Pair(name, cleanNum))
                        }
                    }
                }
            }

            // Pass A: Exact normalized match
            for ((dispName, phone) in allContacts) {
                val normDisp = normalizeContactSearchText(dispName)
                if (normDisp == normalizedQuery) {
                    return ContactMatch(phone, dispName)
                }
            }

            // Pass B: Token containment (e.g. query "kartik" in "kartik saini personal", or query "prashant dad" matching "prashant dad")
            for ((dispName, phone) in allContacts) {
                val normDisp = normalizeContactSearchText(dispName)
                val dispTokens = normDisp.split(" ").filter { it.length >= 2 }

                if (dispTokens.contains(normalizedQuery)) {
                    return ContactMatch(phone, dispName)
                }

                if (queryTokens.isNotEmpty() && queryTokens.all { qTok -> dispTokens.any { dTok -> dTok == qTok || dTok.contains(qTok) || qTok.contains(dTok) } }) {
                    return ContactMatch(phone, dispName)
                }
            }

            // Pass C: Substring match on normalized string
            for ((dispName, phone) in allContacts) {
                val normDisp = normalizeContactSearchText(dispName)
                if (normDisp.contains(normalizedQuery) || normalizedQuery.contains(normDisp)) {
                    return ContactMatch(phone, dispName)
                }
            }

            // Pass D: Common Hindi nickname / familial mappings
            val aliasMap = mapOf(
                "mummy" to listOf("mom", "mother", "maa", "मम्मी", "माँ", "माताजी"),
                "mom" to listOf("mummy", "maa", "mother", "मम्मी", "माँ"),
                "मम्मी" to listOf("mummy", "mom", "mother", "maa", "माँ"),
                "माँ" to listOf("mummy", "mom", "mother", "maa", "मम्मी"),
                "papa" to listOf("dad", "father", "pitaji", "पापा", "पिताजी"),
                "dad" to listOf("papa", "father", "pitaji", "पापा", "पिताजी"),
                "पापा" to listOf("papa", "dad", "father", "pitaji", "पिताजी"),
                "bhai" to listOf("brother", "bro", "भाई"),
                "भाई" to listOf("bhai", "brother", "bro")
            )
            val aliases = aliasMap[normalizedQuery] ?: emptyList()
            if (aliases.isNotEmpty()) {
                for ((dispName, phone) in allContacts) {
                    val normDisp = normalizeContactSearchText(dispName)
                    for (alias in aliases) {
                        if (normDisp.contains(alias) || normDisp == alias) {
                            return ContactMatch(phone, dispName)
                        }
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve contact: $query", e)
        }
        return null
    }

    private fun resolveContactNumber(context: Context, query: String): String? {
        return resolveContactDetails(context, query)?.number
    }

    private suspend fun executeMakeCall(context: Context, args: JsonObject): JsonObject {
        val result = JsonObject()
        wakeAndUnlockIfNeeded(context)
        val rawPhone = args.get("phone_number")?.asString?.trim() ?: ""
        val rawContact = (args.get("contact_name")?.asString
            ?: args.get("contact")?.asString
            ?: args.get("name")?.asString
            ?: "").trim()

        var targetName = rawContact
        var targetPhone = rawPhone

        // If phone_number contains alphabets (e.g. "Rahul"), treat it as contact name
        if (targetPhone.any { it.isLetter() }) {
            if (targetName.isBlank()) targetName = targetPhone
            targetPhone = ""
        }

        var resolvedPhone: String? = null
        var resolvedDisplayName: String = targetName

        // 1. Check contact_name first if provided
        if (targetName.isNotBlank()) {
            val memoryEntry = IshaContactMemoryManager.getContactByName(context, targetName)
            if (memoryEntry != null) {
                resolvedPhone = memoryEntry.primaryNumber
                resolvedDisplayName = memoryEntry.name
            } else {
                val contactMatch = resolveContactDetails(context, targetName)
                if (contactMatch != null) {
                    resolvedPhone = contactMatch.number
                    resolvedDisplayName = contactMatch.displayName
                }
            }

            // ANTI-HALLUCINATION GUARD: If user asked to call someone by name,
            // NEVER fall back to dialing random/placeholder numbers!
            if (resolvedPhone.isNullOrBlank()) {
                result.addProperty("status", "not_found")
                result.addProperty("message", "Contacts mein '$targetName' ka phone number nahi mila Boss. Kripya check karein ya number batayein.")
                return result
            }
        } else if (targetPhone.isNotBlank()) {
            // User explicitly provided digits to call (no contact name given)
            val digits = targetPhone.replace(Regex("[^0-9+]"), "")
            if (isDummyPhoneNumber(digits)) {
                result.addProperty("status", "error")
                result.addProperty("message", "Ye valid phone number nahi lag raha hai Boss.")
                return result
            }
            if (digits.replace("+", "").length >= 7) {
                resolvedPhone = digits
                resolvedDisplayName = digits
            } else {
                // Short query or name without letters
                val contactMatch = resolveContactDetails(context, targetPhone)
                if (contactMatch != null) {
                    resolvedPhone = contactMatch.number
                    resolvedDisplayName = contactMatch.displayName
                }
            }
        }

        if (resolvedPhone.isNullOrBlank()) {
            val missingTarget = if (targetName.isNotBlank()) targetName else rawPhone
            result.addProperty("status", "not_found")
            result.addProperty("message", "Contacts mein '$missingTarget' ka phone number nahi mila Boss. Kripya check karein ya number batayein.")
            return result
        }

        val finalPhone = resolvedPhone
        val callTarget = if (resolvedDisplayName.isNotBlank()) resolvedDisplayName else finalPhone

        try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$finalPhone")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            result.addProperty("status", "success")
            result.addProperty("phone_number", finalPhone)
            result.addProperty("contact_name", callTarget)
            result.addProperty("message", "$callTarget ($finalPhone) ko call lagayi ja rahi hai Boss! 📞")
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_CALL failed, falling back to ACTION_DIAL", e)
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$finalPhone")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(dialIntent)
            result.addProperty("status", "success")
            result.addProperty("phone_number", finalPhone)
            result.addProperty("contact_name", callTarget)
            result.addProperty("message", "Dialer open kar diya hai $callTarget ke number $finalPhone ke saath Boss!")
        }
        return result
    }

    private suspend fun executeSendSms(context: Context, args: JsonObject): JsonObject {
        val result = JsonObject()
        val rawPhone = args.get("phone_number")?.asString?.trim() ?: ""
        val rawContact = (args.get("contact_name")?.asString ?: "").trim()
        val msg = args.get("message")?.asString ?: ""

        var resolvedPhone: String? = null
        var resolvedDisplayName: String = rawContact

        if (rawContact.isNotBlank()) {
            val memoryEntry = IshaContactMemoryManager.getContactByName(context, rawContact)
            if (memoryEntry != null) {
                resolvedPhone = memoryEntry.primaryNumber
                resolvedDisplayName = memoryEntry.name
            } else {
                val contactMatch = resolveContactDetails(context, rawContact)
                if (contactMatch != null) {
                    resolvedPhone = contactMatch.number
                    resolvedDisplayName = contactMatch.displayName
                }
            }
            if (resolvedPhone.isNullOrBlank()) {
                result.addProperty("status", "not_found")
                result.addProperty("message", "Contacts mein '$rawContact' ka phone number nahi mila SMS bhejne ke liye Boss.")
                return result
            }
        } else if (rawPhone.isNotBlank()) {
            val digits = rawPhone.replace(Regex("[^0-9+]"), "")
            if (!isDummyPhoneNumber(digits) && digits.replace("+", "").length >= 7) {
                resolvedPhone = digits
                resolvedDisplayName = digits
            }
        }

        if (resolvedPhone.isNullOrBlank()) {
            val target = rawContact.ifBlank { rawPhone }
            result.addProperty("status", "not_found")
            result.addProperty("message", "Contacts mein '$target' ka phone number nahi mila SMS bhejne ke liye Boss.")
            return result
        }

        val finalPhone = resolvedPhone
        val smsTarget = if (resolvedDisplayName.isNotBlank()) resolvedDisplayName else finalPhone
        val smsManager = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }) ?: @Suppress("DEPRECATION") SmsManager.getDefault()
        smsManager?.sendTextMessage(finalPhone, null, msg, null, null)
        result.addProperty("status", "success")
        result.addProperty("phone_number", finalPhone)
        result.addProperty("message", "$smsTarget ($finalPhone) ko SMS bhej diya hai Boss!")
        return result
    }

    private suspend fun executeSendWhatsappMedia(context: Context, args: JsonObject): JsonObject {
        val result = JsonObject()
        wakeAndUnlockIfNeeded(context)
        val contact = args.get("contact_name")?.asString
            ?: args.get("contact")?.asString
            ?: args.get("to")?.asString
            ?: ""
        val mediaType = (args.get("media_type")?.asString ?: "screenshot").lowercase()
        val caption = args.get("caption")?.asString ?: args.get("message")?.asString ?: ""

        val a11y = IshaAccessibilityService.instance
        var imageUri: Uri? = null

        // If a screenshot was captured within the last 30 seconds (e.g. chained from take_screenshot), use it immediately!
        val recentScreenshot = lastCapturedScreenshotUri
        if (recentScreenshot != null && (System.currentTimeMillis() - lastCapturedScreenshotTime) < 30_000L) {
            imageUri = recentScreenshot
        } else if (mediaType.contains("screenshot") || mediaType.contains("screen")) {
            if (a11y != null) {
                var capturedBytes: ByteArray? = null
                try {
                    withTimeoutOrNull(2000L) {
                        suspendCancellableCoroutine<Unit> { cont ->
                            a11y.takeScreenCapture { bytes ->
                                capturedBytes = bytes
                                if (cont.isActive) cont.resume(Unit)
                            }
                        }
                    }
                } catch (_: Exception) {}
                if (capturedBytes != null) {
                    imageUri = saveBytesToGallery(context, capturedBytes!!)
                }
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

        var cleanContact = contact.replace(Regex("\\b(ko|ji|bhai|sahab|de|ka|ki|se|pe|par)\\b", RegexOption.IGNORE_CASE), "").trim()
        val cLower = cleanContact.lowercase()
        if (cLower.isBlank() || cLower in listOf("this person", "is person", "current", "here", "him", "her", "unhe", "usse", "inhe", "ise", "isko")) {
            val activeTitle = (if (a11y != null && a11y.isWhatsappChatOpen()) a11y.getWhatsappActiveContactTitle() else null)
                ?: a11y?.lastKnownWhatsappContactTitle
            if (!activeTitle.isNullOrBlank()) {
                cleanContact = activeTitle.trim()
            }
        }
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
            result.addProperty("message", "Screenshot capture karke WhatsApp par ${cleanContact.ifBlank { "chat" }} ko send kar diya gaya hai Boss! 📸💬")
        } catch (e: Exception) {
            result.addProperty("status", "error")
            result.addProperty("message", "WhatsApp me screenshot bhejne me issue aaya: ${e.message}")
        }
        return result
    }

    private suspend fun executeTakeScreenshot(context: Context): JsonObject {
        val result = JsonObject()
        val a11y = IshaAccessibilityService.instance
        if (a11y != null) {
            var savedUri: Uri? = null
            var capturedBytes: ByteArray? = null
            try {
                withTimeoutOrNull(2500L) {
                    suspendCancellableCoroutine<Unit> { cont ->
                        a11y.takeScreenCapture { bytes ->
                            capturedBytes = bytes
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }
                }
            } catch (_: Exception) {}

            if (capturedBytes != null) {
                savedUri = saveBytesToGallery(context, capturedBytes!!)
            }

            if (savedUri != null) {
                lastCapturedScreenshotUri = savedUri
                lastCapturedScreenshotTime = System.currentTimeMillis()
                result.addProperty("status", "success")
                result.addProperty("message", "Screenshot taken and saved to Gallery! 📸")
                result.addProperty("uri", savedUri.toString())
            } else {
                val sysOk = a11y.takeSystemScreenshot()
                if (sysOk) {
                    try { Thread.sleep(800) } catch (_: Exception) {}
                    val latest = getLatestScreenshotOrImageUri(context)
                    if (latest != null) {
                        lastCapturedScreenshotUri = latest
                        lastCapturedScreenshotTime = System.currentTimeMillis()
                    }
                }
                result.addProperty("status", if (sysOk) "success" else "error")
                result.addProperty("message", if (sysOk) "Screenshot captured successfully! 📸" else "Could not capture screenshot.")
            }
        } else {
            result.addProperty("status", "error")
            result.addProperty("message", "ISHA Accessibility Service is not active. Please enable it in Android Settings to capture screenshots.")
        }
        return result
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

    fun wakeAndUnlockIfNeeded(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            val isScreenOn = pm?.isInteractive ?: true
            val isLocked = km?.isKeyguardLocked ?: false

            if (!isScreenOn || isLocked) {
                val wl = pm?.newWakeLock(
                    android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    android.os.PowerManager.ON_AFTER_RELEASE,
                    "isha:auto_wake_unlock"
                )
                try {
                    wl?.acquire(10000L)
                } catch (_: Exception) {}

                if (isLocked) {
                    val a11y = IshaAccessibilityService.instance
                    a11y?.performUnlockSwipe()
                    try {
                        Thread.sleep(400L)
                    } catch (_: Exception) {}

                    val pin = IshaMemoryManager.getDevicePin("local", context)
                    val pattern = IshaMemoryManager.getDevicePattern("local", context)
                    if (!pin.isNullOrBlank()) {
                        a11y?.enterPin(pin)
                    } else if (!pattern.isNullOrBlank()) {
                        a11y?.unlockWithPattern(pattern)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "wakeAndUnlockIfNeeded error: ${e.message}")
        }
    }

    /**
     * Universal Media Player helper:
     * Resolves app package, sends MediaStore search intent, launches deep links,
     * and arms IshaAccessibilityService to auto-tap the first result.
     */
    private fun playMediaInApp(context: Context, query: String, targetApp: String): Boolean {
        wakeAndUnlockIfNeeded(context)
        val trimmedQuery = query.trim()
        val appLower = targetApp.lowercase().trim()

        // 1. Dynamic lookup: Check installed apps dynamically by label or package on the user's phone
        val dynamicPkg = resolveAppPackage(context, targetApp)

        // 2. Standard universal fallback only if app is not found dynamically
        val packageName = when {
            dynamicPkg != null -> dynamicPkg
            appLower.contains("youtube") && !appLower.contains("music") -> "com.google.android.youtube"
            appLower.contains("yt music") || appLower.contains("youtube music") -> "com.google.android.apps.youtube.music"
            appLower.contains("spotify") -> "com.spotify.music"
            appLower.contains("saavn") || appLower.contains("jio") -> "com.jio.media.jiobeats"
            appLower.contains("wynk") -> "com.wynk.music"
            appLower.contains("gaana") -> "com.gaana"
            else -> "com.google.android.youtube"
        }

        try {
            if (packageName == "com.google.android.youtube") {
                val isDirectUrl = trimmedQuery.startsWith("http://", ignoreCase = true) || trimmedQuery.startsWith("https://", ignoreCase = true)
                val targetUri = if (isDirectUrl) {
                    Uri.parse(trimmedQuery)
                } else {
                    Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(trimmedQuery, "UTF-8"))
                }
                val intent = Intent(Intent.ACTION_VIEW, targetUri).apply {
                    setPackage(packageName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                try {
                    context.startActivity(intent)
                } catch (_: Exception) {
                    val fallback = Intent(Intent.ACTION_VIEW, targetUri).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(fallback)
                }
                if (!isDirectUrl) {
                    IshaAccessibilityService.instance?.armYouTubeAutoPlayFirstResult(trimmedQuery)
                }
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
                IshaAccessibilityService.instance?.armAutoSearchAndPlayInApp(targetApp, trimmedQuery)
                return true
            }

            // Fallback: Launch target app and use accessibility search
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(launchIntent)
                IshaAccessibilityService.instance?.armAutoSearchAndPlayInApp(targetApp, trimmedQuery)
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
            IshaAccessibilityService.instance?.armAppSearchAndLaunch(appName)
            return true
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(launchIntent)

        val a11y = IshaAccessibilityService.instance
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

    fun resolveAppPackage(context: Context, appName: String): String? {
        val pm = context.packageManager
        val clean = appName.lowercase().trim()
        if (pm.getLaunchIntentForPackage(appName) != null) return appName

        // Direct package aliases mapping (handles India/global package variations)
        val aliasMap = mapOf(
            "amazon" to listOf("in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping"),
            "youtube" to listOf("com.google.android.youtube", "app.revanced.android.youtube"),
            "flipkart" to listOf("com.flipkart.android"),
            "zomato" to listOf("com.application.zomato"),
            "swiggy" to listOf("in.swiggy.android"),
            "uber" to listOf("com.ubercab"),
            "ola" to listOf("com.olacabs.customer"),
            "rapido" to listOf("com.rapido.passenger"),
            "spotify" to listOf("com.spotify.music"),
            "twitter" to listOf("com.twitter.android", "com.twitter.android.lite"),
            "x" to listOf("com.twitter.android", "com.twitter.android.lite"),
            "snapchat" to listOf("com.snapchat.android"),
            "chrome" to listOf("com.android.chrome"),
            "telegram" to listOf("org.telegram.messenger"),
            "gpay" to listOf("com.google.android.apps.nbu.paisa.user", "com.google.android.apps.nfc.payment", "com.google.android.apps.walletnfcrel"),
            "google pay" to listOf("com.google.android.apps.nbu.paisa.user", "com.google.android.apps.nfc.payment", "com.google.android.apps.walletnfcrel"),
            "phonepe" to listOf("com.phonepe.app"),
            "paytm" to listOf("net.one97.paytm"),
            "bhim" to listOf("in.org.npci.upiapp"),
            "meesho" to listOf("com.meesho.supply"),
            "wynk" to listOf("tv.accedo.airtel.wynk"),
            "chatgpt" to listOf("com.openai.chatgpt")
        )
        for ((alias, pkgs) in aliasMap) {
            if (clean == alias || clean.contains(alias)) {
                for (p in pkgs) {
                    if (pm.getLaunchIntentForPackage(p) != null) return p
                }
            }
        }

        try {
            // 1. Check all launcher activities (user's app drawer)
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val activities = pm.queryIntentActivities(launcherIntent, 0)
            val matchedActivity = activities.firstOrNull {
                val label = it.loadLabel(pm).toString()
                label.contains(clean, ignoreCase = true) ||
                it.activityInfo.packageName.contains(clean, ignoreCase = true)
            }
            if (matchedActivity != null) return matchedActivity.activityInfo.packageName

            // 2. Check all installed applications by label or package
            val apps = pm.getInstalledApplications(0)
            val matched = apps.firstOrNull {
                pm.getApplicationLabel(it).toString().contains(clean, ignoreCase = true) ||
                it.packageName.contains(clean, ignoreCase = true)
            }
            if (matched != null) return matched.packageName
        } catch (_: Exception) {}
        return null
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * Fetches real-time RSS from Google News and parses top headlines.
     */
    fun fetchLiveGoogleNews(topic: String?, lang: String?): JsonObject {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return try {
                java.util.concurrent.Executors.newSingleThreadExecutor().submit<JsonObject> {
                    fetchLiveGoogleNews(topic, lang)
                }.get(5, TimeUnit.SECONDS)
            } catch (e: Exception) {
                JsonObject().apply {
                    addProperty("status", "error")
                    addProperty("error", e.message)
                }
            }
        }
        val result = JsonObject()
        try {
            val isHindi = lang?.lowercase()?.contains("en") != true
            val hl = if (isHindi) "hi" else "en-IN"
            val gl = "IN"
            val ceid = if (isHindi) "IN:hi" else "IN:en"

            val url = if (!topic.isNullOrBlank() && topic.lowercase() != "general" && topic.lowercase() != "all") {
                "https://news.google.com/rss/search?q=${URLEncoder.encode(topic, "UTF-8")}&hl=$hl&gl=$gl&ceid=$ceid"
            } else {
                "https://news.google.com/rss?hl=$hl&gl=$gl&ceid=$ceid"
            }

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                result.addProperty("status", "error")
                result.addProperty("error", "News server HTTP ${response.code}")
                return result
            }

            val xml = response.body?.string() ?: ""
            if (xml.isBlank()) {
                result.addProperty("status", "error")
                result.addProperty("error", "Empty news feed")
                return result
            }

            // Parse RSS XML using Android's native XmlPullParser
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val xpp = factory.newPullParser()
            xpp.setInput(StringReader(xml))

            val articles = JsonArray()
            var eventType = xpp.eventType
            var inItem = false
            var currentTitle = ""
            var currentSource = ""
            var currentPubDate = ""
            var count = 0

            while (eventType != XmlPullParser.END_DOCUMENT && count < 6) {
                val tagName = xpp.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (tagName.equals("item", ignoreCase = true)) {
                            inItem = true
                            currentTitle = ""
                            currentSource = ""
                            currentPubDate = ""
                        } else if (inItem) {
                            when {
                                tagName.equals("title", ignoreCase = true) -> currentTitle = xpp.nextText().trim()
                                tagName.equals("source", ignoreCase = true) -> currentSource = xpp.nextText().trim()
                                tagName.equals("pubDate", ignoreCase = true) -> currentPubDate = xpp.nextText().trim()
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tagName.equals("item", ignoreCase = true)) {
                            if (currentTitle.isNotBlank()) {
                                val itemObj = JsonObject().apply {
                                    val dashIdx = currentTitle.lastIndexOf(" - ")
                                    val headline = if (dashIdx != -1) currentTitle.substring(0, dashIdx).trim() else currentTitle
                                    val src = if (dashIdx != -1 && currentSource.isBlank()) currentTitle.substring(dashIdx + 3).trim() else currentSource
                                    addProperty("headline", headline)
                                    if (src.isNotBlank()) addProperty("source", src)
                                    if (currentPubDate.isNotBlank()) addProperty("published", currentPubDate)
                                }
                                articles.add(itemObj)
                                count++
                            }
                            inItem = false
                        }
                    }
                }
                eventType = xpp.next()
            }

            result.addProperty("status", "success")
            result.addProperty("topic", topic ?: "Aaj ki top khabarein")
            result.addProperty("article_count", count)
            result.add("headlines", articles)
            result.addProperty("message", "Aapke liye $count taaza khabarein fetch ho gayi hain.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch live news", e)
            result.addProperty("status", "error")
            result.addProperty("error", "News fetch error: ${e.message}")
        }
        return result
    }

    /**
     * Searches web in real-time using live web search snippets + DuckDuckGo Instant Answers + Wikipedia.
     */
    fun performDirectWebSearch(query: String): JsonObject {
        val result = JsonObject()
        val snippets = JsonArray()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")

            // 1. Primary: DuckDuckGo Lite Search (Lightweight, reliable mobile search with real-time snippets)
            try {
                val liteUrl = "https://lite.duckduckgo.com/lite/"
                val formBody = okhttp3.FormBody.Builder()
                    .add("q", query)
                    .build()
                val liteReq = Request.Builder()
                    .url(liteUrl)
                    .post(formBody)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9,hi;q=0.8")
                    .build()
                val liteResp = httpClient.newCall(liteReq).execute()
                if (liteResp.isSuccessful) {
                    val html = liteResp.body?.string() ?: ""
                    val snippetRegex = Regex("""<td[^>]*class=['"]result-snippet['"][^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)
                    val matches = snippetRegex.findAll(html)
                    for (m in matches.take(5)) {
                        val rawSnippet = m.groupValues[1]
                        val cleanSnippet = rawSnippet
                            .replace(Regex("<[^>]*>"), "")
                            .replace("&quot;", "\"")
                            .replace("&#x27;", "'")
                            .replace("&#039;", "'")
                            .replace("&amp;", "&")
                            .replace("&lt;", "<")
                            .replace("&gt;", ">")
                            .replace("&nbsp;", " ")
                            .trim()
                        if (cleanSnippet.length >= 25 && !cleanSnippet.contains("DuckDuckGo")) {
                            snippets.add(JsonObject().apply {
                                addProperty("title", "Web Result")
                                addProperty("summary", cleanSnippet)
                                addProperty("source", "Web Search")
                            })
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "DDG Lite web search error: ${e.message}")
            }

            // 2. Fallback: DuckDuckGo HTML Search
            if (snippets.size() == 0) {
                try {
                    val ddgHtmlUrl = "https://html.duckduckgo.com/html/?q=$encoded"
                    val htmlReq = Request.Builder()
                        .url(ddgHtmlUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9,hi;q=0.8")
                        .build()
                    val htmlResp = httpClient.newCall(htmlReq).execute()
                    if (htmlResp.isSuccessful) {
                        val html = htmlResp.body?.string() ?: ""
                        val snippetRegex = Regex("""<a[^>]*class=["']result__snippet["'][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
                        val matches = snippetRegex.findAll(html)
                        for (m in matches.take(5)) {
                            val rawSnippet = m.groupValues[1]
                            val cleanSnippet = rawSnippet
                                .replace(Regex("<[^>]*>"), "")
                                .replace("&quot;", "\"")
                                .replace("&#x27;", "'")
                                .replace("&#039;", "'")
                                .replace("&amp;", "&")
                                .replace("&lt;", "<")
                                .replace("&gt;", ">")
                                .replace("&nbsp;", " ")
                                .trim()
                            if (cleanSnippet.length >= 25 && !cleanSnippet.contains("DuckDuckGo")) {
                                snippets.add(JsonObject().apply {
                                    addProperty("title", "Web Result")
                                    addProperty("summary", cleanSnippet)
                                    addProperty("source", "Web Search")
                                })
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Live HTML web search error: ${e.message}")
                }
            }

            Log.i(TAG, "Web search completed for \"$query\": extracted ${snippets.size()} snippets")

            // 2. DuckDuckGo Instant Answer API (Entity facts, definitions)
            if (snippets.size() < 2) {
                try {
                    val ddgUrl = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
                    val ddgReq = Request.Builder().url(ddgUrl).header("User-Agent", "AURA-Android-Assistant/5.0").build()
                    val ddgResp = httpClient.newCall(ddgReq).execute()
                    if (ddgResp.isSuccessful) {
                        val ddgJsonStr = ddgResp.body?.string() ?: ""
                        if (ddgJsonStr.isNotBlank()) {
                            val parsed = com.google.gson.JsonParser.parseString(ddgJsonStr).asJsonObject
                            val abstractText = parsed.get("AbstractText")?.asString ?: ""
                            val heading = parsed.get("Heading")?.asString ?: ""
                            val entity = parsed.get("Entity")?.asString ?: ""
                            if (abstractText.isNotBlank()) {
                                val snip = JsonObject().apply {
                                    addProperty("title", heading.ifBlank { query })
                                    addProperty("summary", abstractText)
                                    if (entity.isNotBlank()) addProperty("entity_type", entity)
                                }
                                snippets.add(snip)
                            }
                            val related = parsed.getAsJsonArray("RelatedTopics")
                            if (related != null && related.size() > 0) {
                                for (r in 0 until minOf(3, related.size())) {
                                    val rObj = related.get(r)
                                    if (rObj.isJsonObject && rObj.asJsonObject.has("Text")) {
                                        val relText = rObj.asJsonObject.get("Text").asString
                                        if (relText.isNotBlank()) {
                                            snippets.add(JsonObject().apply {
                                                addProperty("title", "Related Info")
                                                addProperty("summary", relText)
                                            })
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "DDG API search error: ${e.message}")
                }
            }

            // 3. Wikipedia Search API as rich factual complement
            if (snippets.size() < 2) {
                try {
                    val wikiUrl = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&utf8=&format=json"
                    val wikiReq = Request.Builder().url(wikiUrl).header("User-Agent", "AURA-Android-Assistant/5.0").build()
                    val wikiResp = httpClient.newCall(wikiReq).execute()
                    if (wikiResp.isSuccessful) {
                        val wikiJson = wikiResp.body?.string() ?: ""
                        val parsedWiki = com.google.gson.JsonParser.parseString(wikiJson).asJsonObject
                        val searchArr = parsedWiki.getAsJsonObject("query")?.getAsJsonArray("search")
                        if (searchArr != null) {
                            for (s in 0 until minOf(2, searchArr.size())) {
                                val item = searchArr.get(s).asJsonObject
                                val wTitle = item.get("title")?.asString ?: ""
                                val wSnippet = item.get("snippet")?.asString
                                    ?.replace(Regex("<[^>]*>"), "")
                                    ?.replace("&quot;", "\"")
                                    ?.replace("&#039;", "'")
                                    ?.replace("&amp;", "&") ?: ""
                                if (wSnippet.isNotBlank()) {
                                    snippets.add(JsonObject().apply {
                                        addProperty("title", wTitle)
                                        addProperty("summary", wSnippet)
                                        addProperty("source", "Wikipedia")
                                    })
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Wiki search error: ${e.message}")
                }
            }

            result.addProperty("status", "success")
            result.addProperty("query", query)
            result.add("results", snippets)
            result.addProperty("message", "Search complete for \"$query\".")
        } catch (e: Exception) {
            Log.e(TAG, "Direct web search error", e)
            result.addProperty("status", "error")
            result.addProperty("error", "Web search failed: ${e.message}")
        }
        return result
    }

    /**
     * Dynamically filters all 62 tools to supply the top 5-15 relevant tools to Gemini
     * based on user intent and multi-factor domain scoring.
     * Conforms to Google's official recommendation for large tool catalogs.
     */
    fun getRelevantToolDeclarations(
        query: String,
        minCount: Int = 5,
        maxCount: Int = 15
    ): JsonArray {
        val all = getGeminiToolDeclarations()
        val clean = query.lowercase().trim()
        val queryTokens = clean.split(Regex("[\\s,?.!]+")).filter { it.length > 2 }.toSet()

        data class ScoredDecl(val json: JsonObject, val score: Float)
        val scored = mutableListOf<ScoredDecl>()

        for (i in 0 until all.size()) {
            val fn = all.get(i).asJsonObject
            val name = fn.get("name")?.asString?.lowercase() ?: ""
            val desc = fn.get("description")?.asString?.lowercase() ?: ""

            var score = 0f
            // Direct name mention
            if (clean.contains(name) || clean.contains(name.replace("_", " "))) {
                score += 0.8f
            }
            // Token matches in name and description
            for (t in queryTokens) {
                if (name.contains(t)) score += 0.4f
                if (desc.contains(t)) score += 0.2f
            }

            // Category domain boosts
            if (clean.contains("whatsapp") || clean.contains("message") || clean.contains("bhejo") || clean.contains("sms") || clean.contains("call") || clean.contains("phone")) {
                if (name.contains("whatsapp") || name.contains("call") || name.contains("sms") || name.contains("contact")) {
                    score += 0.4f
                }
            }
            if (clean.contains("alarm") || clean.contains("timer") || clean.contains("note") || clean.contains("calculate") || clean.contains("hisaab")) {
                if (name.contains("alarm") || name.contains("timer") || name.contains("note") || name.contains("calc")) {
                    score += 0.4f
                }
            }
            if (clean.contains("youtube") || clean.contains("gana") || clean.contains("song") || clean.contains("music") || clean.contains("play")) {
                if (name.contains("youtube") || name.contains("media") || name.contains("song") || name.contains("spotify")) {
                    score += 0.4f
                }
            }
            if (clean.contains("torch") || clean.contains("flashlight") || clean.contains("volume") || clean.contains("wifi") || clean.contains("bluetooth") || clean.contains("battery") || clean.contains("speed")) {
                if (name.contains("torch") || name.contains("flash") || name.contains("volume") || name.contains("wifi") || name.contains("bluetooth") || name.contains("battery") || name.contains("speed") || name.contains("dnd")) {
                    score += 0.4f
                }
            }
            // News and live internet search domain boost
            if (clean.contains("news") || clean.contains("khabar") || clean.contains("samachar") || clean.contains("aaj ki") || clean.contains("breaking")) {
                if (name.contains("news") || name.contains("search")) {
                    score += 0.85f
                }
            }
            if (clean.contains("search") || clean.contains("google") || clean.contains("pata karo") || clean.contains("kya hai") || clean.contains("who is") || clean.contains("what is")) {
                if (name.contains("search") || name.contains("news")) {
                    score += 0.7f
                }
            }

            // Phone storage space domain boost (GB / space / storage)
            val isAskingForDiskSpace = clean.contains("storage") || clean.contains("space") || clean.contains("memory") || clean.contains("gb") || clean.contains("disk")
            val isAskingForPhotosVideos = clean.contains("photo") || clean.contains("video") || clean.contains("media") || clean.contains("camera") || clean.contains("gallery")

            if (isAskingForDiskSpace && !isAskingForPhotosVideos) {
                if (name == "get_storage_space") {
                    score += 1.5f
                }
            }

            // Photos / Videos / Media storage stats domain boost (ONLY when specifically asking for media)
            if (isAskingForPhotosVideos || ((clean.contains("kitne") || clean.contains("kitni")) && isAskingForPhotosVideos)) {
                if (name.contains("media_storage") || name.contains("media") || name.contains("gallery")) {
                    score += 0.85f
                }
            }

            // Device Health domain boost
            if (clean.contains("health") || clean.contains("device health") || clean.contains("phone health") || clean.contains("system health")) {
                if (name == "check_device_health") {
                    score += 1.5f
                }
            }

            // Internet speed domain boost
            if (clean.contains("speed") || clean.contains("internet") || clean.contains("net") || clean.contains("ping") || clean.contains("latency") || clean.contains("mbps")) {
                if (name == "check_internet_speed") {
                    score += 1.5f
                }
                if (clean.contains("chrome") && name == "open_app") {
                    score += 1.0f
                }
            }

            // Notifications domain boost
            if (clean.contains("notification") || clean.contains("notif") || clean.contains("status bar")) {
                if (name == "clear_notifications" || name == "read_notifications") {
                    score += 1.2f
                }
            }

            // Message reading / announcement domain boost
            if ((clean.contains("message") || clean.contains("msg")) &&
                (clean.contains("padh") || clean.contains("read") || clean.contains("bol") || clean.contains("sunao") || clean.contains("announce") || clean.contains("speak") || clean.contains("band") || clean.contains("off") || clean.contains("chalu") || clean.contains("on") || clean.contains("mat"))) {
                if (name == "toggle_message_announcements" || name == "read_notifications") {
                    score += 1.8f
                }
            }

            // Time & Date domain boost
            if (clean.contains("time") || clean.contains("samay") || clean.contains("baje") || clean.contains("ghadi") || clean.contains("date") || clean.contains("tareekh") || clean.contains("din")) {
                if (name == "get_current_time") {
                    score += 2.0f
                }
            }

            // Screen and UI Vision domain boost
            if (clean.contains("screen") || clean.contains("dekh") || clean.contains("dikh") || clean.contains("read") || clean.contains("padh") || clean.contains("tap") || clean.contains("click") || clean.contains("kro")) {
                if (name.contains("screen") || name.contains("tap") || name.contains("ocr") || name.contains("vision") || name == "open_app") {
                    score += 0.9f
                }
            }


            // Web search domain boost
            if (clean.contains("search") || clean.contains("weather") || clean.contains("mausam") || clean.contains("google") || clean.contains("pata karo")) {
                if (name == "search_internet" || name == "web_search") {
                    score += 1.8f
                }
            }

            // Instagram domain boost
            if (clean.contains("instagram") || clean.contains("insta") || clean.contains("reel") || clean.contains("reels") || clean.contains("story") || clean.contains("dm") || clean.contains("like") || clean.contains("comment")) {
                if (name.startsWith("instagram_")) {
                    score += 2.2f
                }
            }

            // WhatsApp domain boost
            if (clean.contains("whatsapp") || clean.contains("wa") || clean.contains("status") || clean.contains("backup")) {
                if (name.startsWith("whatsapp_") || name.contains("whatsapp")) {
                    score += 2.2f
                }
            }

            // Autonomous UI & Universal tasks domain boost
            if (clean.contains("karo") || clean.contains("control") || clean.contains("setting") || clean.contains("kaise") || clean.contains("autonomous") || clean.contains("auto")) {
                if (name == "autonomous_ui_action") {
                    score += 1.2f
                }
            }

            // Everyday Apps domain boosts
            if (clean.contains("telegram") || clean.contains("tg")) {
                if (name == "telegram_action") score += 2.2f
            }
            if ((clean.contains("youtube") || clean.contains("short") || clean.contains("shorts") || clean.contains("skip ad")) && (clean.contains("like") || clean.contains("subscribe") || clean.contains("comment") || clean.contains("skip") || clean.contains("next") || clean.contains("scroll"))) {
                if (name == "youtube_interact") score += 2.2f
            }
            if (clean.contains("zomato") || clean.contains("swiggy") || clean.contains("food") || clean.contains("khana") || clean.contains("pizza") || clean.contains("burger") || clean.contains("biryani")) {
                if (name == "food_delivery_control") score += 2.2f
            }
            if (clean.contains("uber") || clean.contains("ola") || clean.contains("rapido") || clean.contains("cab") || clean.contains("taxi") || clean.contains("ride")) {
                if (name == "ride_booking_control") score += 2.2f
            }
            if (clean.contains("amazon") || clean.contains("flipkart") || clean.contains("shopping") || clean.contains("cart") || clean.contains("order track")) {
                if (name == "shopping_control") score += 2.2f
            }
            if (clean.contains("gpay") || clean.contains("phonepe") || clean.contains("paytm") || clean.contains("upi") || clean.contains("qr") || clean.contains("scan") || clean.contains("paise") || clean.contains("payment")) {
                if (name == "upi_payment_control") score += 2.2f
            }
            if (clean.contains("twitter") || clean.contains("tweet") || clean.contains(" x ")) {
                if (name == "twitter_control") score += 2.2f
            }
            if (clean.contains("snapchat") || clean.contains("snap") || clean.contains("streak")) {
                if (name == "snapchat_control") score += 2.2f
            }
            if (clean.contains("chrome") || clean.contains("browser") || clean.contains("incognito") || clean.contains("tab") || clean.contains("url") || clean.contains("website")) {
                if (name == "browser_control") score += 2.0f
            }
            if (clean.contains("spotify")) {
                if (name == "spotify_control") score += 2.2f
            }

            // Daily briefing boost
            if (clean.contains("briefing") || clean.contains("good morning") || clean.contains("subah") || clean.contains("aaj ka din") || clean.contains("morning update")) {
                if (name == "get_daily_briefing") score += 2.5f
            }

            // Dismiss screen popups / ads boost
            if (clean.contains("popup") || clean.contains("ad hatao") || clean.contains("skip ad") || clean.contains("dialog") || clean.contains("dismiss") || (clean.contains("hatao") && clean.contains("screen"))) {
                if (name == "dismiss_screen_popups") score += 2.5f
            }

            // Cross-app workflow relay boost
            if (clean.contains("workflow") || clean.contains("relay") || (clean.contains("se") && (clean.contains("par") || clean.contains("pe"))) || (clean.contains("address") && (clean.contains("cab") || clean.contains("uber") || clean.contains("ola") || clean.contains("maps")))) {
                if (name == "cross_app_workflow") score += 2.5f
            }

            // Live news boost
            if (clean.contains("news") || clean.contains("khabar") || clean.contains("samachar") || clean.contains("breaking")) {
                if (name == "get_latest_news") score += 2.2f
            }

            scored.add(ScoredDecl(fn, score))
        }

        val sorted = scored.sortedByDescending { it.score }
        val result = JsonArray()

        for (item in sorted) {
            if (result.size() < minCount || (result.size() < maxCount && item.score > 0.1f)) {
                result.add(item.json)
            } else {
                break
            }
        }

        Log.d(TAG, "⚡ Dynamic Capability Retrieval: supplied ${result.size()} tools (out of ${all.size()}) for '$query'")
        return result
    }

    private fun performFastWebSearch(query: String): String {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return try {
                java.util.concurrent.Executors.newSingleThreadExecutor().submit<String> {
                    performFastWebSearch(query)
                }.get(5, TimeUnit.SECONDS)
            } catch (e: Exception) {
                "Information retrieved for '$query'."
            }
        }
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://html.duckduckgo.com/html/?q=$encoded"
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                .build()
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .build()
            val resp = client.newCall(req).execute()
            val html = resp.body?.string() ?: ""
            val clean = android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            val snippets = clean.lines()
                .map { it.trim() }
                .filter { it.length in 35..250 && !it.startsWith("http") && !it.contains("DuckDuckGo") }
                .take(4)
                .joinToString("\n• ")
            if (snippets.isNotBlank()) "• $snippets" else "Information retrieved for '$query'."
        } catch (e: Exception) {
            "Web query executed for '$query'. (Network search: ${e.message ?: "fallback"})"
        }
    }

    fun fetchLiveTopHeadlines(): List<String> {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return try {
                java.util.concurrent.Executors.newSingleThreadExecutor().submit<List<String>> {
                    fetchLiveTopHeadlines()
                }.get(5, TimeUnit.SECONDS)
            } catch (_: Exception) {
                emptyList()
            }
        }
        val headlines = mutableListOf<String>()
        try {
            val url = "https://news.google.com/rss?hl=en-IN&gl=IN&ceid=IN:en"
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                .build()
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build()
            val resp = client.newCall(req).execute()
            val xml = resp.body?.string() ?: ""
            if (xml.isNotBlank()) {
                val factory = XmlPullParserFactory.newInstance()
                val parser = factory.newPullParser()
                parser.setInput(StringReader(xml))
                var eventType = parser.eventType
                var inItem = false
                var currentTag = ""
                while (eventType != XmlPullParser.END_DOCUMENT && headlines.size < 5) {
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            currentTag = parser.name?.lowercase() ?: ""
                            if (currentTag == "item") inItem = true
                        }
                        XmlPullParser.TEXT -> {
                            if (inItem && currentTag == "title") {
                                val title = parser.text?.trim() ?: ""
                                if (title.isNotBlank() && !title.startsWith("Google News")) {
                                    val cleanTitle = title.substringBeforeLast(" - ")
                                    if (cleanTitle.isNotBlank() && !headlines.contains(cleanTitle)) {
                                        headlines.add(cleanTitle)
                                    }
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if (parser.name?.equals("item", ignoreCase = true) == true) inItem = false
                            currentTag = ""
                        }
                    }
                    eventType = parser.next()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Google News RSS: ${e.message}")
        }

        if (headlines.isEmpty()) {
            val webSnippet = performFastWebSearch("top headlines today India")
            if (webSnippet.isNotBlank()) {
                headlines.addAll(webSnippet.lines().map { it.replace("•", "").trim() }.filter { it.length > 20 }.take(3))
            }
        }
        return headlines
    }

    /**
     * After a browser search is launched, this function:
     * 1. Waits up to [delayMs] milliseconds while polling for page render via IshaAccessibilityService.
     * 2. Uses IshaAccessibilityService to read visible text from the Chrome screen.
     * 3. Uses GLOBAL_ACTION_BACK to instantly dismiss Chrome and bring AURA's MainActivity back to front.
     * Returns the extracted screen text (trimmed to ~600 chars so it fits in a message).
     */
    private fun readBrowserScreenAndReturn(context: android.content.Context, delayMs: Long = 3000L): String {
        return try {
            val a11y = IshaAccessibilityService.instance
            var meaningful = ""
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < delayMs) {
                Thread.sleep(700L)
                val rawScreen = a11y?.getScreenTextHierarchy() ?: ""
                val lines = rawScreen
                    .lines()
                    .map { it.replace(Regex("^[🔘📄]\\s*"), "").trim() }
                    .filter { line ->
                        line.length > 15 &&
                        !line.contains("DuckDuckGo", ignoreCase = true) &&
                        !line.equals("Search or type URL", ignoreCase = true) &&
                        !line.equals("Search or type web address", ignoreCase = true) &&
                        !line.contains("Tab switcher", ignoreCase = true) &&
                        !line.contains("New tab", ignoreCase = true) &&
                        !line.contains("More options", ignoreCase = true) &&
                        !line.contains("Screen is empty", ignoreCase = true)
                    }
                if (lines.isNotEmpty()) {
                    meaningful = lines.take(8).joinToString(" | ").take(650).trim()
                    break
                }
            }

            // Bring AURA MainActivity back to the foreground:
            // 1. First attempt: AccessibilityService GLOBAL_ACTION_BACK instantly dismisses Chrome and reveals AURA!
            val wentBack = a11y?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) ?: false
            Log.i(TAG, "readBrowserScreenAndReturn: GLOBAL_ACTION_BACK executed = $wentBack")

            // 2. Second attempt: Direct intent to MainActivity
            try {
                val act = com.aura.assistant.MainActivity.instance
                if (act != null) {
                    val bringIntent = android.content.Intent(act, com.aura.assistant.MainActivity::class.java).apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    act.startActivity(bringIntent)
                } else {
                    val appIntent = android.content.Intent(context, com.aura.assistant.MainActivity::class.java).apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    context.startActivity(appIntent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "readBrowserScreenAndReturn direct intent: ${e.message}")
            }

            // 3. Third attempt: via serviceContext launch intent
            try {
                val serviceCtx = a11y ?: context
                val returnIntent = serviceCtx.packageManager
                    .getLaunchIntentForPackage(serviceCtx.packageName)
                    ?.apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                if (returnIntent != null) serviceCtx.startActivity(returnIntent)
            } catch (e: Exception) {
                Log.w(TAG, "readBrowserScreenAndReturn launchIntent: ${e.message}")
            }

            meaningful
        } catch (e: Exception) {
            Log.w(TAG, "readBrowserScreenAndReturn error: ${e.message}")
            ""
        }
    }
}

/** Backward compatibility alias for JarvisToolRegistry */
val JarvisToolRegistry = IshaToolRegistry

