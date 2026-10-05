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

    private val initialized = java.util.concurrent.atomic.AtomicBoolean(false)

    fun register(tool: AuraTool) {
        tools[tool.name] = tool
        Log.d(TAG, "Registered tool: ${tool.name} [${tool.category}]")
    }

    private fun ensureInitialized() {
        if (initialized.compareAndSet(false, true)) {
            try {
                val declarations = com.aura.assistant.ai.IshaToolRegistry.getGeminiToolDeclarations()
                for (i in 0 until declarations.size()) {
                    val decl = declarations.get(i).asJsonObject
                    val name = decl.get("name")?.asString ?: continue
                    val desc = decl.get("description")?.asString ?: ""

                    val category = when {
                        name.contains("whatsapp") || name.contains("call") || name.contains("sms") || name.contains("contact") -> ToolCategory.COMMUNICATION
                        name.contains("media") || name.contains("youtube") || name.contains("music") || name.contains("volume") || name.contains("song") -> ToolCategory.MEDIA
                        name.contains("map") || name.contains("location") || name.contains("cab") -> ToolCategory.NAVIGATION
                        name.contains("app") -> ToolCategory.APPS
                        name.contains("screen") || name.contains("tap") || name.contains("click") -> ToolCategory.AUTOMATION
                        name.contains("file") || name.contains("storage") || name.contains("pdf") -> ToolCategory.FILES
                        name.contains("alarm") || name.contains("timer") || name.contains("note") || name.contains("calendar") -> ToolCategory.PRODUCTIVITY
                        name.contains("camera") || name.contains("photo") || name.contains("vision") -> ToolCategory.VISION
                        else -> ToolCategory.SYSTEM
                    }

                    val (riskLevel, requiresConfirm) = when (name) {
                        "empty_recycle_bin", "manage_app_storage", "delete_whatsapp_media" -> Pair(RiskLevel.HIGH, true)
                        "send_sos" -> Pair(RiskLevel.HIGH, true)
                        "uninstall_app", "wipe_user_data" -> Pair(RiskLevel.CRITICAL, true)
                        else -> when {
                            name.startsWith("toggle_") || name.startsWith("set_") || name.startsWith("create_") -> Pair(RiskLevel.MEDIUM, false)
                            else -> Pair(RiskLevel.LOW, false)
                        }
                    }

                    tools[name] = IshaToolAdapter(
                        id = name,
                        name = name,
                        description = desc,
                        category = category,
                        riskLevel = riskLevel,
                        requiresConfirmation = requiresConfirm
                    )
                }
                Log.i(TAG, "ToolRegistry auto-populated with ${tools.size} tools from IshaToolRegistry declarations.")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to auto-populate ToolRegistry: ${e.message}")
            }
        }
    }

    fun createDynamicTool(name: String): AuraTool {
        val (riskLevel, requiresConfirm) = when (name) {
            "empty_recycle_bin", "manage_app_storage", "delete_whatsapp_media", "send_sos" -> Pair(RiskLevel.HIGH, true)
            else -> Pair(RiskLevel.LOW, false)
        }
        val dynamicTool = IshaToolAdapter(
            id = name,
            name = name,
            description = "Dynamic action $name",
            category = ToolCategory.SYSTEM,
            riskLevel = riskLevel,
            requiresConfirmation = requiresConfirm
        )
        tools[name] = dynamicTool
        return dynamicTool
    }

    fun getTool(name: String): AuraTool? {
        ensureInitialized()
        return tools[name] ?: createDynamicTool(name)
    }

    fun getAllTools(): List<AuraTool> {
        ensureInitialized()
        return tools.values.toList()
    }

    fun getToolsByCategory(category: ToolCategory): List<AuraTool> {
        ensureInitialized()
        return tools.values.filter { it.category == category }
    }

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

/**
 * Universal adapter bridging IshaToolRegistry implementations to the AURA Agent Core v4 AuraTool contract.
 */
class IshaToolAdapter(
    override val id: String,
    override val name: String,
    override val description: String,
    override val category: ToolCategory = ToolCategory.SYSTEM,
    override val riskLevel: RiskLevel = RiskLevel.LOW,
    override val requiresConfirmation: Boolean = false
) : IshaTool {
    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val resultJson = com.aura.assistant.ai.IshaToolRegistry.executeTool(context.applicationContext, name, arguments)
        val status = resultJson.get("status")?.asString ?: "success"
        val message = resultJson.get("message")?.asString
            ?: resultJson.get("error")?.asString
            ?: status
        return if (status == "error" || status == "failed") {
            ToolResult(
                toolId = id,
                status = ToolStatus.FAILED,
                message = message,
                data = resultJson,
                retryable = true
            )
        } else {
            ToolResult.success(
                toolId = id,
                message = message,
                data = resultJson
            )
        }
    }

    override suspend fun verify(
        arguments: JsonObject,
        result: ToolResult,
        context: ToolContext
    ): VerificationResult {
        val evidence = com.aura.assistant.execution.ExecutionVerifier.verify(
            context.applicationContext,
            name,
            arguments,
            result.data
        )
        return VerificationResult(
            status = if (evidence.verified) VerificationStatus.VERIFIED_SUCCESS else VerificationStatus.FAILED_VERIFICATION,
            observedState = evidence.observedState ?: "Observed after $name"
        )
    }
}
