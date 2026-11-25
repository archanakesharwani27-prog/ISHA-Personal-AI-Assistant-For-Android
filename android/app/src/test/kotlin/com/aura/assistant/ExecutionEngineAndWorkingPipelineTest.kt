package com.aura.assistant

import android.content.ContextWrapper
import com.aura.assistant.ai.IshaToolRouter
import com.aura.assistant.execution.ExecutionEngine
import com.aura.assistant.execution.ExecutionVerifier
import com.aura.assistant.execution.RetryPolicy
import com.aura.assistant.security.ConfirmationEngine
import com.aura.assistant.tools.IshaTool
import com.aura.assistant.tools.ToolCategory
import com.aura.assistant.tools.ToolContext
import com.aura.assistant.tools.ToolResult
import com.aura.assistant.tools.ToolStatus
import com.aura.assistant.tools.VerificationResult
import com.aura.assistant.tools.VerificationStatus
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ExecutionEngineAndWorkingPipelineTest {

    @Test
    fun testExecutionEngineRetriesWhenVerificationFailsEvenAfterOptimisticSuccess() = runBlocking {
        val dummyContext = ContextWrapper(null)
        val execCount = AtomicInteger(0)
        val verifyCount = AtomicInteger(0)

        val flakyVerifyTool = object : IshaTool {
            override val id = "test_flaky_verify"
            override val name = "chat_on_whatsapp" // Uses non-default timeout (15_000L) from RetryPolicy
            override val description = "Synthetic verification test tool"
            override val category = ToolCategory.SYSTEM

            override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
                // Ensure RetryPolicy.getToolTimeout is wired into ToolContext.timeoutMs
                assertEquals(15_000L, context.timeoutMs)
                execCount.incrementAndGet()
                return ToolResult.success(id, "Dispatched attempt ${execCount.get()}")
            }

            override suspend fun verify(
                arguments: JsonObject,
                result: ToolResult,
                context: ToolContext
            ): VerificationResult {
                val count = verifyCount.incrementAndGet()
                return if (count == 1) {
                    VerificationResult(
                        status = VerificationStatus.FAILED_VERIFICATION,
                        observedState = "Target window did not update on attempt 1"
                    )
                } else {
                    VerificationResult(
                        status = VerificationStatus.VERIFIED_SUCCESS,
                        observedState = "Verified active on retry #$count"
                    )
                }
            }
        }

        val result = ExecutionEngine.execute(
            tool = flakyVerifyTool,
            arguments = JsonObject(),
            context = dummyContext,
            scope = CoroutineScope(Dispatchers.IO),
            retryPolicy = RetryPolicy(maxRetries = 2, initialBackoffMs = 10L)
        )

        assertEquals(2, execCount.get())
        assertEquals(2, verifyCount.get())
        assertEquals(ToolStatus.SUCCESS, result.status)
        assertTrue(result.evidence?.verified == true)
        assertEquals("Verified active on retry #2", result.evidence?.observedState)
    }

    @Test
    fun testConfirmationEngineInvokesConfirmAndCancelCallbacks() {
        var confirmed = false
        var cancelled = false

        ConfirmationEngine.request(
            toolName = "empty_recycle_bin",
            promptText = "Confirm cleanup?",
            onConfirm = { confirmed = true },
            onCancel = { cancelled = true }
        )
        assertNotNull(ConfirmationEngine.pending.value)
        ConfirmationEngine.confirm()
        assertTrue(confirmed)
        assertFalse(cancelled)
        assertNull(ConfirmationEngine.pending.value)

        confirmed = false
        ConfirmationEngine.request(
            toolName = "empty_recycle_bin",
            promptText = "Confirm cleanup again?",
            onConfirm = { confirmed = true },
            onCancel = { cancelled = true }
        )
        ConfirmationEngine.cancel()
        assertFalse(confirmed)
        assertTrue(cancelled)
        assertNull(ConfirmationEngine.pending.value)
    }

    @Test
    fun testExecutionVerifierRejectsErrorAndHonorsBriefingContract() {
        val dummyContext = ContextWrapper(null)

        val errPayload = JsonObject().apply {
            addProperty("status", "error")
            addProperty("message", "Network unreachable")
        }
        val errEvidence = ExecutionVerifier.verify(dummyContext, "jarvis_status_briefing", JsonObject(), errPayload)
        assertFalse(errEvidence.verified)
        assertTrue(errEvidence.observedState?.contains("Network unreachable") == true)

        val okPayload = JsonObject().apply {
            addProperty("status", "success")
            addProperty("summary", "All systems nominal, Boss.")
        }
        val okEvidence = ExecutionVerifier.verify(dummyContext, "jarvis_status_briefing", JsonObject(), okPayload)
        assertTrue(okEvidence.verified)

        // YouTube with no AudioManager/AccessibilityService must remain unverified instead of false Done!
        val ytEvidence = ExecutionVerifier.verify(dummyContext, "play_youtube", JsonObject(), JsonObject().apply {
            addProperty("status", "success")
        })
        assertFalse("play_youtube must NOT prematurely verify on empty dispatch", ytEvidence.verified)
    }

    @Test
    fun testLiveVoiceToolRouterCoversJarvisCoreActions() {
        val required = listOf(
            "jarvis_status_briefing",
            "play_youtube",
            "take_screenshot",
            "get_screen_context",
            "find_and_tap",
            "screen_scroll",
            "press_system_key",
            "media_control",
            "unlock_device",
            "lock_device"
        )
        for (name in required) {
            assertTrue("Tool '$name' must be supported in Live Voice mode", IshaToolRouter.isToolSupportedInLiveVoice(name))
        }
    }
}
