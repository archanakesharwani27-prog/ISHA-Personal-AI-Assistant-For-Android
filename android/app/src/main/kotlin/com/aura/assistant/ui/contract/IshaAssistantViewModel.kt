package com.aura.assistant.ui.contract

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aura.assistant.IshaDynamicIsland
import com.aura.assistant.WakeWordService
import com.aura.assistant.ai.AuraLiveState
import com.aura.assistant.ai.AuraMemoryManager
import com.aura.assistant.ai.ChatStreamCallback
import com.aura.assistant.ai.GeminiChatService
import com.aura.assistant.ai.GeminiLiveClient
import com.aura.assistant.data.AuraChatRepository
import com.aura.assistant.data.IshaChatRepository
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

    // ── Voice Language Preference ("hinglish", "hindi", "english") ────────────
    private val _voiceLanguage = MutableStateFlow(
        prefs.getString("voice_language", "hinglish") ?: "hinglish"
    )
    val voiceLanguage: StateFlow<String> = _voiceLanguage.asStateFlow()

    fun setVoiceLanguage(lang: String) {
        val normalized = when (lang.trim().lowercase()) {
            "hindi", "hi", "हिंदी" -> "hindi"
            "english", "en", "अंग्रेजी" -> "english"
            else -> "hinglish"
        }
        _voiceLanguage.value = normalized
        prefs.edit().putString("voice_language", normalized).apply()
        liveClient.setVoiceLanguage(normalized)
    }

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

    private val _isWakeWordEnabled = MutableStateFlow(prefs.getBoolean("wake_word_enabled", true))
    val isWakeWordEnabled: StateFlow<Boolean> = _isWakeWordEnabled.asStateFlow()

    private val _isShakeEnabled = MutableStateFlow(prefs.getBoolean("shake_enabled", true))
    val isShakeEnabled: StateFlow<Boolean> = _isShakeEnabled.asStateFlow()

    private val _isMessageSpeakEnabled = MutableStateFlow(false)
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
        prefs.edit().putBoolean("message_speak_enabled", false).apply()
        com.aura.assistant.auth.IshaAuthManager.init(application)
        com.aura.assistant.ui.components.IshaSettingsManager.init(application)
        com.aura.assistant.ui.components.IshaSettingsManager.setMessageSpeak(application, false)
        com.aura.assistant.ai.IshaContactMemoryManager.init(application)
        com.aura.assistant.sync.IshaDeviceRegistry.init(application)
        com.aura.assistant.sync.IshaCrossDeviceBridge.init(application)
        com.aura.assistant.sync.IshaCloudSyncBridge.init(application)
        // initTts() disabled: User strictly requested no TTS speech

        liveClient.onLanguageChanged = { lang ->
            _voiceLanguage.value = lang
            prefs.edit().putString("voice_language", lang).apply()
        }
        liveClient.setVoiceLanguage(_voiceLanguage.value)

        // Forward real-time Gemini Live states to Dynamic Island floating overlay
        viewModelScope.launch {
            liveClient.liveState.collect { state ->
                IshaDynamicIsland.updateLiveState(application, state, liveClient.amplitudeFlow.value)
            }
        }
        viewModelScope.launch {
            liveClient.amplitudeFlow.collect { amp ->
                if (_isVoiceModeActive.value) {
                    IshaDynamicIsland.updateLiveState(application, liveClient.liveState.value, amp)
                }
            }
        }

        val currentProfile = com.aura.assistant.auth.IshaAuthManager.sessionState.value
        val initialUid = if (currentProfile.isLoggedIn && currentProfile.provider != "GUEST" && currentProfile.uid.isNotBlank() && !currentProfile.uid.startsWith("guest")) {
            currentProfile.uid
        } else {
            "guest"
        }
        repository.switchUser(initialUid)
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

                val finalSession = _sessions.value.find { it.id == currentSessionId }
                if (finalSession != null) {
                    com.aura.assistant.sync.IshaCloudSyncBridge.syncSessionToCloud(application, finalSession, updated)
                }
            }
        }

        // Real-Time Cloud Sessions Synchronization across devices (Phone A <-> Phone B)
        viewModelScope.launch {
            com.aura.assistant.auth.IshaAuthManager.sessionState.collect { user ->
                val targetUid = if (user.isLoggedIn && user.provider != "GUEST" && user.uid.isNotBlank() && !user.uid.startsWith("guest")) {
                    user.uid
                } else {
                    "guest"
                }
                val sanitizedTarget = IshaChatRepository.sanitizeUserId(targetUid)
                if (repository.getCurrentUserId() != sanitizedTarget) {
                    Log.i(TAG, "Switching user partition strictly: ${repository.getCurrentUserId()} -> $sanitizedTarget")
                    com.aura.assistant.sync.IshaCloudSyncBridge.stopSessionsListener()
                    repository.switchUser(targetUid)
                    _activeSessionId.value = ""
                    _chatMessages.value = emptyList()
                    loadInitialSessions()
                }

                if (targetUid != "guest") {
                    com.aura.assistant.sync.IshaCloudSyncBridge.syncAllLocalSessionsToCloud(application, repository)
                    com.aura.assistant.sync.IshaCloudSyncBridge.startSessionsListener(application, repository) {
                        viewModelScope.launch(Dispatchers.Main) {
                            val updatedSessions = repository.getAllSessions()
                            _sessions.value = updatedSessions
                            val activeId = _activeSessionId.value
                            if (activeId.isNotBlank()) {
                                _chatMessages.value = repository.getMessages(activeId)
                            }
                        }
                    }
                } else {
                    com.aura.assistant.sync.IshaCloudSyncBridge.stopSessionsListener()
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
            val updated = repository.getAllSessions()
            _sessions.value = updated
            updated.find { it.id == sessionId }?.let { sess ->
                val msgs = repository.getMessages(sessionId)
                com.aura.assistant.sync.IshaCloudSyncBridge.syncSessionToCloud(getApplication(), sess, msgs)
            }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteSession(sessionId)
            com.aura.assistant.sync.IshaCloudSyncBridge.deleteSessionFromCloud(getApplication(), sessionId)
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
            val oldSessions = repository.getAllSessions()
            for (s in oldSessions) {
                com.aura.assistant.sync.IshaCloudSyncBridge.deleteSessionFromCloud(getApplication(), s.id)
            }
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
                onSendTextMessage(next.first, alreadyInUi = true)
            }
        }
    }

    private fun triggerLightHaptic() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getApplication<Application>().getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getApplication<Application>().getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(18, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(18)
            }
        } catch (_: Exception) {}
    }

    private fun getFriendlyToolLabel(toolName: String): String {
        return when (toolName.lowercase()) {
            "toggle_flashlight" -> "Flashlight toggle kar rahi hoon..."
            "get_battery_status" -> "Battery status check kar rahi hoon..."
            "make_call" -> "Phone call connect kiya ja raha hai..."
            "send_whatsapp", "chat_on_whatsapp" -> "WhatsApp message bheja ja raha hai..."
            "type_message", "type_text" -> "Input box mein type kar rahi hoon..."
            "open_app" -> "App open kiya ja raha hai..."
            "take_screenshot" -> "Screenshot capture kiya ja raha hai..."
            "show_recent_media" -> "Recent photo open kar rahi hoon..."
            "set_volume" -> "Volume adjust ho raha hai..."
            "set_brightness" -> "Brightness adjust ho rahi hai..."
            "create_alarm", "set_alarm" -> "Alarm set kiya ja raha hai..."
            "set_timer" -> "Timer lagaya ja raha hai..."
            "toggle_wifi" -> "Wi-Fi toggle ho raha hai..."
            "toggle_bluetooth" -> "Bluetooth toggle ho raha hai..."
            "toggle_hotspot" -> "Personal Hotspot toggle ho raha hai..."
            "get_wifi_networks" -> "Wi-Fi network check ho raha hai..."
            "get_current_time" -> "Time check kiya ja raha hai..."
            "get_storage_space" -> "Phone storage check ho rahi hai..."
            "check_device_health" -> "Device health diagnose ho rahi hai..."
            "get_location" -> "Current location find kar rahi hoon..."
            "read_notifications" -> "Notifications check kiye ja rahe hain..."
            "clear_notifications" -> "Notifications clear ho rahe hain..."
            "dispatch_remote_command" -> "Remote device par command dispatch ho raha hai..."
            "autonomous_ui_action" -> "Screen action execute kiya ja raha hai..."
            "find_and_tap" -> "Button find karke tap kar rahi hoon..."
            "read_screen_text" -> "Screen content padh rahi hoon..."
            else -> "Action execute ho raha hai..."
        }
    }

    fun onSendTextMessage(prompt: String, alreadyInUi: Boolean = false) {
        val trimmed = prompt.trim()
        val attached = _selectedAttachment.value
        if (trimmed.isBlank() && attached == null) return
        triggerLightHaptic()

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

        if (_isGenerating.value) {
            Log.i(TAG, "⚡ [QUEUE] Assistant busy generating, queuing command: \"$trimmed\"")
            _selectedAttachment.value = null
            // Display immediately on screen so user sees their message was received!
            val queuedUserMsg = ChatMessage(
                sessionId = currentSessionId,
                role = MessageRole.USER,
                text = trimmed.ifBlank { "Attached image" },
                imageUri = attached?.uri?.toString()
            )
            _chatMessages.value = _chatMessages.value + queuedUserMsg
            synchronized(pendingCommandQueue) {
                pendingCommandQueue.add(Pair(trimmed, attached))
            }
            return
        }

        _selectedAttachment.value = null // consume attachment

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

        // ⚡ Tier 0: Zero-Latency On-Device Reflex Engine (<20ms hardware & system actions)
        if (attached == null && trimmed.isNotBlank()) {
            val reflex = com.aura.assistant.brain.OfflineReflexEngine.tryExecuteSync(getApplication(), trimmed)
            if (reflex != null) {
                if (!alreadyInUi) {
                    val currentWithUser = _chatMessages.value.toMutableList().apply { add(userMsg) }
                    _chatMessages.value = currentWithUser
                }
                executeOfflineReflexTurn(currentSessionId, reflex)
                return
            }
        }

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
        val previousHistory = if (alreadyInUi) _chatMessages.value.dropLast(1) else _chatMessages.value

        val updatedList = _chatMessages.value.toMutableList().apply {
            if (!alreadyInUi) {
                add(userMsg)
            }
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

                override fun onToolStart(toolName: String, args: String) {
                    viewModelScope.launch(Dispatchers.Main) {
                        val toolMsg = ChatMessage(
                            sessionId = currentSessionId,
                            role = MessageRole.TOOL,
                            text = getFriendlyToolLabel(toolName),
                            toolName = toolName,
                            toolArgs = args,
                            toolResult = null,
                            isStreaming = true
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

                override fun onToolExecution(toolName: String, args: String, result: String) {
                    viewModelScope.launch(Dispatchers.Main) {
                        val current = _chatMessages.value.toMutableList()
                        val pendingToolIdx = current.indexOfLast { it.role == MessageRole.TOOL && it.toolName == toolName && it.isStreaming }
                        if (pendingToolIdx != -1) {
                            val existing = current[pendingToolIdx]
                            current[pendingToolIdx] = existing.copy(
                                text = result,
                                toolResult = result,
                                isStreaming = false
                            )
                        } else {
                            val toolMsg = ChatMessage(
                                sessionId = currentSessionId,
                                role = MessageRole.TOOL,
                                text = result,
                                toolName = toolName,
                                toolArgs = args,
                                toolResult = result,
                                isStreaming = false
                            )
                            val assistantIdx = current.indexOfFirst { it.id == assistantMsgId }
                            if (assistantIdx != -1) {
                                current.add(assistantIdx, toolMsg)
                            } else {
                                current.add(toolMsg)
                            }
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
            val iterator = current.listIterator()
            while (iterator.hasNext()) {
                val msg = iterator.next()
                if (msg.isStreaming) {
                    if (msg.role == MessageRole.ASSISTANT && msg.text.isBlank()) {
                        iterator.remove()
                    } else {
                        iterator.set(msg.copy(isStreaming = false))
                    }
                }
            }
            _chatMessages.value = current
            saveCurrentMessages()
            processNextPendingCommand()
        }
    }

    fun setCallAnnouncementEnabled(enabled: Boolean) {
        com.aura.assistant.ui.components.IshaSettingsManager.setCallAnnouncement(getApplication(), enabled)
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
            val msgs = _chatMessages.value
            viewModelScope.launch(Dispatchers.IO) {
                repository.saveMessages(currentSessionId, msgs)
                val all = repository.getAllSessions()
                _sessions.value = all
                all.find { it.id == currentSessionId }?.let { sess ->
                    com.aura.assistant.sync.IshaCloudSyncBridge.syncSessionToCloud(getApplication(), sess, msgs)
                }
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
        // Fast startup: 100ms is sufficient for AudioRecord mic release and binder IPC.
        viewModelScope.launch {
            kotlinx.coroutines.delay(100)
            liveClient.connect()
        }
    }

    fun stopVoiceMode() {
        stopLiveScreenShare()
        _isVoiceModeActive.value = false
        liveClient.disconnect()
        IshaDynamicIsland.hide()
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
                        // Route through ScreenFrameSampler: drops identical frames, throttles to ≤2fps
                        liveClient.sendScreenJpeg(bytes)
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
    // Disabled: User strictly requested no TTS speech
    fun speakText(text: String) {
        tts?.stop()
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
            app.getSharedPreferences("isha_app_settings", Context.MODE_PRIVATE)
                .edit().putBoolean("wake_word_enabled", enabled).apply()
        } catch (_: Exception) {}
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
        val targetUid = if (userProfile.isLoggedIn && userProfile.provider != "GUEST" && userProfile.uid.isNotBlank() && !userProfile.uid.startsWith("guest")) {
            userProfile.uid
        } else {
            "guest"
        }
        val sanitizedTarget = IshaChatRepository.sanitizeUserId(targetUid)
        if (repository.getCurrentUserId() != sanitizedTarget) {
            Log.i(TAG, "Switching user partition strictly in ViewModel: ${repository.getCurrentUserId()} -> $sanitizedTarget")
            com.aura.assistant.sync.IshaCloudSyncBridge.stopSessionsListener()
            repository.switchUser(targetUid)
            _activeSessionId.value = ""
            _chatMessages.value = emptyList()
            loadInitialSessions()
            if (targetUid != "guest") {
                com.aura.assistant.sync.IshaCloudSyncBridge.syncAllLocalSessionsToCloud(getApplication(), repository)
                com.aura.assistant.sync.IshaCloudSyncBridge.startSessionsListener(getApplication(), repository) {
                    viewModelScope.launch(Dispatchers.Main) {
                        val updatedSessions = repository.getAllSessions()
                        _sessions.value = updatedSessions
                        val activeId = _activeSessionId.value
                        if (activeId.isNotBlank()) {
                            _chatMessages.value = repository.getMessages(activeId)
                        }
                    }
                }
            }
        }
    }

    // ── Offline Reflex & Speech Execution ──────────────────────────────────────

    private fun executeOfflineReflexTurn(sessionId: String, reflex: com.aura.assistant.brain.OfflineReflexResult) {
        val toolMsg = ChatMessage(
            sessionId = sessionId,
            role = MessageRole.TOOL,
            text = getFriendlyToolLabel(reflex.toolName),
            toolName = reflex.toolName,
            toolResult = if (reflex.success) "success" else "failed",
            isStreaming = false
        )
        val assistantMsg = ChatMessage(
            sessionId = sessionId,
            role = MessageRole.ASSISTANT,
            text = reflex.naturalResponse,
            isStreaming = false
        )
        val updated = _chatMessages.value.toMutableList().apply {
            add(toolMsg)
            add(assistantMsg)
        }
        _chatMessages.value = updated
        repository.saveMessages(sessionId, updated)
        _sessions.value = repository.getAllSessions()

        // Automatic TTS voice playback disabled per user requirement: "tts nhi chahiye mujhe tumse"
        // if (_isMessageSpeakEnabled.value) {
        //     speakResponse(reflex.naturalResponse)
        // }
        triggerLightHaptic()
    }

    fun speakResponse(text: String) {
        // Explicitly disabled: User strictly requested no TTS voice playback
        return
    }

    // ── Security Governance (ConfirmationEngine) ───────────────────────────────

    val pendingConfirmation = com.aura.assistant.security.ConfirmationEngine.pending

    fun confirmPendingAction() {
        com.aura.assistant.security.ConfirmationEngine.confirm()
    }

    fun cancelPendingAction() {
        com.aura.assistant.security.ConfirmationEngine.cancel()
    }

    // ── Routine Engine ─────────────────────────────────────────────────────────

    fun evaluateActiveRoutines(): List<com.aura.assistant.autonomy.RoutineEngine.RoutineType> {
        return com.aura.assistant.autonomy.RoutineEngine.evaluateRoutines(getApplication())
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

