package com.aura.assistant.brain

import java.util.Random

enum class ReactionSituation {
    ATTENTION_CHECK,        // "Sun rahi ho?", "AURA ruk"
    ACKNOWLEDGEMENT,        // "Hmm...", "Achha"
    LISTENING,              // "Boliye, sun rahi hoon"
    THINKING,               // "Ek second, check kar rahi hoon"
    SUCCESS,                // "Ho gaya Boss!"
    FAILURE,                // "Hmm, ek dikkat aayi"
    EMPATHETIC,             // "Koi baat nahi, main sambhal lungi"
    INTERRUPTED,            // "Ji, aap boliye"
    RECOVERY                // "Haan, main wapas sun rahi hoon"
}

enum class TurnReaction {
    ATTENTION_CHECK,
    CASUAL_DIALOGUE,
    TASK_COMMAND,
    CORRECTION,
    INTERRUPTION
}

/**
 * Deterministic, context-aware human-like reaction engine for AURA 2.0.
 * Follows strict feminine Hindi (stree-ling) grammar.
 */
object ConversationalReactionEngine {
    private val random = Random()

    private val ATTENTION_REACTIONS = listOf(
        "Hmm... haan, bilkul sun rahi hoon. Aap boliye.",
        "Haan Boss, main yahi hoon, sun rahi hoon.",
        "Ji bilkul, dhyan se sun rahi hoon, boliye na.",
        "Haan, boliye Boss, main poori tarah sun rahi hoon."
    )

    private val ACKNOWLEDGEMENT_REACTIONS = listOf(
        "Hmm...",
        "Haan...",
        "Achha...",
        "Ji...",
        "Samajh gayi..."
    )

    private val THINKING_REACTIONS = listOf(
        "Ek second, dekh rahi hoon...",
        "Rukiye, check karti hoon...",
        "Hmm, abhi karti hoon...",
        "Bas ek pal, koshish kar rahi hoon..."
    )

    private val SUCCESS_REACTIONS = listOf(
        "Ho gaya Boss! ✨",
        "Done! Kar diya maine. 👍",
        "Bilkul ho gaya! Kuch aur bataiye?",
        "Kar diya Boss, perfectly done!"
    )

    private val FAILURE_REACTIONS = listOf(
        "Hmm, maine try kiya lekin ek issue aaya. Main alternate tareeqa dekh rahi hoon.",
        "Ek dikkat aa gayi Boss, lekin main try kar rahi hoon.",
        "Ye direct nahi ho paya, doosra tareeqa use kar rahi hoon."
    )

    private val INTERRUPTED_REACTIONS = listOf(
        "Ji Boss, aap boliye, sun rahi hoon.",
        "Ruk gayi, aapki baat sun rahi hoon.",
        "Haan, boliye na, main sun rahi hoon."
    )

    /**
     * Classifies user utterance to detect attention checks or interruptions.
     */
    fun classifyTurn(transcript: String): TurnReaction {
        val clean = transcript.lowercase().trim()

        if (clean.contains("sun rahi ho") || clean.contains("sun rahi h") ||
            clean.contains("meri baat sun") || clean.contains("are you listening") ||
            clean.contains("dhyan hai") || clean.contains("sun rhi ho")) {
            return TurnReaction.ATTENTION_CHECK
        }

        if (clean.startsWith("nahi") || clean.startsWith("no") || clean.startsWith("ruko") ||
            clean.startsWith("ruk") || clean.startsWith("wait") || clean.contains("mera matlab")) {
            return TurnReaction.CORRECTION
        }

        return TurnReaction.CASUAL_DIALOGUE
    }

    /**
     * Selects a natural, randomized feminine Hindi acknowledgement based on situation.
     */
    fun selectAcknowledgement(situation: ReactionSituation): String {
        val bank = when (situation) {
            ReactionSituation.ATTENTION_CHECK -> ATTENTION_REACTIONS
            ReactionSituation.ACKNOWLEDGEMENT -> ACKNOWLEDGEMENT_REACTIONS
            ReactionSituation.LISTENING -> ATTENTION_REACTIONS
            ReactionSituation.THINKING -> THINKING_REACTIONS
            ReactionSituation.SUCCESS -> SUCCESS_REACTIONS
            ReactionSituation.FAILURE -> FAILURE_REACTIONS
            ReactionSituation.EMPATHETIC -> listOf("Koi baat nahi Boss, main hoon na.", "Fikar mat kijiye, sambhal lungi.")
            ReactionSituation.INTERRUPTED -> INTERRUPTED_REACTIONS
            ReactionSituation.RECOVERY -> listOf("Haan Boss, main wapas sun rahi hoon.", "Ji, boliye.")
        }
        return bank[random.nextInt(bank.size)]
    }
}
