package com.aura.assistant.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aura.assistant.ai.AuraLiveState

/**
 * Ultra-Performance Cosmic Energy Orb.
 *
 * Lightweight, 60fps hardware-accelerated 3D glowing sphere with rotating plasma arcs,
 * specular crystal sheen, and voice-reactive laser core. Zero JNI Path allocations.
 */
@Composable
fun CosmicEnergyOrb(
    liveState: AuraLiveState,
    amplitude: Float,
    isMuted: Boolean = false,
    modifier: Modifier = Modifier,
    orbSize: Dp = 290.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "CosmicOrb")

    // Slow organic breathing
    val breathScale by infiniteTransition.animateFloat(
        initialValue = 0.95f, targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "Breath"
    )

    // Plasma arcs continuous rotation
    val rotAngle by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "RotAngle"
    )

    // Amplitude smoothing with low-cost physics
    val smoothAmp by animateFloatAsState(
        targetValue = amplitude.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium),
        label = "SmoothAmp"
    )

    // Color palette according to Live State
    val (primaryColor, secondaryColor, coreColor, laserColor) = when {
        isMuted -> listOf(
            Color(0xFFFF5252),
            Color(0xFFFF8A80),
            Color(0xFF2A0808),
            Color(0xFFFF5252)
        )
        liveState == AuraLiveState.THINKING -> listOf(
            Color(0xFFA855F7), // Cosmic Purple
            Color(0xFFC084FC), // Lavender
            Color(0xFF130624), // Deep violet space
            Color(0xFFE9D5FF)  // Bright laser lavender
        )
        liveState == AuraLiveState.SPEAKING -> listOf(
            Color(0xFF38BDF8), // Sky Cyan
            Color(0xFFE0F2FE), // Soft Cyan
            Color(0xFF031926), // Midnight
            Color(0xFFFFFFFF)  // Pure White laser
        )
        liveState == AuraLiveState.ERROR -> listOf(
            Color(0xFFFF1744),
            Color(0xFFFF8A80),
            Color(0xFF2D0707),
            Color(0xFFFF5252)
        )
        else -> listOf( // LISTENING / CONNECTED / IDLE (Default Cosmic Cyan)
            Color(0xFF00E5FF), // Electric Cyan
            Color(0xFF80DEEA), // Soft Aqua
            Color(0xFF02131D), // Deep cosmic void
            Color(0xFFE0F7FA)  // Piercing Cyan-White laser
        )
    }

    Box(
        modifier = modifier.size(orbSize),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension * 0.36f
            val ampMultiplier = 1f + (smoothAmp * 0.25f)
            val sphereRadius = baseRadius * breathScale * ampMultiplier

            // 1. Ambient Halo Bloom
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.35f + smoothAmp * 0.25f),
                        primaryColor.copy(alpha = 0.10f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = sphereRadius * 1.5f
                ),
                radius = sphereRadius * 1.5f,
                center = center
            )

            // 2. Deep Sphere Body
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        coreColor,
                        coreColor.copy(alpha = 0.95f),
                        primaryColor.copy(alpha = 0.30f)
                    ),
                    center = Offset(center.x - sphereRadius * 0.1f, center.y - sphereRadius * 0.1f),
                    radius = sphereRadius
                ),
                radius = sphereRadius,
                center = center
            )

            // 3. Rotating 3D Gyroscopic Plasma Arcs (Zero heap allocation, pure GPU drawArc)
            rotate(rotAngle, center) {
                val arcRect1 = Size(sphereRadius * 1.6f, sphereRadius * 0.9f)
                val arcTopLeft1 = Offset(center.x - arcRect1.width / 2f, center.y - arcRect1.height / 2f)
                drawArc(
                    color = primaryColor.copy(alpha = 0.65f),
                    startAngle = 20f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = arcTopLeft1,
                    size = arcRect1,
                    style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                )
                drawArc(
                    color = secondaryColor.copy(alpha = 0.45f),
                    startAngle = 200f,
                    sweepAngle = 120f,
                    useCenter = false,
                    topLeft = arcTopLeft1,
                    size = arcRect1,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            rotate(-rotAngle * 0.75f + 45f, center) {
                val arcRect2 = Size(sphereRadius * 0.95f, sphereRadius * 1.65f)
                val arcTopLeft2 = Offset(center.x - arcRect2.width / 2f, center.y - arcRect2.height / 2f)
                drawArc(
                    color = secondaryColor.copy(alpha = 0.60f),
                    startAngle = 60f,
                    sweepAngle = 150f,
                    useCenter = false,
                    topLeft = arcTopLeft2,
                    size = arcRect2,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // 4. Central Voice Flare Ribbon
            val laserLeft = Offset(center.x - sphereRadius * 0.92f, center.y)
            val laserRight = Offset(center.x + sphereRadius * 0.92f, center.y)
            val laserThickness = (3.dp.toPx() + smoothAmp * 8.dp.toPx())

            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        primaryColor.copy(alpha = 0.5f),
                        Color.White,
                        laserColor,
                        primaryColor.copy(alpha = 0.5f),
                        Color.Transparent
                    ),
                    startX = laserLeft.x,
                    endX = laserRight.x
                ),
                start = laserLeft,
                end = laserRight,
                strokeWidth = laserThickness,
                cap = StrokeCap.Round
            )

            // 5. Specular Gloss Highlight (3D Glass Sphere Reflection)
            val glossCenter = Offset(center.x - sphereRadius * 0.35f, center.y - sphereRadius * 0.35f)
            val glossRadius = sphereRadius * 0.35f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.55f),
                        Color.White.copy(alpha = 0.15f),
                        Color.Transparent
                    ),
                    center = glossCenter,
                    radius = glossRadius
                ),
                radius = glossRadius,
                center = glossCenter
            )

            // 6. Outer Rim Ring
            drawCircle(
                color = primaryColor.copy(alpha = 0.5f),
                radius = sphereRadius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )
        }
    }
}
