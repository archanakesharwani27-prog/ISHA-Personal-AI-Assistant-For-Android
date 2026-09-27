package com.aura.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.assistant.ai.AuraLiveState
import com.aura.assistant.audio.AuraDiagnostics
import com.aura.assistant.audio.MicOwner
import com.aura.assistant.audio.MicOwnershipManager
import com.aura.assistant.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IshaDiagnosticsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val micOwner by MicOwnershipManager.currentOwner.collectAsState()
    val wakeDiag by AuraDiagnostics.wakeWord.collectAsState()
    val liveDiag by AuraDiagnostics.liveSession.collectAsState()

    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "ISHA 2.0 Diagnostics",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = ChatGptTextPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(ChatGptAccentGreen.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "DEV",
                                color = ChatGptAccentGreen,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = ChatGptTextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ChatGptBackground
                )
            )
        },
        containerColor = ChatGptBackground,
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // ── 1. Mic Ownership Card ─────────────────────────────────────────
            DiagnosticsCard(title = "🎙 MICROPHONE OWNERSHIP") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Current Hardware Owner",
                        color = ChatGptTextSecondary,
                        fontSize = 14.sp
                    )
                    OwnerBadge(owner = micOwner)
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = { MicOwnershipManager.forceRelease() },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = AuraAlertRed
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AuraAlertRed.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Force Release Mic", fontSize = 12.sp)
                    }
                }
            }

            // ── 2. OpenWakeWord Diagnostics ───────────────────────────────────
            DiagnosticsCard(title = "⚡ WAKE WORD ENGINE") {
                MetricRow(
                    label = "Background Service",
                    value = if (wakeDiag.serviceRunning) "RUNNING" else "STOPPED",
                    color = if (wakeDiag.serviceRunning) ChatGptAccentGreen else ChatGptTextMuted
                )
                MetricRow(
                    label = "Mic Owned",
                    value = if (wakeDiag.micOwned) "YES (Holding Mic)" else "NO",
                    color = if (wakeDiag.micOwned) ChatGptAccentCyan else ChatGptTextMuted
                )
                MetricRow(
                    label = "Acoustic Models",
                    value = if (wakeDiag.modelLoaded) "LOADED" else "PENDING",
                    color = if (wakeDiag.modelLoaded) ChatGptAccentGreen else AuraAlertRed
                )
                MetricRow(
                    label = "Total Detections",
                    value = "${wakeDiag.detectionCount} times"
                )
                if (wakeDiag.lastDetectionTime != null) {
                    MetricRow(
                        label = "Last Trigger Time",
                        value = timeFormatter.format(Date(wakeDiag.lastDetectionTime!!))
                    )
                }
                if (wakeDiag.lastModelName != null) {
                    MetricRow(
                        label = "Last Model / Score",
                        value = "${wakeDiag.lastModelName} (score: ${"%.2f".format(wakeDiag.lastScore)})"
                    )
                }
            }

            // ── 3. ISHA Live Diagnostics ────────────────────────────────────
            DiagnosticsCard(title = "🌐 ISHA LIVE SESSION") {
                MetricRow(
                    label = "WebSocket State",
                    value = if (liveDiag.wsConnected) "CONNECTED" else "DISCONNECTED",
                    color = if (liveDiag.wsConnected) ChatGptAccentGreen else AuraAlertRed
                )
                MetricRow(
                    label = "Session State",
                    value = liveDiag.sessionState.name,
                    color = when (liveDiag.sessionState) {
                        AuraLiveState.SPEAKING -> ChatGptAccentCyan
                        AuraLiveState.LISTENING -> ChatGptAccentGreen
                        AuraLiveState.THINKING -> AuraAmberGold
                        AuraLiveState.INTERRUPTED -> AuraAlertRed
                        AuraLiveState.ERROR -> AuraAlertRed
                        else -> ChatGptTextSecondary
                    }
                )
                MetricRow(
                    label = "Mic Owned",
                    value = if (liveDiag.micOwned) "YES (16kHz Streaming)" else "NO",
                    color = if (liveDiag.micOwned) ChatGptAccentGreen else ChatGptTextMuted
                )
                MetricRow(
                    label = "Audio Packets Sent",
                    value = "${liveDiag.audioPacketsSent}"
                )
                MetricRow(
                    label = "Server Events Received",
                    value = "${liveDiag.serverEventsReceived}"
                )
                MetricRow(
                    label = "ISHA Barge-In Interruptions",
                    value = "${liveDiag.interruptionCount}",
                    color = if (liveDiag.interruptionCount > 0) ChatGptAccentCyan else ChatGptTextPrimary
                )
                MetricRow(
                    label = "Native Tool Calls Handled",
                    value = "${liveDiag.toolCallsHandled}"
                )
                if (liveDiag.lastError != null) {
                    MetricRow(
                        label = "Last Error",
                        value = liveDiag.lastError!!,
                        color = AuraAlertRed
                    )
                }
            }

            // ── 4. Audio Pipeline Specs ───────────────────────────────────────
            DiagnosticsCard(title = "🔊 AUDIO PIPELINE SPECIFICATIONS") {
                MetricRow(label = "Input Format", value = "16kHz Mono 16-bit PCM (40ms chunks)")
                MetricRow(label = "Output Format", value = "24kHz Mono 16-bit PCM (Low-Latency AudioTrack)")
                MetricRow(label = "Acoustic Echo Canceler (AEC)", value = "Enabled (Hardware Session)")
                MetricRow(label = "Automatic Gain Control (AGC)", value = "Enabled (Hardware Session)")
                MetricRow(label = "Adaptive Far-Field Gain", value = "1.3x - 3.5x Dynamic RMS")
                MetricRow(label = "Barge-in Threshold", value = "RMS > 0.06 (2 consecutive frames)")
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DiagnosticsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = ChatGptCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = ChatGptAccentGreen,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun MetricRow(
    label: String,
    value: String,
    color: Color = ChatGptTextPrimary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = ChatGptTextSecondary
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = color
        )
    }
}

@Composable
private fun OwnerBadge(owner: MicOwner) {
    val (bgColor, textColor, label) = when (owner) {
        MicOwner.NONE -> Triple(ChatGptCard, ChatGptTextMuted, "NONE (FREE)")
        MicOwner.WAKE_WORD -> Triple(ChatGptAccentCyan.copy(alpha = 0.2f), ChatGptAccentCyan, "WAKE_WORD")
        MicOwner.GEMINI_LIVE -> Triple(ChatGptAccentGreen.copy(alpha = 0.2f), ChatGptAccentGreen, "ISHA_LIVE")
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(1.dp, textColor.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = textColor
        )
    }
}

@Composable
fun AuraDiagnosticsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) = IshaDiagnosticsScreen(
    onBack = onBack,
    modifier = modifier
)

