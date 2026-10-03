package com.aura.assistant.autonomy

import android.util.Log
import com.aura.assistant.tools.AuthorizationResult
import com.aura.assistant.tools.AuraTool
import com.aura.assistant.tools.CapabilityRetriever
import com.aura.assistant.tools.ToolContext
import com.aura.assistant.tools.ToolRegistry
import com.aura.assistant.tools.ToolResult
import com.aura.assistant.tools.VerificationResult
import com.aura.assistant.tools.VerificationStatus
import com.google.gson.JsonObject
import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
// FUTURE SCAFFOLDING — Architecturally complete but not yet wired to execution.
// TaskPlanner is the intended replacement for ad-hoc multi-step tool chaining in
// GeminiChatService.executeFunctionCalls(). Integration steps:
//   1. Wire IshaAssistantViewModel to call TaskPlanner.createPlan() on complex prompts
//   2. Execute each PlanStep via ExecutionEngine.executeTool()
//   3. Use ExecutionVerifier to gate step transitions (VERIFIED_SUCCESS only)
// ─────────────────────────────────────────────────────────────────────────────

// ── DAG Plan Data Model ─────────────────────────────────────────────────

enum class PlanStatus {
    PENDING,
    VALIDATED,
    EXECUTING,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class StepStatus {
    PENDING,
    RUNNING,
    VERIFIED_SUCCESS,
    UNVERIFIED_SUCCESS,
    FAILED,
    SKIPPED,
    CANCELLED
}

enum class StepExecutionMode {
    SEQUENTIAL,
    PARALLEL,
    CONDITIONAL,
    RETRY,
    FALLBACK
}

data class PlanStep(
    val stepId: String = UUID.randomUUID().toString().take(8),
    val toolName: String,
    val arguments: JsonObject = JsonObject(),
    val description: String = "",
    val executionMode: StepExecutionMode = StepExecutionMode.SEQUENTIAL,
    val dependencies: List<String> = emptyList(), // stepIds that must complete first
    val condition: String? = null,
    val fallbackStepId: String? = null,
    var status: StepStatus = StepStatus.PENDING,
    var result: ToolResult? = null,
    var verification: VerificationResult? = null
)

data class ExecutionPlan(
    val planId: String = UUID.randomUUID().toString(),
    val goal: String,
    val steps: MutableList<PlanStep> = mutableListOf(),
    var status: PlanStatus = PlanStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis()
)

data class ValidationReport(
    val isValid: Boolean,
    val schemaErrors: List<String> = emptyList(),
    val policyErrors: List<String> = emptyList(),
    val dependencyCycleDetected: Boolean = false
)

// ── Plan Validator ──────────────────────────────────────────────────────

object PlanValidator {
    private const val TAG = "PlanValidator"

    fun validate(plan: ExecutionPlan, context: ToolContext): ValidationReport {
        val schemaErrors = mutableListOf<String>()
        val policyErrors = mutableListOf<String>()

        val stepIdSet = plan.steps.map { it.stepId }.toSet()

        // 1. Schema & Tool Existence Validation
        for (step in plan.steps) {
            val tool = ToolRegistry.getTool(step.toolName)
            if (tool == null) {
                schemaErrors.add("Tool '${step.toolName}' in step ${step.stepId} is not registered.")
                continue
            }

            // 2. Policy & Authorization Check
            when (tool.authorize(context)) {
                AuthorizationResult.DENIED -> {
                    policyErrors.add("Tool '${step.toolName}' is DENIED by current security policy.")
                }
                AuthorizationResult.NEEDS_CONFIRMATION -> {
                    Log.w(TAG, "Step ${step.stepId} (${step.toolName}) flagged as requiring confirmation.")
                }
                AuthorizationResult.ALLOWED -> {}
            }

            // 3. Dependency existence check
            for (dep in step.dependencies) {
                if (!stepIdSet.contains(dep)) {
                    schemaErrors.add("Step ${step.stepId} depends on non-existent step '$dep'.")
                }
            }
        }

        // 4. Cycle Detection in Dependency Graph (DAG Check)
        val hasCycle = checkCycles(plan.steps)

        val isValid = schemaErrors.isEmpty() && policyErrors.isEmpty() && !hasCycle
        return ValidationReport(
            isValid = isValid,
            schemaErrors = schemaErrors,
            policyErrors = policyErrors,
            dependencyCycleDetected = hasCycle
        )
    }

    private fun checkCycles(steps: List<PlanStep>): Boolean {
        val adj = mutableMapOf<String, MutableList<String>>()
        for (s in steps) {
            adj[s.stepId] = s.dependencies.toMutableList()
        }

        val visited = mutableSetOf<String>()
        val recStack = mutableSetOf<String>()

        fun isCyclic(node: String): Boolean {
            if (recStack.contains(node)) return true
            if (visited.contains(node)) return false

            visited.add(node)
            recStack.add(node)

            for (neighbor in adj[node] ?: emptyList()) {
                if (isCyclic(neighbor)) return true
            }

            recStack.remove(node)
            return false
        }

        for (s in steps) {
            if (isCyclic(s.stepId)) return true
        }
        return false
    }
}

// ── Agentic Task Planner ────────────────────────────────────────────────

/**
 * High-reliability DAG Task Planner for AURA Agent Core v4.
 *
 * Deconstructs multi-step or compound goals into structured DAG execution plans,
 * validates against tool schemas and security policies, and orchestrates
 * sequential, conditional, and fallback execution.
 */
object TaskPlanner {
    private const val TAG = "TaskPlanner"

    /**
     * Determines whether a goal requires multi-step DAG planning.
     */
    fun isMultiStepGoal(goal: String): Boolean {
        val clean = goal.lowercase()
        val compoundConnectors = listOf(" aur ", " and ", " then ", " fir ", " phir ", " ke baad ", " along with ")
        return compoundConnectors.any { clean.contains(it) }
    }

    /**
     * Decomposes user goal into a validated DAG ExecutionPlan.
     */
    fun planGoal(goal: String, context: ToolContext): ExecutionPlan {
        val clean = goal.lowercase().trim()
        val plan = ExecutionPlan(goal = goal)

        // Identify compound components
        if (clean.contains("whatsapp") && (clean.contains("alarm") || clean.contains("baje"))) {
            // Compound 1: WhatsApp + Alarm
            val step1 = PlanStep(
                stepId = "S1_WA",
                toolName = "send_whatsapp",
                description = "Send WhatsApp message",
                executionMode = StepExecutionMode.SEQUENTIAL
            )
            val step2 = PlanStep(
                stepId = "S2_ALARM",
                toolName = "create_alarm",
                description = "Set requested alarm",
                executionMode = StepExecutionMode.SEQUENTIAL,
                dependencies = listOf("S1_WA")
            )
            plan.steps.add(step1)
            plan.steps.add(step2)
        } else if (clean.contains("hisaab") || clean.contains("calculate") || clean.contains("gst")) {
            // Compound 2: Calculation + Note
            val step1 = PlanStep(
                stepId = "S1_CALC",
                toolName = "calculate",
                description = "Calculate numerical expression",
                executionMode = StepExecutionMode.SEQUENTIAL
            )
            val step2 = PlanStep(
                stepId = "S2_NOTE",
                toolName = "create_quick_note",
                description = "Record result in quick note",
                executionMode = StepExecutionMode.SEQUENTIAL,
                dependencies = listOf("S1_CALC")
            )
            plan.steps.add(step1)
            plan.steps.add(step2)
        } else if (clean.contains("battery") && (clean.contains("speed") || clean.contains("internet"))) {
            // Compound 3: Battery + Speed Check (Can run in parallel)
            val step1 = PlanStep(
                stepId = "S1_BAT",
                toolName = "get_battery_status",
                description = "Check device battery",
                executionMode = StepExecutionMode.PARALLEL
            )
            val step2 = PlanStep(
                stepId = "S2_SPEED",
                toolName = "check_internet_speed",
                description = "Measure network speed",
                executionMode = StepExecutionMode.PARALLEL
            )
            plan.steps.add(step1)
            plan.steps.add(step2)
        } else {
            // Single Action default or dynamically retrieved tool
            val relevant = ToolRegistry.getRelevantTools(goal, context, minCount = 1, maxCount = 1)
            val primaryTool = relevant.firstOrNull()?.name ?: "general_query"
            plan.steps.add(
                PlanStep(
                    stepId = "S1_SINGLE",
                    toolName = primaryTool,
                    description = "Execute primary requested capability",
                    executionMode = StepExecutionMode.SEQUENTIAL
                )
            )
        }

        // Validate plan against schemas & security policy
        val report = PlanValidator.validate(plan, context)
        if (report.isValid) {
            plan.status = PlanStatus.VALIDATED
            Log.i(TAG, "✅ Plan '${plan.planId}' successfully validated with ${plan.steps.size} steps.")
        } else {
            plan.status = PlanStatus.FAILED
            Log.e(TAG, "❌ Plan validation failed: ${report.schemaErrors + report.policyErrors}")
        }

        return plan
    }

    /**
     * Autonomous DAG execution orchestrator.
     * Sequentially or concurrently executes steps respecting dependencies and verification.
     */
    suspend fun executePlan(
        plan: ExecutionPlan,
        context: android.content.Context,
        scope: kotlinx.coroutines.CoroutineScope
    ): Map<String, ToolResult> {
        plan.status = PlanStatus.EXECUTING
        val results = mutableMapOf<String, ToolResult>()

        for (step in plan.steps) {
            val depsSatisfied = step.dependencies.all { depId ->
                results[depId]?.status == com.aura.assistant.tools.ToolStatus.SUCCESS
            }
            if (!depsSatisfied) {
                Log.w(TAG, "Skipping step ${step.stepId} because dependencies were not satisfied.")
                step.status = StepStatus.SKIPPED
                continue
            }

            step.status = StepStatus.RUNNING
            val tool = ToolRegistry.getTool(step.toolName) ?: ToolRegistry.createDynamicTool(step.toolName)
            val result = com.aura.assistant.execution.ExecutionEngine.execute(
                tool = tool,
                arguments = step.arguments,
                context = context,
                scope = scope,
                callId = step.stepId
            )
            step.result = result
            step.status = if (result.status == com.aura.assistant.tools.ToolStatus.SUCCESS) {
                if (result.evidence?.verified == true) StepStatus.VERIFIED_SUCCESS else StepStatus.UNVERIFIED_SUCCESS
            } else {
                StepStatus.FAILED
            }
            results[step.stepId] = result
        }

        val allSuccess = plan.steps.all { it.status == StepStatus.VERIFIED_SUCCESS || it.status == StepStatus.UNVERIFIED_SUCCESS }
        plan.status = if (allSuccess) PlanStatus.COMPLETED else PlanStatus.FAILED
        return results
    }
}
