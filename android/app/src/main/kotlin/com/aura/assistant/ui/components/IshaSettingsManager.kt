package com.aura.assistant.ui.components

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Centralized Persistent Settings Manager for ISHA Assistant.
 * All settings are stored in SharedPreferences and exposed as reactive StateFlows.
 */
object IshaSettingsManager {

    private const val PREFS_NAME = "isha_app_settings"

    private const val KEY_WAKE_WORD = "wake_word_enabled"
    private const val KEY_SHAKE = "shake_enabled"
    private const val KEY_VOICE_NAME = "voice_name"
    private const val KEY_LANGUAGE = "selected_language"
    private const val KEY_TEXT_SIZE = "text_size"
    private const val KEY_SOUND_ENABLED = "sound_enabled"
    private const val KEY_MESSAGE_SPEAK = "message_speak_enabled"
    private const val KEY_CALL_ANNOUNCEMENT = "call_announcement_enabled"

    private var prefs: SharedPreferences? = null

    val voiceList = listOf("Aoede", "Puck", "Charon", "Fenrir", "Kore")
    val languageList = listOf("Auto-detect", "English", "Hindi", "Hinglish")
    val textSizeList = listOf("Small", "Default", "Large")

    private val _settingsState = MutableStateFlow(IshaSettingsState())
    val settingsState: StateFlow<IshaSettingsState> = _settingsState.asStateFlow()

    fun init(context: Context) {
        if (prefs == null) {
            val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p

            val wake = p.getBoolean(KEY_WAKE_WORD, true)
            val shake = p.getBoolean(KEY_SHAKE, true)
            val voice = p.getString(KEY_VOICE_NAME, "Aoede") ?: "Aoede"
            val lang = p.getString(KEY_LANGUAGE, "Auto-detect") ?: "Auto-detect"
            val textSz = p.getString(KEY_TEXT_SIZE, "Default") ?: "Default"
            val sound = p.getBoolean(KEY_SOUND_ENABLED, true)
            val msgSpeak = false
            val callAnnounce = p.getBoolean(KEY_CALL_ANNOUNCEMENT, true)

            _settingsState.value = IshaSettingsState(
                isWakeWordEnabled = wake,
                isShakeEnabled = shake,
                selectedVoice = voice,
                selectedLanguage = lang,
                textSize = textSz,
                isSoundEnabled = sound,
                isMessageSpeakEnabled = false,
                isCallAnnouncementEnabled = callAnnounce
            )
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        init(context)
        return prefs ?: context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun setWakeWord(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_WAKE_WORD, enabled).apply()
        _settingsState.value = _settingsState.value.copy(isWakeWordEnabled = enabled)
    }

    fun setShake(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHAKE, enabled).apply()
        _settingsState.value = _settingsState.value.copy(isShakeEnabled = enabled)
    }

    fun setVoice(context: Context, voice: String) {
        if (voice in voiceList) {
            getPrefs(context).edit().putString(KEY_VOICE_NAME, voice).apply()
            _settingsState.value = _settingsState.value.copy(selectedVoice = voice)
        }
    }

    fun setLanguage(context: Context, language: String) {
        if (language in languageList) {
            getPrefs(context).edit().putString(KEY_LANGUAGE, language).apply()
            _settingsState.value = _settingsState.value.copy(selectedLanguage = language)
        }
    }

    fun setTextSize(context: Context, size: String) {
        if (size in textSizeList) {
            getPrefs(context).edit().putString(KEY_TEXT_SIZE, size).apply()
            _settingsState.value = _settingsState.value.copy(textSize = size)
        }
    }

    fun setSoundEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SOUND_ENABLED, enabled).apply()
        _settingsState.value = _settingsState.value.copy(isSoundEnabled = enabled)
    }

    fun setMessageSpeak(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_MESSAGE_SPEAK, enabled).apply()
        _settingsState.value = _settingsState.value.copy(isMessageSpeakEnabled = enabled)
    }

    fun setCallAnnouncement(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_CALL_ANNOUNCEMENT, enabled).apply()
        _settingsState.value = _settingsState.value.copy(isCallAnnouncementEnabled = enabled)
    }

    fun isCallAnnouncementEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_CALL_ANNOUNCEMENT, true)
    }

    fun getSavedVoice(context: Context): String {
        return getPrefs(context).getString(KEY_VOICE_NAME, "Aoede") ?: "Aoede"
    }

    fun getSavedLanguage(context: Context): String {
        return getPrefs(context).getString(KEY_LANGUAGE, "Auto-detect") ?: "Auto-detect"
    }

    fun getSavedTextSize(context: Context): String {
        return getPrefs(context).getString(KEY_TEXT_SIZE, "Default") ?: "Default"
    }
}
