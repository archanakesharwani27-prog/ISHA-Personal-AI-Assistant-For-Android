package com.aura.assistant.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.aura.assistant.ai.AuraLiveState
import com.aura.assistant.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

/**
 * Multi-layer Canvas Glowing Voice Orb with real-time reactive audio visualization.
 * Responds to microphone amplitude and live AI state (Idle, Listening, Thinking, Speaking).
 */
@Composable
fun GlowingVoiceOrb(
    liveState: AuraLiveState,
    amplitude: Float,
    onOrbClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "OrbBreathing")

    // Ambient breathing scale
    val breatheScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "BreatheScale"
    )

    // Thinking rotation angle
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RotationAngle"
    )

    // Animated ripple phase
    val ripplePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RipplePhase"
    )

    // Smooth animated amplitude to avoid jitter
    val smoothAmplitude by animateFloatAsState(
        targetValue = amplitude.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "SmoothAmplitude"
    )

    val primaryColor = when (liveState) {
        AuraLiveState.IDLE            -> AuraNeonCyan
        AuraLiveState.CONNECTING      -> AuraAmberGold
        AuraLiveState.CONNECTED       -> AuraHoloEmerald
        AuraLiveState.LISTENING       -> AuraNeonCyan
        AuraLiveState.THINKING        -> AuraCyberPurple
        AuraLiveState.SPEAKING        -> AuraNeonCyan
        AuraLiveState.ERROR           -> AuraAlertRed
        AuraLiveState.WAKE_LISTENING  -> AuraNeonCyan.copy(alpha = 0.7f)
        AuraLiveState.WAKE_DETECTED   -> AuraHoloEmerald
        AuraLiveState.INTERRUPTED     -> AuraAmberGold
        AuraLiveState.RECOVERY        -> AuraCyberPurple.copy(alpha = 0.6f)
    }

    val secondaryColor = when (liveState) {
        AuraLiveState.THINKING -> AuraNeonCyan
        AuraLiveState.SPEAKING -> AuraCyberPurple
        else -> AuraCyberPurple
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(180.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOrbClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val baseRadius = size.width * 0.28f * breatheScale
                val dynamicRadius = baseRadius * (1f + smoothAmplitude * 0.45f)

                // 1. Outer Pulse Waves (when listening or speaking)
                if (liveState == AuraLiveState.LISTENING || liveState == AuraLiveState.SPEAKING) {
                    val ring1Radius = dynamicRadius + (35f * ripplePhase) + (smoothAmplitude * 40f)
                    val ring1Alpha = (1f - ripplePhase) * 0.45f
                    drawCircle(
                        color = primaryColor.copy(alpha = ring1Alpha),
                        radius = ring1Radius,
                        center = center,
                        style = Stroke(width = 2.5f)
                    )

                    val phase2 = (ripplePhase + 0.5f) % 1f
                    val ring2Radius = dynamicRadius + (45f * phase2) + (smoothAmplitude * 50f)
                    val ring2Alpha = (1f - phase2) * 0.35f
                    drawCircle(
                        color = secondaryColor.copy(alpha = ring2Alpha),
                        radius = ring2Radius,
                        center = center,
                        style = Stroke(width = 2f)
                    )
                }

                // 2. Thinking Orbiting Particles
                if (liveState == AuraLiveState.THINKING) {
                    val particleCount = 6
                    for (i in 0 until particleCount) {
                        val angle = Math.toRadians((rotationAngle + (i * 360f / particleCount)).toDouble())
                        val orbitDist = dynamicRadius * 1.35f
                        val px = center.x + (orbitDist * cos(angle)).toFloat()
                        val py = center.y + (orbitDist * sin(angle)).toFloat()
                        drawCircle(
                            color = primaryColor.copy(alpha = 0.85f),
                            radius = 4f,
                            center = Offset(px, py)
                        )
                    }
                }

                // 3. Ambient Glow Aura (Soft blurred ring)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.35f),
                            secondaryColor.copy(alpha = 0.15f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = dynamicRadius * 1.6f
                    ),
                    radius = dynamicRadius * 1.6f,
                    center = center
                )

                // 4. Core Orb Surface with Metallic Glow
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.9f),
                            primaryColor,
                            secondaryColor,
                            AuraPitchBlack
                        ),
                        center = Offset(center.x - dynamicRadius * 0.25f, center.y - dynamicRadius * 0.25f),
                        radius = dynamicRadius
                    ),
                    radius = dynamicRadius,
                    center = center
                )

                // 5. Precision Hologram Rings
                drawCircle(
                    color = Color.White.copy(alpha = 0.6f),
                    radius = dynamicRadius * 0.88f,
                    center = center,
                    style = Stroke(width = 1.2f)
                )

                drawCircle(
                    color = primaryColor.copy(alpha = 0.8f),
                    radius = dynamicRadius,
                    center = center,
                    style = Stroke(width = 2.5f)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // State Badge
        val statusText = when (liveState) {
            AuraLiveState.IDLE           -> "TAP TO TALK  •  SAY \"HEY ISHA\""
            AuraLiveState.CONNECTING     -> "CONNECTING TO ISHA LIVE..."
            AuraLiveState.CONNECTED      -> "ISHA READY  •  LISTENING"
            AuraLiveState.LISTENING      -> "ISHA IS LISTENING..."
            AuraLiveState.THINKING       -> "ISHA IS THINKING..."
            AuraLiveState.SPEAKING       -> "ISHA IS SPEAKING..."
            AuraLiveState.ERROR          -> "CONNECTION ERROR • RETRY"
            AuraLiveState.WAKE_LISTENING -> "WAKE WORD LISTENING..."
            AuraLiveState.WAKE_DETECTED  -> "WAKE WORD DETECTED!"
            AuraLiveState.INTERRUPTED    -> "INTERRUPTED • PROCESSING..."
            AuraLiveState.RECOVERY       -> "RECOVERING..."
        }

        Text(
            text = statusText,
            style = AuraTypography.labelSmall.copy(
                color = if (liveState == AuraLiveState.ERROR) AuraAlertRed else primaryColor
            )
        )
    }
}
