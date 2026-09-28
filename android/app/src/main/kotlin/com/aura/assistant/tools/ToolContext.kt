package com.aura.assistant.tools

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope

/**
 * Execution context passed to every AuraTool during discovery, authorization, execution, and verification.
 */
data class ToolContext(
    val applicationContext: Context,
    val scope: CoroutineScope,
    val callId: String = "",
    val timeoutMs: Long = 10000L,
    val currentAppPackage: String? = null,
    val screenContentSummary: String? = null
) {
    fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            applicationContext,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }
}
