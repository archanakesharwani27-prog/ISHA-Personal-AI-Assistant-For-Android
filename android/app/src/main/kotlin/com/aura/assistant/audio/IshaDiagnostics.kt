package com.aura.assistant.audio

import com.aura.assistant.ai.AuraLiveState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Diagnostic metrics for the OpenWakeWord background service.
 */
data class WakeWordDiagnostics(
    val serviceRunning: Boolean = false,
    val micOwned: Boolean = false,
    val modelLoaded: Boolean = false,
    val inferenceRunning: Boolean = false,
    val detectionCount: Int = 0,
    val lastDetectionTime: Long? = null,
    val lastModelName: String? = null,
    val lastScore: Float = 0.0f,
    val permissionGranted: Boolean = false
)

/**
 * Diagnostic metrics for the Gemini Live bidirectional streaming session.
 */
data class LiveSessionDiagnostics(
    val wsConnected: Boolean = false,
    val sessionState: AuraLiveState = AuraLiveState.IDLE,
    val micOwned: Boolean = false,
    val audioPacketsSent: Int = 0,
    val serverEventsReceived: Int = 0,
    val toolCallsHandled: Int = 0,
    val interruptionCount: Int = 0,
    val reconnectCount: Int = 0,
    val lastError: String? = null,
    val lastTurnLatencyMs: Long = 0L
)

/**
 * Observable diagnostics singleton for ISHA system monitoring.
 */
object IshaDiagnostics {
    private val _wakeWord = MutableStateFlow(WakeWordDiagnostics())
    val wakeWord: StateFlow<WakeWordDiagnostics> = _wakeWord.asStateFlow()

    private val _liveSession = MutableStateFlow(LiveSessionDiagnostics())
    val liveSession: StateFlow<LiveSessionDiagnostics> = _liveSession.asStateFlow()

    fun updateWake(update: (WakeWordDiagnostics) -> WakeWordDiagnostics) {
        _wakeWord.value = update(_wakeWord.value)
    }

    fun updateLive(update: (LiveSessionDiagnostics) -> LiveSessionDiagnostics) {
        _liveSession.value = update(_liveSession.value)
    }
}

/** Backward compatibility alias for AuraDiagnostics */
val AuraDiagnostics = IshaDiagnostics

