package com.aura.assistant.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.aura.assistant.ui.components.*
import com.aura.assistant.ui.contract.IshaAssistantViewModel
import com.aura.assistant.ui.contract.AuraAssistantViewModel
import com.aura.assistant.ui.theme.ChatGptBackground
import com.aura.assistant.auth.UserProfile
import kotlinx.coroutines.launch

/**
 * Main Screen for ISHA Assistant.
 * Pure black clean aesthetic.
 */
@Composable
fun IshaMainScreen(
    viewModel: IshaAssistantViewModel,
    userProfile: UserProfile = UserProfile(),
    onLogout: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    val sessions by viewModel.sessions.collectAsState()
    val activeSessionId by viewModel.activeSessionId.collectAsState()
    val chatMessages by viewModel.chatMessages.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()

    val isVoiceModeActive by viewModel.isVoiceModeActive.collectAsState()
    val liveState by viewModel.voiceState.collectAsState()
    val amplitude by viewModel.audioAmplitude.collectAsState()
    val liveTranscript by viewModel.liveTranscript.collectAsState()
    val isLiveScreenSharing by viewModel.isLiveScreenSharing.collectAsState()

    val selectedAttachment by viewModel.selectedAttachment.collectAsState()

    val isToolsSheetVisible by viewModel.isToolsSheetVisible.collectAsState()
    val isSettingsVisible by viewModel.isSettingsVisible.collectAsState()
    val isWakeWordEnabled by viewModel.isWakeWordEnabled.collectAsState()
    val isShakeEnabled by viewModel.isShakeEnabled.collectAsState()
    val isMessageSpeakEnabled by viewModel.isMessageSpeakEnabled.collectAsState()
    val textSize by viewModel.textSize.collectAsState()
    val selectedVoice by viewModel.selectedVoice.collectAsState()
    val selectedLanguage by viewModel.selectedLanguage.collectAsState()
    val voiceLanguage by viewModel.voiceLanguage.collectAsState()
    val isSoundEnabled by viewModel.isSoundEnabled.collectAsState()
    val isApiKeyScreenVisible by viewModel.isApiKeyScreenVisible.collectAsState()
    val settingsState by viewModel.settingsState.collectAsState()
    var isDiagnosticsVisible by remember { mutableStateOf(false) }

    // Activity Launchers for Attachments
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.attachFromUri(uri)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            viewModel.attachFromBitmap(bitmap)
        }
    }

    val documentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.attachFromUri(uri)
        }
    }

    val listState = rememberLazyListState()
    var editingPrompt by remember { mutableStateOf("") }

    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)

    // Auto-scroll on new messages or when keyboard opens
    LaunchedEffect(chatMessages.size, chatMessages.lastOrNull()?.text?.length, imeBottom) {
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                drawerContentColor = androidx.compose.ui.graphics.Color.White
            ) {
                IshaDrawer(
                    sessions = sessions,
                    activeSessionId = activeSessionId,
                    onSelectSession = { id ->
                        viewModel.selectSession(id)
                        coroutineScope.launch { drawerState.close() }
                    },
                    onNewChat = {
                        viewModel.createNewChat()
                        coroutineScope.launch { drawerState.close() }
                    },
                    onRenameSession = { id, title -> viewModel.renameSession(id, title) },
                    onDeleteSession = { id ->
                        viewModel.deleteSession(id)
                    },
                    onClearAllChats = {
                        viewModel.clearAllChats()
                        coroutineScope.launch { drawerState.close() }
                    },
                    onOpenSettings = {
                        coroutineScope.launch { drawerState.close() }
                        viewModel.showSettings()
                    },
                    onOpenApiKeyEntry = {
                        coroutineScope.launch { drawerState.close() }
                        viewModel.showApiKeyScreen()
                    },
                    userProfile = userProfile,
                    onLogout = {
                        coroutineScope.launch { drawerState.close() }
                        onLogout()
                    }
                )
            }
        }
    ) {
        Box(modifier = modifier.fillMaxSize()) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = ChatGptBackground,
                topBar = {
                    IshaTopBar(
                        onOpenDrawer = {
                            coroutineScope.launch { drawerState.open() }
                        },
                        onNewChat = {
                            viewModel.createNewChat()
                        }
                    )
                },
                bottomBar = {
                    IshaBottomBar(
                        onSendMessage = { query -> viewModel.onSendTextMessage(query) },
                        onVoiceClick = { viewModel.startVoiceMode() },
                        onStopGenerating = { viewModel.stopGeneration() },
                        onPlusClick = { viewModel.showToolsSheet() },
                        isGenerating = isGenerating,
                        attachedMedia = selectedAttachment,
                        onClearAttachment = { viewModel.clearAttachment() },
                        initialText = editingPrompt,
                        onTextConsumed = { editingPrompt = "" }
                    )
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .background(ChatGptBackground)
                ) {
                    if (chatMessages.isEmpty()) {
                        IshaEmptyState(
                            onPromptClick = { prompt -> viewModel.onSendTextMessage(prompt) }
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp),
                            contentPadding = PaddingValues(vertical = 12.dp)
                        ) {
                            items(chatMessages, key = { it.id }) { message ->
                                ChatMessageItem(
                                    message = message,
                                    onSpeak = { text -> viewModel.speakText(text) },
                                    onRegenerate = { viewModel.regenerateLastMessage() },
                                    onEditPrompt = { prompt -> editingPrompt = prompt },
                                    textSize = textSize
                                )
                            }
                        }
                    }
                }
            }

            // ── Full-Screen Voice Mode Overlay ────────────────────────────────
            AnimatedVisibility(
                visible = isVoiceModeActive,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 })
            ) {
                IshaVoiceScreen(
                    liveState = liveState,
                    amplitude = amplitude,
                    liveTranscript = liveTranscript,
                    onCloseVoiceMode = { viewModel.stopVoiceMode() },
                    onToggleMute = { muted -> viewModel.setVoiceMuted(muted) },
                    onStartListening = { viewModel.startVoiceMode() },
                    onSendCameraImage = { bytes ->
                        viewModel.sendImageToLive(bytes, "Analyze what is in this camera frame")
                    },
                    onSendScreenCapture = {
                        viewModel.startLiveScreenShare()
                    },
                    isScreenSharing = isLiveScreenSharing,
                    onStopScreenSharing = {
                        viewModel.stopLiveScreenShare()
                    },
                    userName = "Ansh",
                    selectedLanguage = voiceLanguage,
                    onSelectLanguage = { viewModel.setVoiceLanguage(it) }
                )
            }

            // ── Modern Attachment & Quick Tools Bottom Sheet ──────────────────
            if (isToolsSheetVisible) {
                IshaAttachmentBottomSheet(
                    onTakePhoto = {
                        try { cameraLauncher.launch(null) } catch (_: Exception) {}
                    },
                    onPickGallery = {
                        try { galleryLauncher.launch("image/*") } catch (_: Exception) {}
                    },
                    onPickDocument = {
                        try { documentLauncher.launch(arrayOf("*/*")) } catch (_: Exception) {}
                    },
                    onToolSelected = { prompt -> viewModel.onSendTextMessage(prompt) },
                    onDismiss = { viewModel.hideToolsSheet() }
                )
            }

            // ── Settings Sheet ────────────────────────────────────────────────
            if (isSettingsVisible) {
                IshaSettingsSheet(
                    isWakeWordEnabled = isWakeWordEnabled,
                    isShakeEnabled = isShakeEnabled,
                    isMessageSpeakEnabled = isMessageSpeakEnabled,
                    isCallAnnouncementEnabled = settingsState.isCallAnnouncementEnabled,
                    userProfile = userProfile,
                    selectedVoice = selectedVoice,
                    selectedLanguage = selectedLanguage,
                    textSize = textSize,
                    isSoundEnabled = isSoundEnabled,
                    onWakeWordToggle = { viewModel.setWakeWordEnabled(it) },
                    onShakeToggle = { viewModel.setShakeEnabled(it) },
                    onMessageSpeakToggle = { viewModel.setMessageSpeakEnabled(it) },
                    onCallAnnouncementToggle = { viewModel.setCallAnnouncementEnabled(it) },
                    onVoiceChange = { viewModel.setSelectedVoice(it) },
                    onLanguageChange = { viewModel.setSelectedLanguage(it) },
                    onTextSizeChange = { viewModel.setTextSize(it) },
                    onSoundToggle = { viewModel.setSoundEnabled(it) },
                    onClearAllChats = { viewModel.clearAllChats() },
                    onOpenDiagnostics = { isDiagnosticsVisible = true },
                    onOpenApiKeyEntry = { viewModel.showApiKeyScreen() },
                    onLogout = onLogout,
                    onDismiss = { viewModel.hideSettings() }
                )
            }

            // ── Diagnostics Screen ───────────────────────────────────────────
            if (isDiagnosticsVisible) {
                IshaDiagnosticsScreen(
                    onBack = { isDiagnosticsVisible = false }
                )
            }

            // ── API Key Entry Screen ─────────────────────────────────────────
            if (isApiKeyScreenVisible) {
                IshaApiKeyScreen(
                    onBack = { viewModel.hideApiKeyScreen() }
                )
            }

            // ── Security Governance Confirmation Dialog ───────────────────────
            val pendingConfirmation by viewModel.pendingConfirmation.collectAsState()
            pendingConfirmation?.let { conf ->
                AlertDialog(
                    onDismissRequest = { viewModel.cancelPendingAction() },
                    title = {
                        Text(
                            text = "Security Confirmation",
                            color = androidx.compose.ui.graphics.Color.White,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    },
                    text = {
                        Text(
                            text = conf.promptText,
                            color = androidx.compose.ui.graphics.Color.LightGray
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = { viewModel.confirmPendingAction() }
                        ) {
                            Text("Confirm", color = androidx.compose.ui.graphics.Color(0xFF10A37F))
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { viewModel.cancelPendingAction() }
                        ) {
                            Text("Cancel", color = androidx.compose.ui.graphics.Color.Gray)
                        }
                    },
                    containerColor = androidx.compose.ui.graphics.Color(0xFF202123)
                )
            }
        }
    }
}

@Composable
fun AuraMainScreen(
    viewModel: AuraAssistantViewModel,
    userProfile: UserProfile = UserProfile(),
    onLogout: () -> Unit = {},
    modifier: Modifier = Modifier
) = IshaMainScreen(
    viewModel = viewModel,
    userProfile = userProfile,
    onLogout = onLogout,
    modifier = modifier
)

