package com.aura.assistant.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.assistant.ai.AuraLiveState
import com.aura.assistant.ui.theme.*
import kotlin.math.*

/**
 * AURA Full-Screen Voice Mode.
 *
 * Features a fluid, organic glowing orb — exactly matching the design mockup:
 * - White glowing core at center
 * - Cyan fluid wisps swirling outward
 * - Violet soft glow rim
 * - Amplitude-reactive pulsing
 * - State-color shifts: Listening → Cyan, Thinking → Purple, Speaking → White/Cyan
 */
@Composable
fun ChatGPTVoiceScreen(
    liveState: AuraLiveState,
    amplitude: Float,
    liveTranscript: String,
    onCloseVoiceMode: () -> Unit,
    onToggleMute: (Boolean) -> Unit = {},
    onStartListening: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isMuted by remember { mutableStateOf(false) }
    var isSpeakerOn by remember { mutableStateOf(true) }

    val infiniteTransition = rememberInfiniteTransition(label = "OrbAnim")

    // Slow organic breathing — sphere "inhales and exhales"
    val breathScale by infiniteTransition.animateFloat(
        initialValue = 0.92f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "Breath"
    )

    // Main fluid warp rotation (slow, calm)
    val rotation1 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(10000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "Rot1"
    )

    // Counter-rotation for second layer of wisps (creates fluid figure-8 effect)
    val rotation2 by infiniteTransition.animateFloat(
        initialValue = 360f, targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(15000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "Rot2"
    )

    // Outer glow pulse
    val outerGlowPulse by infiniteTransition.animateFloat(
        initialValue = 0.5f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "GlowPulse"
    )

    // Amplitude-reactive orb boost — spring-damped so it doesn't jitter
    val boostDp by animateDpAsState(
        targetValue = (amplitude.coerceIn(0f, 1f) * 48).dp,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow),
        label = "AmpBoost"
    )

    // State-dependent colors
    val (coreInner, coreOuter, wispColor, glowColor) = when (liveState) {
        AuraLiveState.SPEAKING -> listOf(
            Color.White,
            Color(0xFF80DEEA),
            Color(0xFF00E5FF),
            Color(0xFF00E5FF).copy(alpha = 0.25f)
        )
        AuraLiveState.THINKING -> listOf(
            Color(0xFFD1C4E9),
            Color(0xFF9C27B0),
            Color(0xFF7C4DFF),
            Color(0xFF7C4DFF).copy(alpha = 0.25f)
        )
        AuraLiveState.ERROR -> listOf(
            Color(0xFFFFCDD2),
            Color(0xFFE53935),
            Color(0xFFFF5252),
            Color(0xFFFF1744).copy(alpha = 0.2f)
        )
        else -> listOf( // Idle / Listening
            Color.White,
            Color(0xFF80CBC4),
            Color(0xFF00E5FF),
            Color(0xFF00BCD4).copy(alpha = 0.22f)
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF050505)) // Near-pure black
    ) {
        // ── Header ──────────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 52.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "AURA",
                style = AuraTypography.titleMedium.copy(
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 6.sp
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Voice Mode  •  Gemini Live",
                style = AuraTypography.bodyMedium.copy(
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 11.sp,
                    letterSpacing = 1.sp
                )
            )
        }

        // ── Fluid Orb (center) ──────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // The orb Canvas — 280dp container, orb fills ~65% of it
            Box(
                modifier = Modifier.size(280.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val baseR = size.minDimension * 0.32f
                    // Amplitude boost: 0..1 scale maps to 1.0..1.5x scale
                    val ampScale = 1f + (amplitude.coerceIn(0f, 1f) * 0.45f)
                    val radius = baseR * breathScale * ampScale
                    val boosted = radius

                    // Color palette according to live state
                    val (wispColor, coreInner, coreOuter) = when {
                        isMuted -> Triple(
                            Color(0xFFFF5252),
                            Color(0xFFFF8A80),
                            Color(0xFFD32F2F)
                        )
                        liveState == AuraLiveState.LISTENING -> Triple(
                            Color(0xFF38BDF8), // Vibrant Sky Blue
                            Color(0xFFBAE6FD), // Light cyan
                            Color(0xFF0284C7)  // Deep cyan
                        )
                        liveState == AuraLiveState.THINKING -> Triple(
                            Color(0xFFA855F7), // Purple/Violet
                            Color(0xFFE9D5FF), // Soft lavender
                            Color(0xFF7E22CE)  // Deep violet
                        )
                        liveState == AuraLiveState.SPEAKING -> Triple(
                            Color(0xFF67E8F9), // Aqua cyan
                            Color(0xFFFFFFFF), // Pure white
                            Color(0xFF06B6D4)  // Cyan
                        )
                        else -> Triple(
                            Color(0xFF38BDF8).copy(alpha = 0.5f),
                            Color(0xFFE0F2FE),
                            Color(0xFF0369A1)
                        )
                    }

                    // ── Layer 0: Deep background ambient glow ──────────────
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                wispColor.copy(alpha = 0.35f),
                                wispColor.copy(alpha = 0.12f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = boosted * 1.7f
                        ),
                        radius = boosted * 1.7f,
                        center = center
                    )

                    // ── Layer 1: Clockwise outer fluid wisps ────────────────
                    rotate(rotation1, center) {
                        drawFluidWisps(
                            center = center,
                            radius = boosted * 1.15f,
                            wispColor = wispColor.copy(alpha = 0.45f),
                            count = 6,
                            wispWidth = 3.5f
                        )
                    }

                    // ── Layer 2: Counter-rotating inner wisps ──────────────
                    rotate(rotation2, center) {
                        drawFluidWisps(
                            center = center,
                            radius = boosted * 0.88f,
                            wispColor = wispColor.copy(alpha = 0.25f),
                            count = 4,
                            wispWidth = 2.0f
                        )
                    }

                    // ── Layer 3: Mid glow ring (torus effect) ──────────────
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.Transparent,
                                wispColor.copy(alpha = 0.18f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = boosted * 1.05f
                        ),
                        radius = boosted * 1.05f,
                        center = center,
                        style = Stroke(width = (boosted * 0.18f))
                    )

                    // ── Layer 4: Core glowing sphere ────────────────────────
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.98f),    // Bright white center
                                coreInner.copy(alpha = 0.85f),      // Light inner color
                                coreOuter.copy(alpha = 0.7f),       // Transition to wisp color
                                wispColor.copy(alpha = 0.3f),       // Soft edge blend
                            ),
                            center = Offset(
                                center.x - boosted * 0.12f,
                                center.y - boosted * 0.12f
                            ),
                            radius = boosted
                        ),
                        radius = boosted,
                        center = center
                    )

                    // ── Layer 5: Specular highlight (top-left glossy spot) ──
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.65f),
                                Color.Transparent
                            ),
                            center = Offset(center.x - boosted * 0.3f, center.y - boosted * 0.3f),
                            radius = boosted * 0.35f
                        ),
                        radius = boosted * 0.35f,
                        center = Offset(center.x - boosted * 0.3f, center.y - boosted * 0.3f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

            // Status Text
            val statusText = when {
                isMuted -> "Microphone muted • Tap to unmute"
                liveState == AuraLiveState.CONNECTING -> "Connecting..."
                liveState == AuraLiveState.LISTENING -> "Listening..."
                liveState == AuraLiveState.THINKING -> "Thinking..."
                liveState == AuraLiveState.SPEAKING -> "Speaking"
                liveState == AuraLiveState.ERROR -> "Connection lost • Tap to reconnect"
                liveState == AuraLiveState.IDLE -> "Ready • Tap to speak"
                else -> "Listening..."
            }

            Text(
                text = statusText,
                style = AuraTypography.titleMedium.copy(
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 0.5.sp
                )
            )

            // Transcript
            if (liveTranscript.isNotBlank()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = liveTranscript,
                    style = AuraTypography.bodyMedium.copy(
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    ),
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        }

        // ── Bottom Controls ──────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 58.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = Color(0xFF111111).copy(alpha = 0.9f),
                shape = RoundedCornerShape(40.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, Color.White.copy(alpha = 0.1f)
                ),
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Mute / Speak Button
                    val isDisconnected = liveState == AuraLiveState.IDLE || liveState == AuraLiveState.ERROR
                    val micLabel = when {
                        isMuted -> "Unmute"
                        isDisconnected -> "Speak"
                        else -> "Mute"
                    }
                    val micIcon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic

                    VoiceControlButton(
                        icon = micIcon,
                        label = micLabel,
                        isActive = isMuted,
                        activeColor = Color(0xFFFF5252),
                        onClick = {
                            if (isMuted) {
                                isMuted = false
                                onToggleMute(false)
                            } else if (isDisconnected) {
                                onStartListening()
                            } else {
                                isMuted = true
                                onToggleMute(true)
                            }
                        }
                    )

                    // Center Action Button (large white button)
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable {
                                if (isDisconnected) {
                                    if (isMuted) {
                                        isMuted = false
                                        onToggleMute(false)
                                    }
                                    onStartListening()
                                } else {
                                    isSpeakerOn = !isSpeakerOn
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isDisconnected) {
                                Icons.Default.Mic
                            } else if (isSpeakerOn) {
                                Icons.Default.VolumeUp
                            } else {
                                Icons.Default.VolumeOff
                            },
                            contentDescription = if (isDisconnected) "Start Voice" else "Speaker",
                            tint = Color.Black,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    // End Call Button
                    VoiceControlButton(
                        icon = Icons.Default.Close,
                        label = "End",
                        isActive = false,
                        activeColor = Color.White,
                        onClick = onCloseVoiceMode
                    )
                }
            }
        }
    }
}

// ── Fluid Wisps Draw Helper ───────────────────────────────────────────────────
// Draws N organic arc wisps around a center point.
// Each wisp is a curved arc that mimics a fluid tendril.
private fun DrawScope.drawFluidWisps(
    center: Offset,
    radius: Float,
    wispColor: Color,
    count: Int,
    wispWidth: Float
) {
    val angleStep = 360f / count
    for (i in 0 until count) {
        val startAngle = i * angleStep
        val sweepAngle = 65f + (i % 2) * 15f  // Slightly varied arc length for organic feel
        val wispPath = Path().apply {
            val startRad = Math.toRadians(startAngle.toDouble())
            val startX = center.x + cos(startRad).toFloat() * radius * 0.5f
            val startY = center.y + sin(startRad).toFloat() * radius * 0.5f
            moveTo(startX, startY)

            val midRad = Math.toRadians((startAngle + sweepAngle / 2.0))
            val ctrlX = center.x + cos(midRad).toFloat() * radius * 1.1f
            val ctrlY = center.y + sin(midRad).toFloat() * radius * 1.1f

            val endRad = Math.toRadians((startAngle + sweepAngle).toDouble())
            val endX = center.x + cos(endRad).toFloat() * radius * 0.75f
            val endY = center.y + sin(endRad).toFloat() * radius * 0.75f

            quadraticBezierTo(ctrlX, ctrlY, endX, endY)
        }
        drawPath(
            path = wispPath,
            color = wispColor,
            style = Stroke(width = wispWidth.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        )
    }
}

// ── Voice Control Button ──────────────────────────────────────────────────────
@Composable
private fun VoiceControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(if (isActive) activeColor.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) activeColor else Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            text = label,
            style = AuraTypography.bodyMedium.copy(
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 10.sp
            )
        )
    }
}
