package com.aura.assistant.ui.contract

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aura.assistant.WakeWordService
import com.aura.assistant.ai.AuraLiveState
import com.aura.assistant.ai.ChatStreamCallback
import com.aura.assistant.ai.GeminiChatService
import com.aura.assistant.ai.GeminiLiveClient
import com.aura.assistant.data.AuraChatRepository
import com.aura.assistant.data.ChatMessage
import com.aura.assistant.data.ChatSession
import com.aura.assistant.data.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

data class AuraChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: MessageSender = MessageSender.AURA,
    val text: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

enum class MessageSender {
    USER,
    AURA,
    TOOL_SYSTEM
}

/**
 * Main ViewModel for ChatGPT-Style AURA.
 * Orchestrates multi-session persistence, Gemini 2.0 Flash token streaming,
 * native tool execution, and Gemini Live Advanced Voice Mode.
 */
class AuraAssistantViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "AuraViewModel"
    }

    private val prefs = application.getSharedPreferences("aura_settings", Context.MODE_PRIVATE)
    private val repository = AuraChatRepository(application.applicationContext)
    private val chatService = GeminiChatService(application.applicationContext)
    private val liveClient = GeminiLiveClient(application.applicationContext)

    // ── TTS Engine ─────────────────────────────────────────────────────────────
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    // ── Sessions & Active Chat State ───────────────────────────────────────────
    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow("")
    val activeSessionId: StateFlow<String> = _activeSessionId.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    // ── Voice Mode State ───────────────────────────────────────────────────────
    private val _isVoiceModeActive = MutableStateFlow(false)
    val isVoiceModeActive: StateFlow<Boolean> = _isVoiceModeActive.asStateFlow()

    val voiceState: StateFlow<AuraLiveState> = liveClient.liveState
    val liveTranscript: StateFlow<String> = liveClient.transcriptFlow
    val audioAmplitude: StateFlow<Float> = liveClient.amplitudeFlow

    // ── Quick Tools Bottom Sheet ───────────────────────────────────────────────
    private val _isToolsSheetVisible = MutableStateFlow(false)
    val isToolsSheetVisible: StateFlow<Boolean> = _isToolsSheetVisible.asStateFlow()

    private val _isWakeWordEnabled = MutableStateFlow(prefs.getBoolean("wake_word_enabled", false))
    val isWakeWordEnabled: StateFlow<Boolean> = _isWakeWordEnabled.asStateFlow()

    private val _isShakeEnabled = MutableStateFlow(prefs.getBoolean("shake_enabled", true))
    val isShakeEnabled: StateFlow<Boolean> = _isShakeEnabled.asStateFlow()

    private val _isSettingsVisible = MutableStateFlow(false)
    val isSettingsVisible: StateFlow<Boolean> = _isSettingsVisible.asStateFlow()

    init {
        initTts()
        loadInitialSessions()

        // Sync completed Gemini Live turns into current chat session
        viewModelScope.launch {
            liveClient.turnCompletedFlow.collect { turn ->
                var currentSessionId = _activeSessionId.value
                if (currentSessionId.isBlank()) {
                    val newSession = withContext(Dispatchers.IO) { repository.createSession("Voice Chat") }
                    currentSessionId = newSession.id
                    _activeSessionId.value = currentSessionId
                }
                val newMsgs = mutableListOf<ChatMessage>()
                if (turn.userText.isNotBlank()) {
                    newMsgs.add(
                        ChatMessage(
                            sessionId = currentSessionId,
                            role = MessageRole.USER,
                            text = turn.userText
                        )
                    )
                }
                if (turn.assistantText.isNotBlank()) {
                    newMsgs.add(
                        ChatMessage(
                            sessionId = currentSessionId,
                            role = MessageRole.ASSISTANT,
                            text = turn.assistantText
                        )
                    )
                }
                if (newMsgs.isNotEmpty()) {
                    val updated = _chatMessages.value.toMutableList().apply { addAll(newMsgs) }
                    _chatMessages.value = updated
                    withContext(Dispatchers.IO) {
                        repository.saveMessages(currentSessionId, updated)
                        val session = repository.getAllSessions().find { it.id == currentSessionId }
                        if (session != null && (session.title == "New Chat" || session.title == "Voice Chat") && turn.userText.isNotBlank()) {
                            val autoTitle = if (turn.userText.length > 28) turn.userText.take(25) + "..." else turn.userText
                            repository.renameSession(currentSessionId, autoTitle)
                        }
                        _sessions.value = repository.getAllSessions()
                    }
                }
            }
        }
    }

    private fun initTts() {
        tts = TextToSpeech(getApplication()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                isTtsReady = true
            }
        }
    }

    private fun loadInitialSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            val all = repository.getAllSessions()
            _sessions.value = all
            val first = all.firstOrNull()
            if (first != null) {
                selectSession(first.id)
            } else {
                createNewChat()
            }
        }
    }

    // ── Chat Session Management ────────────────────────────────────────────────

    fun createNewChat() {
        viewModelScope.launch(Dispatchers.IO) {
            stopGeneration()
            val newSession = repository.createSession("New Chat")
            _sessions.value = repository.getAllSessions()
            _activeSessionId.value = newSession.id
            _chatMessages.value = emptyList()
        }
    }

    fun selectSession(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            stopGeneration()
            _activeSessionId.value = sessionId
            _chatMessages.value = repository.getMessages(sessionId)
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.renameSession(sessionId, newTitle)
            _sessions.value = repository.getAllSessions()
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteSession(sessionId)
            val updated = repository.getAllSessions()
            _sessions.value = updated
            if (_activeSessionId.value == sessionId) {
                val next = updated.firstOrNull()
                if (next != null) {
                    selectSession(next.id)
                } else {
                    createNewChat()
                }
            }
        }
    }

    fun clearChat() {
        val currentSessionId = _activeSessionId.value
        if (currentSessionId.isNotBlank()) {
            _chatMessages.value = emptyList()
            viewModelScope.launch(Dispatchers.IO) {
                repository.saveMessages(currentSessionId, emptyList())
            }
        }
    }

    fun clearAllChats() {
        viewModelScope.launch(Dispatchers.IO) {
            val defaultSession = repository.clearAllSessions()
            _sessions.value = listOf(defaultSession)
            _activeSessionId.value = defaultSession.id
            _chatMessages.value = emptyList()
        }
    }

    // ── Text Chat & Gemini Streaming Pipeline ──────────────────────────────────

    fun onSendTextMessage(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isBlank() || _isGenerating.value) return

        val currentSessionId = _activeSessionId.value.ifBlank {
            val newSess = repository.createSession()
            _activeSessionId.value = newSess.id
            newSess.id
        }

        // 1. Append User Message
        val userMsg = ChatMessage(
            sessionId = currentSessionId,
            role = MessageRole.USER,
            text = trimmed
        )

        // 2. Prepare streaming Assistant Message
        val assistantMsgId = UUID.randomUUID().toString()
        val assistantMsg = ChatMessage(
            id = assistantMsgId,
            sessionId = currentSessionId,
            role = MessageRole.ASSISTANT,
            text = "",
            isStreaming = true
        )

        val updatedList = _chatMessages.value.toMutableList().apply {
            add(userMsg)
            add(assistantMsg)
        }
        _chatMessages.value = updatedList
        _isGenerating.value = true

        val historySnapshot = updatedList.dropLast(1) // History prior to assistant answer

        // 3. Initiate Stream
        chatService.sendChatStream(
            history = historySnapshot,
            newPrompt = trimmed,
            callback = object : ChatStreamCallback {
                override fun onToken(chunk: String) {
                    viewModelScope.launch(Dispatchers.Main) {
                        val current = _chatMessages.value.toMutableList()
                        val idx = current.indexOfFirst { it.id == assistantMsgId }
                        if (idx != -1) {
                            val existing = current[idx]
                            current[idx] = existing.copy(text = existing.text + chunk, isStreaming = true)
                            _chatMessages.value = current
                        }
                    }
                }

                override fun onToolExecution(toolName: String, args: String, result: String) {
                    viewModelScope.launch(Dispatchers.Main) {
                        val toolMsg = ChatMessage(
                            sessionId = currentSessionId,
                            role = MessageRole.TOOL,
                            text = result,
                            toolName = toolName,
                            toolArgs = args,
                            toolResult = result
                        )
                        val current = _chatMessages.value.toMutableList()
                        val assistantIdx = current.indexOfFirst { it.id == assistantMsgId }
                        if (assistantIdx != -1) {
                            current.add(assistantIdx, toolMsg)
                        } else {
                            current.add(toolMsg)
                        }
                        _chatMessages.value = current
                    }
                }

                override fun onComplete(fullText: String) {
                    viewModelScope.launch(Dispatchers.Main) {
                        val current = _chatMessages.value.toMutableList()
                        val idx = current.indexOfFirst { it.id == assistantMsgId }
                        if (idx != -1) {
                            val existing = current[idx]
                            val finalText = if (existing.text.isNotBlank()) existing.text else fullText
                            current[idx] = existing.copy(text = finalText, isStreaming = false)
                            _chatMessages.value = current
                        }
                        _isGenerating.value = false
                        saveCurrentMessages()
                    }
                }

                override fun onError(errorMessage: String) {
                    viewModelScope.launch(Dispatchers.Main) {
                        val current = _chatMessages.value.toMutableList()
                        val idx = current.indexOfFirst { it.id == assistantMsgId }
                        if (idx != -1) {
                            val existing = current[idx]
                            val errorDisplay = if (existing.text.isNotBlank()) existing.text else "⚠️ $errorMessage"
                            current[idx] = existing.copy(text = errorDisplay, isStreaming = false)
                            _chatMessages.value = current
                        }
                        _isGenerating.value = false
                        saveCurrentMessages()
                    }
                }
            }
        )
    }

    fun stopGeneration() {
        if (_isGenerating.value) {
            chatService.cancelCurrentGeneration()
            _isGenerating.value = false
            val current = _chatMessages.value.toMutableList()
            val idx = current.indexOfLast { it.isStreaming }
            if (idx != -1) {
                current[idx] = current[idx].copy(isStreaming = false)
                _chatMessages.value = current
            }
            saveCurrentMessages()
        }
    }

    fun regenerateLastMessage() {
        val msgs = _chatMessages.value
        val lastUserMsg = msgs.lastOrNull { it.role == MessageRole.USER } ?: return
        val lastUserIdx = msgs.indexOf(lastUserMsg)
        _chatMessages.value = msgs.take(lastUserIdx)
        onSendTextMessage(lastUserMsg.text)
    }

    private fun saveCurrentMessages() {
        val currentSessionId = _activeSessionId.value
        if (currentSessionId.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                repository.saveMessages(currentSessionId, _chatMessages.value)
                _sessions.value = repository.getAllSessions()
            }
        }
    }

    // ── Voice Mode Controls ────────────────────────────────────────────────────

    fun startVoiceMode() {
        _isVoiceModeActive.value = true
        val app = getApplication<Application>()
        try {
            val pauseIntent = Intent(app, WakeWordService::class.java).apply {
                action = WakeWordService.ACTION_PAUSE
            }
            app.startService(pauseIntent)
        } catch (_: Exception) {}
        liveClient.connect()
    }

    fun stopVoiceMode() {
        _isVoiceModeActive.value = false
        liveClient.disconnect()
        val app = getApplication<Application>()
        try {
            val resumeIntent = Intent(app, WakeWordService::class.java).apply {
                action = WakeWordService.ACTION_RESUME
            }
            app.startService(resumeIntent)
        } catch (_: Exception) {}
    }

    fun onMicButtonPressed() {
        if (_isVoiceModeActive.value) {
            stopVoiceMode()
        } else {
            startVoiceMode()
        }
    }

    fun retryConnection() {
        liveClient.disconnect()
        viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            liveClient.connect()
        }
    }

    fun setVoiceMuted(muted: Boolean) {
        liveClient.setMuted(muted)
    }

    fun sendImageToLive(jpegBytes: ByteArray, prompt: String = "") {
        liveClient.sendImageChunk(jpegBytes)
        if (prompt.isNotBlank()) {
            liveClient.sendTextMessage(prompt)
        }
    }

    fun captureAndSendScreenToLive(onResult: (Boolean) -> Unit = {}) {
        val a11y = com.aura.assistant.AuraAccessibilityService.instance
        if (a11y != null) {
            a11y.takeScreenCapture { bytes: ByteArray? ->
                if (bytes != null) {
                    liveClient.sendImageChunk(bytes)
                    liveClient.sendTextMessage("Here is a screenshot of what is currently on my screen. Please analyze it and help me.")
                    onResult(true)
                } else {
                    val screenText = a11y.getScreenTextHierarchy()
                    liveClient.sendTextMessage("Here is the textual content on my current screen:\n$screenText\nPlease help me with this screen.")
                    onResult(true)
                }
            }
        } else {
            liveClient.sendTextMessage("I attempted to share my screen, but Aura's Accessibility Service is currently not active. Please remind me to enable it in Android Settings.")
            onResult(false)
        }
    }

    fun sendLivePrompt(prompt: String) {
        liveClient.sendTextMessage(prompt)
    }

    // ── Text-to-Speech (Read Aloud) ────────────────────────────────────────────

    fun speakText(text: String) {
        if (isTtsReady && text.isNotBlank()) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "AuraReadAloud")
        }
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    // ── Bottom Sheet & Settings ────────────────────────────────────────────────

    fun showToolsSheet() { _isToolsSheetVisible.value = true }
    fun hideToolsSheet() { _isToolsSheetVisible.value = false }

    fun showSettings() { _isSettingsVisible.value = true }
    fun hideSettings() { _isSettingsVisible.value = false }

    fun setWakeWordEnabled(enabled: Boolean) {
        _isWakeWordEnabled.value = enabled
        prefs.edit().putBoolean("wake_word_enabled", enabled).apply()
        val app = getApplication<Application>()
        try {
            val serviceIntent = Intent(app, WakeWordService::class.java).apply {
                action = WakeWordService.ACTION_CONFIG
                putExtra(WakeWordService.EXTRA_WAKE_ENABLED, enabled)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(serviceIntent)
            } else {
                app.startService(serviceIntent)
            }
        } catch (_: Exception) {}
        app.sendBroadcast(Intent("com.aura.assistant.WAKE_WORD_TOGGLE").apply {
            putExtra("enabled", enabled)
            setPackage(app.packageName)
        })
    }

    fun setShakeEnabled(enabled: Boolean) {
        _isShakeEnabled.value = enabled
        prefs.edit().putBoolean("shake_enabled", enabled).apply()
        val app = getApplication<Application>()
        app.sendBroadcast(Intent("com.aura.assistant.SHAKE_TOGGLE").apply {
            putExtra("enabled", enabled)
            setPackage(app.packageName)
        })
    }

    override fun onCleared() {
        super.onCleared()
        liveClient.release()
        val app = getApplication<Application>()
        try {
            val resumeIntent = Intent(app, WakeWordService::class.java).apply {
                action = WakeWordService.ACTION_RESUME
            }
            app.startService(resumeIntent)
        } catch (_: Exception) {}
        chatService.cancelCurrentGeneration()
        tts?.shutdown()
    }
}
