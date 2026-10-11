package com.aura.assistant

import com.aura.assistant.ai.ConversationTurnBuffer
import com.aura.assistant.ai.IshaToolRegistry
import com.aura.assistant.ai.IshaToolRouter
import com.aura.assistant.ai.LiveVoiceTurn
import com.aura.assistant.ai.IshaVoiceContextBridge
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class IshaSelfHealingAndVoiceContextTest {

    @Test
    fun testSelfHealingAndFileToolsRegisteredInGemini() {
        val toolsArray = IshaToolRegistry.getGeminiToolDeclarations()
        assertNotNull(toolsArray)

        val toolMap = mutableMapOf<String, JsonObject>()
        for (i in 0 until toolsArray.size()) {
            val t = toolsArray[i].asJsonObject
            toolMap[t.get("name").asString] = t
        }

        // Verify all 4 new tools exist
        val requiredTools = listOf(
            "write_local_file",
            "open_local_file",
            "execute_code",
            "synthesize_action"
        )

        for (name in requiredTools) {
            assertTrue("Tool '$name' must be declared in IshaToolRegistry", toolMap.containsKey(name))
            val declaration = toolMap[name]!!
            assertTrue("Tool '$name' must have description", declaration.has("description") && declaration.get("description").asString.isNotBlank())
            assertTrue("Tool '$name' must have parameters", declaration.has("parameters"))
        }

        println("✅ All 4 Self-Healing & File Tools are fully registered in Gemini declarations!")
    }

    @Test
    fun testPrunedLiveVoiceDeclarationsIncludesFileTools() {
        val voiceTools = IshaToolRegistry.getPrunedLiveVoiceToolDeclarations()
        assertNotNull(voiceTools)

        val names = (0 until voiceTools.size()).map { voiceTools[it].asJsonObject.get("name").asString }

        assertTrue("Pruned live voice tools must include write_local_file", names.contains("write_local_file"))
        assertTrue("Pruned live voice tools must include open_local_file", names.contains("open_local_file"))
        println("✅ Live Voice tool schema contains write_local_file and open_local_file for voice duplex!")
    }

    @Test
    fun testIntentRouterRoutesHtmlCalculatorAndCodePrompts() {
        // Test 1: HTML Calculator prompt
        val calcPrompt = "ek html mein calculator bnao aur use save kr ke chrome pr open kro aur mujhe show kro"
        val calcTools = IshaToolRouter.routeToolsForPrompt(calcPrompt)
        assertNotNull("Router must provide tools for HTML calculator prompt", calcTools)
        val calcToolNames = (0 until calcTools!!.size()).map { calcTools[it].asJsonObject.get("name").asString }
        assertTrue("Router must include write_local_file for HTML calculator", calcToolNames.contains("write_local_file"))
        assertTrue("Router must include open_local_file for HTML calculator", calcToolNames.contains("open_local_file"))

        // Test 2: Chrome Open prompt
        val openPrompt = "calculator.html ko Chrome me open karo"
        val openTools = IshaToolRouter.routeToolsForPrompt(openPrompt)
        assertNotNull("Router must provide tools for file open prompt", openTools)
        val openToolNames = (0 until openTools!!.size()).map { openTools[it].asJsonObject.get("name").asString }
        assertTrue("Router must include open_local_file", openToolNames.contains("open_local_file"))

        // Test 3: JavaScript code run prompt
        val codePrompt = "ye javascript code chalao aur result batao"
        val codeTools = IshaToolRouter.routeToolsForPrompt(codePrompt)
        assertNotNull("Router must provide tools for dynamic code execution", codeTools)
        val codeToolNames = (0 until codeTools!!.size()).map { codeTools[it].asJsonObject.get("name").asString }
        assertTrue("Router must include execute_code", codeToolNames.contains("execute_code"))

        // Test 4: Self-healing prompt
        val healPrompt = "self heal aur naya macro widget synthesize karo"
        val healTools = IshaToolRouter.routeToolsForPrompt(healPrompt)
        assertNotNull("Router must provide tools for self-healing prompt", healTools)
        val healToolNames = (0 until healTools!!.size()).map { healTools[it].asJsonObject.get("name").asString }
        assertTrue("Router must include synthesize_action", healToolNames.contains("synthesize_action"))

        println("✅ IshaToolRouter accurately classifies and routes all code and file intents!")
    }

    @Test
    fun testToolNameNormalization() {
        assertEquals("write_local_file", IshaToolRegistry.normalizeToolName("create_file"))
        assertEquals("write_local_file", IshaToolRegistry.normalizeToolName("save_file"))
        assertEquals("write_local_file", IshaToolRegistry.normalizeToolName("write_file"))
        assertEquals("open_local_file", IshaToolRegistry.normalizeToolName("open_file"))
        assertEquals("open_local_file", IshaToolRegistry.normalizeToolName("launch_file"))
        assertEquals("open_local_file", IshaToolRegistry.normalizeToolName("view_file"))
        assertEquals("execute_code", IshaToolRegistry.normalizeToolName("run_code"))
        assertEquals("execute_code", IshaToolRegistry.normalizeToolName("execute_script"))
        assertEquals("execute_code", IshaToolRegistry.normalizeToolName("run_javascript"))
        assertEquals("synthesize_action", IshaToolRegistry.normalizeToolName("self_heal"))
        assertEquals("synthesize_action", IshaToolRegistry.normalizeToolName("synthesize_tool"))

        println("✅ All tool name variations and synonyms normalized accurately!")
    }

    @Test
    fun testToolCallParameterNormalization() {
        // Given raw args with aliases
        val rawWriteArgs = JsonObject().apply {
            addProperty("fileName", "calculator.html")
            addProperty("code", "<!DOCTYPE html><html><body><h1>Calc</h1></body></html>")
        }
        val (normName1, normArgs1) = IshaToolRegistry.normalizeToolCall("create_file", rawWriteArgs)
        assertEquals("write_local_file", normName1)
        assertEquals("calculator.html", normArgs1.get("file_name").asString)
        assertEquals("<!DOCTYPE html><html><body><h1>Calc</h1></body></html>", normArgs1.get("content").asString)

        val rawOpenArgs = JsonObject().apply {
            addProperty("filePath", "calculator.html")
        }
        val (normName2, normArgs2) = IshaToolRegistry.normalizeToolCall("open_file", rawOpenArgs)
        assertEquals("open_local_file", normName2)
        assertEquals("calculator.html", normArgs2.get("file_path_or_name").asString)

        println("✅ Tool call argument aliases normalized into canonical parameters!")
    }

    @Test
    fun testConversationTurnBuffer30TurnsRetention() {
        val buffer = ConversationTurnBuffer(maxTurns = 30)
        assertTrue(buffer.isEmpty())

        // Add 20 turns (simulating 5+ minutes of continuous conversation)
        for (i in 1..20) {
            buffer.add(
                LiveVoiceTurn(
                    userText = "Command $i from Boss",
                    assistantText = "Reply $i from Isha"
                )
            )
        }

        val turns = buffer.recentTurns()
        assertEquals("Buffer must retain all 20 turns without dropping early commands", 20, turns.size)
        assertEquals("First command must still be present", "Command 1 from Boss", turns[0].userText)
        assertEquals("20th command must be present", "Command 20 from Boss", turns[19].userText)

        // Add 15 more turns (total 35 turns added)
        for (i in 21..35) {
            buffer.add(
                LiveVoiceTurn(
                    userText = "Command $i from Boss",
                    assistantText = "Reply $i from Isha"
                )
            )
        }

        val overflowTurns = buffer.recentTurns()
        assertEquals("Buffer max capacity must strictly cap at 30", 30, overflowTurns.size)
        // Earliest 5 turns (1..5) should have rolled off, so index 0 is turn 6
        assertEquals("Command 6 from Boss", overflowTurns[0].userText)
        assertEquals("Command 35 from Boss", overflowTurns[29].userText)

        println("✅ ConversationTurnBuffer retains 30 full turns for long uninterrupted voice sessions!")
    }

    @Test
    fun testVoiceContextBridgeCrossModalRetention() {
        IshaVoiceContextBridge.clear()
        assertEquals(0, IshaVoiceContextBridge.getTurnCount())

        // 1. Record voice turn
        IshaVoiceContextBridge.recordVoiceTurn("Boss: flashlight on karo", "Haan Boss, flashlight on kar di hai!")
        assertEquals(1, IshaVoiceContextBridge.getTurnCount())

        // 2. Record text chat turn
        IshaVoiceContextBridge.recordTextTurn("kal ka weather kaisa hoga?", "Kal thoda badal chaaye rahenge Boss.")
        assertEquals(2, IshaVoiceContextBridge.getTurnCount())

        // 3. Verify formatted context incorporates both modalities
        val contextSnippet = IshaVoiceContextBridge.getFormattedCrossModalContext(10)
        assertTrue(contextSnippet.contains("flashlight on karo"))
        assertTrue(contextSnippet.contains("weather kaisa hoga"))
        assertTrue(contextSnippet.contains("VOICE ↔ TEXT SHARED MEMORY"))

        // 4. Test clear
        IshaVoiceContextBridge.clear()
        assertEquals(0, IshaVoiceContextBridge.getTurnCount())
        assertEquals("", IshaVoiceContextBridge.getFormattedCrossModalContext(10))
        println("✅ IshaVoiceContextBridge retains and bridges cross-modal voice & text context seamlessly!")
    }

    @Test
    fun testToolDeclarationSubsetCaching() {
        val hardware1 = IshaToolRegistry.getSpecificToolDeclarations(IshaToolRouter.HARDWARE_TOOLS)
        val hardware2 = IshaToolRegistry.getSpecificToolDeclarations(IshaToolRouter.HARDWARE_TOOLS)

        // Caching must return identical reference in O(1) time
        assertSame("Subsequent calls with same tool subset must return cached JsonArray reference", hardware1, hardware2)
        assertTrue("Hardware subset must contain toggle_flashlight",
            (0 until hardware1.size()).any { hardware1[it].asJsonObject.get("name").asString == "toggle_flashlight" })
        println("✅ IshaToolRegistry specific tool declarations are cached in O(1) with zero allocations!")
    }
}
