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
import com.aura.assistant.ai.AuraMemoryManager
import com.aura.assistant.ai.ChatStreamCallback
import com.aura.assistant.ai.GeminiChatService
import com.aura.assistant.ai.GeminiLiveClient
import com.aura.assistant.data.AuraChatRepository
import com.aura.assistant.data.ChatMessage
import com.aura.assistant.data.ChatSession
import com.aura.assistant.data.MessageRole
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID

data class AttachedMedia(
    val uri: Uri,
    val bytes: ByteArray,
    val name: String,
    val mimeType: String = "image/jpeg",
    val isImage: Boolean = true
)

data class IshaChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: MessageSender = MessageSender.ISHA,
    val text: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

typealias AuraChatMessage = IshaChatMessage

enum class MessageSender {
    USER,
    ISHA,
    AURA,
    TOOL_SYSTEM
}

/**
 * Main ViewModel for ISHA.
 * Orchestrates multi-session persistence, Gemini 2.0 Flash token streaming,
 * native tool execution, and Gemini Live Advanced Voice Mode.
 */
class IshaAssistantViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "IshaViewModel"
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

    // ── Attached Media State ──────────────────────────────────────────────────
    private val _selectedAttachment = MutableStateFlow<AttachedMedia?>(null)
    val selectedAttachment: StateFlow<AttachedMedia?> = _selectedAttachment.asStateFlow()

    // ── Live Screen Share State ────────────────────────────────────────────────
    private val _isLiveScreenSharing = MutableStateFlow(false)
    val isLiveScreenSharing: StateFlow<Boolean> = _isLiveScreenSharing.asStateFlow()
    private var screenShareJob: Job? = null

    // ── Quick Tools Bottom Sheet ───────────────────────────────────────────────
    private val _isToolsSheetVisible = MutableStateFlow(false)
    val isToolsSheetVisible: StateFlow<Boolean> = _isToolsSheetVisible.asStateFlow()

    private val _isWakeWordEnabled = MutableStateFlow(prefs.getBoolean("wake_word_enabled", false))
    val isWakeWordEnabled: StateFlow<Boolean> = _isWakeWordEnabled.asStateFlow()

    private val _isShakeEnabled = MutableStateFlow(prefs.getBoolean("shake_enabled", true))
    val isShakeEnabled: StateFlow<Boolean> = _isShakeEnabled.asStateFlow()

    private val _isMessageSpeakEnabled = MutableStateFlow(prefs.getBoolean("message_speak_enabled", true))
    val isMessageSpeakEnabled: StateFlow<Boolean> = _isMessageSpeakEnabled.asStateFlow()

    private val _isSettingsVisible = MutableStateFlow(false)
    val isSettingsVisible: StateFlow<Boolean> = _isSettingsVisible.asStateFlow()

    private val _isApiKeyScreenVisible = MutableStateFlow(false)
    val isApiKeyScreenVisible: StateFlow<Boolean> = _isApiKeyScreenVisible.asStateFlow()

    // ── Reactive Settings State ───────────────────────────────────────────────
    val settingsState = com.aura.assistant.ui.components.IshaSettingsManager.settingsState
    val textSize = MutableStateFlow(com.aura.assistant.ui.components.IshaSettingsManager.getSavedTextSize(application))
    val selectedVoice = MutableStateFlow(com.aura.assistant.ui.components.IshaSettingsManager.getSavedVoice(application))
    val selectedLanguage = MutableStateFlow(com.aura.assistant.ui.components.IshaSettingsManager.getSavedLanguage(application))
    val isSoundEnabled = MutableStateFlow(com.aura.assistant.ui.components.IshaSettingsManager.settingsState.value.isSoundEnabled)

    init {
        com.aura.assistant.ui.components.IshaSettingsManager.init(application)
        com.aura.assistant.ai.IshaContactMemoryManager.init(application)
        com.aura.assistant.sync.IshaDeviceRegistry.init(application)
        com.aura.assistant.sync.IshaCrossDeviceBridge.init(application)
        com.aura.assistant.sync.IshaCloudSyncBridge.init(application)
        initTts()
        loadInitialSessions()

        // Sync completed Gemini Live turns into current chat session
        viewModelScope.launch {
            liveClient.turnCompletedFlow.collect { turn ->
                val userTextClean = turn.userText.trim()
                val asstTextClean = turn.assistantText.trim()
                if (userTextClean.isBlank() && asstTextClean.isBlank()) return@collect

                val currentSessionId = if (_activeSessionId.value.isNotBlank()) {
                    _activeSessionId.value
                } else {
                    val promptTitle = if (userTextClean.isNotBlank() && userTextClean != "Spoken query") userTextClean else "Voice Conversation"
                    val autoTitle = if (promptTitle.length > 28) promptTitle.take(25) + "..." else promptTitle
                    val newSess = repository.createSession(autoTitle)
                    _activeSessionId.value = newSess.id
                    _sessions.value = repository.getAllSessions()
                    newSess.id
                }

                val finalUserMsgText = if (userTextClean == "Spoken query" || userTextClean.isBlank()) "Voice input" else userTextClean
                val newMsgs = mutableListOf<ChatMessage>()
                newMsgs.add(
                    ChatMessage(
                        sessionId = currentSessionId,
                        role = MessageRole.USER,
                        text = finalUserMsgText
                    )
                )
                if (asstTextClean.isNotBlank()) {
                    newMsgs.add(
                        ChatMessage(
                            sessionId = currentSessionId,
                            role = MessageRole.ASSISTANT,
                            text = asstTextClean
                        )
                    )
                }

                val updated = _chatMessages.value.toMutableList().apply { addAll(newMsgs) }
                _chatMessages.value = updated
                repository.saveMessages(currentSessionId, updated)
                _sessions.value = repository.getAllSessions()

                // Auto-title if session title is generic
                val session = _sessions.value.find { it.id == currentSessionId }
                if (session != null && (session.title == "New Chat" || session.title == "Voice Conversation") && userTextClean.isNotBlank() && userTextClean != "Spoken query") {
                    val autoTitle = if (userTextClean.length > 28) userTextClean.take(25) + "..." else userTextClean
                    repository.renameSession(currentSessionId, autoTitle)
                    _sessions.value = repository.getAllSessions()
                }
            }
        }
    }

    private fun initTts() {
        tts = TextToSpeech(getApplication()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("hi", "IN")
                tts?.setSpeechRate(0.95f)
                isTtsReady = true
            }
        }
    }

    private fun loadInitialSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            val all = repository.getAllSessions()
            // Clean up any empty ghost sessions (sessions with 0 messages)
            for (s in all) {
                if (repository.getMessages(s.id).isEmpty()) {
                    repository.deleteSession(s.id)
                }
            }
            val remaining = repository.getAllSessions()
            _sessions.value = remaining
            // Fresh screen on cold launch (ChatGPT / Gemini style): clean screen, no auto-load of old chat
            _activeSessionId.value = ""
            _chatMessages.value = emptyList()
        }
    }

    // ── Chat Session Management ────────────────────────────────────────────────

    fun createNewChat() {
        synchronized(pendingCommandQueue) { pendingCommandQueue.clear() }
        viewModelScope.launch(Dispatchers.IO) {
            stopGeneration()
            // Fresh screen for new chat: lazy creation, do not insert into SQLite until first prompt sent
            _activeSessionId.value = ""
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

    // ── Attachment Handling ───────────────────────────────────────────────────

    fun setAttachment(media: AttachedMedia?) {
        _selectedAttachment.value = media
    }

    fun clearAttachment() {
        _selectedAttachment.value = null
    }

    fun attachFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cr = getApplication<Application>().contentResolver
                val mimeType = cr.getType(uri) ?: "image/jpeg"
                val name = getFileNameFromUri(cr, uri) ?: "attachment.jpg"
                val bytes = cr.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) {
                    _selectedAttachment.value = AttachedMedia(
                        uri = uri,
                        bytes = bytes,
                        name = name,
                        mimeType = mimeType,
                        isImage = mimeType.startsWith("image/")
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach from URI: $uri", e)
            }
        }
    }

    fun attachFromBitmap(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val file = File(context.cacheDir, "camera_attachment_${System.currentTimeMillis()}.jpg")
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                val bytes = stream.toByteArray()
                file.writeBytes(bytes)
                val uri = Uri.fromFile(file)
                _selectedAttachment.value = AttachedMedia(
                    uri = uri,
                    bytes = bytes,
                    name = "Photo_${System.currentTimeMillis() % 10000}.jpg",
                    mimeType = "image/jpeg",
                    isImage = true
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach from Bitmap", e)
            }
        }
    }

    private fun getFileNameFromUri(cr: ContentResolver, uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                cr.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) return cursor.getString(nameIndex)
                    }
                }
            } catch (_: Exception) {}
        }
        return uri.lastPathSegment
    }

    // ── Text Chat & Gemini Streaming Pipeline ──────────────────────────────────

    private val pendingCommandQueue = mutableListOf<Pair<String, AttachedMedia?>>()

    private fun processNextPendingCommand() {
        if (_isGenerating.value) return
        val next = synchronized(pendingCommandQueue) {
            if (pendingCommandQueue.isNotEmpty()) pendingCommandQueue.removeAt(0) else null
        }
        if (next != null) {
            viewModelScope.launch(Dispatchers.Main) {
                delay(350)
                _selectedAttachment.value = next.second
                onSendTextMessage(next.first)
            }
        }
    }

    fun onSendTextMessage(prompt: String) {
        val trimmed = prompt.trim()
        val attached = _selectedAttachment.value
        if (trimmed.isBlank() && attached == null) return
        if (_isGenerating.value) {
            Log.i(TAG, "⚡ [QUEUE] Assistant busy generating, queuing command: \"$trimmed\"")
            _selectedAttachment.value = null
            synchronized(pendingCommandQueue) {
                pendingCommandQueue.add(Pair(trimmed, attached))
            }
            return
        }

        _selectedAttachment.value = null // consume attachment

        val currentSessionId = if (_activeSessionId.value.isNotBlank()) {
            _activeSessionId.value
        } else {
            val autoTitle = if (trimmed.isNotBlank()) {
                if (trimmed.length > 28) trimmed.take(25) + "..." else trimmed
            } else "New Chat"
            val newSess = repository.createSession(autoTitle)
            _activeSessionId.value = newSess.id
            _sessions.value = repository.getAllSessions()
            newSess.id
        }

        var promptToSend = if (trimmed.isNotBlank()) trimmed else "Describe this attached image and help me understand it."

        // Implicit memory auto-extraction: continuously learn user facts from chat
        if (trimmed.isNotBlank()) {
            AuraMemoryManager.extractAndSaveImplicitFacts(getApplication<Application>(), trimmed)
        }

        // Cross-session context search if user asks about past conversations or memory
        val lowerPrompt = trimmed.lowercase()
        if (lowerPrompt.contains("pichhli") || lowerPrompt.contains("pehle") || lowerPrompt.contains("kal") || 
            lowerPrompt.contains("bataya tha") || lowerPrompt.contains("past") || lowerPrompt.contains("yaad hai")) {
            val pastSnippets = repository.searchPastConversations(trimmed)
            if (pastSnippets.isNotEmpty()) {
                val pastContext = pastSnippets.joinToString("\n")
                promptToSend = "$promptToSend\n\n[Relevant Past Conversation Context]:\n$pastContext"
            }
        }

        // If user asks about the screen and hasn't manually attached an image, inject active screen context if accessibility is available
        if (attached == null && (lowerPrompt.contains("screen") || lowerPrompt.contains("ye kya chal raha") || lowerPrompt.contains("dekh kar") || lowerPrompt.contains("dekh kr"))) {
            val a11y = com.aura.assistant.IshaAccessibilityService.instance
            if (a11y != null) {
                val screenSummary = a11y.getScreenTextHierarchy()
                if (screenSummary.isNotBlank() && !screenSummary.startsWith("Screen is empty")) {
                    promptToSend = "$promptToSend\n\n[Active Screen Context]:\n$screenSummary"
                }
            }
        }

        // 1. Append User Message
        val userMsg = ChatMessage(
            sessionId = currentSessionId,
            role = MessageRole.USER,
            text = trimmed.ifBlank { "Attached image" },
            imageUri = attached?.uri?.toString()
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

        // Preserve full conversation history including Tool execution outputs for maximum contextual continuity
        val previousHistory = _chatMessages.value

        val updatedList = _chatMessages.value.toMutableList().apply {
            add(userMsg)
            add(assistantMsg)
        }
        _chatMessages.value = updatedList
        _isGenerating.value = true

        // 3. Initiate Stream with history prior to current prompt
        chatService.sendChatStream(
            history = previousHistory,
            newPrompt = promptToSend,
            imageBytes = attached?.bytes,
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
                        processNextPendingCommand()
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
                        processNextPendingCommand()
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
            processNextPendingCommand()
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
        // CRITICAL FIX: ACTION_PAUSE is dispatched asynchronously via startService().
        // We must wait for WakeWordService to fully release the AudioRecord mic resource
        // before GeminiLiveClient tries to open it. 500ms is enough for the Binder IPC
        // + OpenWakeWord engine.release() to complete on all Android OEMs.
        viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            liveClient.connect()
        }
    }

    fun stopVoiceMode() {
        stopLiveScreenShare()
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

    // ── Gemini Live True Screen Share (Continuous Real-time View) ───────────────

    fun startLiveScreenShare() {
        if (_isLiveScreenSharing.value) return
        _isLiveScreenSharing.value = true

        // Ensure voice mode / Live connection is active
        if (!_isVoiceModeActive.value) {
            startVoiceMode()
        }

        screenShareJob?.cancel()
        screenShareJob = viewModelScope.launch(Dispatchers.IO) {
            // Initial briefing to Gemini Live
            liveClient.sendTextMessage("Boss has started live screen sharing. You are now seeing their device screen in real-time. Greet Boss briefly, tell them what you see on screen, and let them know you are watching and ready to help.")

            while (isActive && _isLiveScreenSharing.value) {
                val a11y = com.aura.assistant.IshaAccessibilityService.instance
                if (a11y != null) {
                    val frameDeferred = CompletableDeferred<ByteArray?>()
                    a11y.takeOptimizedScreenCapture(maxDim = 1024, quality = 75) { bytes: ByteArray? ->
                        frameDeferred.complete(bytes)
                    }
                    val bytes = withTimeoutOrNull(2500L) { frameDeferred.await() }
                    if (bytes != null && bytes.isNotEmpty() && _isLiveScreenSharing.value) {
                        liveClient.sendImageChunk(bytes)
                    }
                }
                delay(1600L) // Continuous live stream at ~1.6s interval matching Gemini Live
            }
        }
    }

    fun stopLiveScreenShare() {
        if (!_isLiveScreenSharing.value) return
        _isLiveScreenSharing.value = false
        screenShareJob?.cancel()
        screenShareJob = null
        try {
            com.aura.assistant.AuraDynamicIsland.hide()
        } catch (_: Exception) {}
        liveClient.sendTextMessage("Boss has stopped screen sharing.")
    }

    fun toggleLiveScreenShare() {
        if (_isLiveScreenSharing.value) {
            stopLiveScreenShare()
        } else {
            startLiveScreenShare()
        }
    }

    fun captureAndSendScreenToLive(onResult: (Boolean) -> Unit = {}) {
        startLiveScreenShare()
        onResult(true)
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

    fun showApiKeyScreen() { _isApiKeyScreenVisible.value = true }
    fun hideApiKeyScreen() { _isApiKeyScreenVisible.value = false }

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
        try {
            val serviceIntent = Intent(app, WakeWordService::class.java).apply {
                action = WakeWordService.ACTION_CONFIG
                putExtra(WakeWordService.EXTRA_SHAKE_ENABLED, enabled)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(serviceIntent)
            } else {
                app.startService(serviceIntent)
            }
        } catch (_: Exception) {}
        app.sendBroadcast(Intent("com.aura.assistant.SHAKE_TOGGLE").apply {
            putExtra("enabled", enabled)
            setPackage(app.packageName)
        })
    }

    fun setMessageSpeakEnabled(enabled: Boolean) {
        _isMessageSpeakEnabled.value = enabled
        com.aura.assistant.ui.components.IshaSettingsManager.setMessageSpeak(getApplication(), enabled)
    }

    fun setTextSize(size: String) {
        com.aura.assistant.ui.components.IshaSettingsManager.setTextSize(getApplication(), size)
        textSize.value = size
    }

    fun setSelectedVoice(voice: String) {
        com.aura.assistant.ui.components.IshaSettingsManager.setVoice(getApplication(), voice)
        selectedVoice.value = voice
        liveClient.setActiveVoice(voice)
    }

    fun setSelectedLanguage(lang: String) {
        com.aura.assistant.ui.components.IshaSettingsManager.setLanguage(getApplication(), lang)
        selectedLanguage.value = lang
        try {
            val loc = when (lang) {
                "Hindi" -> Locale("hi", "IN")
                "English" -> Locale.US
                else -> Locale("en", "IN")
            }
            tts?.language = loc
        } catch (_: Exception) {}
    }

    fun setSoundEnabled(enabled: Boolean) {
        com.aura.assistant.ui.components.IshaSettingsManager.setSoundEnabled(getApplication(), enabled)
        isSoundEnabled.value = enabled
    }

    /**
     * Switched active user partition: Clears previous user's chat memory and loads target user's private sessions!
     */
    fun switchUser(userProfile: com.aura.assistant.auth.UserProfile) {
        val uid = if (userProfile.isLoggedIn) userProfile.uid.ifBlank { userProfile.email } else "guest"
        val prevUid = repository.getCurrentUserId()
        if (uid != prevUid) {
            Log.i(TAG, "Switching user partition in ViewModel: $prevUid -> $uid")
            repository.switchUser(uid)
            val userSessions = repository.getAllSessions()
            _sessions.value = userSessions
            val target = userSessions.firstOrNull() ?: repository.createSession("New Chat")
            _activeSessionId.value = target.id
            _chatMessages.value = repository.getMessages(target.id)
        }
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

/** Backward compatibility alias for AuraAssistantViewModel */
typealias AuraAssistantViewModel = IshaAssistantViewModel

