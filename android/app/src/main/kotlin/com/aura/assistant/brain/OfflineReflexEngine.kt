package com.aura.assistant.brain

import android.content.Context
import com.aura.assistant.ai.IshaContactMemoryManager
import com.aura.assistant.ai.IshaToolRegistry
import com.google.gson.JsonObject
import java.util.regex.Pattern

data class OfflineReflexResult(
    val toolName: String,
    val naturalResponse: String,
    val success: Boolean
)

/**
 * High-speed, zero-latency on-device execution engine for common Android tasks.
 * Executes basic hardware, app, and system actions in <20ms completely offline
 * without invoking Gemini cloud endpoints.
 */
object OfflineReflexEngine {

    /**
     * Synchronous blocking execution helper for legacy non-coroutine contexts.
     */
    @JvmStatic
    fun tryExecuteSync(context: Context, prompt: String, execute: Boolean = true): OfflineReflexResult? =
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            tryExecute(context, prompt, execute)
        }

    /**
     * Checks if the user prompt can be executed offline.
     * Returns an OfflineReflexResult if handled, or null if cloud LLM reasoning is required.
     * If [execute] is false, simulates success without invoking hardware actions (useful for test suites & training batches).
     */
    suspend fun tryExecute(context: Context, prompt: String, execute: Boolean = true): OfflineReflexResult? {
        val clean = prompt.lowercase().trim()

        // Deterministic Fast Intent Classification
        val routed = IntentRouter.route(prompt)
        if (routed.type == IntentType.ATTENTION_CHECK) {
            // Use ConversationalReactionEngine for varied, natural attention-check responses
            val reaction = ConversationalReactionEngine.selectAcknowledgement(ReactionSituation.ATTENTION_CHECK)
            return OfflineReflexResult(
                toolName = "attention_check",
                naturalResponse = reaction,
                success = true
            )
        }

        // Guard: Never trigger offline reflex on complaints, questions, or problem descriptions
        val isNegativeOrComplaint = clean.contains("kharab") || clean.contains("problem") ||
                                   clean.contains("nahi chal") || clean.contains("dikkat") ||
                                   clean.contains("issue") || clean.contains("toot") ||
                                   clean.contains("kya hua") || clean.contains("kyu")
        if (isNegativeOrComplaint) return null

        // Guard: Never trigger single-action offline reflex on multi-task / compound / sequential workflows!
        // Multi-task commands require Gemini LLM reasoning and multi-step execution.
        if (isCompoundOrMultiActionCommand(clean)) {
            android.util.Log.i("OfflineReflexEngine", "Multi-task compound command detected: '$prompt'. Routing to Gemini reasoning engine.")
            return null
        }

        // 0. Cross-Device Command Interceptor (e.g. "Pixel par torch jalao", "OnePlus par WhatsApp open karo", "Phone B par volume 80 karo")
        val otherDevs = com.aura.assistant.sync.IshaDeviceRegistry.getOtherDevices()
        val matchedDev = otherDevs.firstOrNull { dev ->
            val devAlias = dev.deviceAlias.lowercase()
            val devModel = dev.model.lowercase()
            val devMfr = dev.manufacturer.lowercase()
            clean.contains(devAlias) || clean.contains(devModel) || (devMfr.length >= 3 && clean.contains(devMfr))
        }

        val isGenericCross = clean.contains("dusre phone") || clean.contains("dusra phone") ||
                            clean.contains("other phone") || clean.contains("second phone") ||
                            clean.contains("phone a") || clean.contains("phone b") ||
                            clean.contains("phone 1") || clean.contains("phone 2") ||
                            clean.contains("lava") || clean.contains("samsung")

        val isCrossDevice = matchedDev != null || isGenericCross
        if (isCrossDevice) {
            val targetQuery = matchedDev?.deviceAlias
                ?: com.aura.assistant.sync.IshaDeviceRegistry.resolveTargetDevice(clean)?.deviceAlias
                ?: when {
                    clean.contains("phone a") || clean.contains("phone 1") || clean.contains("lava") -> "Phone A"
                    clean.contains("phone b") || clean.contains("phone 2") || clean.contains("samsung") -> "Phone B"
                    else -> "other"
                }

            // Robust NLP action prompt extraction: strip target device references cleanly
            var s = clean
            matchedDev?.let { dev ->
                s = s.replace(dev.deviceAlias.lowercase(), " ")
                     .replace(dev.model.lowercase(), " ")
                     .replace(dev.manufacturer.lowercase(), " ")
            }
            s = s.replace("phone a", " ").replace("phone b", " ")
                 .replace("phone 1", " ").replace("phone 2", " ")
                 .replace("lava", " ").replace("samsung", " ")
                 .replace("dusre phone", " ").replace("dusra phone", " ")
                 .replace("wale phone", " ").replace("wala phone", " ")
                 .replace("yaani", " ").trim()

            // Remove leading prepositions (e.g. "par ", "pe ", "mein ", "me ", "on ", "pr ")
            val actionPrompt = s.replace(Regex("""^(par|pr|pe|on|mein|me)\s+""", RegexOption.IGNORE_CASE), "").trim()

            if (execute) {
                val (action, params) = when {
                    actionPrompt.contains("whatsapp") -> "open_app" to mapOf("app_name" to "WhatsApp", "package_name" to "com.whatsapp")
                    actionPrompt.contains("torch") || actionPrompt.contains("flashlight") -> {
                        val turnOn = !(actionPrompt.contains("off") || actionPrompt.contains("band"))
                        "toggle_flashlight" to mapOf("state" to if (turnOn) "on" else "off")
                    }
                    actionPrompt.contains("volume") -> {
                        val num = Regex("[0-9]+").find(actionPrompt)?.value?.toIntOrNull() ?: 50
                        "set_volume" to mapOf("level" to num)
                    }
                    actionPrompt.contains("youtube") || actionPrompt.contains("song") || actionPrompt.contains("play") || actionPrompt.contains("video") || actionPrompt.contains("gaana") -> {
                        val urlRegex = Regex("""https?://[^\s]+""")
                        val url = urlRegex.find(actionPrompt)?.value ?: ""
                        "play_youtube" to mapOf("query" to actionPrompt, "url" to url)
                    }
                    actionPrompt.contains("siren") -> {
                        val isOff = actionPrompt.contains("band") || actionPrompt.contains("off") || actionPrompt.contains("roko") || actionPrompt.contains("stop")
                        (if (isOff) "stop_siren" else "play_siren") to emptyMap<String, Any>()
                    }
                    actionPrompt.contains("hotspot") -> {
                        val isOff = actionPrompt.contains("band") || actionPrompt.contains("off")
                        "toggle_hotspot" to mapOf("enable" to !isOff)
                    }
                    actionPrompt.contains("unlock") || actionPrompt.contains("kholo") -> {
                        val unlockMap = mutableMapOf<String, Any>()
                        val patMatch = Regex("""pattern\s*([0-9,\s-]+)""", RegexOption.IGNORE_CASE).find(actionPrompt)?.groupValues?.get(1)?.trim()
                        val pinMatch = Regex("""\b\d{4,6}\b""").find(actionPrompt)?.value
                        if (!patMatch.isNullOrBlank()) {
                            unlockMap["pattern"] = patMatch
                        } else if (!pinMatch.isNullOrBlank()) {
                            unlockMap["pin"] = pinMatch
                        }
                        "unlock_device" to unlockMap
                    }
                    actionPrompt.contains("lock") || actionPrompt.contains("band karo") -> {
                        "lock_device" to emptyMap<String, Any>()
                    }
                    else -> "generic_command" to mapOf("prompt" to actionPrompt)
                }

                val res = com.aura.assistant.sync.IshaCrossDeviceBridge.dispatchRemoteCommand(
                    context = context,
                    targetDeviceQuery = targetQuery,
                    action = action,
                    params = params,
                    rawPrompt = actionPrompt
                )
                return OfflineReflexResult("dispatch_remote_command", res.message, res.success)
            } else {
                return OfflineReflexResult("dispatch_remote_command", "$targetQuery par task bhej diya gaya hai Boss!", true)
            }
        }

        // 1. Flashlight / Torch (Requires explicit action verb or direct noun)
        val isTorchMention = clean.contains("torch") || clean.contains("flashlight") || clean.contains("flash light")
        val isTorchAction = clean.contains("on") || clean.contains("chalu") || clean.contains("jala") ||
                            clean.contains("chala") || clean.contains("khol") || clean.contains("start") ||
                            clean.contains("band") || clean.contains("off") || clean.contains("bujha") ||
                            clean.contains("rok") || clean.contains("krdo") || clean.contains("kardo")
        val isTorchExact = clean == "torch" || clean == "flashlight" || clean == "flash light" || clean == "torch light"
        val isTorchCommand = isTorchMention && (isTorchAction || isTorchExact)
        if (isTorchCommand) {
            val isOff = clean.contains("band") || clean.contains("off") || clean.contains("bujha") || clean.contains("rok")
            val args = JsonObject().apply { addProperty("enable", !isOff) }
            val result = if (execute) IshaToolRegistry.executeTool(context, "toggle_flashlight", args) else JsonObject().apply { addProperty("status", "success") }
            val success = isSuccess(result)
            // ConversationalReactionEngine: natural personality variety on success/failure
            val resp = if (success) {
                val reaction = ConversationalReactionEngine.selectAcknowledgement(ReactionSituation.SUCCESS)
                val detail = if (!isOff) "Torch chala di Boss! 🔦" else "Torch band kar di Boss!"
                "$detail ${reaction}"
            } else {
                ConversationalReactionEngine.selectAcknowledgement(ReactionSituation.FAILURE)
            }
            return OfflineReflexResult("toggle_flashlight", resp, success)
        }

        // 2. Battery Status
        if (clean.contains("battery") || clean.contains("charge kitna") || clean.contains("charging")) {
            val result = if (execute) IshaToolRegistry.executeTool(context, "get_battery_status", JsonObject()) else JsonObject().apply { addProperty("level", 85); addProperty("is_charging", false); addProperty("status", "success") }
            val level = result.get("level")?.asInt ?: 50
            val isCharging = result.get("is_charging")?.asBoolean ?: false
            val chargingText = if (isCharging) " (charger plugged in hai ⚡)" else ""
            val warning = if (level <= 20) " Kafi kam hai, please charger lagaiye!" else ""
            val resp = "Aapke phone ki battery $level% hai Boss$chargingText.$warning"
            return OfflineReflexResult("get_battery_status", resp, true)
        }

        // 3. Volume Control
        if (clean.contains("volume") || clean.contains("awaaz") || clean.contains("awaj") || clean.contains("sound")) {
            val percent = extractNumber(clean) ?: when {
                clean.contains("full") || clean.contains("max") || clean.contains("tez") -> 100
                clean.contains("mute") || clean.contains("silent") || clean.contains("band") || clean.contains("zero") -> 0
                clean.contains("aadhi") || clean.contains("half") || clean.contains("50") -> 50
                clean.contains("kam") || clean.contains("down") || clean.contains("ghata") -> 30
                clean.contains("badha") || clean.contains("up") -> 80
                else -> null
            }
            if (percent != null) {
                val args = JsonObject().apply { addProperty("percent", percent) }
                val result = if (execute) IshaToolRegistry.executeTool(context, "set_volume", args) else JsonObject().apply { addProperty("status", "success") }
                val success = isSuccess(result)
                val resp = if (success) {
                    "Awaaz $percent% par set kar di hai Boss! 🔊"
                } else {
                    "Boss, volume change karne me pareshani aayi."
                }
                return OfflineReflexResult("set_volume", resp, success)
            }
        }

        // 4. Brightness Control
        if (clean.contains("brightness") || clean.contains("roshni") || clean.contains("screen light")) {
            val percent = extractNumber(clean) ?: when {
                clean.contains("full") || clean.contains("max") || clean.contains("tez") -> 100
                clean.contains("zero") || clean.contains("min") -> 10
                clean.contains("aadhi") || clean.contains("half") -> 50
                clean.contains("kam") || clean.contains("dim") || clean.contains("down") -> 25
                clean.contains("badha") || clean.contains("up") -> 85
                else -> null
            }
            if (percent != null) {
                val args = JsonObject().apply { addProperty("percent", percent) }
                val result = if (execute) IshaToolRegistry.executeTool(context, "set_brightness", args) else JsonObject().apply { addProperty("status", "success") }
                val success = isSuccess(result)
                val resp = if (success) {
                    "Screen brightness $percent% par set kar di hai Boss! ☀️"
                } else {
                    "Boss, brightness badalne ke liye system permission ki zaroorat hai."
                }
                return OfflineReflexResult("set_brightness", resp, success)
            }
        }

        // 5. WiFi Toggle — requires explicit action verbs.
        //    Queries like "wifi ka naam batao" or "wifi password kya hai" must fall through
        //    to Gemini which handles them via get_device_connectivity_and_usage.
        val isWifiQuery = clean.contains("naam") || clean.contains("name") ||
            clean.contains("password") || clean.contains("pasword") ||
            clean.contains("kya hai") || clean.contains("kaunsa") || clean.contains("kaun sa") ||
            clean.contains("kitna") || clean.contains("speed") || clean.contains("connected") ||
            (clean.contains("batao") && !clean.contains(" on") && !clean.contains("chalu"))
        val isWifiToggle = (clean.contains("wifi") || clean.contains("wi-fi")) &&
            !isWifiQuery &&
            (clean.contains(" on") || clean.startsWith("on ") || clean.contains("off") ||
             clean.contains("band") || clean.contains("chalu") || clean.contains("chalao") ||
             clean.contains("shuru") || clean.contains("toggle") ||
             clean.contains("connect") || clean.contains("disconnect") ||
             clean.contains("enable") || clean.contains("disable"))
        if (isWifiToggle) {
            val isOff = clean.contains("band") || clean.contains("off") || clean.contains("disconnect") || clean.contains("disable")
            val args = JsonObject().apply { addProperty("enable", !isOff) }
            val result = if (execute) IshaToolRegistry.executeTool(context, "toggle_wifi", args) else JsonObject().apply { addProperty("status", "success") }
            val success = isSuccess(result)
            val resp = if (success) {
                if (!isOff) "WiFi on kar diya hai Boss! 📶" else "WiFi band kar diya hai Boss!"
            } else {
                "Boss, WiFi setting change karne ke liye page khol rahi hoon."
            }
            return OfflineReflexResult("toggle_wifi", resp, success)
        }

        // 6. Bluetooth Toggle
        if (clean.contains("bluetooth")) {
            val isOff = clean.contains("band") || clean.contains("off") || clean.contains("disconnect")
            val args = JsonObject().apply { addProperty("enable", !isOff) }
            val result = if (execute) IshaToolRegistry.executeTool(context, "toggle_bluetooth", args) else JsonObject().apply { addProperty("status", "success") }
            val success = isSuccess(result)
            val resp = if (success) {
                if (!isOff) "Bluetooth chalu kar diya hai Boss! ᛒ" else "Bluetooth band kar diya hai Boss!"
            } else {
                "Boss, Bluetooth toggle nahi ho saka."
            }
            return OfflineReflexResult("toggle_bluetooth", resp, success)
        }

        // 7. Hotspot Toggle
        if (clean.contains("hotspot")) {
            val isOff = clean.contains("band") || clean.contains("off")
            val args = JsonObject().apply { addProperty("enable", !isOff) }
            val result = if (execute) IshaToolRegistry.executeTool(context, "toggle_hotspot", args) else JsonObject().apply { addProperty("status", "success") }
            val success = isSuccess(result)
            val resp = if (success) {
                if (!isOff) "Hotspot shuru kar diya hai Boss! 📶" else "Hotspot band kar diya hai Boss!"
            } else {
                "Boss, hotspot toggle nahi ho saka. Settings check karein."
            }
            return OfflineReflexResult("toggle_hotspot", resp, success)
        }

        // 7b. Siren Control
        if (clean.contains("siren")) {
            val isOff = clean.contains("band") || clean.contains("off") || clean.contains("roko") || clean.contains("stop")
            val toolName = if (isOff) "stop_siren" else "play_siren"
            val result = if (execute) IshaToolRegistry.executeTool(context, toolName, JsonObject()) else JsonObject().apply { addProperty("status", "success") }
            val success = isSuccess(result)
            val resp = if (!isOff) "Siren bajna shuru ho gaya hai Boss! 🚨" else "Siren band kar diya hai Boss! 🛑"
            return OfflineReflexResult(toolName, resp, success)
        }

        // 8. Screenshot
        if (clean.contains("screenshot") || clean.contains("screen shot")) {
            val result = if (execute) IshaToolRegistry.executeTool(context, "take_screenshot", JsonObject()) else JsonObject().apply { addProperty("status", "success") }
            val success = isSuccess(result)
            val resp = if (success) {
                "Screenshot le liya hai Boss, gallery me save ho gaya! 📸"
            } else {
                "Boss, accessibility service activate honi chahiye screenshot ke liye."
            }
            return OfflineReflexResult("take_screenshot", resp, success)
        }

        // 9. Alarm Creation
        if (clean.contains("alarm") || clean.contains("alaram")) {
            val hour = extractHour(clean)
            val minute = extractMinute(clean) ?: 0
            if (hour != null) {
                val args = JsonObject().apply {
                    addProperty("hour", hour)
                    addProperty("minute", minute)
                    addProperty("message", "AURA Alarm")
                }
                val result = if (execute) IshaToolRegistry.executeTool(context, "create_alarm", args) else JsonObject().apply { addProperty("status", "success") }
                val success = isSuccess(result)
                val minStr = if (minute > 0) ":${minute.toString().padStart(2, '0')}" else ""
                val resp = if (success) {
                    "Kal subah $hour$minStr baje ka alarm set kar diya hai Boss! ⏰"
                } else {
                    "Boss, alarm set karne me dikkat aayi."
                }
                return OfflineReflexResult("create_alarm", resp, success)
            }
        }

        // 10. Timer Setting
        if (clean.contains("timer")) {
            val seconds = extractTimerSeconds(clean)
            if (seconds != null && seconds > 0) {
                val args = JsonObject().apply {
                    addProperty("seconds", seconds)
                    addProperty("message", "AURA Timer")
                }
                val result = if (execute) IshaToolRegistry.executeTool(context, "set_timer", args) else JsonObject().apply { addProperty("status", "success") }
                val success = isSuccess(result)
                val mins = seconds / 60
                val durStr = if (mins > 0) "$mins minute" else "$seconds second"
                val resp = if (success) {
                    "$durStr ka timer shuru kar diya hai Boss! ⏳"
                } else {
                    "Boss, timer set nahi ho saka."
                }
                return OfflineReflexResult("set_timer", resp, success)
            }
        }

        // 11. Open Quick Apps (Camera, Settings, YouTube, WhatsApp, Chrome, Gallery)
        val isOpenAppCmd = clean.startsWith("open ") || clean.startsWith("kholo ") || 
                           clean.endsWith(" kholo") || clean.endsWith(" open karo") ||
                           clean.endsWith(" open krdo") || clean.contains("open karo") ||
                           clean.contains("open krdo") || clean.contains("khol do")
        if (isOpenAppCmd) {
            val appMap = mapOf(
                "camera" to "camera",
                "settings" to "settings",
                "setting" to "settings",
                "youtube" to "youtube",
                "whatsapp" to "whatsapp",
                "chrome" to "chrome",
                "gallery" to "gallery",
                "photos" to "photos",
                "calculator" to "calculator",
                "clock" to "clock",
                "calendar" to "calendar",
                "maps" to "maps",
                "melodify" to "melodify",
                "spotify" to "spotify"
            )
            for ((key, name) in appMap) {
                if (clean.contains(key)) {
                    val args = JsonObject().apply { addProperty("app_name", name) }
                    val result = if (execute) IshaToolRegistry.executeTool(context, "open_app", args) else JsonObject().apply { addProperty("status", "success") }
                    val success = isSuccess(result)
                    val resp = if (success) {
                        "${name.replaceFirstChar { it.uppercase() }} open kar diya hai Boss!"
                    } else {
                        "Boss, $name kholne me dikkat aayi."
                    }
                    return OfflineReflexResult("open_app", resp, success)
                }
            }
        }

        // 12. Toggle Incoming Message Announcements (Read messages on arrival)
        val isMsgSpeakCmd = (clean.contains("message") || clean.contains("msg")) &&
                (clean.contains("padh") || clean.contains("read") || clean.contains("bol") || clean.contains("sunao") || clean.contains("announce") || clean.contains("speak"))
        if (isMsgSpeakCmd) {
            val isOff = clean.contains("band") || clean.contains("off") || clean.contains("mat") || clean.contains("stop") || clean.contains("mute")
            val args = JsonObject().apply { addProperty("enable", !isOff) }
            val result = if (execute) IshaToolRegistry.executeTool(context, "toggle_message_announcements", args) else JsonObject().apply { addProperty("status", "success") }
            val success = isSuccess(result)
            val resp = if (success) {
                if (isOff) "Incoming message announcements band kar diye hain Boss! Ab message aane par main shant rahungi. 🔕"
                else "Incoming message announcements chalu kar diye hain Boss! Ab message aane par main bol kar sunaungi. 📢"
            } else {
                "Boss, setting update karne me dikkat aayi."
            }
            return OfflineReflexResult("toggle_message_announcements", resp, success)
        }

        // 13. Current Live Device Time & Date Check
        val isTimeQuery = (
            clean == "time" ||
            clean.startsWith("time ") ||
            clean.endsWith(" time") ||
            clean.contains("what time") ||
            clean.contains("what is the time") ||
            ((clean.contains("time") || clean.contains("samay") || clean.contains("baje") || clean.contains("ghadi")) &&
             (clean.contains("kya") || clean.contains("bata") || clean.contains("kitne") || clean.contains("dekho") || clean.contains("hua") || clean.contains("bolo") || clean.contains("batao") || clean.contains("bataiye"))) ||
            ((clean.contains("date") || clean.contains("tareekh") || clean.contains("din")) && (clean.contains("kya") || clean.contains("aaj") || clean.contains("today") || clean.contains("bata")))
        ) && !clean.contains("alarm") && !clean.contains("timer")
        if (isTimeQuery) {
            val result = if (execute) IshaToolRegistry.executeTool(context, "get_current_time", JsonObject()) else JsonObject().apply { addProperty("status", "success") }
            val msg = result.get("message")?.asString ?: "Abhi exact time check kar liya hai Boss!"
            return OfflineReflexResult("get_current_time", msg, true)
        }

        // 14. Media Playback (Melodify, Spotify, YouTube)
        val isMediaPlay = (clean.contains("chalao") || clean.contains("play") || clean.contains("baja do") || clean.contains("chala do") || clean.contains("laga do")) &&
                          (clean.contains("melodify") || clean.contains("spotify") || clean.contains("gaana") || clean.contains("youtube") || clean.contains("music") || clean.contains("song"))
        if (isMediaPlay) {
            val app = when {
                clean.contains("melodify") -> "Melodify"
                clean.contains("spotify") -> "Spotify"
                clean.contains("gaana") -> "Gaana"
                clean.contains("jio") || clean.contains("saavn") -> "JioSaavn"
                clean.contains("wynk") -> "Wynk"
                else -> "YouTube"
            }
            val songQuery = clean
                .replace(Regex("(melodify|spotify|gaana|jiosaavn|wynk|youtube|music|app|pe|par|me|mein|song|gaana|chalao|play|karo|do|baja|laga|shuru)"), " ")
                .trim()
                .replace(Regex("\\s+"), " ")

            if (songQuery.isNotBlank()) {
                val args = JsonObject().apply {
                    addProperty("app_name", app)
                    addProperty("query", songQuery)
                }
                val result = if (execute) IshaToolRegistry.executeTool(context, "play_media", args) else JsonObject().apply { addProperty("status", "success") }
                val success = isSuccess(result)
                val resp = if (success) "$app par \"$songQuery\" play kar rahi hoon Boss! 🎵" else "Boss, $app par gaana chalane me pareshani aayi."
                return OfflineReflexResult("play_media", resp, success)
            }
        }

        // 15. Dismiss Screen Popups / Interrupting Ads (Reflex)
        if (clean.contains("popup") || clean.contains("ad hatao") || clean.contains("skip ad") || clean.contains("dialog dismiss") || (clean.contains("hatao") && clean.contains("screen"))) {
            val result = if (execute) IshaToolRegistry.executeTool(context, "dismiss_screen_popups", JsonObject()) else JsonObject().apply { addProperty("status", "success") }
            val msg = result.get("message")?.asString ?: "Screen se popup hata diya gaya hai Boss!"
            return OfflineReflexResult("dismiss_screen_popups", msg, true)
        }

        // 16. Daily Briefing (Reflex)
        if (clean.contains("briefing") || clean == "good morning" || clean == "morning update" || clean.contains("aaj ka din kaisa hai")) {
            val result = if (execute) IshaToolRegistry.executeTool(context, "get_daily_briefing", JsonObject()) else JsonObject().apply { addProperty("status", "success") }
            val msg = result.get("message")?.asString ?: "Good morning Boss! Aaj ka briefing ready hai."
            return OfflineReflexResult("get_daily_briefing", msg, true)
        }

        // 17. Contact Memory Reflex (Save & Update Phone Numbers with Versioning)
        val phoneNum = extractPhoneNumber(prompt)
        val hasContactIntent = (clean.contains("number") || clean.contains("no") || clean.contains("phone") || clean.contains("mobile") || clean.contains("contact")) &&
                (clean.contains("save") || clean.contains("update") || clean.contains("yaad") || clean.contains("rakh") || clean.contains("store") || clean.contains("likh") || clean.contains("ka hai") || clean.contains("ki hai"))
        if (phoneNum != null && hasContactIntent) {
            val contactName = extractContactName(prompt, phoneNum)
            if (contactName.isNotBlank() && contactName.length >= 2) {
                val updateResult = if (execute) {
                    IshaContactMemoryManager.rememberOrUpdateContact(context, contactName, phoneNum)
                } else {
                    com.aura.assistant.ai.ContactUpdateResult(contactName, phoneNum, null, false, "$contactName ka number save ho gaya hai Boss!")
                }
                return OfflineReflexResult("remember_contact", updateResult.message, true)
            }
        }

        // 18. Contact Memory Query Reflex (Retrieve Active and Previous Phone Numbers)
        val isContactQuery = (clean.contains("number") || clean.contains("no") || clean.contains("contact")) &&
                (clean.contains("kya hai") || clean.contains("batao") || clean.contains("bataiye") || clean.contains("kya tha") || clean.startsWith("what is") || clean.contains("dikhao")) &&
                phoneNum == null
        if (isContactQuery) {
            val targetName = extractContactQueryName(prompt)
            if (targetName.isNotBlank()) {
                val memoryEntry = IshaContactMemoryManager.getContactByName(context, targetName)
                if (memoryEntry != null) {
                    val isAskingOld = clean.contains("purana") || clean.contains("old") || clean.contains("pehle")
                    val resp = if (isAskingOld) {
                        if (memoryEntry.previousNumbers.isNotEmpty()) {
                            "${memoryEntry.name} ka purana number ${memoryEntry.previousNumbers.joinToString(", ")} tha Boss! (Aur active number abhi ${memoryEntry.primaryNumber} hai)."
                        } else {
                            "${memoryEntry.name} ka koi purana number record mein nahi hai Boss. Inka active number ${memoryEntry.primaryNumber} hi hai."
                        }
                    } else {
                        val historyText = if (memoryEntry.previousNumbers.isNotEmpty()) {
                            ", aur inka purana number ${memoryEntry.previousNumbers.joinToString(", ")} tha"
                        } else ""
                        "${memoryEntry.name} ka number ${memoryEntry.primaryNumber} hai Boss$historyText! 📞"
                    }
                    return OfflineReflexResult("query_contact", resp, true)
                }
            }
        }

        return null // Needs Gemini reasoning
    }

    /**
     * Safely inspects the tool result for success status, handling
     * string ("success"), integer (0), and boolean (true) formats.
     */
    fun isSuccess(result: JsonObject?): Boolean {
        if (result == null) return false
        val statusElem = result.get("status") ?: return false
        if (statusElem.isJsonPrimitive) {
            val prim = statusElem.asJsonPrimitive
            if (prim.isNumber) return prim.asInt == 0
            if (prim.isBoolean) return prim.asBoolean
            val s = prim.asString.lowercase()
            // Accept all valid non-failure statuses including when a settings screen was opened
            // (e.g. hotspot/wifi toggle that opens the settings page returns "settings_opened")
            return s == "success" || s == "ok" || s == "0" || s == "true"
                || s == "settings_opened" || s == "dispatched" || s == "completed"
        }
        return false
    }

    private fun extractNumber(text: String): Int? {
        val p = Pattern.compile("(\\d{1,3})\\s*(%|percent)?")
        val m = p.matcher(text)
        if (m.find()) {
            return m.group(1)?.toIntOrNull()?.coerceIn(0, 100)
        }
        return null
    }

    private fun extractHour(text: String): Int? {
        // Handle "saade X" (X:30), "paune X" (X-0:15 = prev hour + 45min)
        val saadePattern = Pattern.compile("(saade|saadhe|sade)\\s*(\\d{1,2})")
        val saadeMatcher = saadePattern.matcher(text)
        if (saadeMatcher.find()) {
            var h = saadeMatcher.group(2)?.toIntOrNull() ?: 0
            if (text.contains("pm") || text.contains("shaam") || text.contains("raat")) {
                if (h in 1..11) h += 12
            }
            return h.coerceIn(0, 23)
        }
        val paPattern = Pattern.compile("(paune|paon)\\s*(\\d{1,2})")
        val paMatcher = paPattern.matcher(text)
        if (paMatcher.find()) {
            // "paune 7" = 6:45, so hour is X-1
            var h = (paMatcher.group(2)?.toIntOrNull() ?: 1) - 1
            if (text.contains("pm") || text.contains("shaam") || text.contains("raat")) {
                if (h in 1..11) h += 12
            }
            return h.coerceIn(0, 23)
        }
        val p = Pattern.compile("(\\d{1,2})\\s*(baje|am|pm|:)?")
        val m = p.matcher(text)
        if (m.find()) {
            var h = m.group(1)?.toIntOrNull() ?: return null
            if (text.contains("pm") || text.contains("shaam") || text.contains("raat")) {
                if (h in 1..11) h += 12
            }
            return h.coerceIn(0, 23)
        }
        return null
    }

    private fun extractMinute(text: String): Int? {
        // 1. Standard HH:MM colon format — "6:30", "7:45"
        val colonPattern = Pattern.compile("\\d{1,2}:(\\d{2})")
        val colonMatcher = colonPattern.matcher(text)
        if (colonMatcher.find()) {
            return colonMatcher.group(1)?.toIntOrNull()
        }

        // 2. "bajke X minute" / "bajkar X min" — "6 bajke 30 minute alarm"
        val bajkePattern = Pattern.compile("baj[a-z]*\\s+(\\d{1,2})\\s*(minute|min|mins)?")
        val bajkeMatcher = bajkePattern.matcher(text)
        if (bajkeMatcher.find()) {
            val mins = bajkeMatcher.group(1)?.toIntOrNull() ?: return null
            if (mins in 0..59) return mins
        }

        // 3. Space-separated — "6 30 alarm": second distinct number after the hour number
        //    Pattern: <hour_digits> <space> <minute_digits_0-59>
        val spacePattern = Pattern.compile("(\\d{1,2})\\s+(\\d{1,2})(?:\\s|$)")
        val spaceMatcher = spacePattern.matcher(text)
        if (spaceMatcher.find()) {
            val mins = spaceMatcher.group(2)?.toIntOrNull() ?: return null
            if (mins in 1..59) return mins  // ignore 0 (handled by default)
        }

        // 4. Hindi time-fraction words
        if (text.contains("saade") || text.contains("saadhe") || text.contains("sade") ||
            text.contains("half") || text.contains("tees")) return 30
        if (text.contains("paune") || text.contains("paon")) return 45
        if (text.contains("sawaa") || text.contains("sawa") || text.contains("quarter")) return 15

        return null
    }

    private fun extractTimerSeconds(text: String): Int? {
        val pMin = Pattern.compile("(\\d{1,3})\\s*(minute|min|m)")
        val mMin = pMin.matcher(text)
        if (mMin.find()) {
            return (mMin.group(1)?.toIntOrNull() ?: 1) * 60
        }
        val pSec = Pattern.compile("(\\d{1,3})\\s*(second|sec|s)")
        val mSec = pSec.matcher(text)
        if (mSec.find()) {
            return mSec.group(1)?.toIntOrNull() ?: 30
        }
        return null
    }

    private fun extractPhoneNumber(text: String): String? {
        val p = Pattern.compile("(?:\\+?91)?[\\s-]?([6-9]\\d{9})|\\b(\\d{10})\\b")
        val m = p.matcher(text)
        if (m.find()) {
            return m.group(1) ?: m.group(2)
        }
        return null
    }

    private fun extractContactName(text: String, phoneNumber: String): String {
        var clean = text.replace(phoneNumber, " ")
        clean = clean.replace(Regex("\\b(\\+91|91)\\b"), " ")
        clean = clean.replace(Regex("\\b(isha|aura|mobile|number|phone|contact|ka|ki|ke|ko|se|hai|save|kar|lo|karo|apne|apni|memory|mein|me|ab|naya|new|purana|update|kijiye|rakhlo|rakho|yaad|bhai|bhaiya|person|wala|wali|as|to|is|the|please)\\b", RegexOption.IGNORE_CASE), " ")
        clean = clean.replace(Regex("[^a-zA-Z\\s]"), " ")
        return clean.trim().replace(Regex("\\s+"), " ")
    }

    private fun extractContactQueryName(text: String): String {
        var clean = text.replace(Regex("\\b(isha|aura|mobile|number|phone|contact|ka|ki|ke|ko|se|hai|tha|the|kya|batao|bataiye|dikhao|bata|what|is|the|tell|me|show|details|please|purana|old|naya|new)\\b", RegexOption.IGNORE_CASE), " ")
        clean = clean.replace(Regex("[^a-zA-Z\\s]"), " ")
        return clean.trim().replace(Regex("\\s+"), " ")
    }

    /**
     * Determines whether the given prompt contains multiple tasks, sequential actions,
     * or compound chained requests that require full Gemini LLM multi-step execution.
     */
    fun isCompoundOrMultiActionCommand(cleanPrompt: String): Boolean {
        val clean = cleanPrompt.lowercase().trim()

        // 1. Explicit multi-action conjunctions and sequence connectors
        val compoundConnectors = listOf(
            " aur ", " and ", " & ", " then ", " phir ", " fir ",
            " karke ", " kar ke ", " krke ", " kr ke ",
            " ke baad ", " k baad ", " after ", " along with ",
            " saath me ", " sath me ", " sath hi ", " saath hi "
        )
        if (compoundConnectors.any { clean.contains(it) }) {
            return true
        }

        // 2. Chained follow-up markers (e.g. "ye bhi bta do", "ye bhi kar do", "battery kitna charge hai ye bhi bta do")
        if (clean.contains("bhi") && (clean.contains("bta") || clean.contains("bata") || clean.contains("kar") || clean.contains("kr") || clean.contains("check") || clean.contains("dekh") || clean.contains("play"))) {
            val beforeBhi = clean.substringBefore("bhi")
            if (beforeBhi.length > 8 && (beforeBhi.contains("play") || beforeBhi.contains("on") || beforeBhi.contains("off") || beforeBhi.contains("start") || beforeBhi.contains("khol") || beforeBhi.contains("do") || beforeBhi.contains("karo") || beforeBhi.contains("krdo"))) {
                return true
            }
        }

        // 3. Comma-separated or semicolon-separated clauses
        if (clean.contains(",") || clean.contains(";")) {
            val parts = clean.split(Regex("[,;]+")).map { it.trim() }.filter { it.isNotBlank() }
            if (parts.size >= 2) {
                return true
            }
        }

        // 4. Chained screenshot workflow (e.g. "take screenshot and send it to whatsapp to this person", "screenshot whatsapp par bhej do")
        if ((clean.contains("screenshot") || clean.contains("screen shot")) &&
            (clean.contains("send") || clean.contains("bhej") || clean.contains("share") ||
             clean.contains("whatsapp") || clean.contains("kisi ko") || clean.contains("person") ||
             clean.contains("to ") || clean.contains("ko "))) {
            return true
        }

        // 5. Multiple distinct action intent domains in one prompt
        var actionDomainCount = 0
        if (clean.contains("youtube") || clean.contains("song") || clean.contains("gaana") || clean.contains("music") || clean.contains("video")) actionDomainCount++
        if (clean.contains("battery") || clean.contains("charging") || clean.contains("charge kitna")) actionDomainCount++
        if (clean.contains("dnd") || clean.contains("do not disturb")) actionDomainCount++
        if (clean.contains("game") || clean.contains("app")) actionDomainCount++
        if (clean.contains("torch") || clean.contains("flashlight")) actionDomainCount++
        if (clean.contains("volume") || clean.contains("awaaz") || clean.contains("sound")) actionDomainCount++
        if (clean.contains("wifi") || clean.contains("wi-fi") || clean.contains("hotspot")) actionDomainCount++
        if (clean.contains("bluetooth")) actionDomainCount++
        if (clean.contains("call") || clean.contains("phone lagao") || clean.contains("dial")) actionDomainCount++
        if (clean.contains("whatsapp") || clean.contains("message") || clean.contains("sms")) actionDomainCount++

        return actionDomainCount >= 2
    }
}
