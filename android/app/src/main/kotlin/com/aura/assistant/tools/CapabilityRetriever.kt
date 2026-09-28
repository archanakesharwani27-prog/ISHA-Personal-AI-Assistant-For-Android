package com.aura.assistant.tools

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlin.math.max
import kotlin.math.min

/**
 * Multi-factor dynamic capability retriever for AURA Agent Core v4.
 *
 * Implements Google's official Gemini recommendation to supply an active,
 * highly-relevant subset (5-15 tools) instead of the entire 60+ tool catalog.
 *
 * Scoring Formula:
 * Score = (w_semantic * S_semantic)
 *       + (w_app * S_active_app)
 *       + (w_ui * S_current_ui)
 *       + (w_type * S_task_type)
 *       + (w_history * S_historical_success)
 *       + (w_avail * S_availability)
 *       - (w_risk * P_risk)
 */
object CapabilityRetriever {

    private const val TAG = "CapabilityRetriever"

    const val DEFAULT_MIN_TOOLS = 5
    const val DEFAULT_MAX_TOOLS = 15
    const val DEFAULT_TARGET_TOOLS = 10

    data class CapabilityScore(
        val tool: AuraTool,
        val totalScore: Float,
        val semanticScore: Float,
        val contextScore: Float
    )

    /**
     * Dynamically retrieves the most relevant tools for a given user request.
     */
    fun retrieveRelevantTools(
        query: String,
        context: ToolContext,
        allTools: List<AuraTool>,
        historicalSuccessRates: Map<String, Float> = emptyMap(),
        minCount: Int = DEFAULT_MIN_TOOLS,
        maxCount: Int = DEFAULT_MAX_TOOLS
    ): List<AuraTool> {
        if (allTools.isEmpty()) return emptyList()

        val cleanQuery = query.lowercase().trim()
        val queryTokens = cleanQuery.split(Regex("[\\s,?.!]+")).filter { it.isNotBlank() }.toSet()

        val scoredTools = allTools.map { tool ->
            val score = scoreTool(
                tool = tool,
                query = cleanQuery,
                queryTokens = queryTokens,
                context = context,
                historicalSuccess = historicalSuccessRates[tool.name] ?: 0.5f
            )
            CapabilityScore(tool, score.first, score.second, score.third)
        }

        // Sort descending by total score
        val sorted = scoredTools.sortedByDescending { it.totalScore }

        // Dynamic thresholding:
        // Always include top minCount; continue including while score >= 0.25 until maxCount is reached
        val result = mutableListOf<AuraTool>()
        for (i in sorted.indices) {
            val item = sorted[i]
            if (result.size < minCount) {
                result.add(item.tool)
            } else if (result.size < maxCount && item.totalScore >= 0.25f) {
                result.add(item.tool)
            } else {
                break
            }
        }

        Log.d(
            TAG,
            "⚡ Retrieved ${result.size} relevant tools for \"$query\": ${result.map { it.name }}"
        )
        return result
    }

    /**
     * Converts a retrieved list of tools into standard Gemini function declarations.
     */
    fun toGeminiDeclarations(tools: List<AuraTool>): JsonArray {
        val array = JsonArray()
        for (tool in tools) {
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

    // ── Scoring Logic ───────────────────────────────────────────────────

    private fun scoreTool(
        tool: AuraTool,
        query: String,
        queryTokens: Set<String>,
        context: ToolContext,
        historicalSuccess: Float
    ): Triple<Float, Float, Float> {
        // 1. Semantic Match (0.0 to 1.0)
        val toolTokens = (tool.name.lowercase().split("_") + 
                          tool.description.lowercase().split(Regex("[\\s,?.!]+"))).toSet()

        var tokenMatches = 0
        for (q in queryTokens) {
            if (q.length > 2 && (toolTokens.contains(q) || tool.name.contains(q) || tool.description.contains(q))) {
                tokenMatches++
            }
        }
        val semanticScore = if (queryTokens.isEmpty()) 0f else min(1.0f, tokenMatches.toFloat() / max(1, queryTokens.size / 2))

        // Direct tool name mention (highest priority)
        val directMentionBonus = if (query.contains(tool.name.replace("_", " ")) || query.contains(tool.name)) 0.6f else 0f

        // 2. Active App / Foreground Match
        var appMatchScore = 0f
        val activePkg = context.currentAppPackage?.lowercase() ?: ""
        if (activePkg.isNotBlank()) {
            if (activePkg.contains("whatsapp") && (tool.name.contains("whatsapp") || tool.category == ToolCategory.COMMUNICATION)) {
                appMatchScore = 0.5f
            } else if (activePkg.contains("youtube") && (tool.name.contains("youtube") || tool.category == ToolCategory.MEDIA)) {
                appMatchScore = 0.5f
            } else if (activePkg.contains("chrome") && (tool.name.contains("browser") || tool.category == ToolCategory.NAVIGATION)) {
                appMatchScore = 0.4f
            }
        }

        // 3. UI Context Match
        var uiMatchScore = 0f
        val screenSummary = context.screenContentSummary?.lowercase() ?: ""
        if (screenSummary.isNotBlank()) {
            if (tool.category == ToolCategory.VISION && (query.contains("screen") || query.contains("dikh") || query.contains("dekh"))) {
                uiMatchScore = 0.5f
            }
        }

        // 4. Task Category Affinity
        var categoryAffinity = 0f
        when (tool.category) {
            ToolCategory.COMMUNICATION -> {
                if (query.contains("call") || query.contains("phone") || query.contains("message") ||
                    query.contains("whatsapp") || query.contains("sms") || query.contains("bhejo") ||
                    query.contains("bolo") || query.contains("batana")
                ) {
                    categoryAffinity = 0.45f
                }
            }
            ToolCategory.MEDIA -> {
                if (query.contains("gana") || query.contains("song") || query.contains("music") ||
                    query.contains("youtube") || query.contains("chalao") || query.contains("play") ||
                    query.contains("video")
                ) {
                    categoryAffinity = 0.45f
                }
            }
            ToolCategory.SYSTEM -> {
                if (query.contains("volume") || query.contains("torch") || query.contains("flashlight") ||
                    query.contains("bluetooth") || query.contains("wifi") || query.contains("battery") ||
                    query.contains("screen") || query.contains("brightness") || query.contains("alarm")
                ) {
                    categoryAffinity = 0.40f
                }
            }
            ToolCategory.PRODUCTIVITY -> {
                if (query.contains("alarm") || query.contains("timer") || query.contains("note") ||
                    query.contains("reminder") || query.contains("calculate") || query.contains("hisaab") ||
                    query.contains("calendar") || query.contains("meeting")
                ) {
                    categoryAffinity = 0.45f
                }
            }
            ToolCategory.APPS -> {
                if (query.contains("open") || query.contains("kholo") || query.contains("launch") || query.contains("app")) {
                    categoryAffinity = 0.35f
                }
            }
            ToolCategory.VISION -> {
                if (query.contains("screen") || query.contains("dekh") || query.contains("dikh") ||
                    query.contains("padho") || query.contains("read") || query.contains("ocr")
                ) {
                    categoryAffinity = 0.50f
                }
            }
            else -> {}
        }

        // 5. Tool Availability
        val avail = tool.isAvailable(context)
        val availWeight = when (avail) {
            CapabilityStatus.AVAILABLE -> 0.15f
            CapabilityStatus.PERMISSION_REQUIRED -> 0.05f
            CapabilityStatus.HARDWARE_MISSING, CapabilityStatus.DISABLED -> -0.8f
        }

        // 6. Historical Success
        val historyBonus = (historicalSuccess - 0.5f) * 0.2f // +0.1 for 100%, -0.1 for 0%

        // 7. Risk Penalty
        val riskPenalty = when (tool.riskLevel) {
            RiskLevel.CRITICAL -> 0.25f
            RiskLevel.HIGH -> 0.05f
            else -> 0f
        }

        // Weighted Combination
        val total = (semanticScore * 0.40f) +
                directMentionBonus +
                (appMatchScore * 0.20f) +
                (uiMatchScore * 0.15f) +
                (categoryAffinity * 0.25f) +
                availWeight +
                historyBonus -
                riskPenalty

        return Triple(total, semanticScore, appMatchScore + uiMatchScore + categoryAffinity)
    }
}
