package com.aura.assistant.ai

import android.util.Log

/**
 * 🔗 IshaVoiceContextBridge: Universal Cross-Modal Memory Bridge.
 *
 * Bridges conversation context between:
 * 1. Gemini Live duplex voice sessions (GeminiLiveClient)
 * 2. Streaming text chat sessions (GeminiChatService)
 * 3. Dynamic Island voice & text actions (IshaDynamicIsland)
 *
 * Guarantees zero context loss when switching between speaking and typing,
 * or when voice reconnects across network/microphone drops.
 */
object IshaVoiceContextBridge {
    private const val TAG = "IshaVoiceContextBridge"
    private const val MAX_TURNS = 30

    private val turnBuffer = ConversationTurnBuffer(maxTurns = MAX_TURNS)
    private val lock = Any()

    @Volatile
    var lastTargetContact: String? = null

    @Volatile
    var lastUserPrompt: String? = null

    /**
     * Records a completed turn from any modality (Voice, Text chat, Dynamic Island).
     */
    fun recordTurn(source: String, userText: String, assistantText: String) {
        val cleanUser = userText.trim()
        val cleanAsst = assistantText.trim()
        if (cleanUser.isBlank() && cleanAsst.isBlank()) return

        synchronized(lock) {
            turnBuffer.add(
                LiveVoiceTurn(
                    userText = cleanUser,
                    assistantText = cleanAsst,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
        Log.d(TAG, "Recorded $source turn: user='${cleanUser.take(40)}', asst='${cleanAsst.take(40)}'. Total in bridge=${getTurnCount()}")
    }

    /**
     * Helper to record voice turns.
     */
    fun recordVoiceTurn(userText: String, assistantText: String) {
        recordTurn("voice", userText, assistantText)
    }

    /**
     * Helper to record text chat turns.
     */
    fun recordTextTurn(userText: String, assistantText: String) {
        recordTurn("text", userText, assistantText)
    }

    /**
     * Returns the total number of turns currently retained in the bridge buffer.
     */
    fun getTurnCount(): Int = synchronized(lock) { turnBuffer.recentTurns().size }

    /**
     * Returns recent cross-modal turns (up to [limit]).
     */
    fun getRecentTurns(limit: Int = 20): List<LiveVoiceTurn> {
        return synchronized(lock) {
            turnBuffer.recentTurns().takeLast(limit)
        }
    }

    /**
     * Formats recent conversation turns as a compact context block for system prompts or Live API.
     */
    fun getFormattedCrossModalContext(limit: Int = 15): String {
        val turns = getRecentTurns(limit)
        if (turns.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("💬 RECENT CONVERSATION CONTINUITY (VOICE ↔ TEXT SHARED MEMORY):")
        sb.appendLine("The user ('Boss') recently had the following interactions across voice/text chat:")
        for (turn in turns) {
            val user = if (turn.userText.isNotBlank()) turn.userText.take(180) else "(Spoken query)"
            val asst = if (turn.assistantText.isNotBlank()) turn.assistantText.take(220) else "(Action completed)"
            sb.appendLine("Boss: $user")
            sb.appendLine("Isha: $asst")
        }
        return sb.toString().trim()
    }

    /**
     * Clears buffer (e.g., when user explicitly resets or clears all chats).
     */
    fun clear() {
        synchronized(lock) {
            turnBuffer.clear()
        }
        Log.d(TAG, "Cross-modal context bridge cleared.")
    }
}
