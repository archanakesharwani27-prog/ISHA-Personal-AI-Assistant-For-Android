package com.aura.assistant.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistent preferences manager for Isha AI Persona, Custom Instructions & Themes.
 * Stores configuration in SharedPreferences and exposes reactive StateFlows.
 */
object IshaPersonaPreferences {

    private const val PREFS_NAME = "isha_persona_preferences"

    private const val KEY_AGE_GROUP = "age_group"
    private const val KEY_SALUTATION_TITLE = "salutation_title"
    private const val KEY_CUSTOM_TITLE = "custom_title"
    private const val KEY_ARCHETYPE = "archetype"
    private const val KEY_THEME_PALETTE = "theme_palette"
    private const val KEY_CUSTOM_ABOUT_ME = "custom_about_me"
    private const val KEY_CUSTOM_RESPONSE_STYLE = "custom_response_style"

    private var prefs: SharedPreferences? = null

    private val _personaConfigFlow = MutableStateFlow(IshaPersonaConfig())
    val personaConfigFlow: StateFlow<IshaPersonaConfig> = _personaConfigFlow.asStateFlow()

    fun init(context: Context) {
        if (prefs == null) {
            val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p

            val ageGroupId = p.getString(KEY_AGE_GROUP, UserAgeGroup.YOUNG_ADULT.id) ?: UserAgeGroup.YOUNG_ADULT.id
            val salutationId = p.getString(KEY_SALUTATION_TITLE, SalutationTitle.BOSS.id) ?: SalutationTitle.BOSS.id
            val customTitle = p.getString(KEY_CUSTOM_TITLE, "") ?: ""
            val archetypeId = p.getString(KEY_ARCHETYPE, PersonaArchetype.BEST_FRIEND.id) ?: PersonaArchetype.BEST_FRIEND.id
            val themePaletteId = p.getString(KEY_THEME_PALETTE, IshaThemePalette.CYBER_CYAN.id) ?: IshaThemePalette.CYBER_CYAN.id
            val aboutMe = p.getString(KEY_CUSTOM_ABOUT_ME, "") ?: ""
            val responseStyle = p.getString(KEY_CUSTOM_RESPONSE_STYLE, "") ?: ""

            _personaConfigFlow.value = IshaPersonaConfig(
                ageGroup = UserAgeGroup.fromId(ageGroupId),
                salutationTitle = SalutationTitle.fromId(salutationId),
                customTitle = customTitle,
                archetype = PersonaArchetype.fromId(archetypeId),
                themePaletteId = themePaletteId,
                customAboutMe = aboutMe,
                customResponseStyle = responseStyle
            )
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        init(context)
        return prefs ?: context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getPersonaConfig(context: Context): IshaPersonaConfig {
        init(context)
        return _personaConfigFlow.value
    }

    fun savePersonaConfig(context: Context, config: IshaPersonaConfig) {
        val p = getPrefs(context)
        p.edit()
            .putString(KEY_AGE_GROUP, config.ageGroup.id)
            .putString(KEY_SALUTATION_TITLE, config.salutationTitle.id)
            .putString(KEY_CUSTOM_TITLE, config.customTitle)
            .putString(KEY_ARCHETYPE, config.archetype.id)
            .putString(KEY_THEME_PALETTE, config.themePaletteId)
            .putString(KEY_CUSTOM_ABOUT_ME, config.customAboutMe)
            .putString(KEY_CUSTOM_RESPONSE_STYLE, config.customResponseStyle)
            .apply()

        _personaConfigFlow.value = config
    }

    fun setAgeGroup(context: Context, ageGroup: UserAgeGroup) {
        val updated = _personaConfigFlow.value.copy(ageGroup = ageGroup)
        savePersonaConfig(context, updated)
    }

    fun setSalutation(context: Context, title: SalutationTitle, customTitle: String = "") {
        val updated = _personaConfigFlow.value.copy(
            salutationTitle = title,
            customTitle = if (title == SalutationTitle.CUSTOM) customTitle else _personaConfigFlow.value.customTitle
        )
        savePersonaConfig(context, updated)
    }

    fun setCustomTitle(context: Context, customTitle: String) {
        val updated = _personaConfigFlow.value.copy(customTitle = customTitle)
        savePersonaConfig(context, updated)
    }

    fun setArchetype(context: Context, archetype: PersonaArchetype) {
        val updated = _personaConfigFlow.value.copy(archetype = archetype)
        savePersonaConfig(context, updated)
    }

    fun setThemePalette(context: Context, paletteId: String) {
        val updated = _personaConfigFlow.value.copy(themePaletteId = paletteId)
        savePersonaConfig(context, updated)
    }

    fun setCustomAboutMe(context: Context, aboutMe: String) {
        val updated = _personaConfigFlow.value.copy(customAboutMe = aboutMe)
        savePersonaConfig(context, updated)
    }

    fun setCustomResponseStyle(context: Context, style: String) {
        val updated = _personaConfigFlow.value.copy(customResponseStyle = style)
        savePersonaConfig(context, updated)
    }

    /**
     * Resolves the salutation title for current user (always Boss).
     */
    fun getResolvedSalutation(context: Context, fallbackUserName: String = "Ansh"): String {
        return "Boss"
    }
}
