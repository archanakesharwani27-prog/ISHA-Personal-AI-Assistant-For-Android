package com.aura.assistant.tools

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Central registry of all AURA 2.0 autonomous tools.
 * Organizes tools by category and exports Gemini function declarations.
 */
object ToolRegistry {
    private const val TAG = "ToolRegistry"
    private val tools = ConcurrentHashMap<String, AuraTool>()

    fun register(tool: AuraTool) {
        tools[tool.name] = tool
        Log.d(TAG, "Registered tool: ${tool.name} [${tool.category}]")
    }

    fun getTool(name: String): AuraTool? = tools[name]

    fun getAllTools(): List<AuraTool> = tools.values.toList()

    fun getToolsByCategory(category: ToolCategory): List<AuraTool> =
        tools.values.filter { it.category == category }

    fun getGeminiToolDeclarations(): JsonArray {
        val array = JsonArray()
        for (tool in tools.values) {
            val fn = JsonObject().apply {
                addProperty("name", tool.name)
                addProperty("description", tool.description)
                val params = JsonObject().apply {
                    addProperty("type", "object")
                    add("properties", JsonObject())
                }
                add("parameters", params)
            }
            array.add(fn)
        }
        return array
    }
}
