package com.aura.assistant

import com.aura.assistant.data.*
import org.junit.Assert.*
import org.junit.Test

class IshaPersonaConfigTest {

    @Test
    fun testAllAgeGroupsHaveDistinctDirectivesAndPreveiw() {
        assertEquals(4, UserAgeGroup.entries.size)

        val kids = UserAgeGroup.KIDS
        assertTrue(kids.promptDirective.contains("CHILD"))
        assertTrue(kids.promptDirective.contains("playful"))
        assertEquals("< 10 yrs", kids.ageRange)

        val teens = UserAgeGroup.TEENS
        assertTrue(teens.promptDirective.contains("TEENAGER"))
        assertTrue(teens.promptDirective.contains("supportive elder sister"))
        assertEquals("11-15 yrs", teens.ageRange)

        val youngAdult = UserAgeGroup.YOUNG_ADULT
        assertTrue(youngAdult.promptDirective.contains("COLLEGE / YOUNG ADULT"))
        assertEquals("16-20 yrs", youngAdult.ageRange)

        val adult = UserAgeGroup.ADULT
        assertTrue(adult.promptDirective.contains("ADULT"))
        assertEquals("20+ yrs", adult.ageRange)

        // Verify fallback
        assertEquals(UserAgeGroup.YOUNG_ADULT, UserAgeGroup.fromId("unknown_id"))
        assertEquals(UserAgeGroup.KIDS, UserAgeGroup.fromId("kids"))
    }

    @Test
    fun testSalutationResolutionRespectsUserPreferences() {
        val configBoss = IshaPersonaConfig(salutationTitle = SalutationTitle.BOSS)
        assertEquals("Boss", configBoss.resolveSalutation("Ansh"))

        val configDost = IshaPersonaConfig(salutationTitle = SalutationTitle.DOST)
        assertEquals("Dost", configDost.resolveSalutation("Ansh"))

        val configBhai = IshaPersonaConfig(salutationTitle = SalutationTitle.BHAI)
        assertEquals("Bhai", configBhai.resolveSalutation("Ansh"))

        val configSir = IshaPersonaConfig(salutationTitle = SalutationTitle.SIR_MAAM)
        assertEquals("Sir ji", configSir.resolveSalutation("Ansh"))

        val configName = IshaPersonaConfig(salutationTitle = SalutationTitle.NAME_ONLY)
        assertEquals("Ansh", configName.resolveSalutation("Ansh"))

        val configCustom = IshaPersonaConfig(
            salutationTitle = SalutationTitle.CUSTOM,
            customTitle = "Captain"
        )
        assertEquals("Captain", configCustom.resolveSalutation("Ansh"))

        val configCustomBlank = IshaPersonaConfig(
            salutationTitle = SalutationTitle.CUSTOM,
            customTitle = "   "
        )
        assertEquals("Ansh", configCustomBlank.resolveSalutation("Ansh"))
    }

    @Test
    fun testAllPersonaArchetypesDefinedWithDirectives() {
        assertEquals(4, PersonaArchetype.entries.size)

        val bestFriend = PersonaArchetype.BEST_FRIEND
        assertTrue(bestFriend.promptDirective.contains("TRUE BEST FRIEND"))

        val mentor = PersonaArchetype.MENTOR_ADVISOR
        assertTrue(mentor.promptDirective.contains("MENTOR & ADVISOR"))

        val jarvis = PersonaArchetype.JARVIS_EXECUTIVE
        assertTrue(jarvis.promptDirective.contains("JARVIS / EXECUTIVE"))

        val studyBuddy = PersonaArchetype.STUDY_BUDDY
        assertTrue(studyBuddy.promptDirective.contains("STUDY & LEARNING BUDDY"))

        assertEquals(PersonaArchetype.BEST_FRIEND, PersonaArchetype.fromId("invalid"))
    }

    @Test
    fun testAllFiveThemePalettesAvailable() {
        val palettes = IshaThemePalette.entries
        assertEquals(5, palettes.size)

        val cyan = IshaThemePalette.fromId("cyber_cyan")
        val amethyst = IshaThemePalette.fromId("royal_amethyst")
        val emerald = IshaThemePalette.fromId("emerald_matrix")
        val amber = IshaThemePalette.fromId("sunset_amber")
        val crimson = IshaThemePalette.fromId("neon_crimson")

        assertEquals("Cyber Cyan", cyan.displayName)
        assertEquals("Royal Amethyst", amethyst.displayName)
        assertEquals("Emerald Matrix", emerald.displayName)
        assertEquals("Sunset Amber", amber.displayName)
        assertEquals("Neon Crimson", crimson.displayName)

        // Verify distinct colors
        val hexes = palettes.map { it.accentHex }.toSet()
        assertEquals(5, hexes.size)
    }

    @Test
    fun testLivePreviewGreetingAdaptsToAgeAndSalutation() {
        val kidsConfig = IshaPersonaConfig(
            ageGroup = UserAgeGroup.KIDS,
            salutationTitle = SalutationTitle.DOST,
            archetype = PersonaArchetype.BEST_FRIEND
        )
        val kidsPreview = kidsConfig.getPreviewGreeting("Rohan")
        assertTrue("Preview must contain Dost", kidsPreview.contains("Dost"))
        assertTrue("Preview must be playful for kids", kidsPreview.contains("kahani") || kidsPreview.contains("game"))

        val adultJarvisConfig = IshaPersonaConfig(
            ageGroup = UserAgeGroup.ADULT,
            salutationTitle = SalutationTitle.SIR_MAAM,
            archetype = PersonaArchetype.JARVIS_EXECUTIVE
        )
        val adultPreview = adultJarvisConfig.getPreviewGreeting("Ansh")
        assertTrue("Preview must contain Sir ji", adultPreview.contains("Sir ji"))
        assertTrue("Preview must be executive", adultPreview.contains("operations") || adultPreview.contains("tasks") || adultPreview.contains("Good day"))
    }
}
