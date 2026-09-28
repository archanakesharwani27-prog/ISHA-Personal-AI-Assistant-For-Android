package com.aura.assistant.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class PendingConfirmation(
    val id: String = UUID.randomUUID().toString(),
    val toolName: String,
    val promptText: String,
    val onConfirm: () -> Unit,
    val onCancel: () -> Unit = {},
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Confirmation Engine for handling user confirmation flows before executing high-impact actions.
 */
object ConfirmationEngine {
    private val _pending = MutableStateFlow<PendingConfirmation?>(null)
    val pending: StateFlow<PendingConfirmation?> = _pending.asStateFlow()

    fun request(
        toolName: String,
        promptText: String,
        onConfirm: () -> Unit,
        onCancel: () -> Unit = {}
    ) {
        _pending.value = PendingConfirmation(
            toolName = toolName,
            promptText = promptText,
            onConfirm = onConfirm,
            onCancel = onCancel
        )
    }

    fun confirm() {
        val current = _pending.value
        _pending.value = null
        current?.onConfirm?.invoke()
    }

    fun cancel() {
        val current = _pending.value
        _pending.value = null
        current?.onCancel?.invoke()
    }
}
