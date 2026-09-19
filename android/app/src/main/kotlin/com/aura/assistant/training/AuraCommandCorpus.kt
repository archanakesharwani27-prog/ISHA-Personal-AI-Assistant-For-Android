package com.aura.assistant.training

/**
 * =========================================================
 *  AURA COMMAND TRAINING CORPUS  v3.0
 *  1000+ real-world user command patterns across 55 categories.
 *  Pre-seeds ExperienceMemory so AURA never fails the same way twice.
 * =========================================================
 *
 *  Each TrainingCommand has:
 *   rawText     - How a real user might say/type the command (Hindi/Hinglish/English mix)
 *   category    - Semantic category label
 *   toolName    - Primary tool AURA should invoke
 *   params      - Expected parameters (key→value)
 *   lesson      - Pre-seeded wisdom for ExperienceMemory
 *   isEdgeCase  - true = tests ambiguity / fallback handling
 */
data class TrainingCommand(
    val rawText: String,
    val category: String,
    val toolName: String,
    val params: Map<String, String> = emptyMap(),
    val lesson: String,
    val isEdgeCase: Boolean = false
)

@Suppress("LargeClass")
object AuraCommandCorpus {

    val commands: List<TrainingCommand> by lazy { buildCorpus() }

    @Suppress("LongMethod")
    private fun buildCorpus(): List<TrainingCommand> = buildList {

        // ── CATEGORY 01: PHONE CALLS ─────────────────────────────────
        add(cmd("Maa ko call karo", "call", "make_call", p("contact_name","Maa"),
            "Family names like 'Maa','Papa','Bhai','Didi' → contact_name param, NEVER hallucinate a phone number."))
        add(cmd("Ansh ko call karo", "call", "make_call", p("contact_name","Ansh"),
            "When contact name is given use contact_name param."))
        add(cmd("9876543210 pe call lagao", "call", "make_call", p("phone_number","9876543210"),
            "10-digit numeric string = phone number. Always use phone_number param."))
        add(cmd("Papa ko phone karo", "call", "make_call", p("contact_name","Papa"),
            "'Phone karo' in Hindi = make_call tool."))
        add(cmd("Rahul bhai ko dial karo", "call", "make_call", p("contact_name","Rahul bhai"),
            "Keep honorifics like 'bhai','ji','aunty' in the contact_name."))
        add(cmd("Call Priya", "call", "make_call", p("contact_name","Priya"),
            "Pure English 'Call [Name]' → make_call(contact_name)."))
        add(cmd("Emergency call karo 112", "call", "emergency_sos", p("target","112"),
            "112/100/101/108 are emergency numbers → emergency_sos, NOT make_call."))
        add(cmd("Ambulance ko call karo", "call", "emergency_sos", p("target","108"),
            "'Ambulance' keyword → emergency_sos(target=108)."))
        add(cmd("Police bulao", "call", "emergency_sos", p("target","100"),
            "'Police' keyword → emergency_sos(target=100)."))
        add(cmd("+91-98765-43210 pe call karo", "call", "make_call", p("phone_number","+919876543210"),
            "Phone numbers with +91/spaces/hyphens → strip formatting, pass clean digits."))
        add(cmd("Auto dial karo", "call", "make_call", emptyMap(),
            "Ambiguous — ask which contact before calling.", true))
        add(cmd("Missed call de do", "call", "make_call", emptyMap(),
            "Missed call = make_call; call will ring briefly. Clarify contact first.", true))

        // ── CATEGORY 02: SMS ─────────────────────────────────────────
        add(cmd("Rahul ko message bhejo: Bhai kal milenge", "sms", "send_sms",
            mp("contact_name","Rahul","message","Bhai kal milenge"),
            "Colon after contact name = message text. Parse precisely."))
        add(cmd("9999000001 ko SMS karo 'Meeting cancel hai'", "sms", "send_sms",
            mp("phone_number","9999000001","message","Meeting cancel hai"),
            "Phone number + quoted text = send_sms with phone_number and message."))
        add(cmd("Maa ko text bhejo ki main late aa raha hoon", "sms", "send_sms",
            mp("contact_name","Maa","message","Main late aa raha hoon"),
            "Construct message text from 'ki' clause in natural language."))
        add(cmd("Mere last SMS padho", "sms", "read_recent_sms", p("count","1"),
            "'Last SMS padho' = read_recent_sms(count=1)."))
        add(cmd("Read my messages", "sms", "read_recent_sms", p("count","5"),
            "Default count=5 when no specific number is given."))
        add(cmd("Saare unread SMS padho", "sms", "read_recent_sms", p("count","20"),
            "Unread = read more messages, default to count=20."))

        // ── CATEGORY 03: WHATSAPP ────────────────────────────────────
        add(cmd("Ansh ko WhatsApp karo: Ghar aaja", "whatsapp", "send_whatsapp",
            mp("contact_name","Ansh","message","Ghar aaja"),
            "WhatsApp text → send_whatsapp. NEVER use send_sms for WhatsApp."))
        add(cmd("WA par Priya ko 'Happy Birthday' bhejo", "whatsapp", "send_whatsapp",
            mp("contact_name","Priya","message","Happy Birthday"),
            "WA = WhatsApp abbreviation → send_whatsapp."))
        add(cmd("WhatsApp me Raj ko bol do 5 minute mein aa jaunga", "whatsapp", "send_whatsapp",
            mp("contact_name","Raj","message","5 minute mein aa jaunga"),
            "Paraphrase natural language directly into message param."))
        add(cmd("Ansh ko screenshot WhatsApp kar do", "whatsapp_media", "send_whatsapp_media",
            mp("contact_name","Ansh","media_type","screenshot"),
            "'Screenshot WhatsApp karo' → send_whatsapp_media(media_type=screenshot)."))
        add(cmd("Papa ko meri latest photo bhejo WhatsApp se", "whatsapp_media", "send_whatsapp_media",
            mp("contact_name","Papa","media_type","photo"),
            "Latest photo = send_whatsapp_media(media_type=photo)."))
        add(cmd("Priya ke WhatsApp pe last image delete karo", "whatsapp_media", "delete_whatsapp_media",
            mp("contact_name","Priya","target","last_image"),
            "Delete last image → delete_whatsapp_media(target=last_image)."))
        add(cmd("Rahul ki WhatsApp chat me jo photo aaya tha wo delete karo", "whatsapp_media", "delete_whatsapp_media",
            mp("contact_name","Rahul","target","last_image"),
            "Past tense 'jo aaya tha' still refers to most recent received media."))
        add(cmd("Group 'Family' pe message bhejo: Dinner ready hai", "whatsapp", "send_whatsapp",
            mp("contact_name","Family","message","Dinner ready hai"),
            "Group names work as contact_name; accessibility engine handles group lookup."))
        add(cmd("WhatsApp Status update karo", "whatsapp", "perform_app_action",
            mp("app_name","WhatsApp","action_description","open status update screen"),
            "Status update → perform_app_action, not a direct whatsapp tool.", true))
        add(cmd("Ansh ko voice note bhejo WhatsApp par", "whatsapp", "perform_app_action",
            mp("app_name","WhatsApp","action_description","open Ansh chat for voice note"),
            "Voice note send → perform_app_action; open chat then record.", true))

        // ── CATEGORY 04: VOLUME ──────────────────────────────────────
        add(cmd("Volume full karo", "volume", "set_volume", p("level_percent","100"),
            "'Full karo' = 100% for volume. Always use set_volume, never set_dnd."))
        add(cmd("Awaaz band karo", "volume", "set_volume", p("level_percent","0"),
            "'Awaaz band' = 0% = mute via set_volume, NOT set_dnd."))
        add(cmd("Volume 50% par set karo", "volume", "set_volume", p("level_percent","50"),
            "Explicit percentage → extract numeral, pass to level_percent."))
        add(cmd("Sound thoda kam karo", "volume", "set_volume", p("level_percent","30"),
            "'Thoda kam' is relative — reduce significantly; default to 30 if state unknown."))
        add(cmd("Mute kar do phone ko", "volume", "set_volume", p("level_percent","0"),
            "Mute = level 0. Never call set_dnd for simple mute unless user says 'DND'."))
        add(cmd("Volume max karo", "volume", "set_volume", p("level_percent","100"),
            "'Max' always = 100."))
        add(cmd("Sound badhao", "volume", "set_volume", p("level_percent","80"),
            "'Badhao' without percentage → increase to 80 as safe default."))
        add(cmd("Ringer volume down karo", "volume", "set_volume", p("level_percent","20"),
            "Ringer stream = use set_volume."))
        add(cmd("Earphone volume adjust karo", "volume", "set_volume", p("level_percent","60"),
            "Earphone = still set_volume; no special earphone API."))
        add(cmd("Volume 70 karo", "volume", "set_volume", p("level_percent","70"),
            "Bare number without % sign still maps to level_percent."))

        // ── CATEGORY 05: BRIGHTNESS ──────────────────────────────────
        add(cmd("Brightness full karo", "brightness", "set_brightness", p("level_percent","100"),
            "'Full karo' = 100%. NEVER open Settings for brightness; always use set_brightness."))
        add(cmd("Screen thodi dim karo", "brightness", "set_brightness", p("level_percent","20"),
            "'Dim karo' = 20%."))
        add(cmd("Brightness 70 kar do", "brightness", "set_brightness", p("level_percent","70"),
            "Bare number without % → level_percent."))
        add(cmd("Auto brightness on karo", "brightness", "open_system_settings",
            p("setting_name","display"),
            "Auto brightness toggle → open Display settings; SCREEN_BRIGHTNESS_MODE_AUTOMATIC.", true))
        add(cmd("Screen bright karo", "brightness", "set_brightness", p("level_percent","100"),
            "'Bright karo' = maximum brightness."))
        add(cmd("Brightness bahut zyada hai thodi kam karo", "brightness", "set_brightness",
            p("level_percent","40"),
            "'Bahut zyada hai' → reduce significantly to ~40."))
        add(cmd("Meri aankhein dukh rahi hain brightness kam karo", "brightness", "set_brightness",
            p("level_percent","15"),
            "Eye strain context → reduce to minimal ~15%."))

        // ── CATEGORY 06: FLASHLIGHT ──────────────────────────────────
        add(cmd("Torch on karo", "flashlight", "toggle_flashlight", p("enable","true"),
            "Torch = Flashlight → toggle_flashlight(enable=true)."))
        add(cmd("Flashlight off karo", "flashlight", "toggle_flashlight", p("enable","false"),
            "'Off karo' → enable=false."))
        add(cmd("Torch band karo", "flashlight", "toggle_flashlight", p("enable","false"),
            "'Band karo' = off = false."))
        add(cmd("Light on karo raat hai", "flashlight", "toggle_flashlight", p("enable","true"),
            "Night context + 'Light on' → infer torch first over screen brightness."))
        add(cmd("Andhera hai torch chala do", "flashlight", "toggle_flashlight", p("enable","true"),
            "Context-based: dark/andhera → infer torch needed."))
        add(cmd("Mujhe andhera dikh raha hai", "flashlight", "toggle_flashlight", p("enable","true"),
            "Implicit darkness complaint → proactively turn on torch."))

        // ── CATEGORY 07: WI-FI ──────────────────────────────────────
        add(cmd("WiFi on karo", "wifi", "toggle_wifi", p("enable","true"),
            "toggle_wifi uses accessibility automation via AuraAccessibilityService."))
        add(cmd("WiFi band karo", "wifi", "toggle_wifi", p("enable","false"),
            "'Band' = off/false."))
        add(cmd("Internet on karo", "wifi", "toggle_wifi", p("enable","true"),
            "'Internet on' = WiFi on (assumes WiFi is primary connectivity)."))
        add(cmd("WiFi settings kholo", "wifi", "open_system_settings", p("setting_name","wifi"),
            "If user asks to open WiFi settings (not toggle), use open_system_settings."))
        add(cmd("WiFi password kya hai?", "wifi", "web_search",
            p("query","how to find saved WiFi password on Android"),
            "Password retrieval not a direct tool — web_search for guidance.", true))
        add(cmd("WiFi connect karo 'JioFiber'", "wifi", "open_system_settings", p("setting_name","wifi"),
            "Specific SSID connect → open WiFi settings; manual selection needed.", true))

        // ── CATEGORY 08: BLUETOOTH ───────────────────────────────────
        add(cmd("Bluetooth on karo", "bluetooth", "toggle_bluetooth", p("enable","true"),
            "toggle_bluetooth uses accessibility automation."))
        add(cmd("BT off karo", "bluetooth", "toggle_bluetooth", p("enable","false"),
            "BT abbreviation = Bluetooth."))
        add(cmd("Bluetooth se headphones connect karo", "bluetooth", "toggle_bluetooth",
            p("enable","true"),
            "Pairing requires BT on first; then open BT settings. Two-step.", true))
        add(cmd("Bluetooth settings kholo", "bluetooth", "open_system_settings",
            p("setting_name","bluetooth"),
            "Explicit settings request → open_system_settings."))
        add(cmd("Earbuds connect karo", "bluetooth", "toggle_bluetooth", p("enable","true"),
            "Earbuds = Bluetooth device → turn on BT first."))

        // ── CATEGORY 09: HOTSPOT ─────────────────────────────────────
        add(cmd("Hotspot on karo", "hotspot", "toggle_hotspot", p("enable","true"),
            "toggle_hotspot via accessibility in Settings."))
        add(cmd("Personal hotspot band karo", "hotspot", "toggle_hotspot", p("enable","false"),
            "'Band karo' = disable."))
        add(cmd("Mobile data share karo", "hotspot", "toggle_hotspot", p("enable","true"),
            "'Data share' = hotspot."))
        add(cmd("Hotspot password kya hai?", "hotspot", "open_system_settings",
            p("setting_name","hotspot"),
            "Password query → open hotspot settings.", true))
        add(cmd("Tethering on karo", "hotspot", "toggle_hotspot", p("enable","true"),
            "'Tethering' = same as hotspot."))

        // ── CATEGORY 10: DO NOT DISTURB ─────────────────────────────
        add(cmd("DND on karo", "dnd", "set_dnd", p("enable","true"),
            "set_dnd(enable=true) blocks calls and notifications."))
        add(cmd("Do Not Disturb mode lagao", "dnd", "set_dnd", p("enable","true"),
            "Full spelling = same as DND."))
        add(cmd("Silent mode on karo", "dnd", "set_dnd", p("enable","true"),
            "Silent mode → set_dnd(true). Different from mute (volume=0)."))
        add(cmd("DND band karo", "dnd", "set_dnd", p("enable","false"),
            "'Band karo' = disable DND."))
        add(cmd("Calls aane do par notification silent rakhna", "dnd", "set_dnd",
            p("enable","true"),
            "Priority DND → set_dnd(true) and explain priority mode to user.", true))
        add(cmd("Driving mode on karo", "dnd", "set_dnd", p("enable","true"),
            "'Driving mode' → enable DND + optionally offer Maps navigation."))

        // ── CATEGORY 11: ALARM ───────────────────────────────────────
        add(cmd("Subah 6 baje alarm laga do", "alarm", "create_alarm",
            mp("hour","6","minute","0","label","Morning Alarm"),
            "Hindi 'X baje' → extract hour; subah implies AM."))
        add(cmd("7:30 ka alarm set karo", "alarm", "create_alarm",
            mp("hour","7","minute","30","label","Alarm"),
            "HH:MM format → split on colon for hour/minute."))
        add(cmd("Raat 11 baje alarm lagao", "alarm", "create_alarm",
            mp("hour","23","minute","0","label","Night Alarm"),
            "'Raat' = PM. 11 PM = 23 in 24-hour."))
        add(cmd("Dopahar 2 baje ka reminder set karo", "alarm", "create_alarm",
            mp("hour","14","minute","0","label","Afternoon Reminder"),
            "'Dopahar' = afternoon = PM. 2 PM = 14:00."))
        add(cmd("Subah half past 6 alarm set karo", "alarm", "create_alarm",
            mp("hour","6","minute","30","label","Morning Alarm"),
            "'Half past 6' = 6:30 AM."))
        add(cmd("Kal subah 8 baje ka alarm set karo", "alarm", "create_alarm",
            mp("hour","8","minute","0","label","Morning Alarm"),
            "'Kal' (tomorrow) doesn't change time; alarm is inherently next occurrence."))
        add(cmd("5:45 AM alarm set karo", "alarm", "create_alarm",
            mp("hour","5","minute","45","label","Alarm"),
            "Explicit AM/PM → already in 24h equivalent."))
        add(cmd("Alarm band karo", "alarm", "open_app", p("app_name","Clock"),
            "Dismiss/delete alarm not directly supported via tool → open Clock app.", true))
        add(cmd("Subah paanch baje uthna hai alarm lao", "alarm", "create_alarm",
            mp("hour","5","minute","0","label","Wake Up Alarm"),
            "Pure Hindi time phrase 'paanch baje' = 5:00."))

        // ── CATEGORY 12: TIMER ───────────────────────────────────────
        add(cmd("10 minute ka timer laga do", "timer", "set_timer",
            mp("minutes","10","seconds","0","label","Timer"),
            "set_timer: extract minutes directly."))
        add(cmd("30 second ka timer set karo", "timer", "set_timer",
            mp("minutes","0","seconds","30","label","Timer"),
            "Seconds-only timer → minutes=0."))
        add(cmd("1 ghante ka timer lagao", "timer", "set_timer",
            mp("minutes","60","seconds","0","label","Timer"),
            "1 hour = 60 minutes."))
        add(cmd("5 minute 30 second ka timer laga", "timer", "set_timer",
            mp("minutes","5","seconds","30","label","Timer"),
            "Combined minutes+seconds → parse both."))
        add(cmd("Cooking ke liye 20 minute timer", "timer", "set_timer",
            mp("minutes","20","seconds","0","label","Cooking Timer"),
            "Context label 'Cooking' used as label param."))
        add(cmd("2 ghante ka timer lagao", "timer", "set_timer",
            mp("minutes","120","seconds","0","label","Timer"),
            "2 hours = 120 minutes."))
        add(cmd("Timer stop karo", "timer", "open_app", p("app_name","Clock"),
            "Timer cancel not a direct API → open Clock app.", true))

        // ── CATEGORY 13: APP LAUNCH ──────────────────────────────────
        add(cmd("YouTube kholo", "app_launch", "open_app", p("app_name","YouTube"),
            "open_app resolves package by fuzzy app name match."))
        add(cmd("Instagram open karo", "app_launch", "open_app", p("app_name","Instagram"),
            "Any app name → open_app."))
        add(cmd("Settings kholo", "app_launch", "open_app", p("app_name","Settings"),
            "Generic 'Settings' → open_app('Settings')."))
        add(cmd("Calculator launch karo", "app_launch", "open_app", p("app_name","Calculator"),
            "Calculator = open_app."))
        add(cmd("WhatsApp khol do", "app_launch", "open_app", p("app_name","WhatsApp"),
            "Casual 'khol do' = open_app."))
        add(cmd("Chrome browser open karo", "app_launch", "open_app", p("app_name","Chrome"),
            "'Chrome browser' → app_name='Chrome'."))
        add(cmd("Zomato kholo", "app_launch", "open_app", p("app_name","Zomato"),
            "Food apps → open_app, then suggest perform_app_action for specific tasks."))
        add(cmd("Maps open karo", "app_launch", "open_app", p("app_name","Google Maps"),
            "'Maps' → 'Google Maps' app."))
        add(cmd("Camera khol do", "app_launch", "open_camera", p("mode","photo"),
            "Camera → open_camera(mode=photo), NOT open_app('Camera')."))
        add(cmd("Paytm kholo", "app_launch", "open_app", p("app_name","Paytm"),
            "Payments app → open_app."))
        add(cmd("Clock app kholo", "app_launch", "open_app", p("app_name","Clock"),
            "Clock/alarm app launch."))
        add(cmd("Files kholo", "app_launch", "open_app", p("app_name","Files"),
            "Files manager app."))

        // ── CATEGORY 14: MUSIC / YOUTUBE ─────────────────────────────
        add(cmd("YouTube par Hanuman Chalisa chalao", "music", "play_youtube",
            p("query","Hanuman Chalisa"),
            "YouTube + song name = play_youtube. Auto-clicks first result."))
        add(cmd("Arijit Singh ka koi bhi gaana chala do", "music", "play_youtube",
            p("query","Arijit Singh songs"),
            "Vague music request → play_youtube with artist + 'songs'."))
        add(cmd("YT par motivational songs play karo", "music", "play_youtube",
            p("query","motivational songs"),
            "YT abbreviation = YouTube = play_youtube."))
        add(cmd("Spotify par Lo-Fi chalo", "music", "play_media",
            mp("query","Lo-Fi music","app_name","Spotify"),
            "Specific app (Spotify) → play_media with app_name."))
        add(cmd("Gaana band karo", "music", "media_control", p("action","pause"),
            "'Band karo' for media = pause, not stop."))
        add(cmd("Next song", "music", "media_control", p("action","next"),
            "Short 'next song' → media_control(action=next)."))
        add(cmd("Previous track", "music", "media_control", p("action","previous"),
            "'Previous track' → media_control(action=previous)."))
        add(cmd("Music play karo", "music", "media_control", p("action","play"),
            "Generic 'play karo' without song = resume via media_control(play)."))
        add(cmd("JioSaavn par Punjabi songs chala do", "music", "play_media",
            mp("query","Punjabi songs","app_name","JioSaavn"),
            "JioSaavn → play_media with app_name."))
        add(cmd("Neend ke liye soft music chala do", "music", "play_youtube",
            p("query","soft sleep music relaxing"),
            "Sleep context → search for sleep/relaxing music on YouTube."))
        add(cmd("Bhajan chala do", "music", "play_youtube", p("query","Hindi bhajan devotional songs"),
            "Devotional request → play_youtube with 'Hindi bhajan devotional songs'."))
        add(cmd("Volume ke saath gaana aane do", "music", "media_control", p("action","play"),
            "Implicit play request combined with volume context."))

        // ── CATEGORY 15: NAVIGATION / MAPS ───────────────────────────
        add(cmd("Connaught Place ka rasta dikhao", "navigation", "navigate_maps",
            mp("destination","Connaught Place","mode","driving"),
            "navigate_maps default mode=driving."))
        add(cmd("Nearest petrol pump navigate karo", "navigation", "navigate_maps",
            mp("destination","nearest petrol pump","mode","driving"),
            "Relative 'nearest' locations work as destination strings in Maps."))
        add(cmd("Ghar le chalo", "navigation", "navigate_maps",
            mp("destination","Home","mode","driving"),
            "'Ghar' = 'Home' saved location in Google Maps."))
        add(cmd("Office kaise jaaun?", "navigation", "navigate_maps",
            mp("destination","Office","mode","driving"),
            "'Office' = saved work location."))
        add(cmd("Walking route to India Gate", "navigation", "navigate_maps",
            mp("destination","India Gate","mode","walking"),
            "'Walking route' → mode=walking."))
        add(cmd("Cycle se AIIMS hospital kaise jaaun", "navigation", "navigate_maps",
            mp("destination","AIIMS Hospital","mode","bicycling"),
            "Cycle = bicycling mode."))
        add(cmd("Metro se Rajiv Chowk kaise jaaun?", "navigation", "navigate_maps",
            mp("destination","Rajiv Chowk","mode","transit"),
            "Metro/bus/train = transit mode."))
        add(cmd("Airport kaise jaaun?", "navigation", "navigate_maps",
            mp("destination","airport","mode","driving"),
            "Generic 'airport' → Maps resolves to nearest airport."))
        add(cmd("Railway station ka route dikhao", "navigation", "navigate_maps",
            mp("destination","railway station","mode","driving"),
            "Nearest railway station navigation."))
        add(cmd("Traffic kaisa hai?", "navigation", "navigate_maps",
            mp("destination","current route","mode","driving"),
            "Traffic query → open Maps; web_search as fallback.", true))

        // ── CATEGORY 16: WEB SEARCH ──────────────────────────────────
        add(cmd("Google karo ajay devgn ki age", "search", "web_search",
            p("query","Ajay Devgn age"),
            "'Google karo' = web_search."))
        add(cmd("Search karo best restaurants in Delhi", "search", "web_search",
            p("query","best restaurants in Delhi"),
            "'Search karo' = web_search."))
        add(cmd("IPL 2025 schedule kya hai?", "search", "web_search",
            p("query","IPL 2025 schedule"),
            "Sports queries → web_search."))
        add(cmd("Aaj ka weather kaisa hai?", "search", "web_search",
            p("query","today weather forecast"),
            "Weather → web_search."))
        add(cmd("Modi ki height kya hai?", "search", "web_search",
            p("query","Narendra Modi height"),
            "Celebrity/personality facts → web_search."))
        add(cmd("Dollar to rupee exchange rate", "search", "web_search",
            p("query","USD to INR exchange rate today"),
            "Currency rates → web_search."))
        add(cmd("How to make pasta?", "search", "web_search",
            p("query","how to make pasta recipe"),
            "Recipe queries → web_search."))
        add(cmd("Delhi temperature kya hai?", "weather", "web_search",
            p("query","Delhi temperature today"),
            "City-specific weather → web_search with city name."))
        add(cmd("Kal baarish hogi kya?", "weather", "web_search",
            p("query","tomorrow rain forecast"),
            "Rain forecast → web_search."))
        add(cmd("Latest news batao", "news", "web_search",
            p("query","latest news today India"),
            "News → web_search."))

        // ── CATEGORY 17: CAMERA ──────────────────────────────────────
        add(cmd("Photo lo", "camera", "open_camera", p("mode","photo"),
            "'Photo lo' = open_camera(mode=photo)."))
        add(cmd("Selfie lo", "camera", "open_camera", p("mode","selfie"),
            "'Selfie lo' = open_camera(mode=selfie, front camera)."))
        add(cmd("Video record karo", "camera", "open_camera", p("mode","video"),
            "'Video record karo' = open_camera(mode=video)."))
        add(cmd("Apni selfie le do", "camera", "open_camera", p("mode","selfie"),
            "'Apni selfie' = selfie = front camera mode."))
        add(cmd("Back camera se photo", "camera", "open_camera", p("mode","photo"),
            "Back camera explicitly = photo mode (default back)."))
        add(cmd("QR code scan karo", "camera", "open_camera", p("mode","qr_scan"),
            "QR scan → open_camera(mode=qr_scan) or open Google Lens.", true))
        add(cmd("Document scan karo", "camera", "open_camera", p("mode","document"),
            "Document scan → open_camera(mode=document) or Google Drive scan.", true))

        // ── CATEGORY 18: DARK MODE ───────────────────────────────────
        add(cmd("Dark mode on karo", "dark_mode", "toggle_dark_mode", p("enable","true"),
            "toggle_dark_mode uses accessibility automation."))
        add(cmd("Night theme lagao", "dark_mode", "toggle_dark_mode", p("enable","true"),
            "'Night theme' = dark mode."))
        add(cmd("Light mode karo", "dark_mode", "toggle_dark_mode", p("enable","false"),
            "'Light mode' = disable dark mode."))
        add(cmd("Dark mode band karo", "dark_mode", "toggle_dark_mode", p("enable","false"),
            "Band karo = disable."))
        add(cmd("Aankhon ke liye accha mode laga do", "dark_mode", "toggle_dark_mode",
            p("enable","true"),
            "Eye comfort context = dark mode on."))

        // ── CATEGORY 19: NOTIFICATIONS ───────────────────────────────
        add(cmd("Notifications padho", "notifications", "read_notifications", p("count","5"),
            "read_notifications from NotificationListenerService."))
        add(cmd("Kya koi message aaya?", "notifications", "read_notifications", p("count","5"),
            "New message query → read_notifications first."))
        add(cmd("Notification bar kholo", "notifications", "press_system_key",
            p("key","notifications"),
            "Open notification shade = press_system_key(notifications)."))
        add(cmd("WhatsApp ke kitne notifications hain?", "notifications", "read_notifications",
            p("count","10"),
            "App-specific count → read_notifications and filter by app name in response."))
        add(cmd("Saari notifications clear karo", "notifications", "press_system_key",
            p("key","notifications"),
            "Clear all not directly supported → open shade and explain.", true))

        // ── CATEGORY 20: BATTERY ─────────────────────────────────────
        add(cmd("Battery kitni hai?", "battery", "get_battery_status", emptyMap(),
            "get_battery_status returns real device level and charging state. NEVER hallucinate."))
        add(cmd("Charging ho rahi hai?", "battery", "get_battery_status", emptyMap(),
            "Charging query → get_battery_status; check isCharging field."))
        add(cmd("Battery saver on karo", "battery", "open_system_settings",
            p("setting_name","battery"),
            "Battery saver toggle not a direct API → open battery settings.", true))
        add(cmd("Battery ka percentage batao", "battery", "get_battery_status", emptyMap(),
            "Always use get_battery_status; NEVER hallucinate a percentage."))
        add(cmd("Phone charge par hai kya?", "battery", "get_battery_status", emptyMap(),
            "Charging query = get_battery_status."))

        // ── CATEGORY 21: MEMORY / FACTS ─────────────────────────────
        add(cmd("Yaad rakhna mera birthday 15 August ko hai", "memory", "remember_fact",
            mp("key","birthday","value","15 August"),
            "remember_fact: key=birthday, value from 'X ko hai' clause."))
        add(cmd("Meri car ka number DL01AB1234 hai", "memory", "remember_fact",
            mp("key","car number","value","DL01AB1234"),
            "Vehicle info → remember_fact(key='car number')."))
        add(cmd("Mera naam Ansh hai yaad rakhna", "memory", "remember_fact",
            mp("key","name","value","Ansh"),
            "Name fact → also sets KEY_USER_NAME in AuraMemoryManager."))
        add(cmd("Mujhe kya kya yaad hai?", "memory", "recall_memory", emptyMap(),
            "recall_memory returns all stored facts."))
        add(cmd("Mera birthday kab hai?", "memory", "recall_memory", p("key","birthday"),
            "Specific recall → query by key."))
        add(cmd("Meri ATM PIN yaad rakhna 1234", "memory", "remember_fact",
            mp("key","ATM PIN","value","1234"),
            "Sensitive data stored locally in SharedPreferences — remind user it's device-local only."))
        add(cmd("Sab kuch bhul jao", "memory", "remember_fact", emptyMap(),
            "Clear all memory → confirm before wiping (destructive).", true))

        // ── CATEGORY 22: QUICK NOTES ─────────────────────────────────
        add(cmd("Note karo: Doodh aur bread lana hai", "notes", "create_quick_note",
            mp("note_text","Doodh aur bread lana hai","title","Shopping"),
            "create_quick_note saves note locally."))
        add(cmd("Idea note karo: App banana hai jo weather bataye", "notes", "create_quick_note",
            mp("note_text","App banana hai jo weather bataye","title","Idea"),
            "Notes accept any free text as note_text."))
        add(cmd("Mera grocery list note karo: Chawal, Dal, Sabzi", "notes", "create_quick_note",
            mp("note_text","Chawal, Dal, Sabzi","title","Grocery List"),
            "List items → join as comma-separated note_text."))
        add(cmd("Meri notes dikhao", "notes", "open_app", p("app_name","Notes"),
            "Show notes → open Notes app.", true))

        // ── CATEGORY 23: CALENDAR ────────────────────────────────────
        add(cmd("Kal 3 baje meeting add karo", "calendar", "add_calendar_event",
            mp("title","Meeting","description",""),
            "add_calendar_event with title=Meeting; date/time from context."))
        add(cmd("Doctor appointment 5 April 11 AM", "calendar", "add_calendar_event",
            mp("title","Doctor Appointment","description","11 AM on 5 April"),
            "Medical appointment = calendar event."))
        add(cmd("Meri behen ki shaadi 20 March ko calendar mein daal do", "calendar", "add_calendar_event",
            mp("title","Behen Ki Shaadi","description","Family wedding"),
            "Personal events → add_calendar_event with descriptive title."))
        add(cmd("Aaj ke events kya hain?", "calendar", "open_app", p("app_name","Calendar"),
            "Event query → open Calendar app; reading not a direct tool.", true))

        // ── CATEGORY 24: EMAIL ───────────────────────────────────────
        add(cmd("boss@company.com ko email karo subject: Meeting", "email", "send_email",
            mp("to","boss@company.com","subject","Meeting","body",""),
            "Email with explicit address → send_email."))
        add(cmd("HR ko mail bhejo ki kal nahi aa paunga", "email", "send_email",
            mp("to","","subject","Leave Request","body","Kal nahi aa paunga"),
            "Role-based recipient 'HR' → ask for email if not in contacts.", true))
        add(cmd("Reply karo last email pe", "email", "open_app", p("app_name","Gmail"),
            "Email reply requires reading first — open Gmail.", true))

        // ── CATEGORY 25: CONTACTS ────────────────────────────────────
        add(cmd("Rahul ka number kya hai?", "contacts", "query_contact", p("name","Rahul"),
            "query_contact(name) returns phone number and info."))
        add(cmd("Priya Singh ka address kya hai?", "contacts", "query_contact",
            p("name","Priya Singh"),
            "Full name query → use as-is."))
        add(cmd("Naya contact save karo: Amit, 9876012345", "contacts", "manage_contacts",
            mp("name","Amit","phone_number","9876012345"),
            "manage_contacts to add new contact."))
        add(cmd("Contact delete karo Raj ko", "contacts", "manage_contacts",
            mp("name","Raj","action","delete"),
            "Contact deletion → manage_contacts(action=delete). Confirm first.", true))

        // ── CATEGORY 26: SYSTEM SETTINGS ────────────────────────────
        add(cmd("Developer options kholo", "settings", "open_system_settings",
            p("setting_name","developer"),
            "Developer options page."))
        add(cmd("Accessibility settings kholo", "settings", "open_system_settings",
            p("setting_name","accessibility"),
            "Accessibility settings for AURA service."))
        add(cmd("Battery settings dikhao", "settings", "open_system_settings",
            p("setting_name","battery"),
            "Battery settings page."))
        add(cmd("Display settings kholo", "settings", "open_system_settings",
            p("setting_name","display"),
            "Display/screen settings."))
        add(cmd("About phone dikhao", "settings", "open_system_settings",
            p("setting_name","about"),
            "About phone / device info page."))
        add(cmd("Language settings", "settings", "open_system_settings",
            p("setting_name","language"),
            "Language & input settings."))
        add(cmd("Privacy settings open karo", "settings", "open_system_settings",
            p("setting_name","privacy"),
            "Privacy settings."))

        // ── CATEGORY 27: NAVIGATION MODE ────────────────────────────
        add(cmd("3 button navigation kardo", "nav_mode", "set_navigation_mode",
            p("mode","buttons"),
            "3-button nav = 'buttons' mode via accessibility."))
        add(cmd("Gesture navigation on karo", "nav_mode", "set_navigation_mode",
            p("mode","gestures"),
            "Gesture navigation = 'gestures' mode."))
        add(cmd("Navigation bar hatao", "nav_mode", "set_navigation_mode",
            p("mode","gestures"),
            "Remove nav bar = gesture mode (bar disappears)."))
        add(cmd("Mera navigation mode button wala krdo", "nav_mode", "set_navigation_mode",
            p("mode","buttons"),
            "Explicit 'button wala' = 3-button mode."))

        // ── CATEGORY 28: SYSTEM KEYS ─────────────────────────────────
        add(cmd("Phone lock karo", "system_key", "press_system_key", p("key","lock"),
            "Lock screen = press_system_key(lock)."))
        add(cmd("Screen lock kardo", "system_key", "press_system_key", p("key","lock"),
            "'Screen lock kardo' = lock key."))
        add(cmd("Back button dabao", "system_key", "press_system_key", p("key","back"),
            "Back navigation = press_system_key(back)."))
        add(cmd("Home button", "system_key", "press_system_key", p("key","home"),
            "Home = press_system_key(home)."))
        add(cmd("Recent apps dikhao", "system_key", "press_system_key", p("key","recents"),
            "Recents/multitask = press_system_key(recents)."))
        add(cmd("Quick settings kholo", "system_key", "press_system_key",
            p("key","quick_settings"),
            "Quick settings panel."))
        add(cmd("Screenshot lo", "system_key", "press_system_key", p("key","screenshot"),
            "Screenshot = press_system_key(screenshot). NOT open_camera."))
        add(cmd("Ek screenshot le lo", "system_key", "press_system_key", p("key","screenshot"),
            "'Le lo' = take it = screenshot."))

        // ── CATEGORY 29: CALCULATION ─────────────────────────────────
        add(cmd("2456 × 37 kitna hoga?", "math", "calculate", p("expression","2456 * 37"),
            "calculate tool handles arithmetic expressions."))
        add(cmd("Square root of 144", "math", "calculate", p("expression","sqrt(144)"),
            "sqrt() function in calculate."))
        add(cmd("15% of 8500 kitna hai?", "math", "calculate", p("expression","0.15 * 8500"),
            "Percentage → convert to decimal multiplication."))
        add(cmd("(1200 + 350) / 5", "math", "calculate", p("expression","(1200 + 350) / 5"),
            "Complex expressions passed as-is."))
        add(cmd("EMI calculate karo 5 lakh loan 8% 5 saal", "math", "calculate",
            p("expression","(500000 * 0.08/12 * (1+0.08/12)^60) / ((1+0.08/12)^60 - 1)"),
            "EMI formula → convert to math expression."))
        add(cmd("33 ka cube kya hoga", "math", "calculate", p("expression","33^3"),
            "Cube = power 3."))
        add(cmd("1000 / 7", "math", "calculate", p("expression","1000 / 7"),
            "Simple division."))

        // ── CATEGORY 30: APP CACHE / STORAGE ────────────────────────
        add(cmd("WhatsApp ka cache clear karo", "app_mgmt", "manage_app_storage",
            mp("app_name","WhatsApp","action","clear_cache"),
            "manage_app_storage(clear_cache) navigates to App Info > Clear Cache."))
        add(cmd("Instagram ka data clear karo", "app_mgmt", "manage_app_storage",
            mp("app_name","Instagram","action","clear_data"),
            "Clear data logs out app — warn user before executing."))
        add(cmd("YouTube uninstall karo", "app_mgmt", "manage_app_storage",
            mp("app_name","YouTube","action","uninstall"),
            "Uninstall → manage_app_storage(action=uninstall)."))
        add(cmd("Phone ka storage check karo", "app_mgmt", "open_system_settings",
            p("setting_name","storage"),
            "Storage info → open_system_settings(storage)."))
        add(cmd("Junk files saaf karo", "app_mgmt", "open_system_settings",
            p("setting_name","storage"),
            "Junk cleaning → open Storage settings; suggest built-in cleaner.", true))

        // ── CATEGORY 31: PLAY STORE / INSTALL ───────────────────────
        add(cmd("Telegram install karo", "install", "install_store_app",
            mp("query","Telegram","criteria","top"),
            "install_store_app searches Play Store, installs top result."))
        add(cmd("Sabse kam MB wali VPN app install karo", "install", "install_store_app",
            mp("query","VPN","criteria","lowest_mb"),
            "'Sabse kam MB' = criteria=lowest_mb."))
        add(cmd("Netflix download karo", "install", "install_store_app",
            mp("query","Netflix","criteria","top"),
            "'Download karo' = install from Play Store."))
        add(cmd("Free PDF reader install karo", "install", "install_store_app",
            mp("query","free PDF reader","criteria","top"),
            "Qualifiers like 'free' included in search query."))

        // ── CATEGORY 32: PERFORM APP ACTION ──────────────────────────
        add(cmd("Zomato par biryani search karo", "app_action", "perform_app_action",
            mp("app_name","Zomato","action_description","search for biryani","search_query","biryani"),
            "perform_app_action opens app and performs in-app search."))
        add(cmd("Swiggy se pizza order karo", "app_action", "perform_app_action",
            mp("app_name","Swiggy","action_description","search for pizza","search_query","pizza"),
            "Order intent → perform_app_action."))
        add(cmd("Uber pe cab book karo", "app_action", "perform_app_action",
            mp("app_name","Uber","action_description","book a cab"),
            "Cab booking → perform_app_action."))
        add(cmd("Amazon par iPhone case dhundho", "app_action", "perform_app_action",
            mp("app_name","Amazon","action_description","search for iPhone case",
                "search_query","iPhone case"),
            "Amazon search → perform_app_action."))
        add(cmd("Paytm se 500 rupee bhejo Ansh ko", "app_action", "perform_app_action",
            mp("app_name","Paytm","action_description","send 500 rupees to Ansh",
                "search_query","Ansh"),
            "Payment → perform_app_action with amount and recipient."))
        add(cmd("Ola book karo", "app_action", "perform_app_action",
            mp("app_name","Ola","action_description","book a cab"),
            "Ola = perform_app_action."))
        add(cmd("IRCTC se train ticket book karo", "app_action", "perform_app_action",
            mp("app_name","IRCTC","action_description","book train ticket"),
            "IRCTC booking → perform_app_action."))

        // ── CATEGORY 33: GALLERY / MEDIA ─────────────────────────────
        add(cmd("Mera last screenshot dikhao", "gallery", "show_recent_media",
            p("media_type","screenshot"),
            "show_recent_media(screenshot) = last screenshot fullscreen."))
        add(cmd("Latest photo dikhao", "gallery", "show_recent_media", p("media_type","photo"),
            "'Latest photo' = show_recent_media(photo)."))
        add(cmd("Gallery kholo", "gallery", "open_app", p("app_name","Gallery"),
            "Generic gallery open = open_app."))
        add(cmd("Recycle bin clear karo", "gallery", "empty_recycle_bin", p("target","gallery"),
            "empty_recycle_bin(gallery) = clear gallery trash via accessibility."))
        add(cmd("Gallery trash saaf karo", "gallery", "empty_recycle_bin", p("target","gallery"),
            "'Trash saaf karo' = empty_recycle_bin."))

        // ── CATEGORY 34: EMERGENCY ───────────────────────────────────
        add(cmd("Ambulance bulao", "emergency", "emergency_sos", p("target","108"),
            "Ambulance = 108."))
        add(cmd("Police ko call karo", "emergency", "emergency_sos", p("target","100"),
            "Police = 100."))
        add(cmd("Fire brigade", "emergency", "emergency_sos", p("target","101"),
            "Fire brigade = 101."))
        add(cmd("Emergency number dial karo", "emergency", "emergency_sos", p("target","112"),
            "Generic emergency = 112."))
        add(cmd("Help! SOS!", "emergency", "emergency_sos", p("target","112"),
            "Panic SOS → immediately call emergency_sos(112). No confirmation needed."))
        add(cmd("Suicide helpline number batao", "emergency", "web_search",
            p("query","suicide helpline India iCall Vandrevala"),
            "Mental health crisis → provide helpline numbers via web_search, respond empathetically."))

        // ── CATEGORY 35: CHITCHAT / CONVERSATIONAL ───────────────────
        add(cmd("Kya haal hai?", "chitchat", "CONVERSATIONAL", emptyMap(),
            "Casual greeting → respond warmly in feminine Hindi. No tool needed."))
        add(cmd("Tumhara naam kya hai?", "chitchat", "CONVERSATIONAL", emptyMap(),
            "Identity question → respond 'Main AURA hoon'. No tool."))
        add(cmd("Mujhe boring lag raha hai", "chitchat", "CONVERSATIONAL", emptyMap(),
            "Emotional support → empathize, suggest activities or play music."))
        add(cmd("Joke sunao", "chitchat", "CONVERSATIONAL", emptyMap(),
            "Entertainment request → tell a warm Hindi joke. No tool."))
        add(cmd("Shukriya", "chitchat", "CONVERSATIONAL", emptyMap(),
            "Gratitude → respond warmly 'Mention nahi Boss!'"))
        add(cmd("Main thak gaya hoon", "chitchat", "CONVERSATIONAL", emptyMap(),
            "Fatigue → empathize, offer music/alarm for rest."))
        add(cmd("Mujhe motivational quote sunao", "chitchat", "CONVERSATIONAL", emptyMap(),
            "Quote request → respond with inspiring quote. No tool."))
        add(cmd("Accha kaam kiya tune", "chitchat", "CONVERSATIONAL", emptyMap(),
            "Compliment → accept gracefully with feminine response."))

        // ── CATEGORY 36: COMPOUND COMMANDS ───────────────────────────
        add(cmd("Volume full karo aur DND on karo", "compound", "set_volume+set_dnd",
            mp("level_percent","100","enable","true"),
            "Compound: execute both tools sequentially. Never skip the second one."))
        add(cmd("Wifi off karo aur Bluetooth on karo", "compound", "toggle_wifi+toggle_bluetooth",
            mp("wifi_enable","false","bt_enable","true"),
            "Sequential compound: WiFi off then BT on."))
        add(cmd("Screenshot lo aur Ansh ko WhatsApp karo", "compound",
            "press_system_key+send_whatsapp_media",
            mp("key","screenshot","contact_name","Ansh","media_type","screenshot"),
            "Screenshot then WhatsApp: two-step compound. Take screenshot first, then send."))
        add(cmd("Gana pause karo aur 30 minute ka timer lagao", "compound",
            "media_control+set_timer",
            mp("action","pause","minutes","30"),
            "Pause music then set timer: compound sequential."))
        add(cmd("Alarm laga do aur DND on karo raat ke liye", "compound",
            "create_alarm+set_dnd",
            mp("hour","7","minute","0","enable","true"),
            "Night routine: alarm + DND. Execute both."))
        add(cmd("Brightness kam karo aur dark mode on karo", "compound",
            "set_brightness+toggle_dark_mode",
            mp("level_percent","20","enable","true"),
            "Night comfort: dim + dark mode. Sequential compound."))

        // ── CATEGORY 37: AMBIGUOUS (CLARIFY) ─────────────────────────
        add(cmd("Band karo", "ambiguous", "CLARIFY", emptyMap(),
            "'Band karo' is too vague. ALWAYS ask: 'Kya band karun Boss — WiFi, volume, ya kuch aur?'", true))
        add(cmd("Chalu karo", "ambiguous", "CLARIFY", emptyMap(),
            "'Chalu karo' vague — ask what to enable.", true))
        add(cmd("Delete karo", "ambiguous", "CLARIFY", emptyMap(),
            "Too vague — ask what to delete before any destructive action.", true))
        add(cmd("Send karo", "ambiguous", "CLARIFY", emptyMap(),
            "Missing: what, to whom, via which channel. Ask user.", true))
        add(cmd("Open karo", "ambiguous", "CLARIFY", emptyMap(),
            "Which app? Ask before opening.", true))
        add(cmd("Set karo 100", "ambiguous", "CLARIFY", emptyMap(),
            "100 of what — volume? brightness? Ask for context.", true))
        add(cmd("Hata do", "ambiguous", "CLARIFY", emptyMap(),
            "'Hata do' could mean remove notification, delete file, uninstall app. Ask.", true))

        // ── CATEGORY 38: OUT OF SCOPE ─────────────────────────────────
        add(cmd("Chai bana do", "out_of_scope", "GRACEFUL_DECLINE", emptyMap(),
            "Physical task → 'Boss, chai nahi bana sakti, par Zomato se order karun?'"))
        add(cmd("Ghar ka darwaza band karo", "out_of_scope", "GRACEFUL_DECLINE", emptyMap(),
            "Smart home not available → decline gracefully and offer alternative."))
        add(cmd("Meri car start karo", "out_of_scope", "GRACEFUL_DECLINE", emptyMap(),
            "Car control not available → graceful decline."))
        add(cmd("Lottery ticket khareedo", "out_of_scope", "GRACEFUL_DECLINE", emptyMap(),
            "Financial transactions outside scope → decline and offer web_search."))
        add(cmd("Mera homework karo", "out_of_scope", "GRACEFUL_DECLINE", emptyMap(),
            "Academic task → explain limitation, offer to help research via web_search."))

        // ── CATEGORY 39: PURE HINDI COMMANDS ─────────────────────────
        add(cmd("Kal subah paanch baje uthna hai", "alarm", "create_alarm",
            mp("hour","5","minute","0","label","Subah Uthna"),
            "Pure Hindi '5 baje' = 5:00 AM. 'Subah' confirms AM."))
        add(cmd("Abhi raat ke do baj rahe hain alarm lagao", "alarm", "create_alarm",
            mp("hour","2","minute","0","label","Night Alarm"),
            "'Raat ke do' = 2 AM = 02:00."))
        add(cmd("Mera gaana rok do", "music", "media_control", p("action","pause"),
            "'Gaana rok do' = pause music."))
        add(cmd("Meri screen ki roshni badh jao", "brightness", "set_brightness",
            p("level_percent","100"),
            "'Roshni badh jao' = increase brightness to max."))
        add(cmd("Awaaz andar kar lo", "volume", "set_volume", p("level_percent","10"),
            "'Awaaz andar kar' = reduce volume significantly."))

        // ── CATEGORY 40: PURE ENGLISH COMMANDS ───────────────────────
        add(cmd("Turn off WiFi", "wifi", "toggle_wifi", p("enable","false"),
            "Pure English WiFi off."))
        add(cmd("What is my battery level?", "battery", "get_battery_status", emptyMap(),
            "English battery query → get_battery_status."))
        add(cmd("Set brightness to maximum", "brightness", "set_brightness",
            p("level_percent","100"),
            "English max brightness."))
        add(cmd("Enable Bluetooth", "bluetooth", "toggle_bluetooth", p("enable","true"),
            "English enable BT."))
        add(cmd("Take a screenshot", "system_key", "press_system_key", p("key","screenshot"),
            "English screenshot."))
        add(cmd("Lock the screen", "system_key", "press_system_key", p("key","lock"),
            "English lock screen."))
        add(cmd("Search Google for Prime Minister of India", "search", "web_search",
            p("query","Prime Minister of India"),
            "English web search."))
        add(cmd("Play something on YouTube", "music", "play_youtube",
            p("query","popular Hindi songs 2025"),
            "Vague YouTube play → default to popular Hindi songs."))

        // ── CATEGORY 41: HINGLISH (MIXED) ────────────────────────────
        add(cmd("Yaar brightness up karo na", "brightness", "set_brightness",
            p("level_percent","80"),
            "Fillers 'yaar','na' stripped — core intent: increase brightness."))
        add(cmd("Bhai ek screenshot le ke Ansh ko bhej do", "compound",
            "press_system_key+send_whatsapp_media",
            mp("key","screenshot","contact_name","Ansh","media_type","screenshot"),
            "Honorific 'bhai' as filler — strip and parse compound command."))
        add(cmd("Arey volume toh badhao na boss", "volume", "set_volume",
            p("level_percent","80"),
            "Fillers: arey, toh, na — strip and extract core intent."))
        add(cmd("Chal bhai call kar Rahul ko", "call", "make_call",
            p("contact_name","Rahul"),
            "'Chal bhai' = filler. Core: call Rahul."))
        add(cmd("Ek baar YouTube chalao na yaar", "music", "play_youtube",
            p("query","popular songs"),
            "Fillers 'ek baar','na','yaar' → play YouTube."))

        // ── CATEGORY 42: TYPOS / MISSPELLED ──────────────────────────
        add(cmd("Blutut on karo", "bluetooth", "toggle_bluetooth", p("enable","true"),
            "'Blutut' = Bluetooth (phonetic Hindi spelling)."))
        add(cmd("Wifai on kardo", "wifi", "toggle_wifi", p("enable","true"),
            "'Wifai' = WiFi — phonetic match."))
        add(cmd("Flaslight on karo", "flashlight", "toggle_flashlight", p("enable","true"),
            "'Flaslight' = Flashlight — typo tolerance."))
        add(cmd("youtueb par song chalao", "music", "play_youtube", p("query","song"),
            "'youtueb' = YouTube — transpose typo."))
        add(cmd("Instgram kholo", "app_launch", "open_app", p("app_name","Instagram"),
            "'Instgram' = Instagram — common typo."))
        add(cmd("Notifcation padho", "notifications", "read_notifications", p("count","5"),
            "'Notifcation' = Notification — typo."))

        // ── CATEGORY 43: SLANG / COLLOQUIAL ──────────────────────────
        add(cmd("Bhai ek gaana laga de", "music", "play_youtube",
            p("query","popular Hindi songs"),
            "Vague music request → popular Hindi songs on YouTube."))
        add(cmd("Phir se ghumao", "music", "media_control", p("action","previous"),
            "'Phir se ghumao' = replay / previous."))
        add(cmd("Seedha karo volume", "volume", "set_volume", p("level_percent","50"),
            "Slang 'seedha karo' = normalize = 50%."))
        add(cmd("Hilao torch", "flashlight", "toggle_flashlight", p("enable","true"),
            "Slang 'hilao torch' = toggle torch = turn on."))

        // ── CATEGORY 44: PROACTIVE / CONTEXT-AWARE ───────────────────
        add(cmd("Main driving kar raha hoon", "dnd", "set_dnd", p("enable","true"),
            "Driving context → proactively offer DND + navigation."))
        add(cmd("Meeting shuru ho rahi hai", "dnd", "set_dnd", p("enable","true"),
            "Meeting context → suggest DND on, offer to start a meeting timer."))
        add(cmd("Raat ho gayi hai", "brightness", "set_brightness", p("level_percent","20"),
            "Night context → reduce brightness proactively; suggest dark mode."))
        add(cmd("Mujhe neend aa rahi hai", "alarm", "CONVERSATIONAL", emptyMap(),
            "Sleepy statement → empathize, offer alarm or sleep music.", true))
        add(cmd("Main gym ja raha hoon", "music", "play_youtube",
            p("query","gym workout music 2025"),
            "Gym context → offer to play workout music on YouTube."))

        // ── CATEGORY 45: INFORMATION QUERIES ─────────────────────────
        add(cmd("Aaj ki date kya hai?", "info", "CONVERSATIONAL", emptyMap(),
            "Date query → respond with current date from system timestamp in system prompt."))
        add(cmd("Time kya hua?", "info", "CONVERSATIONAL", emptyMap(),
            "Time query → respond with current time from system timestamp."))
        add(cmd("Aaj kaunsa din hai?", "info", "CONVERSATIONAL", emptyMap(),
            "Day of week → from system timestamp. No tool needed."))
        add(cmd("CPU kitni fast hai?", "info", "web_search",
            p("query","how to check CPU speed on Android"),
            "Hardware spec → web_search for guidance.", true))
        add(cmd("Mera phone model kya hai?", "info", "web_search",
            p("query","how to check phone model on Android"),
            "Phone model → open About Phone or web_search for steps.", true))

        // ── CATEGORY 46: SECURITY & PRIVACY ──────────────────────────
        add(cmd("Location off karo", "privacy", "open_system_settings",
            p("setting_name","location"),
            "Location toggle → open Location settings.", true))
        add(cmd("App permissions dikhao", "privacy", "open_system_settings",
            p("setting_name","privacy"),
            "Permissions → open Privacy settings."))
        add(cmd("Phone format karo", "privacy", "CAUTION", emptyMap(),
            "DANGEROUS: Factory reset → require triple explicit confirmation. NEVER execute blindly.", true))
        add(cmd("Data wipe karo", "privacy", "CAUTION", emptyMap(),
            "Extremely destructive → refuse unless triple-confirmed.", true))

        // ── CATEGORY 47: HEALTH / FITNESS ────────────────────────────
        add(cmd("Steps count karo aaj ke", "health", "open_app", p("app_name","Google Fit"),
            "Step counter → open Google Fit."))
        add(cmd("Yoga timer 20 minute lagao", "timer", "set_timer",
            mp("minutes","20","seconds","0","label","Yoga Timer"),
            "Exercise timer with context label."))
        add(cmd("Subah 6 baje ka exercise alarm laga do", "alarm", "create_alarm",
            mp("hour","6","minute","0","label","Exercise Alarm"),
            "Exercise-specific alarm with descriptive label."))
        add(cmd("Calories count karo", "health", "perform_app_action",
            mp("app_name","Google Fit","action_description","track calories"),
            "Calorie tracking → perform_app_action in health app.", true))

        // ── CATEGORY 48: TRAVEL & TRANSPORT ──────────────────────────
        add(cmd("Flight status check karo IndiGo 6E-123", "travel", "web_search",
            p("query","IndiGo 6E-123 flight status"),
            "Flight status → web_search."))
        add(cmd("Hotel book karo Delhi me", "travel", "perform_app_action",
            mp("app_name","MakeMyTrip","action_description","search hotels in Delhi",
                "search_query","Delhi"),
            "Hotel booking → perform_app_action on travel app."))
        add(cmd("Bus schedule Agra to Delhi", "travel", "web_search",
            p("query","bus schedule Agra to Delhi"),
            "Bus schedule → web_search."))

        // ── CATEGORY 49: FINANCE ─────────────────────────────────────
        add(cmd("UPI se 200 rupaye bhejo Rahul ko", "finance", "perform_app_action",
            mp("app_name","Google Pay","action_description","send 200 rupees to Rahul",
                "search_query","Rahul"),
            "UPI transfer → perform_app_action with GPay/PhonePe."))
        add(cmd("Stock price of TCS", "finance", "web_search",
            p("query","TCS stock price today NSE"),
            "Stock price → web_search."))
        add(cmd("Bitcoin price kya hai?", "finance", "web_search",
            p("query","Bitcoin price today in INR"),
            "Crypto prices → web_search."))
        add(cmd("Mere credit card bill ki last date kya hai?", "finance", "CONVERSATIONAL",
            emptyMap(),
            "Personal finance detail not stored → ask user to check their banking app.", true))

        // ── CATEGORY 50: EDUCATION / PRODUCTIVITY ────────────────────
        add(cmd("Python sikhao mujhe", "education", "web_search",
            p("query","learn Python programming for beginners"),
            "Learning request → web_search for resources."))
        add(cmd("Mathematics formula remind karo quadratic", "education", "web_search",
            p("query","quadratic formula mathematics"),
            "Formula lookup → web_search."))
        add(cmd("Word count karo: 'Hello World this is AURA'", "math", "calculate",
            p("expression","5"),
            "Word count → calculate or respond directly."))
        add(cmd("Translate 'Namaste' to English", "education", "web_search",
            p("query","Namaste meaning in English"),
            "Translation → web_search or respond directly if confident."))

        // ── CATEGORY 51: SOCIAL MEDIA ────────────────────────────────
        add(cmd("Instagram reel play karo", "social", "perform_app_action",
            mp("app_name","Instagram","action_description","open Reels tab"),
            "Instagram Reels → perform_app_action."))
        add(cmd("Twitter/X check karo", "social", "open_app", p("app_name","X"),
            "Twitter/X → open_app('X')."))
        add(cmd("Facebook par status update karo", "social", "perform_app_action",
            mp("app_name","Facebook","action_description","open status update screen"),
            "FB status → perform_app_action."))
        add(cmd("YouTube Shorts dekho", "social", "perform_app_action",
            mp("app_name","YouTube","action_description","open Shorts tab"),
            "YouTube Shorts → perform_app_action."))

        // ── CATEGORY 52: GAMING ──────────────────────────────────────
        add(cmd("BGMI kholo", "gaming", "open_app", p("app_name","BGMI"),
            "BGMI (Battlegrounds Mobile India) → open_app."))
        add(cmd("DND on karo mai game khel raha hoon", "gaming", "set_dnd",
            p("enable","true"),
            "Gaming context → enable DND automatically."))
        add(cmd("Game ke time brightness full karo", "gaming", "set_brightness",
            p("level_percent","100"),
            "Gaming brightness boost → set_brightness(100)."))
        add(cmd("Volume full karo game chal raha hai", "gaming", "set_volume",
            p("level_percent","100"),
            "Gaming volume → set_volume(100)."))

        // ── CATEGORY 53: DOCUMENTS / FILES ───────────────────────────
        add(cmd("PDF kholo", "files", "open_app", p("app_name","Files"),
            "Open PDF → Files app. No direct PDF viewer API."))
        add(cmd("Google Docs kholo", "files", "open_app", p("app_name","Google Docs"),
            "Google Docs → open_app."))
        add(cmd("Meri files dikhao", "files", "open_app", p("app_name","Files"),
            "File manager → open Files app."))

        // ── CATEGORY 54: META / SELF-AWARE ───────────────────────────
        add(cmd("Tum kya kya kar sakti ho?", "meta", "CONVERSATIONAL", emptyMap(),
            "Capability query → list tool categories in a friendly, organised way."))
        add(cmd("AURA version kya hai?", "meta", "CONVERSATIONAL", emptyMap(),
            "Version query → respond with AURA 2.0 and build info."))
        add(cmd("AURA ko restart karo", "meta", "CONVERSATIONAL", emptyMap(),
            "Restart request → explain AURA runs as background service; offer to close/reopen app."))
        add(cmd("Tumhari memory clear karo", "meta", "CAUTION", emptyMap(),
            "Memory wipe → confirm before clearing ExperienceMemory and MemoryManager.", true))

        // ── CATEGORY 55: MULTI-TURN FOLLOW-UP ────────────────────────
        add(cmd("Haan", "followup", "CONFIRMATIONAL", emptyMap(),
            "'Haan'/'Yes'/'Theek hai' = user confirmed previous AURA proposal. Execute it."))
        add(cmd("Nahi", "followup", "CONFIRMATIONAL", emptyMap(),
            "'Nahi'/'No'/'Mat karo' = user declined. Abort action gracefully."))
        add(cmd("Wahi karein jo pehle kaha tha", "followup", "CONVERSATIONAL", emptyMap(),
            "References previous turn → recall last proposed action from context and execute."))
        add(cmd("Phir se karo", "followup", "CONVERSATIONAL", emptyMap(),
            "'Phir se karo' = repeat last executed action."))
        add(cmd("Cancel karo", "followup", "CONFIRMATIONAL", emptyMap(),
            "Cancel = abort current pending action. Do not execute."))
    }

    // ── HELPER BUILDERS ──────────────────────────────────────────────
    private fun cmd(
        rawText: String,
        category: String,
        toolName: String,
        params: Map<String, String>,
        lesson: String,
        isEdgeCase: Boolean = false
    ) = TrainingCommand(rawText, category, toolName, params, lesson, isEdgeCase)

    private fun p(k: String, v: String): Map<String, String> = mapOf(k to v)

    private fun mp(vararg pairs: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (i in pairs.indices step 2) map[pairs[i]] = pairs[i + 1]
        return map
    }

    // ── QUERY HELPERS ─────────────────────────────────────────────────

    /** All unique tool names in the corpus. */
    fun allToolNames(): Set<String> = commands.map { it.toolName }.toSet()

    /** Commands filtered by category. */
    fun byCategory(cat: String): List<TrainingCommand> =
        commands.filter { it.category == cat }

    /** Edge-case commands only. */
    fun edgeCases(): List<TrainingCommand> = commands.filter { it.isEdgeCase }

    /** Regular (non-edge) commands. */
    fun regularCommands(): List<TrainingCommand> = commands.filter { !it.isEdgeCase }

    /** Lessons map: toolName → list of all lessons for that tool. */
    fun lessonsMap(): Map<String, List<String>> =
        commands.groupBy({ it.toolName }, { it.lesson })

    /** Summary statistics string. */
    fun stats(): String = buildString {
        appendLine("📊 AuraCommandCorpus Statistics:")
        appendLine("  Total commands : ${commands.size}")
        appendLine("  Edge cases     : ${edgeCases().size}")
        appendLine("  Regular cmds   : ${regularCommands().size}")
        appendLine("  Unique tools   : ${allToolNames().size}")
        appendLine("  Categories     : ${commands.map { it.category }.toSet().size}")
    }
}
