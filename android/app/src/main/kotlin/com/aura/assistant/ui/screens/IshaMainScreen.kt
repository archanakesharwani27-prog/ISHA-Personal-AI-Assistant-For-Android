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
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import com.aura.assistant.R
import com.aura.assistant.ui.components.*
import com.aura.assistant.ui.contract.IshaAssistantViewModel
import com.aura.assistant.ui.contract.AuraAssistantViewModel
import com.aura.assistant.ui.theme.ChatGptBackground
import com.aura.assistant.ui.theme.ChatGptAccentCyan
import com.aura.assistant.ui.theme.ChatGptAccentGreen
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
    var isOpeningActive by remember { mutableStateOf(true) }
    var actionIconCenter by remember { mutableStateOf<Offset?>(null) }

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
                        onActionPositioned = { center ->
                            actionIconCenter = center
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
                    targetOffset = actionIconCenter,
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
 * Isha App Opening Animation:
 * 1. Centered Original Master Round Icon (R.mipmap.ic_launcher_round) blooms and spins smoothly (0 -> 360 deg)
 *    with a glowing cyan/emerald halo on AMOLED pitch-black background.
 * 2. As the spin concludes (~730ms), the icon smoothly shifts and scales down towards the top-right
 *    header action button (targetOffset), docking seamlessly into place.
 * 3. The black splash scrim cross-fades out to reveal the clean, minimal chat screen.
 * Dismisses immediately upon tap so the user is never blocked.
 */
@Composable
fun IshaOpeningLaunchAnimation(
    targetOffset: Offset? = null,
    onDismiss: () -> Unit
) {
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 1400,
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
        val density = LocalDensity.current
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }

        val startCenter = Offset(screenWidthPx / 2f, screenHeightPx / 2f)
        val endCenter = targetOffset ?: Offset(
            screenWidthPx - with(density) { 31.dp.toPx() },
            with(density) { 56.dp.toPx() }
        )

        val p = progress.value

        // Phase 1 (0f..0.52f ~ 730ms): Centered spin & bloom
        // Phase 2 (0.52f..1.0f ~ 670ms): Graceful shift to top-right & dock into header
        val phase1Frac = (p / 0.52f).coerceIn(0f, 1f)
        val phase2Frac = ((p - 0.52f) / 0.48f).coerceIn(0f, 1f)

        val easeP1 = FastOutSlowInEasing.transform(phase1Frac)
        val easeP2 = FastOutSlowInEasing.transform(phase2Frac)

        // Splash background cross-fades out during Phase 2
        val scrimAlpha = (1f - easeP2).coerceIn(0f, 1f)

        // Full rotation: 360 deg during Phase 1, plus additional 180 deg swirl while moving to header
        val currentRotation = (easeP1 * 360f) + (easeP2 * 180f)

        // Interpolated center position
        val currentX = startCenter.x + (endCenter.x - startCenter.x) * easeP2
        val currentY = startCenter.y + (endCenter.y - startCenter.y) * easeP2

        // Size & Scale: Pops from 70dp to 105dp during Phase 1, then scales down to 24dp as it reaches header
        val baseSizeDp = 105.dp
        val startScale = 0.7f + 0.3f * easeP1
        val targetScale = 24f / 105f // dock into top bar size (~0.23)
        val currentScale = if (phase2Frac > 0f) {
            startScale + (targetScale - startScale) * easeP2
        } else {
            startScale
        }

        // Floating icon alpha
        val iconAlpha = if (phase2Frac >= 0.96f) {
            ((1f - phase2Frac) / 0.04f).coerceIn(0f, 1f)
        } else {
            (phase1Frac / 0.25f).coerceIn(0f, 1f)
        }

        // AMOLED Black Splash Background
        if (scrimAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = scrimAlpha }
                    .background(ChatGptBackground)
            )
        }

        // Shifting Original Master Icon
        if (iconAlpha > 0.01f) {
            val baseSizePx = with(density) { baseSizeDp.toPx() }
            val halfSizePx = (baseSizePx * currentScale) / 2f

            Box(
                modifier = Modifier
                    .offset {
                        androidx.compose.ui.unit.IntOffset(
                            (currentX - halfSizePx).toInt(),
                            (currentY - halfSizePx).toInt()
                        )
                    }
                    .size(baseSizeDp)
                    .graphicsLayer {
                        scaleX = currentScale
                        scaleY = currentScale
                        rotationZ = currentRotation
                        alpha = iconAlpha
                    },
                contentAlignment = Alignment.Center
            ) {
                // Soft Cyan-Emerald radial halo glow behind the icon during Phase 1
                if (phase2Frac < 0.6f) {
                    val haloAlpha = (1f - phase2Frac * 1.5f).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .size(130.dp)
                            .graphicsLayer { alpha = haloAlpha }
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        ChatGptAccentCyan.copy(alpha = 0.40f),
                                        ChatGptAccentGreen.copy(alpha = 0.20f),
                                        Color.Transparent
                                    )
                                ),
                                shape = CircleShape
                            )
                    )
                }

                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher_round),
                    contentDescription = "ISHA",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(baseSizeDp)
                        .clip(CircleShape)
                )
            }
        }
    }
}

