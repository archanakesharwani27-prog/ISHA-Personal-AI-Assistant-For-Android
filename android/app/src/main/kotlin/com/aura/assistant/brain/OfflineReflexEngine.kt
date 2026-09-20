package com.aura.assistant.brain

import android.content.Context
import com.aura.assistant.ai.JarvisToolRegistry
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
     * Checks if the user prompt can be executed offline.
     * Returns an OfflineReflexResult if handled, or null if cloud LLM reasoning is required.
     */
    fun tryExecute(context: Context, prompt: String): OfflineReflexResult? {
        val clean = prompt.lowercase().trim()

        // 1. Flashlight / Torch
        if (clean.contains("torch") || clean.contains("flashlight") || clean.contains("flash light")) {
            val isOff = clean.contains("band") || clean.contains("off") || clean.contains("bujha") || clean.contains("rok")
            val args = JsonObject().apply { addProperty("enable", !isOff) }
            val result = JarvisToolRegistry.executeTool(context, "toggle_flashlight", args)
            val success = isSuccess(result)
            val resp = if (success) {
                if (!isOff) "Torch chala di hai Boss! Roshan ho gaya! 🔦" else "Torch band kar di hai Boss!"
            } else {
                "Boss, flashlight access karne me dikkat aayi."
            }
            return OfflineReflexResult("toggle_flashlight", resp, success)
        }

        // 2. Battery Status
        if (clean.contains("battery") || clean.contains("charge kitna") || clean.contains("charging")) {
            val result = JarvisToolRegistry.executeTool(context, "get_battery_status", JsonObject())
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
                val result = JarvisToolRegistry.executeTool(context, "set_volume", args)
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
                val result = JarvisToolRegistry.executeTool(context, "set_brightness", args)
                val success = isSuccess(result)
                val resp = if (success) {
                    "Screen brightness $percent% par set kar di hai Boss! ☀️"
                } else {
                    "Boss, brightness badalne ke liye system permission ki zaroorat hai."
                }
                return OfflineReflexResult("set_brightness", resp, success)
            }
        }

        // 5. WiFi Toggle
        if (clean.contains("wifi") || clean.contains("wi-fi")) {
            val isOff = clean.contains("band") || clean.contains("off") || clean.contains("disconnect")
            val args = JsonObject().apply { addProperty("enable", !isOff) }
            val result = JarvisToolRegistry.executeTool(context, "toggle_wifi", args)
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
            val result = JarvisToolRegistry.executeTool(context, "toggle_bluetooth", args)
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
            val result = JarvisToolRegistry.executeTool(context, "toggle_hotspot", args)
            val success = isSuccess(result)
            val resp = if (!isOff) "Hotspot shuru kar diya hai Boss!" else "Hotspot band kar diya hai Boss!"
            return OfflineReflexResult("toggle_hotspot", resp, success)
        }

        // 8. Screenshot
        if (clean.contains("screenshot") || clean.contains("screen shot")) {
            val result = JarvisToolRegistry.executeTool(context, "take_screenshot", JsonObject())
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
                val result = JarvisToolRegistry.executeTool(context, "create_alarm", args)
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
                val result = JarvisToolRegistry.executeTool(context, "set_timer", args)
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
        if (clean.startsWith("open ") || clean.startsWith("kholo ") || clean.endsWith(" kholo") || clean.endsWith(" open karo")) {
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
                "maps" to "maps"
            )
            for ((key, name) in appMap) {
                if (clean.contains(key)) {
                    val args = JsonObject().apply { addProperty("app_name", name) }
                    val result = JarvisToolRegistry.executeTool(context, "open_app", args)
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
            return s == "success" || s == "ok" || s == "0" || s == "true"
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
        val p = Pattern.compile("\\d{1,2}:(\\d{2})")
        val m = p.matcher(text)
        if (m.find()) {
            return m.group(1)?.toIntOrNull()
        }
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
}
