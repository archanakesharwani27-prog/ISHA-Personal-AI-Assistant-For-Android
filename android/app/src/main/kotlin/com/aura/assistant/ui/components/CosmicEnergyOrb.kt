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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aura.assistant.ai.AuraLiveState
import kotlin.math.*
import kotlin.random.Random

private data class CosmicParticle(
    val normalizedRadius: Float, // 0.1f .. 0.88f
    val baseAngle: Float,        // 0 .. 360
    val speed: Float,            // angular speed
    val size: Float,             // particle size in dp
    val alphaOffset: Float,      // phase for twinkle
    val isWhite: Boolean
)

/**
 * Cosmic Energy Orb.
 *
 * Faithfully reproduces the 3D Glass Sphere with Swirling Cyan Plasma,
 * Stardust Particles, Central Laser Ribbon, and Specular Gloss Highlight (Image 2).
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
        initialValue = 0.94f, targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(3600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "Breath"
    )

    // Plasma ribbons clockwise rotation
    val rotClockwise by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(14000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "RotClockwise"
    )

    // Plasma ribbons counter-clockwise rotation
    val rotCounter by infiniteTransition.animateFloat(
        initialValue = 360f, targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(19000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "RotCounter"
    )

    // Continuous time accumulator for particle orbit and laser waver
    val timeProgression by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "Time"
    )

    // Amplitude smoothing with spring physics
    val smoothAmp by animateFloatAsState(
        targetValue = amplitude.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow),
        label = "SmoothAmp"
    )

    // Generate stable cosmic stardust particles
    val particles = remember {
        val rng = Random(42)
        List(42) {
            CosmicParticle(
                normalizedRadius = rng.nextFloat() * 0.78f + 0.08f,
                baseAngle = rng.nextFloat() * 360f,
                speed = (rng.nextFloat() * 0.4f + 0.15f) * if (rng.nextBoolean()) 1f else -1f,
                size = rng.nextFloat() * 1.8f + 0.8f,
                alphaOffset = rng.nextFloat() * (2 * PI).toFloat(),
                isWhite = rng.nextFloat() > 0.4f
            )
        }
    }

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
            val ampMultiplier = 1f + (smoothAmp * 0.28f)
            val sphereRadius = baseRadius * breathScale * ampMultiplier

            // ── 1. Volumetric Ambient Backlight (blooming outside the sphere) ──
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.45f + smoothAmp * 0.3f),
                        primaryColor.copy(alpha = 0.15f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = sphereRadius * 1.65f
                ),
                radius = sphereRadius * 1.65f,
                center = center
            )

            // ── 2. Deep Cosmic Abyss Sphere Body ──
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        coreColor,
                        coreColor.copy(alpha = 0.95f),
                        primaryColor.copy(alpha = 0.35f)
                    ),
                    center = Offset(center.x - sphereRadius * 0.1f, center.y - sphereRadius * 0.1f),
                    radius = sphereRadius
                ),
                radius = sphereRadius,
                center = center
            )

            // ── 3. Internal Stardust Starfield Particles ──
            for (p in particles) {
                val currentAngle = (p.baseAngle + p.speed * (timeProgression * 180f / PI.toFloat())) % 360f
                val rad = Math.toRadians(currentAngle.toDouble())
                val pRadius = sphereRadius * p.normalizedRadius
                val px = center.x + (cos(rad) * pRadius).toFloat()
                val py = center.y + (sin(rad) * pRadius).toFloat()

                // Twinkle modulation
                val twinkle = (sin((timeProgression * 2f + p.alphaOffset).toDouble()).toFloat() + 1f) / 2f
                val alpha = (0.25f + twinkle * 0.7f).coerceIn(0f, 1f)
                val dotColor = if (p.isWhite) Color.White.copy(alpha = alpha) else secondaryColor.copy(alpha = alpha)

                drawCircle(
                    color = dotColor,
                    radius = p.size.dp.toPx() * (0.8f + smoothAmp * 0.5f),
                    center = Offset(px, py)
                )
            }

            // ── 4. Swirling 3D Plasma Wisps (Clockwise Layer) ──
            rotate(rotClockwise, center) {
                drawCosmicPlasmaRibbons(
                    center = center,
                    radius = sphereRadius,
                    primaryColor = primaryColor,
                    secondaryColor = secondaryColor,
                    smoothAmp = smoothAmp,
                    isClockwise = true
                )
            }

            // ── 5. Swirling 3D Plasma Wisps (Counter-Clockwise Layer) ──
            rotate(rotCounter, center) {
                drawCosmicPlasmaRibbons(
                    center = center,
                    radius = sphereRadius * 0.88f,
                    primaryColor = secondaryColor,
                    secondaryColor = primaryColor,
                    smoothAmp = smoothAmp,
                    isClockwise = false
                )
            }

            // ── 6. Central High-Energy Laser Beam / Flare Ribbon ──
            val laserYOffset = sin(timeProgression.toDouble()).toFloat() * (sphereRadius * 0.08f)
            val laserLeft = Offset(center.x - sphereRadius * 0.96f, center.y + laserYOffset)
            val laserRight = Offset(center.x + sphereRadius * 0.96f, center.y + laserYOffset * 0.4f)
            val laserThickness = (3.5f + smoothAmp * 7.5f).dp.toPx()

            // Laser core line
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        primaryColor.copy(alpha = 0.5f),
                        Color.White,
                        laserColor,
                        primaryColor.copy(alpha = 0.6f),
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

            // Laser Outer Radiant Aura
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        primaryColor.copy(alpha = 0.25f + smoothAmp * 0.35f),
                        secondaryColor.copy(alpha = 0.5f),
                        Color.Transparent
                    ),
                    startX = laserLeft.x,
                    endX = laserRight.x
                ),
                start = laserLeft,
                end = laserRight,
                strokeWidth = laserThickness * 3.5f,
                cap = StrokeCap.Round
            )

            // Laser Focal Flare Node on the right
            val flareCenter = Offset(center.x + sphereRadius * 0.48f, center.y + laserYOffset * 0.65f)
            val flareRadius = (16.dp.toPx() + smoothAmp * 24.dp.toPx())

            // Flare radial burst
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White,
                        laserColor.copy(alpha = 0.85f),
                        primaryColor.copy(alpha = 0.4f),
                        Color.Transparent
                    ),
                    center = flareCenter,
                    radius = flareRadius
                ),
                radius = flareRadius,
                center = flareCenter
            )

            // Cross sparkle on the flare node
            val crossSize = flareRadius * 0.8f
            drawLine(
                color = Color.White.copy(alpha = 0.9f),
                start = Offset(flareCenter.x - crossSize, flareCenter.y),
                end = Offset(flareCenter.x + crossSize, flareCenter.y),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color.White.copy(alpha = 0.9f),
                start = Offset(flareCenter.x, flareCenter.y - crossSize),
                end = Offset(flareCenter.x, flareCenter.y + crossSize),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )

            // ── 7. 3D Glass Crystal Fresnel Rim ──
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.9f),
                        secondaryColor.copy(alpha = 0.4f),
                        Color.White.copy(alpha = 0.85f),
                        primaryColor.copy(alpha = 0.3f),
                        primaryColor.copy(alpha = 0.9f)
                    ),
                    center = center
                ),
                radius = sphereRadius - 1.5.dp.toPx(),
                center = center,
                style = Stroke(width = 3.dp.toPx())
            )

            // ── 8. Specular Gloss Sheen (Top-Left Glass Reflection) ──
            val glossCenter = Offset(center.x - sphereRadius * 0.35f, center.y - sphereRadius * 0.38f)
            val glossRadius = sphereRadius * 0.38f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.65f),
                        Color.White.copy(alpha = 0.2f),
                        Color.Transparent
                    ),
                    center = glossCenter,
                    radius = glossRadius
                ),
                radius = glossRadius,
                center = glossCenter
            )
        }
    }
}

/**
 * Renders swirling organic Bézier plasma ribbons inside the cosmic sphere.
 */
private fun DrawScope.drawCosmicPlasmaRibbons(
    center: Offset,
    radius: Float,
    primaryColor: Color,
    secondaryColor: Color,
    smoothAmp: Float,
    isClockwise: Boolean
) {
    val ribbonCount = if (isClockwise) 4 else 3
    val angleStep = 360f / ribbonCount

    for (i in 0 until ribbonCount) {
        val baseA = i * angleStep
        val radStart = Math.toRadians(baseA.toDouble())
        val radCtrl1 = Math.toRadians((baseA + 48.0))
        val radCtrl2 = Math.toRadians((baseA + 110.0))
        val radEnd = Math.toRadians((baseA + 160.0))

        val r1 = radius * (0.35f + (i % 2) * 0.15f)
        val r2 = radius * (0.85f + (i % 2) * 0.08f)
        val r3 = radius * (0.65f)
        val r4 = radius * (0.28f)

        val p1 = Offset(center.x + cos(radStart).toFloat() * r1, center.y + sin(radStart).toFloat() * r1)
        val ctrl1 = Offset(center.x + cos(radCtrl1).toFloat() * r2, center.y + sin(radCtrl1).toFloat() * r2)
        val ctrl2 = Offset(center.x + cos(radCtrl2).toFloat() * r3, center.y + sin(radCtrl2).toFloat() * r3)
        val p2 = Offset(center.x + cos(radEnd).toFloat() * r4, center.y + sin(radEnd).toFloat() * r4)

        val ribbonPath = Path().apply {
            moveTo(p1.x, p1.y)
            cubicTo(ctrl1.x, ctrl1.y, ctrl2.x, ctrl2.y, p2.x, p2.y)
        }

        val ribbonWidth = ((3.0f + (i % 2) * 1.5f) + smoothAmp * 2.5f).dp.toPx()
        val ribbonColor = if (i % 2 == 0) primaryColor.copy(alpha = 0.65f) else secondaryColor.copy(alpha = 0.5f)

        drawPath(
            path = ribbonPath,
            color = ribbonColor,
            style = Stroke(width = ribbonWidth, cap = StrokeCap.Round)
        )
    }
}
