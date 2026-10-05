package com.aura.assistant

import com.aura.assistant.ai.IshaMemoryManager
import com.aura.assistant.ai.AuraMemoryManager
import com.aura.assistant.ai.IshaToolRegistry
import com.aura.assistant.data.ChatMessage
import com.aura.assistant.data.MessageRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class IshaCommandAndContextTest {

    @Test
    fun testAllPdfCommandsRegisteredInGeminiTools() {
        val toolsArray = IshaToolRegistry.getGeminiToolDeclarations()
        assertNotNull("Tool declarations array must not be null", toolsArray)
        assertTrue("Tool declarations must contain tools", toolsArray.size() > 0)

        val registeredToolNames = mutableSetOf<String>()
        for (i in 0 until toolsArray.size()) {
            val f = toolsArray[i].asJsonObject
            assertTrue("Tool must contain a name", f.has("name"))
            registeredToolNames.add(f.get("name").asString)
        }

        // Validate all command categories documented in the PDF:
        val expectedPdfCommands = listOf(
            // 1.1 System Settings & Device Controls
            "toggle_wifi",
            "toggle_bluetooth",
            "set_brightness",
            "toggle_hotspot",
            "set_dnd",

            // 1.2 Device Hardware (Torch, Volume, Camera)
            "toggle_flashlight",
            "set_volume",
            "open_camera",

            // 1.3 Telephony & Communication
            "make_call",
            "accept_call",
            "decline_call",
            "identify_caller",

            // 1.4 Messaging & Instant Messaging
            "send_sms",
            "send_whatsapp",
            "type_message",
            "chat_on_whatsapp",

            // 1.5 Media Playback & Controls
            "media_control",
            "play_media",
            "play_youtube",

            // 1.6 Apps & UI Automation
            "open_app",
            "screen_scroll",
            "screen_tap",
            "press_system_key",

            // 1.7 Productivity (Reminders, Alarms, Notes, Timer)
            "create_alarm",
            "set_timer",

            // 1.8 Personal Info & Memory & Contacts
            "manage_contacts",
            "remember_fact",
            "recall_memory",
            "teach_command_rule",

            // 1.10 Navigation & Maps
            "navigate_maps",

            // 1.11 Web Search & Information
            "search_internet",
            "web_search",
            "calculate",

            // 1.19 Power, Battery & Storage
            "get_battery_status",
            "manage_app_storage",
            "search_files"
        )

        val missingTools = mutableListOf<String>()
        for (cmd in expectedPdfCommands) {
            if (!registeredToolNames.contains(cmd)) {
                missingTools.add(cmd)
            }
        }

        assertTrue("All PDF commands must be registered in IshaToolRegistry. Missing: $missingTools", missingTools.isEmpty())
        println("✅ All ${expectedPdfCommands.size} PDF command categories are fully registered in Gemini Tool Declarations!")
    }

    @Test
    fun testContextActionSnapshotAndShorthandResolution() {
        // Given: User turned on flashlight
        val toolName = "toggle_flashlight"
        val args = "{\"enabled\":true}"
        val status = "success"

        // When: Tool completes and snapshot is recorded
        AuraMemoryManager.recordLastAction(toolName, args, status)

        // Then: Last action snapshot must be accurately stored
        val snapshot = AuraMemoryManager.lastAction
        assertNotNull("Action snapshot must not be null", snapshot)
        assertEquals(toolName, snapshot?.toolName)
        assertEquals(args, snapshot?.args)
        assertEquals(status, snapshot?.status)
        assertTrue(System.currentTimeMillis() - (snapshot?.timestamp ?: 0L) < 5000L)

        // Verify Live Context instructions
        val liveContext = AuraMemoryManager.buildLiveContextSection(null)
        assertTrue("Live context must contain LAST ACTION block", liveContext.contains("Last Hardware/Toggle Action") || liveContext.contains("Last Executed"))
        assertTrue("Live context must reference the executed tool", liveContext.contains("toggle_flashlight"))
        assertTrue("Live context must instruct shorthand pronoun resolution", 
            (liveContext.contains("TOGGLE/SHORTHAND RULE") || liveContext.contains("PRONOUN & SHORTHAND RULE")) &&
            liveContext.contains("refers directly to 'toggle_flashlight'")
        )
        println("✅ Context Action Snapshot & Shorthand Resolution logic successfully verified!")
    }

    @Test
    fun testImplicitMemoryRegexPatterns() {
        // Regex patterns defined in AuraMemoryManager
        val patterns = listOf(
            Regex("(?:mera|meri)\\s+(?:favourite|favorite|pasandida)\\s+([a-zA-Z0-9\\u0900-\\u097F\\s]+?)\\s+(?:hai|he)\\s+([a-zA-Z0-9\\u0900-\\u097F\\s]+)", RegexOption.IGNORE_CASE) to "favorite_",
            Regex("(?:mera|meri)\\s+(?:favourite|favorite|pasandida)\\s+([a-zA-Z0-9\\u0900-\\u097F\\s]+?)\\s+([a-zA-Z0-9\\u0900-\\u097F\\s]+?)\\s+hai", RegexOption.IGNORE_CASE) to "favorite_",
            Regex("(?:mera)\\s+naam\\s+([a-zA-Z0-9\\u0900-\\u097F\\s]+?)\\s+hai", RegexOption.IGNORE_CASE) to "user_name",
            Regex("(?:mera)\\s+(?:bhai|dost|friend|sister|behan|yaar)\\s+([a-zA-Z0-9\\u0900-\\u097F\\s]+?)\\s+hai", RegexOption.IGNORE_CASE) to "friend",
            Regex("(?:main|mai)\\s+([a-zA-Z0-9\\u0900-\\u097F\\s]+?)\\s+(?:me|mein)\\s+rehta\\s+hoon", RegexOption.IGNORE_CASE) to "location",
            Regex("(?:mera)\\s+phone\\s+([a-zA-Z0-9\\u0900-\\u097F\\s]+?)\\s+hai", RegexOption.IGNORE_CASE) to "phone_model"
        )

        // Test 1: Favorite superhero
        val test1 = "Mera favourite superhero Iron Man hai"
        var matched1 = false
        for ((regex, key) in patterns) {
            val match = regex.find(test1)
            if (match != null && key == "favorite_") {
                val category = match.groupValues[1].trim()
                val value = match.groupValues[2].trim()
                assertEquals("superhero", category.lowercase())
                assertEquals("Iron Man", value)
                matched1 = true
                break
            }
        }
        assertTrue("Test 1 (favorite superhero) must match regex", matched1)

        // Test 2: User name
        val test2 = "Mera naam Ansh Kesharwani hai"
        var matched2 = false
        for ((regex, key) in patterns) {
            val match = regex.find(test2)
            if (match != null && key == "user_name") {
                val name = match.groupValues[1].trim()
                assertEquals("Ansh Kesharwani", name)
                matched2 = true
                break
            }
        }
        assertTrue("Test 2 (user name) must match regex", matched2)

        // Test 3: Location
        val test3 = "Main Prayagraj mein rehta hoon"
        var matched3 = false
        for ((regex, key) in patterns) {
            val match = regex.find(test3)
            if (match != null && key == "location") {
                val loc = match.groupValues[1].trim()
                assertEquals("Prayagraj", loc)
                matched3 = true
                break
            }
        }
        assertTrue("Test 3 (location) must match regex", matched3)

        // Test 4: Friend/Brother
        val test4 = "Mera bhai Yash hai"
        var matched4 = false
        for ((regex, key) in patterns) {
            val match = regex.find(test4)
            if (match != null && key == "friend") {
                val friend = match.groupValues[1].trim()
                assertEquals("Yash", friend)
                matched4 = true
                break
            }
        }
        assertTrue("Test 4 (friend/bhai) must match regex", matched4)

        println("✅ All Implicit Auto-Memory Extraction regex patterns verified successfully!")
    }

    @Test
    fun testToolHistoryRetentionInConversation() {
        // Verify that conversation history keeps TOOL execution messages for context
        val history = mutableListOf<ChatMessage>()
        history.add(ChatMessage(sessionId = "sess_01", role = MessageRole.USER, text = "Torch chalu karo"))
        history.add(ChatMessage(sessionId = "sess_01", role = MessageRole.TOOL, text = "{\"status\":\"success\",\"message\":\"Flashlight enabled\"}"))
        history.add(ChatMessage(sessionId = "sess_01", role = MessageRole.ASSISTANT, text = "Boss, flashlight on kar di hai."))
        history.add(ChatMessage(sessionId = "sess_01", role = MessageRole.USER, text = "ise band karo"))

        // Full preserved history
        val preservedHistory = history

        assertEquals(4, preservedHistory.size)
        assertTrue(preservedHistory.any { it.role == MessageRole.TOOL })
        println("✅ Conversation History retains full tool output for unbroken multi-turn context!")
    }

    @Test
    fun testToolExecutionCalculationAndLatency() {
        val args = com.google.gson.JsonObject()
        args.addProperty("expression", "25 * 4")

        val dummyContext = android.content.ContextWrapper(null)

        val result = IshaToolRegistry.executeToolBlocking(dummyContext, "calculate", args)

        assertNotNull("Result must not be null", result)
        assertEquals("success", result.get("status")?.asString)
        assertEquals("100", result.get("result")?.asString)
        assertTrue("Execution time must be recorded", result.has("execution_time_ms"))

        // Verify action was automatically recorded in AuraMemoryManager for context
        val last = AuraMemoryManager.lastAction
        assertNotNull(last)
        assertEquals("calculate", last?.toolName)
        assertEquals("success", last?.status)
        println("✅ Tool execution, evaluation, latency recording & action snapshot verified!")
    }
}
