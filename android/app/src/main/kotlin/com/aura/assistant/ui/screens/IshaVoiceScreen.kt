package com.aura.assistant.ui.screens

import android.app.Activity
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aura.assistant.ai.AuraLiveState
import com.aura.assistant.ui.components.CosmicEnergyOrb
import com.aura.assistant.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.*

/**
 * Gemini Live Voice Mode Screen.
 *
 * Implements the authentic Gemini Live experience (Image 1):
 * - Ambient light bloom radiating from the bottom and edges (Deep Blue & Electric Cyan)
 * - 4-point Gemini star header with Google color gradient
 * - Personalized greeting: "Hi Ansh, what's on your mind?"
 * - Center Cosmic Energy Orb (Image 2) with 3D glass sphere, cyan plasma wisps, and laser flare
 * - Floating bottom pill bar with 100% working controls (Camera, Screen Share, Equalizer, Mute, Close)
 */
@Composable
fun IshaVoiceScreen(
    liveState: AuraLiveState,
    amplitude: Float,
    liveTranscript: String,
    onCloseVoiceMode: () -> Unit,
    onToggleMute: (Boolean) -> Unit = {},
    onStartListening: () -> Unit = {},
    onSendCameraImage: (ByteArray) -> Unit = {},
    onSendScreenCapture: () -> Unit = {},
    isScreenSharing: Boolean = false,
    onStopScreenSharing: () -> Unit = {},
    userName: String = "Ansh",
    selectedLanguage: String = "hinglish",
    onSelectLanguage: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isMuted by remember { mutableStateOf(false) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var showScreenShareConfirmDialog by remember { mutableStateOf(false) }

    // Transient feedback auto-dismiss
    LaunchedEffect(feedbackMessage) {
        if (feedbackMessage != null) {
            delay(2800L)
            feedbackMessage = null
        }
    }

    // Camera capture launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            coroutineScope.launch {
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                val bytes = stream.toByteArray()
                bitmap.recycle()
                if (bytes.isNotEmpty()) {
                    feedbackMessage = "Photo sent to ISHA"
                    onSendCameraImage(bytes)
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF030712)) // Dark midnight background
    ) {
        // ── 1. Radiant Ambient Bottom & Edge Lighting Mesh (Gemini signature) ──
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Bottom large radiant blue/cyan bloom
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF0284C7).copy(alpha = 0.35f), // Electric Cyan-Blue
                        Color(0xFF0369A1).copy(alpha = 0.20f),
                        Color(0xFF1E1B4B).copy(alpha = 0.15f), // Indigo base
                        Color.Transparent
                    ),
                    center = Offset(w * 0.5f, h * 0.98f),
                    radius = w * 0.95f
                ),
                radius = w * 0.95f,
                center = Offset(w * 0.5f, h * 0.98f)
            )

            // Bottom right accent glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF4F46E5).copy(alpha = 0.22f), // Violet glow
                        Color.Transparent
                    ),
                    center = Offset(w * 0.85f, h * 0.88f),
                    radius = w * 0.6f
                ),
                radius = w * 0.6f,
                center = Offset(w * 0.85f, h * 0.88f)
            )

            // Bottom left accent glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF00E5FF).copy(alpha = 0.18f), // Cyan glow
                        Color.Transparent
                    ),
                    center = Offset(w * 0.15f, h * 0.88f),
                    radius = w * 0.6f
                ),
                radius = w * 0.6f,
                center = Offset(w * 0.15f, h * 0.88f)
            )
        }

        // ── 2. Top Header (Gemini 4-Point Star + Greeting) ──────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 54.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Sparkling 4-point Gemini star
            GeminiFourPointStar(
                modifier = Modifier.size(36.dp),
                pulseScale = if (liveState == AuraLiveState.THINKING) 1.2f else 1.0f
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Greeting matching Image 1
            Text(
                text = "Hi $userName, what's on your mind?",
                style = AuraTypography.titleLarge.copy(
                    color = Color.White.copy(alpha = 0.94f),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.2.sp,
                    textAlign = TextAlign.Center
                )
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Voice Mode  •  ISHA Live",
                style = AuraTypography.bodySmall.copy(
                    color = Color.White.copy(alpha = 0.40f),
                    fontSize = 11.sp,
                    letterSpacing = 1.sp
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── Language Selector Chips (Hindi, Hinglish, English) ──────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val languages = listOf(
                    "hindi" to "हिंदी",
                    "hinglish" to "Hinglish",
                    "english" to "English"
                )
                languages.forEach { (key, label) ->
                    val isSelected = selectedLanguage.equals(key, ignoreCase = true)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) Color(0x3300E5FF) else Color(0x1AFFFFFF),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) Color(0xFF00E5FF) else Color(0x22FFFFFF)
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onSelectLanguage(key) }
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            style = AuraTypography.bodySmall.copy(
                                color = if (isSelected) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.65f),
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 12.sp
                            )
                        )
                    }
                }
            }
        }

        // ── 3. Center Stage (Cosmic Energy Orb + Live Transcript) ───────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Cosmic Energy Orb (Image 2)
            CosmicEnergyOrb(
                liveState = liveState,
                amplitude = amplitude,
                isMuted = isMuted,
                orbSize = 280.dp
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Status label with live state dot
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val dotColor = when {
                    isMuted -> Color(0xFFFF5252)
                    liveState == AuraLiveState.THINKING -> Color(0xFFA855F7)
                    liveState == AuraLiveState.SPEAKING -> Color(0xFF38BDF8)
                    liveState == AuraLiveState.LISTENING -> Color(0xFF00E5FF)
                    liveState == AuraLiveState.ERROR -> Color(0xFFFF1744)
                    else -> Color(0xFF94A3B8)
                }

                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )

                val statusText = when {
                    isMuted -> "Microphone muted"
                    liveState == AuraLiveState.CONNECTING -> "Connecting to ISHA Live..."
                    liveState == AuraLiveState.LISTENING -> "Listening..."
                    liveState == AuraLiveState.THINKING -> "Thinking..."
                    liveState == AuraLiveState.SPEAKING -> "Speaking..."
                    liveState == AuraLiveState.ERROR -> "Connection issue • Tap to reconnect"
                    liveState == AuraLiveState.IDLE -> "Ready • Tap to speak"
                    else -> "Listening..."
                }

                Text(
                    text = statusText,
                    style = AuraTypography.titleMedium.copy(
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Normal
                    )
                )
            }
        }

        // ── 4. Transient Feedback Pill ──────────────────────────────────────────
        AnimatedVisibility(
            visible = feedbackMessage != null,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 120.dp)
        ) {
            Surface(
                color = Color(0xFF1E293B).copy(alpha = 0.95f),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = feedbackMessage ?: "",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // ── 5. Bottom Floating Controls Bar (Image 1 Phone Mockup) ──────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 44.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = Color(0xFF0B0F19).copy(alpha = 0.92f),
                shape = RoundedCornerShape(42.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, Color.White.copy(alpha = 0.12f)
                ),
                shadowElevation = 16.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Camera Button (Live capture frame to send to Gemini Live)
                    GeminiLiveCircleButton(
                        icon = Icons.Default.CameraAlt,
                        contentDescription = "Share Camera Frame",
                        isActive = false,
                        onClick = {
                            try {
                                cameraLauncher.launch(null)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Camera unavailable", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )

                    // 2. Screen Share Button (True Gemini Live Device Screen Sharing)
                    GeminiLiveCircleButton(
                        icon = Icons.AutoMirrored.Filled.ScreenShare,
                        contentDescription = if (isScreenSharing) "Stop Screen Sharing" else "Share Screen",
                        isActive = isScreenSharing,
                        activeColor = Color(0xFF00E5FF),
                        onClick = {
                            if (isScreenSharing) {
                                onStopScreenSharing()
                                feedbackMessage = "Screen sharing stopped"
                            } else {
                                showScreenShareConfirmDialog = true
                            }
                        }
                    )

                    // 3. Live Equalizer Capsule Pill (Center visualizer)
                    GeminiLiveEqualizerPill(
                        amplitude = amplitude,
                        liveState = liveState,
                        isMuted = isMuted
                    )

                    // 4. Microphone Mute / Unmute Button
                    val isDisconnected = liveState == AuraLiveState.IDLE || liveState == AuraLiveState.ERROR
                    GeminiLiveCircleButton(
                        icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (isMuted) "Unmute" else "Mute",
                        isActive = isMuted,
                        activeColor = Color(0xFFFF5252),
                        onClick = {
                            if (isDisconnected) {
                                onStartListening()
                            } else {
                                val nextMuted = !isMuted
                                isMuted = nextMuted
                                onToggleMute(nextMuted)
                                feedbackMessage = if (nextMuted) "Microphone muted" else "Microphone unmuted"
                            }
                        }
                    )

                    // 5. Close / Exit Button
                    GeminiLiveCircleButton(
                        icon = Icons.Default.Close,
                        contentDescription = "End Live Session",
                        isActive = false,
                        onClick = onCloseVoiceMode
                    )
                }
            }
        }

        // ── 6. Live Screen Sharing Glowing Banner ──────────────────────────────
        AnimatedVisibility(
            visible = isScreenSharing,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 18.dp, start = 20.dp, end = 20.dp)
        ) {
            Surface(
                color = Color(0xFF1E1010).copy(alpha = 0.95f),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.7f)),
                shadowElevation = 12.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF5252))
                        )
                        Text(
                            text = "Live Screen Sharing • ISHA is watching",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Surface(
                        color = Color(0xFFFF5252).copy(alpha = 0.25f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.clickable {
                            onStopScreenSharing()
                            feedbackMessage = "Screen sharing stopped"
                        }
                    ) {
                        Text(
                            text = "Stop",
                            color = Color(0xFFFF8A80),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // ── 7. Screen Share Confirmation Dialog (Gemini Style) ─────────────────
        if (showScreenShareConfirmDialog) {
            Dialog(onDismissRequest = { showScreenShareConfirmDialog = false }) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xFF131826),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.35f)),
                    shadowElevation = 24.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00E5FF).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ScreenShare,
                                contentDescription = null,
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Share screen with ISHA?",
                            style = AuraTypography.titleMedium.copy(
                                color = Color.White,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "ISHA will be able to see whatever is displayed on your screen in real time to assist you with apps, text, and tasks.",
                            style = AuraTypography.bodySmall.copy(
                                color = Color.White.copy(alpha = 0.72f),
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            ),
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showScreenShareConfirmDialog = false },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color.White.copy(alpha = 0.8f)
                                )
                            ) {
                                Text("Cancel", fontSize = 14.sp)
                            }

                            Button(
                                onClick = {
                                    showScreenShareConfirmDialog = false
                                    feedbackMessage = "Screen sharing active — show any screen to ISHA"
                                    val activity = context as? Activity
                                    activity?.moveTaskToBack(true)
                                    try {
                                        com.aura.assistant.AuraDynamicIsland.show(context, "screen_share")
                                    } catch (_: Exception) {}
                                    onSendScreenCapture()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF00E5FF),
                                    contentColor = Color(0xFF030712)
                                )
                            ) {
                                Text("Start", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Authentic 4-Point Gemini Star Icon with Google brand gradient.
 */
@Composable
private fun GeminiFourPointStar(
    modifier: Modifier = Modifier,
    pulseScale: Float = 1.0f
) {
    val infiniteTransition = rememberInfiniteTransition(label = "StarGlow")
    val starRot by infiniteTransition.animateFloat(
        initialValue = -8f, targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "StarRot"
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val r = (min(w, h) / 2f) * pulseScale

        rotate(starRot, Offset(cx, cy)) {
            // Astroid / 4-point cusp path
            val starPath = Path().apply {
                moveTo(cx, cy - r) // Top tip
                quadraticTo(cx, cy, cx + r, cy) // Right tip
                quadraticTo(cx, cy, cx, cy + r) // Bottom tip
                quadraticTo(cx, cy, cx - r, cy) // Left tip
                quadraticTo(cx, cy, cx, cy - r) // Back to top
                close()
            }

            // Google 4-color gradient (Blue, Red/Purple, Yellow, Green/Cyan)
            val starBrush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF4285F4), // Google Blue
                    Color(0xFFEA4335), // Google Red
                    Color(0xFFFBBC05), // Google Yellow
                    Color(0xFF34A853)  // Google Green
                ),
                start = Offset(cx - r, cy - r),
                end = Offset(cx + r, cy + r)
            )

            // Outer soft glow
            drawPath(
                path = starPath,
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF00E5FF).copy(alpha = 0.4f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = r * 1.5f
                )
            )

            // Star fill
            drawPath(
                path = starPath,
                brush = starBrush
            )
        }
    }
}

/**
 * 5-Bar Dynamic Equalizer Pill inside the center of the control bar.
 */
@Composable
private fun GeminiLiveEqualizerPill(
    amplitude: Float,
    liveState: AuraLiveState,
    isMuted: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "EqualizerAnim")

    val phase0 by infiniteTransition.animateFloat(
        initialValue = 0.2f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(420, easing = LinearEasing), RepeatMode.Reverse),
        label = "Eq0"
    )
    val phase1 by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(310, easing = LinearEasing), RepeatMode.Reverse),
        label = "Eq1"
    )
    val phase2 by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(480, easing = LinearEasing), RepeatMode.Reverse),
        label = "Eq2"
    )
    val phase3 by infiniteTransition.animateFloat(
        initialValue = 0.5f, targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(360, easing = LinearEasing), RepeatMode.Reverse),
        label = "Eq3"
    )
    val phase4 by infiniteTransition.animateFloat(
        initialValue = 0.2f, targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(440, easing = LinearEasing), RepeatMode.Reverse),
        label = "Eq4"
    )

    val phases = listOf(phase0, phase1, phase2, phase3, phase4)

    Box(
        modifier = modifier
            .size(width = 72.dp, height = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF030712))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val totalBars = 5
            val barWidth = 3.5.dp.toPx()
            val spacing = (size.width - (totalBars * barWidth)) / (totalBars - 1)
            val maxHeight = size.height * 0.85f
            val baseAmp = if (isMuted) 0.05f else amplitude.coerceIn(0.08f, 1.0f)
            val isIdle = liveState == AuraLiveState.IDLE || liveState == AuraLiveState.ERROR

            for (i in 0 until totalBars) {
                val x = i * (barWidth + spacing)
                val animatedHeightFactor = if (isIdle || isMuted) 0.15f else phases[i] * baseAmp
                val barH = (maxHeight * animatedHeightFactor).coerceAtLeast(3.dp.toPx())
                val y = (size.height - barH) / 2f

                val barColor = when {
                    isMuted -> Color(0xFFFF5252).copy(alpha = 0.4f)
                    liveState == AuraLiveState.THINKING -> Color(0xFFA855F7)
                    liveState == AuraLiveState.SPEAKING -> Color(0xFF38BDF8)
                    else -> Color(0xFF00E5FF)
                }

                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, barH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f, barWidth / 2f)
                )
            }
        }
    }
}

/**
 * Standard circle action button in the Gemini Live bottom bar.
 */
@Composable
private fun GeminiLiveCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    isActive: Boolean,
    activeColor: Color = Color(0xFF00E5FF),
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(
                if (isActive) activeColor.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isActive) activeColor else Color.White.copy(alpha = 0.88f),
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
fun ChatGPTVoiceScreen(
    liveState: AuraLiveState,
    amplitude: Float,
    liveTranscript: String,
    onCloseVoiceMode: () -> Unit,
    onToggleMute: (Boolean) -> Unit = {},
    onStartListening: () -> Unit = {},
    onSendCameraImage: (ByteArray) -> Unit = {},
    onSendScreenCapture: () -> Unit = {},
    isScreenSharing: Boolean = false,
    onStopScreenSharing: () -> Unit = {},
    userName: String = "Ansh",
    modifier: Modifier = Modifier
) = IshaVoiceScreen(
    liveState = liveState,
    amplitude = amplitude,
    liveTranscript = liveTranscript,
    onCloseVoiceMode = onCloseVoiceMode,
    onToggleMute = onToggleMute,
    onStartListening = onStartListening,
    onSendCameraImage = onSendCameraImage,
    onSendScreenCapture = onSendScreenCapture,
    isScreenSharing = isScreenSharing,
    onStopScreenSharing = onStopScreenSharing,
    userName = userName,
    modifier = modifier
)

