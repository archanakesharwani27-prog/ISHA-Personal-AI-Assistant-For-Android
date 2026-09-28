package com.aura.assistant.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.assistant.ai.AuraLiveState
import com.aura.assistant.ui.theme.*

@Composable
fun IshaHeaderHud(
    liveState: AuraLiveState,
    onClearChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetryConnection: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "LedPulse")
    val ledAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "LedAlpha"
    )

    val (ledColor, ledText) = when (liveState) {
        AuraLiveState.IDLE           -> Pair(AuraTextMuted, "IDLE")
        AuraLiveState.CONNECTING     -> Pair(AuraAmberGold.copy(alpha = ledAlpha), "CONNECTING")
        AuraLiveState.CONNECTED      -> Pair(AuraHoloEmerald.copy(alpha = ledAlpha), "CONNECTED")
        AuraLiveState.LISTENING      -> Pair(AuraNeonCyan.copy(alpha = ledAlpha), "LISTENING")
        AuraLiveState.THINKING       -> Pair(AuraCyberPurple.copy(alpha = ledAlpha), "THINKING")
        AuraLiveState.SPEAKING       -> Pair(AuraNeonCyan.copy(alpha = ledAlpha), "SPEAKING")
        AuraLiveState.ERROR          -> Pair(AuraAlertRed, "ERROR")
        AuraLiveState.WAKE_LISTENING -> Pair(AuraNeonCyan.copy(alpha = ledAlpha * 0.6f), "WAKE-LISTEN")
        AuraLiveState.WAKE_DETECTED  -> Pair(AuraHoloEmerald.copy(alpha = ledAlpha), "WAKE!")
        AuraLiveState.INTERRUPTED    -> Pair(AuraAmberGold.copy(alpha = ledAlpha), "INTERRUPTED")
        AuraLiveState.RECOVERY       -> Pair(AuraCyberPurple.copy(alpha = ledAlpha * 0.7f), "RECOVERY")
    }

    Surface(
        color = AuraPitchBlack,
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // ── Left: Brand ────────────────────────────────────────────────────
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "ISHA",
                        style = AuraTypography.headlineMedium.copy(
                            fontWeight = FontWeight.Black,
                            letterSpacing = 3.sp,
                            color = AuraTextPrimary
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = AuraNeonCyan.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "NATIVE",
                            style = AuraTypography.labelSmall.copy(
                                fontSize = 8.sp,
                                letterSpacing = 1.5.sp,
                                color = AuraNeonCyan
                            ),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = "ISHA Live  •  Low-Latency Engine",
                    style = AuraTypography.bodyMedium.copy(
                        fontSize = 10.sp,
                        color = AuraTextMuted
                    )
                )
            }

            // ── Right: Status + Actions ────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {

                // Status LED Pill
                Surface(
                    color = AuraSurfaceCard,
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AuraBorderNeon)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(ledColor)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = ledText,
                            style = AuraTypography.labelSmall.copy(
                                fontSize = 9.sp,
                                letterSpacing = 1.sp,
                                color = ledColor
                            )
                        )
                    }
                }

                // Retry button (only when error)
                if (liveState == AuraLiveState.ERROR) {
                    IconButton(
                        onClick = onRetryConnection,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(AuraAlertRed.copy(alpha = 0.15f))
                            .border(1.dp, AuraAlertRed.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Retry",
                            tint = AuraAlertRed,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Settings Button
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(AuraSurfaceCard)
                        .border(1.dp, AuraBorderNeon, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = AuraTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Clear Chat Button
                IconButton(
                    onClick = onClearChat,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(AuraSurfaceCard)
                        .border(1.dp, AuraBorderNeon, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "Clear Chat",
                        tint = AuraTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun AuraHeaderHud(
    liveState: AuraLiveState,
    onClearChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetryConnection: () -> Unit,
    modifier: Modifier = Modifier
) = IshaHeaderHud(
    liveState = liveState,
    onClearChat = onClearChat,
    onOpenSettings = onOpenSettings,
    onRetryConnection = onRetryConnection,
    modifier = modifier
)

