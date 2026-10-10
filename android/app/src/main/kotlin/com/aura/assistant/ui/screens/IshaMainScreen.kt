package com.aura.assistant.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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

sealed class ChatUiItem(val key: String) {
    data class SingleMessage(val message: com.aura.assistant.data.ChatMessage) : ChatUiItem(message.id)
    data class ToolGroup(val tools: List<com.aura.assistant.data.ChatMessage>) : ChatUiItem("group_" + (tools.firstOrNull()?.id ?: java.util.UUID.randomUUID().toString()))
}

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
    var isOpeningActive by rememberSaveable { mutableStateOf(true) }
    var newChatActionPosition by remember { mutableStateOf<Offset?>(null) }

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

    val groupedItems = remember(chatMessages) {
        val result = mutableListOf<ChatUiItem>()
        var currentTools = mutableListOf<com.aura.assistant.data.ChatMessage>()
        for (msg in chatMessages) {
            if (msg.role == com.aura.assistant.data.MessageRole.TOOL) {
                currentTools.add(msg)
            } else {
                if (currentTools.isNotEmpty()) {
                    result.add(ChatUiItem.ToolGroup(currentTools.toList()))
                    currentTools = mutableListOf()
                }
                result.add(ChatUiItem.SingleMessage(msg))
            }
        }
        if (currentTools.isNotEmpty()) {
            result.add(ChatUiItem.ToolGroup(currentTools.toList()))
        }
        result
    }

    // Auto-scroll on new messages or when keyboard opens - fast, non-blocking scrollToItem
    LaunchedEffect(groupedItems.size, imeBottom) {
        if (groupedItems.isNotEmpty()) {
            listState.scrollToItem(groupedItems.size - 1)
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
                        },
                        onActionPositioned = { pos ->
                            newChatActionPosition = pos
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
                            items(groupedItems, key = { it.key }) { item ->
                                when (item) {
                                    is ChatUiItem.SingleMessage -> ChatMessageItem(
                                        message = item.message,
                                        onSpeak = { text -> viewModel.speakText(text) },
                                        onRegenerate = { viewModel.regenerateLastMessage() },
                                        onEditPrompt = { prompt -> editingPrompt = prompt },
                                        textSize = textSize
                                    )
                                    is ChatUiItem.ToolGroup -> CollapsibleToolGroup(
                                        tools = item.tools,
                                        textSize = textSize
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (isOpeningActive) {
                IshaOpeningLaunchAnimation(
                    targetActionPos = newChatActionPosition,
                    onDismiss = { isOpeningActive = false }
                )
            }

            // ── Full-Screen Voice Mode Overlay ────────────────────────────────
            AnimatedVisibility(
                visible = isVoiceModeActive,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 })
            ) {
                val amplitude by viewModel.audioAmplitude.collectAsState()
                val liveTranscript by viewModel.liveTranscript.collectAsState()

                IshaVoiceScreen(
                    liveState = liveState,
                    amplitude = amplitude,
                    liveTranscript = liveTranscript,
                    onCloseVoiceMode = { viewModel.stopVoiceMode() },
                    onToggleMute = { muted -> viewModel.setVoiceMuted(muted) },
                    onStartListening = { viewModel.retryConnection() },
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

            // ── Settings Screen ───────────────────────────────────────────────
            AnimatedVisibility(
                visible = isSettingsVisible,
                enter = fadeIn() + slideInHorizontally(initialOffsetX = { it }),
                exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it })
            ) {
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

/**
 * YouTube-style opening launch animation:
 * 1. Centered Neural Lotus pops and spins rapidly (0 -> 720 deg) with radiant bloom.
 * 2. Smoothly translates and scales up/across into the TopBar New Chat action button position.
 * 3. Settles seamlessly into the TopBar button while revealing the clean chat screen underneath.
 * Dismisses immediately upon tap so the user is never blocked.
 */
@Composable
fun IshaOpeningLaunchAnimation(
    targetActionPos: Offset?,
    onDismiss: () -> Unit
) {
    val progress = remember { Animatable(0f) }
    val density = LocalDensity.current
    val statusBarHeight = WindowInsets.statusBars.getTop(density)

    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 1250,
                easing = LinearEasing
            )
        )
        onDismiss()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()

        val centerX = widthPx / 2f
        val centerY = heightPx / 2f

        val fallbackTargetX = widthPx - with(density) { 31.dp.toPx() }
        val fallbackTargetY = statusBarHeight + with(density) { 32.dp.toPx() }

        val targetCenterPxX = targetActionPos?.x?.takeIf { it > 0f } ?: fallbackTargetX
        val targetCenterPxY = targetActionPos?.y?.takeIf { it > 0f } ?: fallbackTargetY

        val targetOffsetX = targetCenterPxX - centerX
        val targetOffsetY = targetCenterPxY - centerY

        val p = progress.value

        val phase1Frac = (p / 0.48f).coerceIn(0f, 1f)
        val phase2Frac = ((p - 0.48f) / 0.52f).coerceIn(0f, 1f)

        val easeP1 = FastOutSlowInEasing.transform(phase1Frac)
        val easeP2 = FastOutSlowInEasing.transform(phase2Frac)

        val scrimAlpha = (1f - easeP2).coerceIn(0f, 1f)
        val currentRotation = (easeP1 * 720f) + (easeP2 * 180f)

        val initialScale = 0.35f + (0.65f * easeP1)
        val targetScaleRatio = with(density) { 22.dp.toPx() / 120.dp.toPx() }
        val currentScale = if (phase2Frac > 0f) {
            1.0f + (targetScaleRatio - 1.0f) * easeP2
        } else {
            initialScale
        }

        val currentOffsetX = if (phase2Frac > 0f) targetOffsetX * easeP2 else 0f
        val currentOffsetY = if (phase2Frac > 0f) targetOffsetY * easeP2 else 0f

        val logoAlpha = when {
            phase1Frac < 0.15f -> (phase1Frac / 0.15f).coerceIn(0f, 1f)
            phase2Frac > 0.92f -> (1f - ((phase2Frac - 0.92f) / 0.08f)).coerceIn(0f, 1f)
            else -> 1f
        }

        if (scrimAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = scrimAlpha }
                    .background(ChatGptBackground)
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    translationX = currentOffsetX
                    translationY = currentOffsetY
                    scaleX = currentScale
                    scaleY = currentScale
                    rotationZ = currentRotation
                    alpha = logoAlpha
                },
            contentAlignment = Alignment.Center
        ) {
            IshaNeuralLotus(
                lotusSize = 120.dp
            )
        }
    }
}

