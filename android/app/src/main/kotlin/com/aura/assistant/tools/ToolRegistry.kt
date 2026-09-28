package com.aura.assistant.tools

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Central registry of all AURA 2.0 autonomous tools.
 * Organizes tools by category and exports dynamically retrieved Gemini function declarations.
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

    /**
     * Dynamically retrieves the most relevant 5-15 tools for a specific user query and device context.
     * Implements Google Gemini function-calling recommendations.
     */
    fun getRelevantTools(
        query: String,
        context: ToolContext,
        minCount: Int = CapabilityRetriever.DEFAULT_MIN_TOOLS,
        maxCount: Int = CapabilityRetriever.DEFAULT_MAX_TOOLS
    ): List<AuraTool> {
        return CapabilityRetriever.retrieveRelevantTools(
            query = query,
            context = context,
            allTools = getAllTools(),
            minCount = minCount,
            maxCount = maxCount
        )
    }

    /**
     * Returns dynamic Gemini tool declarations tailored to the user's intent.
     */
    fun getGeminiToolDeclarationsForQuery(
        query: String,
        context: ToolContext,
        minCount: Int = CapabilityRetriever.DEFAULT_MIN_TOOLS,
        maxCount: Int = CapabilityRetriever.DEFAULT_MAX_TOOLS
    ): JsonArray {
        val relevant = getRelevantTools(query, context, minCount, maxCount)
        return CapabilityRetriever.toGeminiDeclarations(relevant)
    }

    /**
     * Legacy declaration provider (all tools). Kept for complete fallback if needed.
     */
    fun getGeminiToolDeclarations(): JsonArray {
        return CapabilityRetriever.toGeminiDeclarations(getAllTools())
    }
}
