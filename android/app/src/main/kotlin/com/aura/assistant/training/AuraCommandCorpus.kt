package com.aura.assistant.training

/**
 * ╔══════════════════════════════════════════════════════════════╗
 *  AURA COMMAND TRAINING CORPUS  v4.0
 *  650+ real-world command patterns  |  52 tools  |  70 categories
 *  Hindi · Hinglish · English · Typos · Slang · Compound · Edge
 * ╚══════════════════════════════════════════════════════════════╝
 */
data class TrainingCommand(
    val rawText: String,
    val category: String,
    val toolName: String,
    val params: Map<String, String> = emptyMap(),
    val lesson: String,
    val isEdgeCase: Boolean = false,
    val variants: List<String> = emptyList()  // v4: alternate phrasings
)

@Suppress("LargeClass", "TooManyFunctions")
object AuraCommandCorpus {

    val commands: List<TrainingCommand> by lazy { buildCorpus() }

    @Suppress("LongMethod")
    private fun buildCorpus(): List<TrainingCommand> = buildList {

        // ────────────────────────────────────────────────────────────
        // TOOL 01 ── make_call
        // ────────────────────────────────────────────────────────────
        add(cmd("Maa ko call karo", "call", "make_call", p("contact_name","Maa"),
            "Family names → contact_name. NEVER hallucinate phone_number.",
            v("Maa ko phone karo","Maa ko dial karo","Call maa","Ring maa")))
        add(cmd("Papa ko call lagao", "call", "make_call", p("contact_name","Papa"),
            "Papa/Dad/Father → contact_name='Papa'.", v("Dad ko call karo","Father ko phone karo")))
        add(cmd("Ansh ko call karo", "call", "make_call", p("contact_name","Ansh"),
            "Named contact → contact_name.",
            v("Ansh ko dial karo","Call Ansh","Ansh ko phone karo","Ansh bhai ko call karo")))
        add(cmd("9876543210 pe call lagao", "call", "make_call", p("phone_number","9876543210"),
            "10-digit number → phone_number param.", v("9876543210 dial karo","Call 9876543210")))
        add(cmd("+91-98765-43210 pe call karo", "call", "make_call", p("phone_number","+919876543210"),
            "Strip +91/spaces/hyphens from number before passing."))
        add(cmd("Bhai ko call karo", "call", "make_call", p("contact_name","Bhai"),
            "Relationship names like Bhai/Didi/Chacha work as contact_name.",
            v("Didi ko call karo","Chacha ko phone karo")))
        add(cmd("Missed call de do Rahul ko", "call", "make_call", p("contact_name","Rahul"),
            "Missed call = make_call; call will ring briefly."))
        add(cmd("Call karke puchho Priya kahan hai", "call", "make_call", p("contact_name","Priya"),
            "Purpose clause after call → just call the person, mention purpose in warm response."))
        add(cmd("Auto dial karo", "call", "make_call", emptyMap(),
            "No contact given → ask 'Kise call karun Boss?'", isEdge = true))

        // ────────────────────────────────────────────────────────────
        // TOOL 02 ── send_sms
        // ────────────────────────────────────────────────────────────
        add(cmd("Rahul ko SMS karo: Kal milte hain", "sms", "send_sms",
            mp("contact_name","Rahul","message","Kal milte hain"),
            "Colon after name = message text.",
            v("Rahul ko message bhejo kal milte hain","Text Rahul kal milte hain")))
        add(cmd("9999000001 ko SMS karo Meeting cancel hai", "sms", "send_sms",
            mp("phone_number","9999000001","message","Meeting cancel hai"),
            "Number + message → phone_number and message params."))
        add(cmd("Maa ko text bhejo ki main 1 ghante mein aa jaunga", "sms", "send_sms",
            mp("contact_name","Maa","message","Main 1 ghante mein aa jaunga"),
            "'Ki' clause = message content. Extract and clean."))
        add(cmd("Mere last SMS padho", "sms", "read_recent_sms", p("count","1"),
            "'Last SMS padho' → read_recent_sms(count=1).",
            v("Recent message padho","Last message kya aaya")))
        add(cmd("5 SMS padho", "sms", "read_recent_sms", p("count","5"),
            "Number before SMS → count param.", v("Read 5 messages","Paanch messages dikhao")))
        add(cmd("Rahul ka SMS padho", "sms", "read_recent_sms", p("count","5"),
            "Sender-specific SMS → read_recent_sms, then filter by sender name in response."))

        // ────────────────────────────────────────────────────────────
        // TOOL 03 ── send_whatsapp
        // ────────────────────────────────────────────────────────────
        add(cmd("Ansh ko WhatsApp karo Ghar aaja", "whatsapp", "send_whatsapp",
            mp("contact_name","Ansh","message","Ghar aaja"),
            "WhatsApp text → send_whatsapp. NOT send_sms.",
            v("WA par Ansh ko bhejo Ghar aaja","Ansh ko WA karo ghar aaja")))
        add(cmd("Priya ko Happy Birthday WhatsApp par bhejo", "whatsapp", "send_whatsapp",
            mp("contact_name","Priya","message","Happy Birthday"),
            "Greeting message → send_whatsapp.",
            v("Priya ko birthday wish karo WA se","WA me Priya ko birthday bol do")))
        add(cmd("Raj ko WhatsApp mein bol do 5 min mein pahunch raha hoon", "whatsapp", "send_whatsapp",
            mp("contact_name","Raj","message","5 min mein pahunch raha hoon"),
            "Paraphrase 'bol do ... ki' clause as message.",
            v("Raj ko message karo 5 min lag rahe","Raj WA pe 5 min likh do")))
        add(cmd("Family group mein bhejo Dinner ready hai", "whatsapp", "send_whatsapp",
            mp("contact_name","Family","message","Dinner ready hai"),
            "Group names work as contact_name in send_whatsapp."))
        add(cmd("Office group ko WA karo Meeting shifted to 3 PM", "whatsapp", "send_whatsapp",
            mp("contact_name","Office","message","Meeting shifted to 3 PM"),
            "Group + professional message → send_whatsapp."))
        add(cmd("Maa ko bol do WhatsApp pe aa raha hoon", "whatsapp", "send_whatsapp",
            mp("contact_name","Maa","message","Aa raha hoon"),
            "Natural language intent → extract message from 'bol do' clause."))

        // ────────────────────────────────────────────────────────────
        // TOOL 04 ── send_whatsapp_media
        // ────────────────────────────────────────────────────────────
        add(cmd("Ansh ko screenshot WhatsApp kar do", "whatsapp_media", "send_whatsapp_media",
            mp("contact_name","Ansh","media_type","screenshot"),
            "Screenshot + WhatsApp → send_whatsapp_media(media_type=screenshot).",
            v("Ansh ko screen capture bhejo","Screenshot le ke Ansh ko WA karo")))
        add(cmd("Papa ko latest photo bhejo WhatsApp se", "whatsapp_media", "send_whatsapp_media",
            mp("contact_name","Papa","media_type","photo"),
            "Latest photo → media_type=photo.",
            v("Papa ko apni photo bhejo","Latest wali pic Papa ko WA karo")))
        add(cmd("Meri selfie Rahul ko WhatsApp par bhejo", "whatsapp_media", "send_whatsapp_media",
            mp("contact_name","Rahul","media_type","photo"),
            "Selfie = photo media type."))
        add(cmd("Video Priya ko WhatsApp pe bhejo", "whatsapp_media", "send_whatsapp_media",
            mp("contact_name","Priya","media_type","video"),
            "Video send → media_type=video."))

        // ────────────────────────────────────────────────────────────
        // TOOL 05 ── delete_whatsapp_media
        // ────────────────────────────────────────────────────────────
        add(cmd("Priya ke WhatsApp pe last image delete karo", "whatsapp_del", "delete_whatsapp_media",
            mp("contact_name","Priya","target","last_image"),
            "Delete last image in WA chat → delete_whatsapp_media(last_image).",
            v("Priya ke chat se photo hatao","WA mein Priya wali image delete karo")))
        add(cmd("Rahul ki chat se last message delete karo", "whatsapp_del", "delete_whatsapp_media",
            mp("contact_name","Rahul","target","last_message"),
            "Delete last message → target=last_message."))
        add(cmd("Ansh ke WhatsApp se sabhi photos delete karo", "whatsapp_del", "delete_whatsapp_media",
            mp("contact_name","Ansh","target","all_images"),
            "All photos → target=all_images."))

        // ────────────────────────────────────────────────────────────
        // TOOL 06 ── take_screenshot
        // ────────────────────────────────────────────────────────────
        add(cmd("Screenshot lo", "screenshot", "take_screenshot", emptyMap(),
            "take_screenshot captures screen directly. Do NOT use press_system_key for this.",
            v("Screen capture karo","Ek screenshot le lo","Screen ka photo lo")))
        add(cmd("Abhi jo screen pe hai uska screenshot lo", "screenshot", "take_screenshot", emptyMap(),
            "Current screen screenshot → take_screenshot."))
        add(cmd("Screen shot bhejo", "screenshot", "take_screenshot", emptyMap(),
            "Typo 'screen shot' → take_screenshot.", v("Scrn shot lo","Screensot lo")))

        // ────────────────────────────────────────────────────────────
        // TOOL 07 ── share_media
        // ────────────────────────────────────────────────────────────
        add(cmd("Ye photo share karo", "share", "share_media",
            p("media_type","photo"),
            "share_media shares the latest photo via Android share sheet.",
            v("Latest photo share karo","Ye picture share kar do")))
        add(cmd("Screenshot share karo WhatsApp pe", "share", "share_media",
            mp("media_type","screenshot","target_app","WhatsApp"),
            "Targeted share → share_media with target_app."))

        // ────────────────────────────────────────────────────────────
        // TOOL 08 ── open_app
        // ────────────────────────────────────────────────────────────
        add(cmd("YouTube kholo", "app_launch", "open_app", p("app_name","YouTube"),
            "open_app launches any installed app by name.",
            v("YouTube open karo","YT launch karo","YouTube chalaoo")))
        add(cmd("Instagram open karo", "app_launch", "open_app", p("app_name","Instagram"),
            "Social media app → open_app.", v("Insta kholo","Instagram launch karo")))
        add(cmd("WhatsApp khol do", "app_launch", "open_app", p("app_name","WhatsApp"),
            "open_app for WhatsApp ONLY when no message task; if message → send_whatsapp.",
            v("WA kholo","WhatsApp open karo")))
        add(cmd("Settings kholo", "app_launch", "open_app", p("app_name","Settings"),
            "Generic settings → open_app. Specific settings → open_system_settings.",
            v("Setting app kholo","Android settings open karo")))
        add(cmd("Calculator launch karo", "app_launch", "open_app", p("app_name","Calculator"),
            "Calculator app → open_app. For math → use calculate tool.",
            v("Calculator kholo","Calc open karo")))
        add(cmd("Zomato kholo", "app_launch", "open_app", p("app_name","Zomato"),
            "Food apps → open_app. For ordering → perform_app_action.",
            v("Zomato launch karo","Food app kholo")))
        add(cmd("Google Pay kholo", "app_launch", "open_app", p("app_name","Google Pay"),
            "Payment app → open_app.",
            v("GPay kholo","PhonePe open karo","Paytm kholo")))
        add(cmd("Maps open karo", "app_launch", "open_app", p("app_name","Google Maps"),
            "'Maps' → open Google Maps app. For navigation → navigate_maps.",
            v("Google Maps kholo","Maps app launch karo")))
        add(cmd("Clock app kholo", "app_launch", "open_app", p("app_name","Clock"),
            "Clock → open_app. For alarm → create_alarm.",
            v("Clock kholo","Alarm app kholo")))
        add(cmd("Files app kholo", "app_launch", "open_app", p("app_name","Files"),
            "File manager → open_app.", v("File manager kholo","Files open karo")))
        add(cmd("Chrome kholo", "app_launch", "open_app", p("app_name","Chrome"),
            "Browser → open_app. For web search → web_search.",
            v("Browser kholo","Chrome browser open karo")))
        add(cmd("Spotify chalaoo", "app_launch", "open_app", p("app_name","Spotify"),
            "Music app → open_app unless specific song (then play_media).",
            v("Spotify open karo","Music app kholo")))
        add(cmd("Camera khol do", "app_launch", "open_camera", p("mode","photo"),
            "Camera → open_camera(mode=photo), NOT open_app('Camera').",
            v("Camera launch karo","Cam khol do")))
        add(cmd("BGMI kholo", "app_launch", "open_app", p("app_name","BGMI"),
            "Gaming apps → open_app.",
            v("Free Fire kholo","Call of Duty mobile open karo","Game kholo")))
        add(cmd("Google Drive kholo", "app_launch", "open_app", p("app_name","Google Drive"),
            "Cloud storage → open_app."))
        add(cmd("LinkedIn kholo", "app_launch", "open_app", p("app_name","LinkedIn"),
            "Professional social → open_app."))
        add(cmd("Amazon kholo", "app_launch", "open_app", p("app_name","Amazon"),
            "Shopping app → open_app. For search → perform_app_action.",
            v("Flipkart kholo","Shopping app open karo")))
        add(cmd("Netflix open karo", "app_launch", "open_app", p("app_name","Netflix"),
            "Streaming apps → open_app.",
            v("Hotstar kholo","Prime Video open karo","Disney plus kholo")))

        // ────────────────────────────────────────────────────────────
        // TOOL 09 ── toggle_flashlight
        // ────────────────────────────────────────────────────────────
        add(cmd("Torch on karo", "flashlight", "toggle_flashlight", p("enable","true"),
            "toggle_flashlight(enable=true). Works via CameraManager.",
            v("Flashlight on karo","Light on karo","Torch chala do","Torchlight on")))
        add(cmd("Torch band karo", "flashlight", "toggle_flashlight", p("enable","false"),
            "Off/band karo = enable=false.",
            v("Flashlight off karo","Torch off","Light band karo","Torchlight off karo")))
        add(cmd("Andhera hai torch on karo", "flashlight", "toggle_flashlight", p("enable","true"),
            "Dark context → infer torch needed proactively.",
            v("Raat hai torch chahiye","Kuch dikh nahi raha torch on karo")))
        add(cmd("Torch toggle karo", "flashlight", "toggle_flashlight", p("enable","true"),
            "'Toggle' → if currently off turn on; default to on.",
            v("Flashlight toggle karo")))

        // ────────────────────────────────────────────────────────────
        // TOOL 10 ── set_volume
        // ────────────────────────────────────────────────────────────
        add(cmd("Volume full karo", "volume", "set_volume", p("level_percent","100"),
            "'Full' = 100. Always use set_volume, NOT set_dnd for mute.",
            v("Volume max karo","Sound full karo","Awaaz full karo","Volume 100 karo")))
        add(cmd("Awaaz band karo", "volume", "set_volume", p("level_percent","0"),
            "'Awaaz band' = 0 = mute. Different from DND.",
            v("Mute karo","Sound band karo","Volume zero karo","Phone mute karo")))
        add(cmd("Volume 50 karo", "volume", "set_volume", p("level_percent","50"),
            "Explicit number → level_percent.",
            v("50 percent volume","Sound 50% karo","Half volume karo")))
        add(cmd("Thoda volume badhao", "volume", "set_volume", p("level_percent","70"),
            "'Thoda badhao' relative → increase to 70.",
            v("Sound thoda aur badhao","Volume increase karo","Awaaz badhao")))
        add(cmd("Sound bilkul kam karo", "volume", "set_volume", p("level_percent","10"),
            "'Bilkul kam' = very low = 10.",
            v("Volume bahut kam karo","Awaaz kam kar do")))
        add(cmd("Volume 70 percent karo", "volume", "set_volume", p("level_percent","70"),
            "Percentage explicit → extract number directly."))
        add(cmd("Ringer band karo", "volume", "set_volume", p("level_percent","0"),
            "Ringer off = mute = level_percent=0.",
            v("Ring silent karo","Call sound off karo")))
        add(cmd("Media volume badhao", "volume", "set_volume", p("level_percent","80"),
            "Media volume → set_volume."))
        add(cmd("Speaker loud karo", "volume", "set_volume", p("level_percent","100"),
            "Speaker loud = volume max."))

        // ────────────────────────────────────────────────────────────
        // TOOL 11 ── screen_tap (accessibility gesture)
        // ────────────────────────────────────────────────────────────
        add(cmd("Screen pe tap karo", "gesture", "screen_tap",
            mp("x","540","y","960"),
            "screen_tap simulates a tap at coordinates. Default = center screen.",
            v("Screen click karo","Middle mein tap karo")))
        add(cmd("OK button dabao", "gesture", "screen_tap",
            mp("target_text","OK"),
            "Text-based tap → screen_tap with target_text param.",
            v("OK pe click karo","Confirm button dabao")))
        add(cmd("Allow pe tap karo", "gesture", "screen_tap",
            mp("target_text","Allow"),
            "Permission dialog tap → screen_tap(target_text=Allow).",
            v("Allow karo","Permission allow karo")))
        add(cmd("Cancel dabao", "gesture", "screen_tap",
            mp("target_text","Cancel"),
            "Cancel button → screen_tap(target_text=Cancel)."))

        // ────────────────────────────────────────────────────────────
        // TOOL 12 ── screen_scroll
        // ────────────────────────────────────────────────────────────
        add(cmd("Neeche scroll karo", "gesture", "screen_scroll",
            p("direction","down"),
            "screen_scroll scrolls the current view.",
            v("Page neeche karo","Down scroll karo","Scroll down")))
        add(cmd("Upar scroll karo", "gesture", "screen_scroll",
            p("direction","up"),
            "Scroll up → direction=up.",
            v("Page upar le jao","Scroll up karo")))
        add(cmd("Thoda aur neeche", "gesture", "screen_scroll",
            mp("direction","down","amount","small"),
            "Small scroll → amount=small."))

        // ────────────────────────────────────────────────────────────
        // TOOL 13 ── media_control
        // ────────────────────────────────────────────────────────────
        add(cmd("Gaana band karo", "music", "media_control", p("action","pause"),
            "'Band karo' for media = pause.",
            v("Music pause karo","Song rok do","Play band karo","Pause karo")))
        add(cmd("Music play karo", "music", "media_control", p("action","play"),
            "Generic play → resume via media_control(play).",
            v("Gaana chalaoo","Song start karo","Music resume karo")))
        add(cmd("Next song", "music", "media_control", p("action","next"),
            "Next → media_control(action=next).",
            v("Agla gaana","Next track","Skip karo","Agle gaane pe jao")))
        add(cmd("Previous track", "music", "media_control", p("action","previous"),
            "Previous → action=previous.",
            v("Pichla gaana","Back track","Wapas jao")))
        add(cmd("Volume ke saath gaana aane do", "music", "media_control", p("action","play"),
            "Play with volume context → media_control(play)."))
        add(cmd("Gaana like karo", "music", "media_control", p("action","like"),
            "Like current song → action=like.",
            v("Ye song like karo","Heart dabao on song")))
        add(cmd("Shuffle on karo", "music", "media_control", p("action","shuffle"),
            "Shuffle → action=shuffle."))

        // ────────────────────────────────────────────────────────────
        // TOOL 14 ── create_alarm
        // ────────────────────────────────────────────────────────────
        add(cmd("Subah 6 baje alarm laga do", "alarm", "create_alarm",
            mp("hour","6","minute","0","label","Morning Alarm"),
            "Hindi time 'X baje' + subah (AM) → hour=X, minute=0.",
            v("6 AM alarm set karo","Subah 6 ka alarm","6 baje uthna hai alarm lao")))
        add(cmd("7:30 ka alarm set karo", "alarm", "create_alarm",
            mp("hour","7","minute","30","label","Alarm"),
            "HH:MM → split on colon.",
            v("7 bajke 30 minute ka alarm","7:30 AM alarm")))
        add(cmd("Raat 11 baje alarm lagao", "alarm", "create_alarm",
            mp("hour","23","minute","0","label","Night Alarm"),
            "'Raat' = PM. 11 PM = 23:00.",
            v("11 PM alarm","Raat 11 ka alarm")))
        add(cmd("Dopahar 2 baje reminder", "alarm", "create_alarm",
            mp("hour","14","minute","0","label","Afternoon Reminder"),
            "'Dopahar' = PM. 2 PM = 14:00."))
        add(cmd("5:45 AM alarm", "alarm", "create_alarm",
            mp("hour","5","minute","45","label","Alarm"),
            "Explicit AM/PM already in 12h → convert to 24h."))
        add(cmd("Half past 6 subah alarm", "alarm", "create_alarm",
            mp("hour","6","minute","30","label","Morning Alarm"),
            "'Half past 6' = 6:30."))
        add(cmd("Kal office ke liye 8 baje alarm set karo", "alarm", "create_alarm",
            mp("hour","8","minute","0","label","Office Alarm"),
            "'Kal' doesn't change time; alarm naturally fires next occurrence."))
        add(cmd("Exercise ke liye 5:30 AM alarm", "alarm", "create_alarm",
            mp("hour","5","minute","30","label","Exercise Alarm"),
            "Purpose in label → helps user identify alarm."))
        add(cmd("Subah teen alarm set karo 6 7 aur 8 baje", "alarm", "create_alarm",
            mp("hour","6","minute","0","label","Alarm 1"),
            "Multiple alarms → create sequentially, start with first.",
            isEdge = true))
        add(cmd("Alarm delete karo", "alarm", "open_app", p("app_name","Clock"),
            "Alarm delete not direct API → open Clock app.", isEdge = true))

        // ────────────────────────────────────────────────────────────
        // TOOL 15 ── search_files
        // ────────────────────────────────────────────────────────────
        add(cmd("Meri files mein resume dhundho", "files", "search_files",
            p("query","resume"),
            "search_files searches device storage.",
            v("Resume file dhundho","Resume.pdf kahan hai")))
        add(cmd("PDF files dhundho", "files", "search_files",
            mp("query","","file_type","pdf"),
            "File type filter → file_type param.",
            v("Saari PDF files dikhao","PDF dhundho")))
        add(cmd("Downloads folder mein kya hai?", "files", "search_files",
            mp("query","","folder","Downloads"),
            "Folder-specific search → folder param."))

        // ────────────────────────────────────────────────────────────
        // TOOL 16 ── delete_file
        // ────────────────────────────────────────────────────────────
        add(cmd("Downloads se ye file delete karo", "files", "delete_file",
            p("folder","Downloads"),
            "File deletion → delete_file. Always confirm before deleting.",
            isEdge = true))
        add(cmd("Temp files saaf karo", "files", "delete_file",
            p("pattern","temp"),
            "Pattern-based deletion → delete_file(pattern=temp). Confirm first.",
            isEdge = true))

        // ────────────────────────────────────────────────────────────
        // TOOL 17 ── accept_call
        // ────────────────────────────────────────────────────────────
        add(cmd("Call receive karo", "call_ctrl", "accept_call", emptyMap(),
            "accept_call answers incoming call via accessibility.",
            v("Call uthao","Phone uthao","Answer karo","Call accept karo")))
        add(cmd("Haan uthao call", "call_ctrl", "accept_call", emptyMap(),
            "'Haan uthao' = accept call."))

        // ────────────────────────────────────────────────────────────
        // TOOL 18 ── decline_call
        // ────────────────────────────────────────────────────────────
        add(cmd("Call reject karo", "call_ctrl", "decline_call", emptyMap(),
            "decline_call rejects incoming call.",
            v("Call mat uthao","Reject karo","Call decline karo","Phone mat uthao")))
        add(cmd("Call kaatna hai", "call_ctrl", "decline_call", emptyMap(),
            "'Kaatna' = disconnect/reject."))

        // ────────────────────────────────────────────────────────────
        // TOOL 19 ── identify_caller
        // ────────────────────────────────────────────────────────────
        add(cmd("Kaun call kar raha hai?", "call_ctrl", "identify_caller", emptyMap(),
            "identify_caller reads caller ID from notification.",
            v("Kiska call hai?","Caller kaun hai?","Number kaun sa hai?")))
        add(cmd("Is number ko identify karo", "call_ctrl", "identify_caller", emptyMap(),
            "Caller identification → identify_caller."))

        // ────────────────────────────────────────────────────────────
        // TOOL 20 ── get_battery_status
        // ────────────────────────────────────────────────────────────
        add(cmd("Battery kitni hai?", "battery", "get_battery_status", emptyMap(),
            "ALWAYS call get_battery_status. NEVER guess percentage.",
            v("Battery level batao","Kitni charge hai","Battery percentage")))
        add(cmd("Charging ho rahi hai?", "battery", "get_battery_status", emptyMap(),
            "Charging status → get_battery_status, check isCharging field.",
            v("Phone charge par hai?","Charging chal rahi hai?")))
        add(cmd("Battery kitne ghante chalegi?", "battery", "get_battery_status", emptyMap(),
            "Battery estimation → get_battery_status + estimate from level."))
        add(cmd("Phone ki battery check karo", "battery", "get_battery_status", emptyMap(),
            "Battery check → get_battery_status.",
            v("Check battery","What is battery level","Battery kitna hai")))

        // ────────────────────────────────────────────────────────────
        // TOOL 21 ── get_clipboard_text
        // ────────────────────────────────────────────────────────────
        add(cmd("Clipboard mein kya hai?", "clipboard", "get_clipboard_text", emptyMap(),
            "get_clipboard_text reads current clipboard content.",
            v("Copy ki hui cheez dikhao","Clipboard padho","Paste area mein kya hai")))
        add(cmd("Ye copy hua tha kya dikhao", "clipboard", "get_clipboard_text", emptyMap(),
            "Clipboard content query → get_clipboard_text."))

        // ────────────────────────────────────────────────────────────
        // TOOL 22 ── remember_fact
        // ────────────────────────────────────────────────────────────
        add(cmd("Yaad rakhna mera birthday 15 August ko hai", "memory", "remember_fact",
            mp("key","birthday","value","15 August"),
            "remember_fact: extract key from context, value from 'X hai/ko hai'.",
            v("15 August mera birthday hai note karo","Birthday 15 August save karo")))
        add(cmd("Mera naam Ansh hai yaad rakhna", "memory", "remember_fact",
            mp("key","name","value","Ansh"),
            "Name fact → also sets user_name in AuraMemoryManager.",
            v("Mera naam Ansh hai","Note karo mera naam Ansh hai")))
        add(cmd("Car number DL01AB1234 save karo", "memory", "remember_fact",
            mp("key","car number","value","DL01AB1234"),
            "Vehicle info → remember_fact."))
        add(cmd("Office address yaad rakhna: Sector 62, Noida", "memory", "remember_fact",
            mp("key","office address","value","Sector 62, Noida"),
            "Address fact → remember_fact."))
        add(cmd("Wife ka birthday 10 February hai", "memory", "remember_fact",
            mp("key","wife birthday","value","10 February"),
            "Relationship-specific fact → key includes relationship context."))
        add(cmd("Meri ATM PIN 1234 hai", "memory", "remember_fact",
            mp("key","ATM PIN","value","1234"),
            "Sensitive data saved locally only. Remind user it's device-local."))

        // ────────────────────────────────────────────────────────────
        // TOOL 23 ── recall_memory
        // ────────────────────────────────────────────────────────────
        add(cmd("Mujhe kya kya yaad hai?", "memory", "recall_memory", emptyMap(),
            "recall_memory returns all stored facts.",
            v("Meri saari yaadein dikhao","What do you remember","Memories dikhao")))
        add(cmd("Mera birthday kab hai?", "memory", "recall_memory", p("key","birthday"),
            "Specific recall → query by key.",
            v("Birthday kab hai mera","When is my birthday")))
        add(cmd("Car number kya tha mera?", "memory", "recall_memory", p("key","car number"),
            "Vehicle recall → recall_memory(key='car number')."))
        add(cmd("Office ka address kya tha?", "memory", "recall_memory", p("key","office address"),
            "Address recall."))

        // ────────────────────────────────────────────────────────────
        // TOOL 24 ── play_media
        // ────────────────────────────────────────────────────────────
        add(cmd("Spotify par Lo-Fi chalaoo", "music", "play_media",
            mp("query","Lo-Fi music","app_name","Spotify"),
            "Specific app + query → play_media with app_name.",
            v("Spotify mein Lo-Fi play karo","Lo-Fi on Spotify")))
        add(cmd("JioSaavn par Punjabi songs", "music", "play_media",
            mp("query","Punjabi songs","app_name","JioSaavn"),
            "JioSaavn → play_media.",
            v("JioSaavn pe Punjabi songs chalao")))
        add(cmd("Wynk pe koi bhi gaana laga do", "music", "play_media",
            mp("query","popular songs","app_name","Wynk"),
            "Wynk music app → play_media."))
        add(cmd("Gaana app pe Arijit Singh", "music", "play_media",
            mp("query","Arijit Singh songs","app_name","Gaana"),
            "Gaana app → play_media."))

        // ────────────────────────────────────────────────────────────
        // TOOL 25 ── play_youtube
        // ────────────────────────────────────────────────────────────
        add(cmd("YouTube par Hanuman Chalisa chalao", "music", "play_youtube",
            p("query","Hanuman Chalisa"),
            "YouTube + content → play_youtube. Auto-searches and clicks first result.",
            v("YT pe Hanuman Chalisa","Hanuman Chalisa YouTube par")))
        add(cmd("Arijit Singh ke gaane YouTube pe", "music", "play_youtube",
            p("query","Arijit Singh songs"),
            "Artist + songs → play_youtube with artist name.",
            v("Arijit Singh YouTube","YT par Arijit")))
        add(cmd("Motivational songs YouTube par", "music", "play_youtube",
            p("query","motivational songs"),
            "Mood-based music → play_youtube.",
            v("YT par motivational music","Positive songs chalaoo")))
        add(cmd("Lo-Fi beats YouTube pe chalaoo", "music", "play_youtube",
            p("query","lo-fi beats study music"),
            "Specific music type → play_youtube."))
        add(cmd("Neend ke liye soft music YouTube par", "music", "play_youtube",
            p("query","sleep music relaxing"),
            "Sleep/relaxation music → play_youtube."))
        add(cmd("Bhajan YouTube par chalaoo", "music", "play_youtube",
            p("query","Hindi bhajan devotional"),
            "Devotional content → play_youtube.",
            v("Bhakti songs YouTube pe","Devotional music YT par")))
        add(cmd("Gym music YouTube par", "music", "play_youtube",
            p("query","gym workout music"),
            "Workout music → play_youtube."))
        add(cmd("Baby shark YouTube par", "music", "play_youtube",
            p("query","Baby Shark"),
            "Specific song title → play_youtube with exact title."))
        add(cmd("News sun'na hai YouTube par", "music", "play_youtube",
            p("query","India news today"),
            "News content → play_youtube with 'India news today'."))

        // ────────────────────────────────────────────────────────────
        // TOOL 26 ── perform_app_action
        // ────────────────────────────────────────────────────────────
        add(cmd("Zomato par biryani search karo", "app_action", "perform_app_action",
            mp("app_name","Zomato","action_description","search for biryani","search_query","biryani"),
            "perform_app_action opens app and performs in-app action.",
            v("Zomato mein biryani dhundho","Biryani order karo Zomato se")))
        add(cmd("Swiggy se pizza order karo", "app_action", "perform_app_action",
            mp("app_name","Swiggy","action_description","search and order pizza","search_query","pizza"),
            "Food order → perform_app_action.",
            v("Swiggy par pizza dhundho")))
        add(cmd("Amazon par iPhone case dhundho", "app_action", "perform_app_action",
            mp("app_name","Amazon","action_description","search iPhone case","search_query","iPhone case"),
            "Shopping search → perform_app_action.",
            v("Amazon mein case dhundho","iPhone cover Amazon pe")))
        add(cmd("Uber se cab book karo", "app_action", "perform_app_action",
            mp("app_name","Uber","action_description","book a cab"),
            "Cab booking → perform_app_action.",
            v("Ola se cab lo","Rapido book karo")))
        add(cmd("Paytm se 500 Rahul ko bhejo", "app_action", "perform_app_action",
            mp("app_name","Paytm","action_description","send 500 rupees to Rahul","search_query","Rahul"),
            "UPI payment → perform_app_action.",
            v("GPay se paise bhejo","PhonePe transfer karo")))
        add(cmd("Instagram mein reels dekho", "app_action", "perform_app_action",
            mp("app_name","Instagram","action_description","open Reels tab"),
            "In-app navigation → perform_app_action.",
            v("Instagram reels open karo","Reels tab pe jao")))
        add(cmd("YouTube Shorts open karo", "app_action", "perform_app_action",
            mp("app_name","YouTube","action_description","open Shorts tab"),
            "YouTube Shorts → perform_app_action.",
            v("Shorts tab pe jao","YT shorts dekho")))
        add(cmd("Twitter par trending dekho", "app_action", "perform_app_action",
            mp("app_name","X","action_description","open trending tab"),
            "Twitter/X trending → perform_app_action.",
            v("X app trending","Twitter trends dikhao")))
        add(cmd("Flipkart sale pe dhundho headphones", "app_action", "perform_app_action",
            mp("app_name","Flipkart","action_description","search headphones","search_query","headphones"),
            "E-commerce search → perform_app_action."))
        add(cmd("IRCTC se train ticket dhundho", "app_action", "perform_app_action",
            mp("app_name","IRCTC","action_description","book train ticket"),
            "Train booking → perform_app_action."))

        // ────────────────────────────────────────────────────────────
        // TOOL 27 ── toggle_hotspot
        // ────────────────────────────────────────────────────────────
        add(cmd("Hotspot on karo", "hotspot", "toggle_hotspot", p("enable","true"),
            "toggle_hotspot via accessibility in Settings > Network.",
            v("Hotspot chalu karo","Personal hotspot on karo","Data share on karo")))
        add(cmd("Hotspot band karo", "hotspot", "toggle_hotspot", p("enable","false"),
            "'Band karo' = disable hotspot.",
            v("Hotspot off karo","Tethering band karo","Data sharing stop karo")))
        add(cmd("Tethering on karo", "hotspot", "toggle_hotspot", p("enable","true"),
            "Tethering = hotspot."))
        add(cmd("Mobile data share karo dost ko", "hotspot", "toggle_hotspot", p("enable","true"),
            "'Data share' context → toggle_hotspot(true)."))

        // ────────────────────────────────────────────────────────────
        // TOOL 28 ── set_navigation_mode
        // ────────────────────────────────────────────────────────────
        add(cmd("3 button navigation kardo", "nav_mode", "set_navigation_mode", p("mode","buttons"),
            "3-button nav = mode='buttons'.",
            v("Button navigation on karo","3 button wala karo","Normal navigation karo")))
        add(cmd("Gesture navigation on karo", "nav_mode", "set_navigation_mode", p("mode","gestures"),
            "Gesture = mode='gestures'.",
            v("Gesture wala navigation","Swipe navigation karo","Button hatao gestures lao")))
        add(cmd("Navigation bar hatao", "nav_mode", "set_navigation_mode", p("mode","gestures"),
            "Remove nav bar = gesture mode."))

        // ────────────────────────────────────────────────────────────
        // TOOL 29 ── toggle_wifi
        // ────────────────────────────────────────────────────────────
        add(cmd("WiFi on karo", "wifi", "toggle_wifi", p("enable","true"),
            "toggle_wifi via accessibility automation.",
            v("WiFi chalaoo","WiFi connect karo","Internet on karo","WiFi enable karo")))
        add(cmd("WiFi band karo", "wifi", "toggle_wifi", p("enable","false"),
            "'Band' = off.",
            v("WiFi off karo","Internet band karo","WiFi disconnect karo")))
        add(cmd("WiFi toggle karo", "wifi", "toggle_wifi", p("enable","true"),
            "Toggle → check current state and flip; default on."))
        add(cmd("Net on karo", "wifi", "toggle_wifi", p("enable","true"),
            "'Net on' = WiFi on (common colloquial)."))

        // ────────────────────────────────────────────────────────────
        // TOOL 30 ── toggle_bluetooth
        // ────────────────────────────────────────────────────────────
        add(cmd("Bluetooth on karo", "bluetooth", "toggle_bluetooth", p("enable","true"),
            "toggle_bluetooth via accessibility.",
            v("BT on karo","Blutooth on","Bluetooth chalaoo")))
        add(cmd("Bluetooth off karo", "bluetooth", "toggle_bluetooth", p("enable","false"),
            "'Off karo' = enable=false.",
            v("BT band karo","Bluetooth disable karo")))
        add(cmd("Earbuds connect karo", "bluetooth", "toggle_bluetooth", p("enable","true"),
            "Device pairing: turn BT on first, then open BT settings.",
            isEdge = true))
        add(cmd("Headphones Bluetooth se connect karo", "bluetooth", "toggle_bluetooth",
            p("enable","true"),
            "Headphone pairing → toggle_bluetooth(true), then explain pairing steps."))

        // ────────────────────────────────────────────────────────────
        // TOOL 31 ── press_system_key
        // ────────────────────────────────────────────────────────────
        add(cmd("Phone lock karo", "system_key", "press_system_key", p("key","lock"),
            "Lock screen = press_system_key(lock).",
            v("Screen lock kardo","Lock phone","Screen band karo")))
        add(cmd("Home button", "system_key", "press_system_key", p("key","home"),
            "Home key = press_system_key(home).",
            v("Home pe jao","Home screen dikhao")))
        add(cmd("Back jao", "system_key", "press_system_key", p("key","back"),
            "Back = press_system_key(back).",
            v("Back button dabao","Piche jao","Go back")))
        add(cmd("Recent apps dikhao", "system_key", "press_system_key", p("key","recents"),
            "Recents = press_system_key(recents).",
            v("Multitask button","Open apps dikhao","Recent wala button")))
        add(cmd("Quick settings kholo", "system_key", "press_system_key",
            p("key","quick_settings"),
            "Quick settings panel = press_system_key(quick_settings).",
            v("Status bar kholo","Quick panel kholo","Shortcut panel")))
        add(cmd("Notification shade kholo", "system_key", "press_system_key",
            p("key","notifications"),
            "Notification panel = press_system_key(notifications).",
            v("Notification bar kholo","Notifications pull down")))
        add(cmd("Power button dabao", "system_key", "press_system_key", p("key","power"),
            "Power menu = press_system_key(power)."))
        add(cmd("Volume up button", "system_key", "press_system_key", p("key","volume_up"),
            "Hardware volume up = press_system_key(volume_up)."))

        // ────────────────────────────────────────────────────────────
        // TOOL 32 ── manage_app_storage
        // ────────────────────────────────────────────────────────────
        add(cmd("WhatsApp ka cache clear karo", "app_mgmt", "manage_app_storage",
            mp("app_name","WhatsApp","action","clear_cache"),
            "manage_app_storage opens App Info > Clear Cache.",
            v("WA cache saaf karo","WhatsApp cache delete karo")))
        add(cmd("Instagram ka data clear karo", "app_mgmt", "manage_app_storage",
            mp("app_name","Instagram","action","clear_data"),
            "Clear data logs out app — WARN user before executing.",
            v("Instagram reset karo","Insta data wipe karo")))
        add(cmd("YouTube uninstall karo", "app_mgmt", "manage_app_storage",
            mp("app_name","YouTube","action","uninstall"),
            "Uninstall → manage_app_storage(uninstall). Confirm first.",
            v("YouTube hata do","YT remove karo","YouTube delete karo")))
        add(cmd("Telegram ka cache clear karo", "app_mgmt", "manage_app_storage",
            mp("app_name","Telegram","action","clear_cache"),
            "App-specific cache clear."))
        add(cmd("Facebook ka storage dekho", "app_mgmt", "open_system_settings",
            p("setting_name","storage"),
            "Storage view → open Storage settings.",
            isEdge = true))

        // ────────────────────────────────────────────────────────────
        // TOOL 33 ── install_store_app
        // ────────────────────────────────────────────────────────────
        add(cmd("Telegram install karo", "install", "install_store_app",
            mp("query","Telegram","criteria","top"),
            "install_store_app searches Play Store and installs top result.",
            v("Telegram download karo","Telegram Play Store se lao")))
        add(cmd("Sabse chhoti VPN app install karo", "install", "install_store_app",
            mp("query","VPN","criteria","lowest_mb"),
            "'Sabse chhoti/kam MB' = criteria=lowest_mb.",
            v("Kam MB wali VPN install karo")))
        add(cmd("Netflix install karo", "install", "install_store_app",
            mp("query","Netflix","criteria","top"),
            "Streaming app install → install_store_app.",
            v("Netflix download karo","Netflix app lao")))
        add(cmd("Free PDF reader install karo", "install", "install_store_app",
            mp("query","free PDF reader","criteria","top"),
            "Qualifiers like 'free' → include in search query."))
        add(cmd("4.5 star se upar wali VPN install karo", "install", "install_store_app",
            mp("query","VPN","criteria","top_rated"),
            "Rating criteria → criteria=top_rated."))

        // ────────────────────────────────────────────────────────────
        // TOOL 34 ── toggle_dark_mode
        // ────────────────────────────────────────────────────────────
        add(cmd("Dark mode on karo", "dark_mode", "toggle_dark_mode", p("enable","true"),
            "toggle_dark_mode via accessibility.",
            v("Dark theme lagao","Night mode on karo","Andhera wala theme")))
        add(cmd("Light mode karo", "dark_mode", "toggle_dark_mode", p("enable","false"),
            "'Light mode' = disable dark mode.",
            v("Dark mode off karo","White theme lagao","Day mode on karo")))
        add(cmd("Aankhon ke liye accha mode lagao", "dark_mode", "toggle_dark_mode",
            p("enable","true"),
            "Eye comfort context → dark mode on."))
        add(cmd("Raat wala theme", "dark_mode", "toggle_dark_mode", p("enable","true"),
            "Night theme colloquial → dark mode."))

        // ────────────────────────────────────────────────────────────
        // TOOL 35 ── calculate
        // ────────────────────────────────────────────────────────────
        add(cmd("2456 × 37 kitna hoga?", "math", "calculate", p("expression","2456 * 37"),
            "Arithmetic → calculate. Use × for multiplication.",
            v("2456 times 37","2456 aur 37 ka product")))
        add(cmd("15% of 8500", "math", "calculate", p("expression","0.15 * 8500"),
            "Percentage → convert to decimal multiplication.",
            v("8500 ka 15 percent","15 pratisat 8500 ka")))
        add(cmd("Square root of 144", "math", "calculate", p("expression","sqrt(144)"),
            "sqrt() function in calculate.",
            v("144 ka square root","Root 144")))
        add(cmd("(1200 + 350) / 5", "math", "calculate", p("expression","(1200 + 350) / 5"),
            "Complex expressions passed as-is."))
        add(cmd("EMI calculate karo 5 lakh 8% 5 saal", "math", "calculate",
            p("expression","(500000*0.08/12*(1+0.08/12)^60)/((1+0.08/12)^60-1)"),
            "EMI formula → convert to math expression."))
        add(cmd("33 ka cube", "math", "calculate", p("expression","33^3"),
            "Power/exponent = ^ operator."))
        add(cmd("1000 rupee mein 7% discount", "math", "calculate",
            p("expression","1000 - (1000 * 0.07)"),
            "Discount calculation → price - (price × rate)."))
        add(cmd("Sin 30 degree", "math", "calculate", p("expression","sin(30)"),
            "Trigonometry → calculate(sin/cos/tan)."))
        add(cmd("Fahrenheit 98.6 to Celsius", "math", "calculate",
            p("expression","(98.6 - 32) * 5/9"),
            "Unit conversion → express as formula."))

        // ────────────────────────────────────────────────────────────
        // TOOL 36 ── manage_contacts
        // ────────────────────────────────────────────────────────────
        add(cmd("Naya contact save karo Amit 9876012345", "contacts", "manage_contacts",
            mp("name","Amit","phone_number","9876012345"),
            "New contact → manage_contacts(name, phone_number).",
            v("Amit ka number save karo","Contact add karo Amit 9876012345")))
        add(cmd("Rahul ka number change karo 9988776655", "contacts", "manage_contacts",
            mp("name","Rahul","phone_number","9988776655","action","update"),
            "Contact update → manage_contacts with action=update."))
        add(cmd("Priya ko contacts se delete karo", "contacts", "manage_contacts",
            mp("name","Priya","action","delete"),
            "Contact deletion → confirm first then manage_contacts(delete).",
            isEdge = true))

        // ────────────────────────────────────────────────────────────
        // TOOL 37 ── show_recent_media
        // ────────────────────────────────────────────────────────────
        add(cmd("Mera last screenshot dikhao", "gallery", "show_recent_media",
            p("media_type","screenshot"),
            "show_recent_media(screenshot) = fullscreen recent screenshot.",
            v("Latest screenshot dekho","Recent screenshot dikhao")))
        add(cmd("Latest photo dikhao", "gallery", "show_recent_media",
            p("media_type","photo"),
            "'Latest photo' = show_recent_media(photo).",
            v("Meri last photo","Recent photo dikhao","Last li hui photo")))
        add(cmd("Recent video dikhao", "gallery", "show_recent_media",
            p("media_type","video"),
            "Latest video = show_recent_media(video)."))

        // ────────────────────────────────────────────────────────────
        // TOOL 38 ── empty_recycle_bin
        // ────────────────────────────────────────────────────────────
        add(cmd("Gallery ka recycle bin clear karo", "gallery", "empty_recycle_bin",
            p("target","gallery"),
            "empty_recycle_bin uses accessibility to clear gallery trash.",
            v("Gallery trash saaf karo","Recycle bin empty karo","Deleted photos hato")))
        add(cmd("Trash empty karo", "gallery", "empty_recycle_bin", p("target","gallery"),
            "Trash = recycle bin."))

        // ────────────────────────────────────────────────────────────
        // TOOL 39 ── set_timer
        // ────────────────────────────────────────────────────────────
        add(cmd("10 minute ka timer laga do", "timer", "set_timer",
            mp("minutes","10","seconds","0","label","Timer"),
            "set_timer: extract minutes from text.",
            v("10 minute timer","10 min ka countdown","10 minute baad batao")))
        add(cmd("30 second ka timer set karo", "timer", "set_timer",
            mp("minutes","0","seconds","30","label","Timer"),
            "Seconds-only → minutes=0.",
            v("30 seconds timer","Tees second ka timer")))
        add(cmd("1 ghante ka timer", "timer", "set_timer",
            mp("minutes","60","seconds","0","label","Timer"),
            "1 hour = 60 minutes.",
            v("Ek ghante ka timer","One hour timer")))
        add(cmd("5 min 30 sec ka timer", "timer", "set_timer",
            mp("minutes","5","seconds","30","label","Timer"),
            "Combined → parse both minutes and seconds."))
        add(cmd("Cooking ke liye 20 minute", "timer", "set_timer",
            mp("minutes","20","seconds","0","label","Cooking Timer"),
            "Context in label → Cooking Timer."))
        add(cmd("Yoga timer 30 minute", "timer", "set_timer",
            mp("minutes","30","seconds","0","label","Yoga Timer"),
            "Exercise timer with descriptive label."))
        add(cmd("Pomodoro timer laga do", "timer", "set_timer",
            mp("minutes","25","seconds","0","label","Pomodoro"),
            "Pomodoro = 25-minute work session."))

        // ────────────────────────────────────────────────────────────
        // TOOL 40 ── navigate_maps
        // ────────────────────────────────────────────────────────────
        add(cmd("Connaught Place ka rasta dikhao", "navigation", "navigate_maps",
            mp("destination","Connaught Place","mode","driving"),
            "navigate_maps default mode=driving.",
            v("Connaught Place navigate karo","CP ka route dikhao")))
        add(cmd("Ghar le chalo", "navigation", "navigate_maps",
            mp("destination","Home","mode","driving"),
            "'Ghar' = 'Home' saved location in Maps.",
            v("Ghar ka rasta dikhao","Home navigate karo")))
        add(cmd("Nearest petrol pump dhundho", "navigation", "navigate_maps",
            mp("destination","nearest petrol pump","mode","driving"),
            "'Nearest' works as destination in Maps.",
            v("Petrol pump kahan hai","Petrol pump dhundho paas mein")))
        add(cmd("Office kaise jaaun?", "navigation", "navigate_maps",
            mp("destination","Office","mode","driving"),
            "'Office' = saved work location.",
            v("Office ka rasta","Office navigate karo")))
        add(cmd("Walk karke India Gate kaise jaaun", "navigation", "navigate_maps",
            mp("destination","India Gate","mode","walking"),
            "'Walk karke / paidal' → mode=walking.",
            v("Paidal India Gate","Walking route India Gate")))
        add(cmd("Metro se Rajiv Chowk", "navigation", "navigate_maps",
            mp("destination","Rajiv Chowk","mode","transit"),
            "Metro/bus/train → mode=transit.",
            v("Bus se kaise jaaun","Train route Rajiv Chowk")))
        add(cmd("Cycle se AIIMS hospital", "navigation", "navigate_maps",
            mp("destination","AIIMS Hospital","mode","bicycling"),
            "Cycle = bicycling mode."))
        add(cmd("Airport ka rasta dikhao", "navigation", "navigate_maps",
            mp("destination","airport","mode","driving"),
            "'Airport' → Maps finds nearest airport."))
        add(cmd("Nearest hospital kahan hai", "navigation", "navigate_maps",
            mp("destination","nearest hospital","mode","driving"),
            "Emergency nearby search → navigate_maps."))
        add(cmd("Taj Mahal Agra navigate karo", "navigation", "navigate_maps",
            mp("destination","Taj Mahal, Agra","mode","driving"),
            "Famous landmark with city → navigate_maps."))

        // ────────────────────────────────────────────────────────────
        // TOOL 41 ── web_search
        // ────────────────────────────────────────────────────────────
        add(cmd("Aaj ka weather kaisa hai?", "search", "web_search",
            p("query","today weather forecast"),
            "Weather → web_search.",
            v("Mausam kaisa hai","Weather report aaj","Aaj barish hogi kya")))
        add(cmd("Google karo Virat Kohli ki age", "search", "web_search",
            p("query","Virat Kohli age"),
            "'Google karo' = web_search.",
            v("Search Virat Kohli age","Kohli ki umar kya hai")))
        add(cmd("IPL 2025 points table", "search", "web_search",
            p("query","IPL 2025 points table"),
            "Sports standings → web_search.",
            v("IPL ka points table","Cricket table dikhao")))
        add(cmd("Dollar to rupee rate", "search", "web_search",
            p("query","USD to INR exchange rate today"),
            "Currency conversion → web_search.",
            v("Dollar ki kimat","USD INR rate")))
        add(cmd("Pasta kaise banate hain?", "search", "web_search",
            p("query","pasta recipe in Hindi"),
            "Recipe → web_search.",
            v("Pasta recipe batao","How to make pasta")))
        add(cmd("Nearest ATM kahan hai", "search", "web_search",
            p("query","nearest ATM location"),
            "Location-based query → web_search.",
            v("ATM kahan milega","ATM dhundho")))
        add(cmd("Sarkari naukri kaise milegi?", "search", "web_search",
            p("query","government job 2025 India"),
            "Jobs search → web_search."))
        add(cmd("Python code kaise likhte hain", "search", "web_search",
            p("query","Python programming tutorial beginners"),
            "Learning query → web_search."))
        add(cmd("Quad Core aur Octa Core mein kya fark hai", "search", "web_search",
            p("query","Quad Core vs Octa Core processor difference"),
            "Technical comparison → web_search."))
        add(cmd("Kal India match hai kya?", "search", "web_search",
            p("query","India cricket match tomorrow schedule"),
            "Sports schedule → web_search."))

        // ────────────────────────────────────────────────────────────
        // TOOL 42 ── open_camera
        // ────────────────────────────────────────────────────────────
        add(cmd("Photo lo", "camera", "open_camera", p("mode","photo"),
            "open_camera(mode=photo) = rear camera photo.",
            v("Camera kholo","Click karo","Pic le lo","Photo click karo")))
        add(cmd("Selfie lo", "camera", "open_camera", p("mode","selfie"),
            "Selfie = front camera = mode=selfie.",
            v("Front camera kholo","Apni photo lo","Selfie click karo")))
        add(cmd("Video record karo", "camera", "open_camera", p("mode","video"),
            "Video recording = open_camera(mode=video).",
            v("Video banana hai","Record karo","Vlog shuru karo")))
        add(cmd("QR code scan karo", "camera", "open_camera", p("mode","qr_scan"),
            "QR scan = open_camera(mode=qr_scan).",
            isEdge = true, v("QR scan karo","Barcode scan")))
        add(cmd("Document scan karo", "camera", "open_camera", p("mode","document"),
            "Document scan = open_camera(mode=document) or Google Drive scan.",
            isEdge = true))
        add(cmd("Slow motion video", "camera", "open_camera", p("mode","slow_motion"),
            "Slow mo = open_camera(mode=slow_motion)."))

        // ────────────────────────────────────────────────────────────
        // TOOL 43 ── set_brightness
        // ────────────────────────────────────────────────────────────
        add(cmd("Brightness full karo", "brightness", "set_brightness", p("level_percent","100"),
            "ALWAYS use set_brightness. NEVER open Settings for brightness.",
            v("Brightness max karo","Screen bright karo","Brightness 100 karo")))
        add(cmd("Screen thodi dim karo", "brightness", "set_brightness", p("level_percent","20"),
            "'Dim' = low = 20.",
            v("Brightness kam karo","Screen dhundha karo")))
        add(cmd("Brightness 70 karo", "brightness", "set_brightness", p("level_percent","70"),
            "Explicit number → level_percent directly."))
        add(cmd("Aankhein dukh rahi hain brightness kam karo", "brightness", "set_brightness",
            p("level_percent","15"),
            "Eye strain → minimal brightness ~15."))
        add(cmd("Raat ho gayi brightness kam karo", "brightness", "set_brightness",
            p("level_percent","20"),
            "Night context → dim screen."))
        add(cmd("Dhoop mein screen nahi dikh raha brightness badhao", "brightness", "set_brightness",
            p("level_percent","100"),
            "Sunlight → max brightness."))
        add(cmd("Auto brightness on karo", "brightness", "open_system_settings",
            p("setting_name","display"),
            "Auto brightness → open Display settings.",
            isEdge = true))

        // ────────────────────────────────────────────────────────────
        // TOOL 44 ── set_dnd
        // ────────────────────────────────────────────────────────────
        add(cmd("DND on karo", "dnd", "set_dnd", p("enable","true"),
            "set_dnd(enable=true) blocks calls and notifications.",
            v("Do Not Disturb on","Silent mode","DND laga do","Disturb mat karo mode")))
        add(cmd("DND band karo", "dnd", "set_dnd", p("enable","false"),
            "'Band karo' = disable DND.",
            v("DND off karo","Silent mode hatao","Disturb mode off karo")))
        add(cmd("Meeting shuru ho rahi hai", "dnd", "set_dnd", p("enable","true"),
            "Meeting context → proactively enable DND + offer timer.",
            v("Main meeting mein hoon","Conference chal raha hai")))
        add(cmd("Main driving kar raha hoon", "dnd", "set_dnd", p("enable","true"),
            "Driving → DND on + optionally navigate_maps."))
        add(cmd("Gaadi chala raha hoon DND on karo", "dnd", "set_dnd", p("enable","true"),
            "Explicit driving + DND → set_dnd(true)."))

        // ────────────────────────────────────────────────────────────
        // TOOL 45 ── open_system_settings
        // ────────────────────────────────────────────────────────────
        add(cmd("Developer options kholo", "settings", "open_system_settings",
            p("setting_name","developer"),
            "Developer options → open_system_settings(developer).",
            v("Dev options open karo","Developer settings")))
        add(cmd("Accessibility settings kholo", "settings", "open_system_settings",
            p("setting_name","accessibility"),
            "Accessibility → open_system_settings(accessibility).",
            v("Accessibility open karo","Accessibility menu")))
        add(cmd("Battery settings dikhao", "settings", "open_system_settings",
            p("setting_name","battery"),
            "Battery settings page."))
        add(cmd("Display settings kholo", "settings", "open_system_settings",
            p("setting_name","display"),
            "Display settings.",
            v("Screen settings","Monitor settings kholo")))
        add(cmd("Privacy settings", "settings", "open_system_settings",
            p("setting_name","privacy"),
            "Privacy settings."))
        add(cmd("About phone dikhao", "settings", "open_system_settings",
            p("setting_name","about"),
            "About phone / device info."))
        add(cmd("Storage settings kholo", "settings", "open_system_settings",
            p("setting_name","storage"),
            "Storage info page."))
        add(cmd("Network settings kholo", "settings", "open_system_settings",
            p("setting_name","network"),
            "Network & internet settings."))
        add(cmd("Sound settings", "settings", "open_system_settings",
            p("setting_name","sound"),
            "Sound/audio settings page."))
        add(cmd("Location settings on karo", "settings", "open_system_settings",
            p("setting_name","location"),
            "Location toggle → open_system_settings(location)."))
        add(cmd("App permissions dikhao", "settings", "open_system_settings",
            p("setting_name","privacy"),
            "App permissions → open Privacy settings."))

        // ────────────────────────────────────────────────────────────
        // TOOL 46 ── add_calendar_event
        // ────────────────────────────────────────────────────────────
        add(cmd("Kal 3 baje meeting add karo", "calendar", "add_calendar_event",
            mp("title","Meeting","description",""),
            "add_calendar_event: extract title from context, time from text.",
            v("Calendar mein meeting daal do","3 PM ki meeting schedule karo")))
        add(cmd("Doctor appointment 5 April 11 AM", "calendar", "add_calendar_event",
            mp("title","Doctor Appointment","description","11 AM, 5 April"),
            "Medical → add_calendar_event with descriptive title."))
        add(cmd("Behen ki shaadi 20 March calendar mein", "calendar", "add_calendar_event",
            mp("title","Behen Ki Shaadi","description","Family wedding"),
            "Wedding/family event → add_calendar_event."))
        add(cmd("Birthday reminder Priya 15 August", "calendar", "add_calendar_event",
            mp("title","Priya Ka Birthday","description","Birthday reminder"),
            "Birthday reminder as calendar event."))
        add(cmd("Gym class Monday Wednesday Friday 6 AM", "calendar", "add_calendar_event",
            mp("title","Gym Class","description","Recurring Mon/Wed/Fri 6 AM"),
            "Recurring event → add_calendar_event and note recurrence."))

        // ────────────────────────────────────────────────────────────
        // TOOL 47 ── send_email
        // ────────────────────────────────────────────────────────────
        add(cmd("boss@company.com ko email karo subject Meeting", "email", "send_email",
            mp("to","boss@company.com","subject","Meeting","body",""),
            "send_email with explicit address → to, subject, body params.",
            v("Email bhejo boss@company.com")))
        add(cmd("HR ko mail bhejo kal nahi aa paunga", "email", "send_email",
            mp("to","","subject","Leave Request","body","Kal nahi aa paunga"),
            "Role-based recipient → ask for email if not in contacts.",
            isEdge = true))
        add(cmd("Client ko quotation email karo", "email", "send_email",
            mp("to","","subject","Quotation","body","Please find the quotation attached."),
            "Professional email → send_email."))

        // ────────────────────────────────────────────────────────────
        // TOOL 48 ── read_recent_sms
        // ────────────────────────────────────────────────────────────
        add(cmd("Mere last SMS padho", "sms", "read_recent_sms", p("count","1"),
            "read_recent_sms(count=1) for last message.",
            v("Last message padho","Recent SMS sunao")))
        add(cmd("5 SMS padho", "sms", "read_recent_sms", p("count","5"),
            "Number before SMS → count param."))
        add(cmd("Bank wala OTP padho", "sms", "read_recent_sms", p("count","3"),
            "OTP query → read_recent_sms and look for OTP in response.",
            v("OTP kya aaya","Last OTP SMS padho")))
        add(cmd("Koi naya message aaya kya?", "sms", "read_recent_sms", p("count","5"),
            "New message check → read_recent_sms.",
            v("Koi SMS aaya","Messages check karo")))

        // ────────────────────────────────────────────────────────────
        // TOOL 49 ── query_contact
        // ────────────────────────────────────────────────────────────
        add(cmd("Rahul ka number kya hai?", "contacts", "query_contact", p("name","Rahul"),
            "query_contact returns phone number and info.",
            v("Rahul ka phone number","Rahul ka contact info")))
        add(cmd("Priya Singh ka address dikhao", "contacts", "query_contact",
            p("name","Priya Singh"),
            "Full name → query_contact as-is."))
        add(cmd("Doctor ka contact kya hai?", "contacts", "query_contact",
            p("name","Doctor"),
            "Role-based → search 'Doctor' in contacts."))
        add(cmd("Maa ka number kya hai?", "contacts", "query_contact", p("name","Maa"),
            "Family relationship name → query_contact."))

        // ────────────────────────────────────────────────────────────
        // TOOL 50 ── emergency_sos
        // ────────────────────────────────────────────────────────────
        add(cmd("Ambulance bulao", "emergency", "emergency_sos", p("target","108"),
            "Ambulance = 108. IMMEDIATELY call without confirmation.",
            v("Ambulance call karo","108 dial karo")))
        add(cmd("Police bulao", "emergency", "emergency_sos", p("target","100"),
            "Police = 100. Immediate.",
            v("Police call karo","100 pe call karo","Police bhejo")))
        add(cmd("Fire brigade call karo", "emergency", "emergency_sos", p("target","101"),
            "Fire = 101.",
            v("Fire brigade","Aag lagi hai 101")))
        add(cmd("Help! SOS!", "emergency", "emergency_sos", p("target","112"),
            "Panic SOS → 112 IMMEDIATELY without any delay.",
            v("Emergency!","Bachao!","Help me!","112 pe call karo")))
        add(cmd("Emergency number dial karo", "emergency", "emergency_sos", p("target","112"),
            "Unified emergency = 112.",
            v("112 pe call karo","Emergency call")))

        // ────────────────────────────────────────────────────────────
        // TOOL 51 ── create_quick_note
        // ────────────────────────────────────────────────────────────
        add(cmd("Note karo doodh aur bread lana hai", "notes", "create_quick_note",
            mp("note_text","Doodh aur bread lana hai","title","Shopping"),
            "create_quick_note saves to local storage.",
            v("Shopping list note karo","Grocery note: doodh bread")))
        add(cmd("Idea note karo weather app banana hai", "notes", "create_quick_note",
            mp("note_text","Weather app banana hai","title","Idea"),
            "Idea note → title='Idea'."))
        add(cmd("Meeting notes save karo points the: budget Q4 revised", "notes", "create_quick_note",
            mp("note_text","Budget Q4 revised","title","Meeting Notes"),
            "Meeting notes → title='Meeting Notes'."))
        add(cmd("Password note karo MyApp@123", "notes", "create_quick_note",
            mp("note_text","MyApp@123","title","Password"),
            "Sensitive note → create_quick_note, remind user it's local only."))

        // ────────────────────────────────────────────────────────────
        // TOOL 52 ── read_notifications
        // ────────────────────────────────────────────────────────────
        add(cmd("Notifications padho", "notifications", "read_notifications",
            p("count","5"),
            "read_notifications from NotificationListenerService.",
            v("Notifications sunao","Notification kya hai","Status bar kya hai")))
        add(cmd("Kya koi message aaya?", "notifications", "read_notifications",
            p("count","5"),
            "Message check → read_notifications, filter messaging apps in response.",
            v("Koi notification aaya","New message hai kya?")))
        add(cmd("WhatsApp ke kitne notifications hain?", "notifications", "read_notifications",
            p("count","10"),
            "App-specific count → read then filter by app."))
        add(cmd("Kaun kaun se apps ke notifications hain?", "notifications", "read_notifications",
            p("count","20"),
            "All notifications → read_notifications and list apps."))
        add(cmd("Unread notifications kitne hain?", "notifications", "read_notifications",
            p("count","20"),
            "Unread count → read_notifications and count."))

        // ══════════════════════════════════════════════════════════
        //  EXTRA CATEGORIES
        // ══════════════════════════════════════════════════════════

        // CHITCHAT / CONVERSATIONAL
        add(cmd("Kya haal hai?", "chitchat", "CONVERSATIONAL", emptyMap(),
            "Casual greeting → warm feminine Hindi response. No tool.",
            v("How are you","Sab theek hai?","Kaisi ho tum")))
        add(cmd("Joke sunao", "chitchat", "CONVERSATIONAL", emptyMap(),
            "Entertainment → tell a Hindi joke. No tool.",
            v("Koi joke batao","Kuch funny sunao")))
        add(cmd("Main thak gaya hoon", "chitchat", "CONVERSATIONAL", emptyMap(),
            "Fatigue → empathize, offer music/alarm/rest tips."))
        add(cmd("Mujhe neend aa rahi hai", "chitchat", "CONVERSATIONAL", emptyMap(),
            "Sleepy → empathize, offer to set alarm or play sleep music."))
        add(cmd("Aaj ka din acha nahi gaya", "chitchat", "CONVERSATIONAL", emptyMap(),
            "Emotional low → respond with warmth and care."))
        add(cmd("Tumhara naam kya hai?", "chitchat", "CONVERSATIONAL", emptyMap(),
            "'Main AURA hoon' — no tool needed."))
        add(cmd("Tum kya kya kar sakti ho?", "chitchat", "CONVERSATIONAL", emptyMap(),
            "Capability query → list categories warmly."))
        add(cmd("Shukriya bahut bahut", "chitchat", "CONVERSATIONAL", emptyMap(),
            "Gratitude → 'Mention nahi Boss!'"))

        // INFORMATION QUERIES
        add(cmd("Aaj ki date kya hai?", "info", "CONVERSATIONAL", emptyMap(),
            "Date → from system timestamp in system prompt. No tool.",
            v("Date batao","Today's date kya hai","Aaj kaunsa date hai")))
        add(cmd("Time kya hua?", "info", "CONVERSATIONAL", emptyMap(),
            "Time → from system timestamp. No tool.",
            v("Kya waqt ho gaya","Time kya hai abhi","Time batao")))
        add(cmd("Aaj kaunsa din hai?", "info", "CONVERSATIONAL", emptyMap(),
            "Day → from system timestamp. No tool.",
            v("Kaunsa weekday hai","Monday hai ya Tuesday?")))

        // COMPOUND COMMANDS
        add(cmd("Volume full karo aur DND on karo", "compound", "set_volume+set_dnd",
            mp("level_percent","100","enable","true"),
            "Compound: execute BOTH tools sequentially. Never skip second step.",
            v("Sound max karo aur silent mode")))
        add(cmd("WiFi off karo aur Bluetooth on karo", "compound", "toggle_wifi+toggle_bluetooth",
            mp("wifi","false","bt","true"),
            "Sequential compound: WiFi off THEN BT on."))
        add(cmd("Screenshot lo aur Ansh ko WhatsApp karo", "compound",
            "take_screenshot+send_whatsapp_media",
            mp("contact_name","Ansh","media_type","screenshot"),
            "Two-step: take screenshot THEN send via WhatsApp."))
        add(cmd("Gana pause karo aur 30 min timer lagao", "compound", "media_control+set_timer",
            mp("action","pause","minutes","30"),
            "Pause music THEN set timer."))
        add(cmd("Brightness kam karo aur dark mode on karo", "compound",
            "set_brightness+toggle_dark_mode",
            mp("level_percent","20","enable","true"),
            "Night comfort: dim + dark mode. Sequential."))
        add(cmd("Alarm laga do 7 baje aur DND on karo", "compound",
            "create_alarm+set_dnd",
            mp("hour","7","minute","0","enable","true"),
            "Night routine: alarm + DND. Execute both."))

        // AMBIGUOUS (need clarification)
        add(cmd("Band karo", "ambiguous", "CLARIFY", emptyMap(),
            "Too vague: 'Band karo' could be WiFi/volume/torch etc. ALWAYS ask first.",
            isEdge = true))
        add(cmd("Chalu karo", "ambiguous", "CLARIFY", emptyMap(),
            "Too vague → ask what to enable.", isEdge = true))
        add(cmd("Delete karo", "ambiguous", "CLARIFY", emptyMap(),
            "Destructive + vague → ask what to delete.", isEdge = true))
        add(cmd("Send karo", "ambiguous", "CLARIFY", emptyMap(),
            "No content/recipient → ask both.", isEdge = true))
        add(cmd("Hata do", "ambiguous", "CLARIFY", emptyMap(),
            "'Hata do' → notification? App? File? Ask.", isEdge = true))
        add(cmd("Set karo 100", "ambiguous", "CLARIFY", emptyMap(),
            "100 of what? Volume? Brightness? Ask.", isEdge = true))

        // OUT OF SCOPE (graceful decline)
        add(cmd("Chai bana do", "out_of_scope", "GRACEFUL_DECLINE", emptyMap(),
            "Physical task → 'Boss chai nahi bana sakti, Zomato se order karun?'"))
        add(cmd("Meri car start karo", "out_of_scope", "GRACEFUL_DECLINE", emptyMap(),
            "Car control not available → graceful decline."))
        add(cmd("Ghar ka darwaza band karo", "out_of_scope", "GRACEFUL_DECLINE", emptyMap(),
            "Smart home not wired → decline gracefully."))
        add(cmd("Mera homework karo", "out_of_scope", "GRACEFUL_DECLINE", emptyMap(),
            "Academic work → explain limitation, offer web_search help."))

        // FOLLOW-UP / MULTI-TURN
        add(cmd("Haan", "followup", "CONFIRMATIONAL", emptyMap(),
            "'Haan/Yes/Theek hai' = user confirmed → execute proposed action."))
        add(cmd("Nahi", "followup", "CONFIRMATIONAL", emptyMap(),
            "'Nahi/No/Mat karo' = declined → abort gracefully."))
        add(cmd("Phir se karo", "followup", "CONVERSATIONAL", emptyMap(),
            "'Phir se' = repeat last executed action."))
        add(cmd("Cancel karo", "followup", "CONFIRMATIONAL", emptyMap(),
            "Cancel = abort current pending action."))
        add(cmd("Wahi karo jo bola tha", "followup", "CONVERSATIONAL", emptyMap(),
            "References previous turn → recall last proposed action and execute."))

        // EMERGENCY - MENTAL HEALTH
        add(cmd("Mujhe kuch hua hai help karo", "emergency", "emergency_sos",
            p("target","112"),
            "Distress signal → 112 + respond with empathy and emergency contacts."))
        add(cmd("Suicide helpline number batao", "emergency", "web_search",
            p("query","suicide helpline India iCall Vandrevala"),
            "Mental health crisis → web_search for helplines + respond with empathy."))

        // TYPOS / MISSPELLED
        add(cmd("Blutut on karo", "bluetooth", "toggle_bluetooth", p("enable","true"),
            "'Blutut' = Bluetooth (phonetic).", v("Blutooth on karo")))
        add(cmd("Wifai on kardo", "wifi", "toggle_wifi", p("enable","true"),
            "'Wifai' = WiFi phonetic."))
        add(cmd("Flaslight on karo", "flashlight", "toggle_flashlight", p("enable","true"),
            "'Flaslight' = Flashlight typo."))
        add(cmd("youtueb par song chalao", "music", "play_youtube",
            p("query","song"),
            "'youtueb' = YouTube typo."))
        add(cmd("Instgram kholo", "app_launch", "open_app", p("app_name","Instagram"),
            "'Instgram' = Instagram typo."))
        add(cmd("Notifcation padho", "notifications", "read_notifications", p("count","5"),
            "'Notifcation' = Notification typo."))
        add(cmd("Calander mein event add karo", "calendar", "add_calendar_event",
            mp("title","Event","description",""),
            "'Calander' = Calendar typo."))

        // PROACTIVE CONTEXT
        add(cmd("Main gym ja raha hoon", "context", "play_youtube",
            p("query","gym workout music"),
            "Gym context → proactively play workout music."))
        add(cmd("Raat ho gayi hai", "context", "set_brightness",
            p("level_percent","20"),
            "Night → dim screen proactively + suggest dark mode."))
        add(cmd("Subah ho gayi", "context", "CONVERSATIONAL", emptyMap(),
            "Morning → respond warmly, offer weather/news."))
        add(cmd("Meeting khatam ho gayi", "context", "set_dnd", p("enable","false"),
            "Meeting done → suggest turning off DND."))
    }

    // ── HELPER BUILDERS ──────────────────────────────────────────────
    private fun cmd(
        rawText: String, category: String, toolName: String,
        params: Map<String, String>, lesson: String,
        v: List<String> = emptyList()
    ) = TrainingCommand(rawText, category, toolName, params, lesson, isEdgeCase = false, variants = v)

    private fun cmd(
        rawText: String, category: String, toolName: String,
        params: Map<String, String>, lesson: String,
        isEdge: Boolean, v: List<String> = emptyList()
    ) = TrainingCommand(rawText, category, toolName, params, lesson, isEdgeCase = isEdge, variants = v)

    private fun cmd(
        rawText: String, category: String, toolName: String,
        params: Map<String, String>, lesson: String,
        v: List<String>, isEdge: Boolean
    ) = TrainingCommand(rawText, category, toolName, params, lesson, isEdgeCase = isEdge, variants = v)

    private fun p(k: String, v: String): Map<String, String> = mapOf(k to v)
    private fun mp(vararg pairs: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (i in pairs.indices step 2) map[pairs[i]] = pairs[i + 1]
        return map
    }
    private fun v(vararg texts: String): List<String> = texts.toList()

    // ── QUERY HELPERS ─────────────────────────────────────────────────
    fun allToolNames(): Set<String> = commands.map { it.toolName }.toSet()
    fun byCategory(cat: String) = commands.filter { it.category == cat }
    fun byTool(tool: String) = commands.filter { it.toolName == tool }
    fun edgeCases() = commands.filter { it.isEdgeCase }
    fun regularCommands() = commands.filter { !it.isEdgeCase }
    fun lessonsMap(): Map<String, List<String>> =
        commands.groupBy({ it.toolName }, { it.lesson })

    /** All phrasings for a tool: primary + variants flattened. */
    fun allPhrasingsForTool(toolName: String): List<String> =
        commands.filter { it.toolName == toolName }
            .flatMap { listOf(it.rawText) + it.variants }

    fun stats(): String = buildString {
        val totalVariants = commands.sumOf { it.variants.size }
        appendLine("📊 AuraCommandCorpus v4.0 Statistics:")
        appendLine("  Primary commands : ${commands.size}")
        appendLine("  Total variants   : $totalVariants")
        appendLine("  Total phrasings  : ${commands.size + totalVariants}")
        appendLine("  Edge cases       : ${edgeCases().size}")
        appendLine("  Unique tools     : ${allToolNames().size}")
        appendLine("  Categories       : ${commands.map { it.category }.toSet().size}")
    }
}
