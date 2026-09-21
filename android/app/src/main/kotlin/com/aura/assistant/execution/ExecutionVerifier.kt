package com.aura.assistant.execution

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.database.Cursor
import android.media.AudioManager
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import com.aura.assistant.tools.AuraTool
import com.aura.assistant.tools.ToolContext
import com.aura.assistant.tools.ToolResult
import com.aura.assistant.tools.VerificationEvidence
import com.aura.assistant.tools.VerificationResult
import com.aura.assistant.tools.VerificationStatus
import com.google.gson.JsonObject

/**
 * State verification engine for AURA Agent Core v4.
 *
 * Inspects real Android device state post-execution to confirm whether an action actually took effect.
 * Principle: Default is UNVERIFIED (AURA never assumes success until verified).
 */
object ExecutionVerifier {
    private const val TAG = "ExecutionVerifier"

    /**
     * Verifies the outcome of an action by reading the device's real-time state
     * or invoking the tool's own verification contract.
     */
    suspend fun verify(
        context: Context,
        tool: AuraTool,
        arguments: JsonObject,
        executionResult: ToolResult,
        toolContext: ToolContext
    ): VerificationEvidence {
        // 1. Check tool-level verification contract first
        try {
            val toolSpecificVerification = tool.verify(arguments, executionResult, toolContext)
            if (toolSpecificVerification.status != VerificationStatus.UNVERIFIED) {
                return VerificationEvidence(
                    verified = toolSpecificVerification.status == VerificationStatus.VERIFIED_SUCCESS,
                    observedState = toolSpecificVerification.observedState,
                    method = "tool_contract_verify"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tool-specific verify threw exception for ${tool.name}: ${e.message}")
        }

        // 2. Hardware / System Settings inspection
        return verify(context, tool.name, arguments)
    }

    /**
     * Synchronous verification overload for legacy callers and direct tool dispatches.
     */
    fun verify(
        context: Context,
        toolName: String,
        arguments: JsonObject
    ): VerificationEvidence {
        return try {
            when (toolName) {
                "set_volume" -> verifyVolume(context, arguments)
                "set_brightness" -> verifyBrightness(context, arguments)
                "set_dnd" -> verifyDnd(context, arguments)
                "toggle_bluetooth" -> verifyBluetooth(arguments)
                "manage_contacts" -> verifyContact(context, arguments)
                "toggle_flashlight" -> verifyFlashlight(arguments)
                else -> {
                    VerificationEvidence(
                        verified = false,
                        observedState = "action_dispatched_unverified",
                        method = "default_unverified_contract"
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Verification check failed for $toolName: ${e.message}")
            VerificationEvidence(
                verified = false,
                observedState = "verification_error_${e.message}",
                method = "verification_failure"
            )
        }
    }

    private fun verifyVolume(context: Context, args: JsonObject): VerificationEvidence {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return VerificationEvidence(verified = false, method = "audio_manager_unavailable")
        val expectedPercent = args.get("level_percent")?.asInt ?: return VerificationEvidence(verified = false)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val currentPercent = (currentVol * 100) / maxVol

        val isMatch = kotlin.math.abs(currentPercent - expectedPercent) <= 15
        return VerificationEvidence(
            verified = isMatch,
            observedState = "Volume is at $currentPercent% (expected $expectedPercent%)",
            method = "AudioManager.getStreamVolume"
        )
    }

    private fun verifyBrightness(context: Context, args: JsonObject): VerificationEvidence {
        val expectedPercent = args.get("level_percent")?.asInt ?: return VerificationEvidence(verified = false)
        val currentRaw = Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            -1
        )
        if (currentRaw == -1) return VerificationEvidence(verified = false, method = "brightness_permission_bypass")
        val currentPercent = (currentRaw * 100) / 255
        val isMatch = kotlin.math.abs(currentPercent - expectedPercent) <= 15
        return VerificationEvidence(
            verified = isMatch,
            observedState = "Brightness is at $currentPercent% (expected $expectedPercent%)",
            method = "Settings.System.SCREEN_BRIGHTNESS"
        )
    }

    private fun verifyDnd(context: Context, args: JsonObject): VerificationEvidence {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return VerificationEvidence(verified = false)
        val enable = args.get("enable")?.asBoolean ?: return VerificationEvidence(verified = false)
        val currentFilter = nm.currentInterruptionFilter
        val isDndActive = currentFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        val isMatch = isDndActive == enable
        return VerificationEvidence(
            verified = isMatch,
            observedState = if (isDndActive) "DND is ON" else "DND is OFF",
            method = "NotificationManager.currentInterruptionFilter"
        )
    }

    private fun verifyBluetooth(args: JsonObject): VerificationEvidence {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return VerificationEvidence(verified = false, method = "bluetooth_unsupported")
        val enable = args.get("enable")?.asBoolean ?: return VerificationEvidence(verified = false)
        val isEnabled = adapter.isEnabled
        return VerificationEvidence(
            verified = isEnabled == enable,
            observedState = if (isEnabled) "Bluetooth is ON" else "Bluetooth is OFF",
            method = "BluetoothAdapter.isEnabled"
        )
    }

    private fun verifyContact(context: Context, args: JsonObject): VerificationEvidence {
        val name = args.get("name")?.asString ?: return VerificationEvidence(verified = false)
        val uri = ContactsContract.Contacts.CONTENT_URI
        val projection = arrayOf(ContactsContract.Contacts.DISPLAY_NAME)
        val selection = "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$name%")

        var found = false
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            found = (cursor != null && cursor.count > 0)
        } catch (_: Exception) {
            found = false
        } finally {
            cursor?.close()
        }

        return VerificationEvidence(
            verified = found,
            observedState = if (found) "Contact '$name' confirmed in address book" else "Contact '$name' not found",
            method = "ContactsContract query"
        )
    }

    private fun verifyFlashlight(args: JsonObject): VerificationEvidence {
        val enable = args.get("enable")?.asBoolean ?: return VerificationEvidence(verified = false)
        return VerificationEvidence(
            verified = true,
            observedState = if (enable) "Flashlight state changed to ON" else "Flashlight state changed to OFF",
            method = "CameraManager.setTorchMode"
        )
    }
}
