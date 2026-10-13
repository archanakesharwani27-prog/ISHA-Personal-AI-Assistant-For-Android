package com.aura.assistant.data

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ── AGE GROUPS ─────────────────────────────────────────────────────────────
 * Directs Isha's vocabulary, complexity, demeanor, and pedagogical style.
 */
enum class UserAgeGroup(
    val id: String,
    val title: String,
    val subtitle: String,
    val ageRange: String,
    val promptDirective: String,
    val sampleResponse: String
) {
    KIDS(
        id = "kids",
        title = "Kids",
        subtitle = "Playful, simple & gentle",
        ageRange = "< 10 yrs",
        promptDirective = "AGE GROUP: CHILD (<10 YEARS OLD). Tone must be extremely warm, playful, encouraging, and gentle. Use simple, easily understood vocabulary. Avoid any technical jargon or complex explanations. When explaining concepts, use fun metaphors, stories, and cheerful enthusiasm. Always be protective, positive, and patient.",
        sampleResponse = "Arre wah! Aao aaj hum milkar ek mazedaar kahani sunte hain ya koi fun puzzle solve karte hain! ✨"
    ),
    TEENS(
        id = "teens",
        title = "Teens",
        subtitle = "Study buddy & friendly mentor",
        ageRange = "11-15 yrs",
        promptDirective = "AGE GROUP: TEENAGER (11-15 YEARS OLD). Tone must be like a cool, supportive elder sister or smart study companion. Speak natural, modern conversational Hinglish. Explain school subjects, science, and coding step-by-step with zero condescension. Keep things relatable, inspiring, and engaging.",
        sampleResponse = "Hey! Kaunse chapter ya homework pe attack karna hai aaj? Chalo milkar ekdum easily solve karte hain! 🚀"
    ),
    YOUNG_ADULT(
        id = "young_adult",
        title = "College & Youth",
        subtitle = "Best friend, coding & career peer",
        ageRange = "16-20 yrs",
        promptDirective = "AGE GROUP: COLLEGE / YOUNG ADULT (16-20 YEARS OLD). Tone must be an energetic, sharp best friend, coding partner, and career mentor. Keep explanations crisp, practical, and real. Understand tech, exams, projects, and life challenges with empathy and actionable advice.",
        sampleResponse = "Yo! Kaisi chal rahi hai padhai aur projects? Batao aaj kis cheez ko crack karna hai! 💡"
    ),
    ADULT(
        id = "adult",
        title = "Adult",
        subtitle = "Executive partner & confidante",
        ageRange = "20+ yrs",
        promptDirective = "AGE GROUP: ADULT (20+ YEARS OLD). Tone must be mature, respectful, highly capable, and intellectually sharp. Be a thoughtful confidante and reliable executive partner. Deliver solutions with high efficiency, nuance, and concise precision.",
        sampleResponse = "Namaste. Main poori tarah ready hoon. Kahiye, aaj kin priorities aur tasks par focus karein?"
    );

    companion object {
        fun fromId(id: String): UserAgeGroup = entries.find { it.id == id } ?: YOUNG_ADULT
    }
}

/**
 * ── SALUTATION / TITLE ──────────────────────────────────────────────────────
 * How Isha addresses the user in conversations, greetings, and tool confirmations.
 */
enum class SalutationTitle(
    val id: String,
    val label: String,
    val defaultDisplay: String
) {
    BOSS("boss", "Boss", "Boss"),
    DOST("dost", "Dost", "Dost"),
    BHAI("bhai", "Bhai", "Bhai"),
    SIR_MAAM("sir", "Sir / Ji", "Sir ji"),
    NAME_ONLY("name_only", "Name", "{name}"),
    CUSTOM("custom", "Custom", "{custom}");

    companion object {
        fun fromId(id: String): SalutationTitle = entries.find { it.id == id } ?: BOSS
    }
}

/**
 * ── PERSONA ARCHETYPE ──────────────────────────────────────────────────────
 * The functional personality stance Isha assumes in day-to-day interactions.
 */
enum class PersonaArchetype(
    val id: String,
    val title: String,
    val subtitle: String,
    val promptDirective: String
) {
    BEST_FRIEND(
        id = "best_friend",
        title = "Best Friend (सच्चा दोस्त)",
        subtitle = "Empathetic, caring, shares laughs & listens deeply",
        promptDirective = "ARCHETYPE: TRUE BEST FRIEND (सच्चा दोस्त). Aap user ki sabse close, loyal aur caring dost hain. Unke emotions ko samjhein, unke din ka haal poochhein, unki safalta par khush hon aur mushkil waqt mein himmat dein. Tone ekdum natural, apnepan se bhari honi chahiye jaise ek saccha dost baat karta hai."
    ),
    MENTOR_ADVISOR(
        id = "mentor",
        title = "Mentor & Advisor (सलाहकार)",
        subtitle = "Wise, strategic, guiding growth & thoughtful choices",
        promptDirective = "ARCHETYPE: MENTOR & ADVISOR (मार्गदर्शक / सलाहकार). Aap ek wise aur thoughtful mentor hain. User ko structured thinking sikhayein, pros & cons batayein, unhe sahi decision lene mein guide karein aur unke goals ko shape karne mein help karein."
    ),
    JARVIS_EXECUTIVE(
        id = "jarvis",
        title = "Jarvis / Executive (पर्सनल असिस्टेंट)",
        subtitle = "Ultra-fast, bullet-point brevity & high execution",
        promptDirective = "ARCHETYPE: JARVIS / EXECUTIVE INTELLIGENCE. Ultra-crisp, mission-driven, zero unnecessary fluff. Actions aur answers direct, accurate aur streamlined hone chahiye. Bullet points aur actionable summaries prefer karein."
    ),
    STUDY_BUDDY(
        id = "study_buddy",
        title = "Study & Learning Buddy (स्टडी पार्टनर)",
        subtitle = "Interactive tutor, breaks down complex topics easily",
        promptDirective = "ARCHETYPE: STUDY & LEARNING BUDDY (ट्यूटर / स्टडी पार्टनर). Concept ko simple analogies aur real-world examples se samjhayein, interactive sawaal poochh kar check karein ki samajh aaya ya nahi, aur patience ke sath har doubt clear karein."
    );

    companion object {
        fun fromId(id: String): PersonaArchetype = entries.find { it.id == id } ?: BEST_FRIEND
    }
}

/**
 * ── COLOR THEME PALETTES ────────────────────────────────────────────────────
 * Visual styling theme for accents, borders, cards, and glowing rings.
 */
enum class IshaThemePalette(
    val id: String,
    val displayName: String,
    val accentHex: Long,
    val secondaryHex: Long,
    val description: String
) {
    CYBER_CYAN(
        id = "cyber_cyan",
        displayName = "Cyber Cyan",
        accentHex = 0xFF00E5FF,
        secondaryHex = 0xFF00B0FF,
        description = "AURA classic electric cyan"
    ),
    ROYAL_AMETHYST(
        id = "royal_amethyst",
        displayName = "Royal Amethyst",
        accentHex = 0xFF9D4EDD,
        secondaryHex = 0xFF7C4DFF,
        description = "Deep violet & cyber purple"
    ),
    EMERALD_MATRIX(
        id = "emerald_matrix",
        displayName = "Emerald Matrix",
        accentHex = 0xFF10A37F,
        secondaryHex = 0xFF00E676,
        description = "ChatGPT tech emerald green"
    ),
    SUNSET_AMBER(
        id = "sunset_amber",
        displayName = "Sunset Amber",
        accentHex = 0xFFFF9100,
        secondaryHex = 0xFFFFAB00,
        description = "Warm golden radiant amber"
    ),
    NEON_CRIMSON(
        id = "neon_crimson",
        displayName = "Neon Crimson",
        accentHex = 0xFFFF2A6D,
        secondaryHex = 0xFFFF1744,
        description = "Vibrant electric crimson"
    );

    val primaryColor: Color get() = Color(accentHex)
    val secondaryColor: Color get() = Color(secondaryHex)

    companion object {
        fun fromId(id: String): IshaThemePalette = entries.find { it.id == id } ?: CYBER_CYAN
    }
}

/**
 * ── COMPLETE PERSONA CONFIGURATION MODEL ─────────────────────────────────────
 */
data class IshaPersonaConfig(
    val ageGroup: UserAgeGroup = UserAgeGroup.YOUNG_ADULT,
    val salutationTitle: SalutationTitle = SalutationTitle.BOSS,
    val customTitle: String = "",
    val archetype: PersonaArchetype = PersonaArchetype.BEST_FRIEND,
    val themePaletteId: String = IshaThemePalette.CYBER_CYAN.id,
    val customAboutMe: String = "",
    val customResponseStyle: String = ""
) {
    val themePalette: IshaThemePalette get() = IshaThemePalette.fromId(themePaletteId)

    /**
     * Resolves the human-readable salutation/addressing label.
     */
    fun resolveSalutation(userName: String = "Ansh"): String {
        return when (salutationTitle) {
            SalutationTitle.BOSS -> "Boss"
            SalutationTitle.DOST -> "Dost"
            SalutationTitle.BHAI -> "Bhai"
            SalutationTitle.SIR_MAAM -> "Sir ji"
            SalutationTitle.NAME_ONLY -> userName.ifBlank { "Dost" }
            SalutationTitle.CUSTOM -> customTitle.trim().ifBlank { userName.ifBlank { "Dost" } }
        }
    }

    /**
     * Generates a sample live response string tailored to this exact configuration.
     */
    fun getPreviewGreeting(userName: String = "Ansh"): String {
        val salutation = resolveSalutation(userName)
        return when (archetype) {
            PersonaArchetype.BEST_FRIEND -> "Yo $salutation! Kaisi chal rahi hai life aur projects? Batao aaj kis cheez ko rock karna hai! 💡"
            PersonaArchetype.STUDY_BUDDY -> "Chalo $salutation, concept review karte hain. Doubt clear karo aur code run karte hain bina kisi tension ke! 💻"
            PersonaArchetype.JARVIS_EXECUTIVE -> "Standing by, $salutation. Systems, tools aur tasks aligned hain. Kahiye kya execute karna hai."
            PersonaArchetype.MENTOR_ADVISOR -> "Hello $salutation. Long-term goals par focus karte hue, aaj ka sabse high-impact task kya hona chahiye?"
        }
    }
}
