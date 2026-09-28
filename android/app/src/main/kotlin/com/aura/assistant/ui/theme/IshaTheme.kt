package com.aura.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Authentic ChatGPT Color Tokens (True Dark — matches ChatGPT exactly) ───
val ChatGptBackground      = Color(0xFF111111)   // True dark black (was #212121, too light)
val ChatGptSidebar         = Color(0xFF0D0D0D)   // Nearly pure black sidebar
val ChatGptCard            = Color(0xFF1E1E1E)   // Slightly lighter than bg for cards
val ChatGptInputBubble     = Color(0xFF1E1E1E)   // Input background
val ChatGptUserBubble      = Color(0xFF1E1E1E)   // User chat bubble
val ChatGptBorder          = Color(0xFF2D2D2D)   // Subtle border
val ChatGptBorderSubtle    = Color(0xFF1A1A1A)   // Very subtle dividers
val ChatGptTextPrimary     = Color(0xFFECECEC)   // Main white text
val ChatGptTextSecondary   = Color(0xFFB4B4B4)   // Secondary text
val ChatGptTextMuted       = Color(0xFF8E8E8E)   // Muted/placeholder text
val ChatGptAccentGreen     = Color(0xFF10A37F)   // ChatGPT brand green
val ChatGptAccentCyan      = Color(0xFF00E5FF)   // AURA cyan accent
val ChatGptVoicePureBlack  = Color(0xFF000000)   // Voice screen pure black

// ── Legacy Compatibility Palette ───────────────────────────────────────────
val AuraPitchBlack     = ChatGptBackground
val AuraObsidianGlass  = ChatGptSidebar
val AuraSurfaceCard    = ChatGptCard
val AuraBorderNeon     = ChatGptBorder

val AuraNeonCyan       = Color(0xFF00E5FF)
val AuraCyberPurple    = Color(0xFF7C4DFF)
val AuraElectricBlue   = Color(0xFF0284C7)
val AuraHoloEmerald    = Color(0xFF10A37F)
val AuraAlertRed       = Color(0xFFFF1744)
val AuraAmberGold      = Color(0xFFFFAB00)

val AuraTextPrimary    = ChatGptTextPrimary
val AuraTextSecondary  = ChatGptTextSecondary
val AuraTextMuted      = ChatGptTextMuted

private val AuraDarkColorScheme = darkColorScheme(
    primary = ChatGptAccentGreen,
    onPrimary = Color.White,
    secondary = ChatGptAccentCyan,
    onSecondary = Color.Black,
    tertiary = AuraHoloEmerald,
    background = ChatGptBackground,
    onBackground = ChatGptTextPrimary,
    surface = ChatGptSidebar,
    onSurface = ChatGptTextPrimary,
    surfaceVariant = ChatGptCard,
    onSurfaceVariant = ChatGptTextSecondary,
    outline = ChatGptBorder,
    error = AuraAlertRed
)

val AuraTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 28.sp,
        letterSpacing = 0.5.sp,
        color = AuraTextPrimary
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        letterSpacing = 0.3.sp,
        color = AuraTextPrimary
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        letterSpacing = 0.2.sp,
        color = AuraTextPrimary
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        color = AuraTextPrimary
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = AuraTextSecondary
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        color = AuraNeonCyan
    )
)

val IshaShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp)
)

val AuraShapes = IshaShapes
val IshaTypography = AuraTypography
val IshaDarkColorScheme = AuraDarkColorScheme

@Composable
fun IshaTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = IshaDarkColorScheme,
        typography = IshaTypography,
        shapes = IshaShapes,
        content = content
    )
}

/** Backward compatibility alias for AuraTheme */
@Composable
fun AuraTheme(
    content: @Composable () -> Unit
) = IshaTheme(content = content)

