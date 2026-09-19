package com.aura.assistant.tools

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope

/**
 * Execution context passed to every AuraTool during execution.
 */
data class ToolContext(
    val applicationContext: Context,
    val scope: CoroutineScope,
    val callId: String = "",
    val timeoutMs: Long = 10000L
) {
    fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            applicationContext,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }
}
