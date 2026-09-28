package com.aura.assistant.audio

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Microphone owner identity in the AURA architecture.
 */
enum class MicOwner {
    NONE,
    WAKE_WORD,
    GEMINI_LIVE
}

/**
 * Central singleton managing hardware microphone ownership across AURA components.
 *
 * Prevents race conditions, double-open crashes, and silent audio dropouts between
 * WakeWordService (OpenWakeWord) and GeminiLiveClient (Bidi WebSocket AudioRecord).
 */
object MicOwnershipManager {
    private const val TAG = "MicOwnershipManager"
    private val lock = ReentrantLock()

    private val _currentOwner = MutableStateFlow(MicOwner.NONE)
    val currentOwner: StateFlow<MicOwner> = _currentOwner.asStateFlow()

    /**
     * Attempts to acquire exclusive ownership of the microphone.
     *
     * @param caller The component requesting the mic.
     * @return true if granted, false if occupied by another owner.
     */
    fun requestOwnership(caller: MicOwner): Boolean = lock.withLock {
        val current = _currentOwner.value
        if (current == caller) {
            Log.d(TAG, "Mic already owned by $caller")
            return true
        }
        if (current == MicOwner.NONE) {
            _currentOwner.value = caller
            Log.i(TAG, "Mic ownership granted to $caller (was NONE)")
            return true
        }
        // GEMINI_LIVE has higher priority than WAKE_WORD — preempt if needed
        if (caller == MicOwner.GEMINI_LIVE && current == MicOwner.WAKE_WORD) {
            Log.i(TAG, "Preempting WAKE_WORD for GEMINI_LIVE")
            _currentOwner.value = caller
            return true
        }
        Log.w(TAG, "Mic ownership request DENIED for $caller (currently owned by $current)")
        return false
    }

    /**
     * Releases ownership of the microphone if held by [caller].
     */
    fun releaseOwnership(caller: MicOwner) = lock.withLock {
        if (_currentOwner.value == caller) {
            _currentOwner.value = MicOwner.NONE
            Log.i(TAG, "Mic ownership released by $caller -> NONE")
        } else {
            Log.d(TAG, "releaseOwnership ignored: $caller tried to release but owner is ${_currentOwner.value}")
        }
    }

    /**
     * Checks if the microphone is currently unallocated.
     */
    fun isAvailable(): Boolean = _currentOwner.value == MicOwner.NONE

    /**
     * Emergency reset of microphone ownership.
     */
    fun forceRelease() = lock.withLock {
        Log.w(TAG, "Forced mic ownership reset to NONE (was ${_currentOwner.value})")
        _currentOwner.value = MicOwner.NONE
    }
}
