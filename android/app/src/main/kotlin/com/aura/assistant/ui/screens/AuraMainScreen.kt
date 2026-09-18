package com.aura.assistant.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.aura.assistant.ui.components.*
import com.aura.assistant.ui.contract.AuraAssistantViewModel
import com.aura.assistant.ui.theme.ChatGptAccentCyan
import com.aura.assistant.ui.theme.ChatGptBackground
import com.aura.assistant.ui.theme.AuraCyberPurple
import kotlinx.coroutines.launch

/**
 * Main Screen for ChatGPT-style AURA Assistant.
 * Features ambient glow that follows user touch — inspired by Gemini's living UI.
 */
@Composable
fun AuraMainScreen(
    viewModel: AuraAssistantViewModel,
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

    val isToolsSheetVisible by viewModel.isToolsSheetVisible.collectAsState()
    val isSettingsVisible by viewModel.isSettingsVisible.collectAsState()
    val isWakeWordEnabled by viewModel.isWakeWordEnabled.collectAsState()
    val isShakeEnabled by viewModel.isShakeEnabled.collectAsState()

    val listState = rememberLazyListState()

    // ── Ambient Glow State ──────────────────────────────────────────────────
    // Tracks the current glow position (user touch or idle drift)
    var glowCenter by remember { mutableStateOf(Offset(0.5f, 0.3f)) } // normalized 0..1
    var glowColor by remember { mutableStateOf(ChatGptAccentCyan) }

    // Idle ambient animation — slowly drifts in a figure-8 when not touched
    val idleAnim = rememberInfiniteTransition(label = "ambient_idle")
    val idleX by idleAnim.animateFloat(
        initialValue = 0.2f, targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idleX"
    )
    val idleY by idleAnim.animateFloat(
        initialValue = 0.1f, targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idleY"
    )

    // Smoothly animate glow center
    val animGlowX by animateFloatAsState(
        targetValue = glowCenter.x,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "glowX"
    )
    val animGlowY by animateFloatAsState(
        targetValue = glowCenter.y,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "glowY"
    )

    // Color cycle: rotate between cyan and purple based on position
    val ambientColors = listOf(
        ChatGptAccentCyan.copy(alpha = 0.06f),
        AuraCyberPurple.copy(alpha = 0.06f),
        Color.Transparent
    )

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
                ChatGPTDrawer(
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
                    ChatGPTTopBar(
                        onOpenDrawer = {
                            coroutineScope.launch { drawerState.open() }
                        },
                        onNewChat = {
                            viewModel.createNewChat()
                        }
                    )
                },
                bottomBar = {
                    ChatGPTBottomBar(
                        onSendMessage = { query -> viewModel.onSendTextMessage(query) },
                        onVoiceClick = { viewModel.startVoiceMode() },
                        onStopGenerating = { viewModel.stopGeneration() },
                        onPlusClick = { viewModel.showToolsSheet() },
                        isGenerating = isGenerating
                    )
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        // ── Ambient Glow Canvas ──────────────────────────────
                        .drawBehind {
                            // Base background
                            drawRect(ChatGptBackground)

                            // Touch-reactive glow orb
                            val cx = animGlowX * size.width
                            val cy = animGlowY * size.height
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        ChatGptAccentCyan.copy(alpha = 0.07f),
                                        AuraCyberPurple.copy(alpha = 0.04f),
                                        Color.Transparent
                                    ),
                                    center = Offset(cx, cy),
                                    radius = size.minDimension * 0.7f
                                ),
                                radius = size.minDimension * 0.7f,
                                center = Offset(cx, cy)
                            )

                            // Idle secondary glow (drifts independently)
                            val ix = idleX * size.width
                            val iy = idleY * size.height
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        AuraCyberPurple.copy(alpha = 0.04f),
                                        Color.Transparent
                                    ),
                                    center = Offset(ix, iy),
                                    radius = size.minDimension * 0.5f
                                ),
                                radius = size.minDimension * 0.5f,
                                center = Offset(ix, iy)
                            )
                        }
                        // ── Touch to move glow ───────────────────────────────
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                glowCenter = Offset(
                                    x = (offset.x / w).coerceIn(0f, 1f),
                                    y = (offset.y / h).coerceIn(0f, 1f)
                                )
                            }
                        }
                ) {
                    if (chatMessages.isEmpty()) {
                        ChatGPTEmptyState(
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
                                    onRegenerate = { viewModel.regenerateLastMessage() }
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
                ChatGPTVoiceScreen(
                    liveState = liveState,
                    amplitude = amplitude,
                    liveTranscript = liveTranscript,
                    onCloseVoiceMode = { viewModel.stopVoiceMode() },
                    onToggleMute = { muted -> viewModel.setVoiceMuted(muted) },
                    onStartListening = { viewModel.startVoiceMode() }
                )
            }

            // ── Quick Device Tools Sheet ──────────────────────────────────────
            if (isToolsSheetVisible) {
                QuickToolsBottomSheet(
                    onToolSelected = { prompt -> viewModel.onSendTextMessage(prompt) },
                    onDismiss = { viewModel.hideToolsSheet() }
                )
            }

            // ── Settings Sheet ────────────────────────────────────────────────
            if (isSettingsVisible) {
                AuraSettingsSheet(
                    isWakeWordEnabled = isWakeWordEnabled,
                    isShakeEnabled = isShakeEnabled,
                    onWakeWordToggle = { viewModel.setWakeWordEnabled(it) },
                    onShakeToggle = { viewModel.setShakeEnabled(it) },
                    onClearAllChats = { viewModel.clearAllChats() },
                    onDismiss = { viewModel.hideSettings() }
                )
            }
        }
    }
}
