package com.aura.assistant.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.aura.assistant.auth.UserProfile
import com.aura.assistant.config.ApiKeyManager
import com.aura.assistant.data.IshaPersonaConfig
import com.aura.assistant.data.IshaThemePalette
import com.aura.assistant.data.PersonaArchetype
import com.aura.assistant.data.SalutationTitle
import com.aura.assistant.data.UserAgeGroup
import com.aura.assistant.ui.theme.*

// ── Settings Sub-Pages Enum ──────────────────────────────────────────────────
enum class SettingsPage(val title: String, val subtitle: String) {
    MAIN("Settings", "ISHA 2.0 • AI Companion"),
    PERSONA_STUDIO("AI Persona & Profile", "Custom instructions & tone"),
    THEME_APPEARANCE("Theme & Glow Studio", "Personalize accent colors"),
    VOICE_ACTIVATION("Voice & Activation", "Persona voice & waking triggers"),
    LANGUAGE_DISPLAY("Language & Display", "Recognition language & text size"),
    CONNECTED_DEVICES("Connected Devices", "Cross-device pairing & remote control"),
    PERMISSIONS_PAGE("Permissions & Status", "Live Android permission status"),
    ABOUT_PAGE("About ISHA", "Architecture & pipeline specs")
}

// ── Settings Data ─────────────────────────────────────────────────────────────
data class IshaSettingsState(
    val isWakeWordEnabled: Boolean = true,
    val isShakeEnabled: Boolean = true,
    val selectedVoice: String = "Aoede",
    val selectedLanguage: String = "Auto-detect",
    val textSize: String = "Default",
    val isSoundEnabled: Boolean = true,
    val isMessageSpeakEnabled: Boolean = false,
    val isCallAnnouncementEnabled: Boolean = true
)

typealias AuraSettingsState = IshaSettingsState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IshaSettingsSheet(
    isWakeWordEnabled: Boolean,
    isShakeEnabled: Boolean,
    isMessageSpeakEnabled: Boolean = false,
    isCallAnnouncementEnabled: Boolean = true,
    userProfile: UserProfile = UserProfile(),
    selectedVoice: String = "Aoede",
    selectedLanguage: String = "Auto-detect",
    textSize: String = "Default",
    isSoundEnabled: Boolean = true,
    personaConfig: IshaPersonaConfig = IshaPersonaConfig(),
    onAgeGroupChange: (UserAgeGroup) -> Unit = {},
    onSalutationChange: (SalutationTitle, String) -> Unit = { _, _ -> },
    onArchetypeChange: (PersonaArchetype) -> Unit = {},
    onThemePaletteChange: (String) -> Unit = {},
    onCustomAboutMeChange: (String) -> Unit = {},
    onCustomResponseStyleChange: (String) -> Unit = {},
    onWakeWordToggle: (Boolean) -> Unit,
    onShakeToggle: (Boolean) -> Unit,
    onMessageSpeakToggle: (Boolean) -> Unit = {},
    onCallAnnouncementToggle: (Boolean) -> Unit = {},
    onVoiceChange: (String) -> Unit = {},
    onLanguageChange: (String) -> Unit = {},
    onTextSizeChange: (String) -> Unit = {},
    onSoundToggle: (Boolean) -> Unit = {},
    onClearAllChats: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenApiKeyEntry: () -> Unit = {},
    onLogout: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var currentPage by remember { mutableStateOf(SettingsPage.MAIN) }

    var showClearConfirm by remember { mutableStateOf(false) }
    var showCustomSalutationDialog by remember { mutableStateOf(false) }
    var customSalutationInput by remember { mutableStateOf(personaConfig.customTitle) }
    var deviceToRename by remember { mutableStateOf<com.aura.assistant.sync.IshaDeviceInfo?>(null) }
    var newDeviceAliasInput by remember { mutableStateOf("") }

    val voices = listOf("Aoede", "Puck", "Charon", "Fenrir", "Kore")
    val languages = listOf("Auto-detect", "English", "Hindi", "Hinglish")
    val textSizes = listOf("Small", "Default", "Large")

    val activeTheme = personaConfig.themePalette
    val themePrimary = activeTheme.primaryColor
    val themeSecondary = activeTheme.secondaryColor

    // Back handling: If on subpage, go back to main settings; if on main, dismiss sheet
    BackHandler {
        if (currentPage != SettingsPage.MAIN) {
            currentPage = SettingsPage.MAIN
        } else {
            onDismiss()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = ChatGptBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = currentPage.title,
                            style = AuraTypography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = ChatGptTextPrimary
                            )
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(themePrimary)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = currentPage.subtitle,
                                style = AuraTypography.bodySmall.copy(
                                    color = themePrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (currentPage != SettingsPage.MAIN) {
                                currentPage = SettingsPage.MAIN
                            } else {
                                onDismiss()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = ChatGptTextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ChatGptBackground,
                    titleContentColor = ChatGptTextPrimary,
                    navigationIconContentColor = ChatGptTextPrimary
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentPage) {
                SettingsPage.MAIN -> {
                    MainSettingsView(
                        userProfile = userProfile,
                        personaConfig = personaConfig,
                        themePrimary = themePrimary,
                        activeTheme = activeTheme,
                        selectedVoice = selectedVoice,
                        selectedLanguage = selectedLanguage,
                        textSize = textSize,
                        isWakeWordEnabled = isWakeWordEnabled,
                        onNavigate = { page -> currentPage = page },
                        onOpenApiKey = {
                            onDismiss()
                            onOpenApiKeyEntry()
                        },
                        onOpenDiagnostics = {
                            onDismiss()
                            onOpenDiagnostics()
                        },
                        onClearChats = { showClearConfirm = true },
                        onLogout = {
                            onDismiss()
                            onLogout()
                        },
                        onDone = onDismiss
                    )
                }

                SettingsPage.PERSONA_STUDIO -> {
                    PersonaStudioSubPage(
                        personaConfig = personaConfig,
                        userName = userProfile.name,
                        themePrimary = themePrimary,
                        themeSecondary = themeSecondary,
                        onAgeGroupChange = onAgeGroupChange,
                        onSalutationChange = onSalutationChange,
                        onArchetypeChange = onArchetypeChange,
                        onCustomAboutMeChange = onCustomAboutMeChange,
                        onCustomResponseStyleChange = onCustomResponseStyleChange,
                        onOpenCustomSalutation = { showCustomSalutationDialog = true },
                        onBack = { currentPage = SettingsPage.MAIN }
                    )
                }

                SettingsPage.THEME_APPEARANCE -> {
                    ThemeAppearanceSubPage(
                        personaConfig = personaConfig,
                        activeTheme = activeTheme,
                        themePrimary = themePrimary,
                        themeSecondary = themeSecondary,
                        onThemePaletteChange = onThemePaletteChange,
                        onBack = { currentPage = SettingsPage.MAIN }
                    )
                }

                SettingsPage.VOICE_ACTIVATION -> {
                    VoiceActivationSubPage(
                        voices = voices,
                        selectedVoice = selectedVoice,
                        isWakeWordEnabled = isWakeWordEnabled,
                        isShakeEnabled = isShakeEnabled,
                        isSoundEnabled = isSoundEnabled,
                        isCallAnnouncementEnabled = isCallAnnouncementEnabled,
                        themePrimary = themePrimary,
                        onVoiceChange = onVoiceChange,
                        onWakeWordToggle = onWakeWordToggle,
                        onShakeToggle = onShakeToggle,
                        onSoundToggle = onSoundToggle,
                        onCallAnnouncementToggle = onCallAnnouncementToggle,
                        onBack = { currentPage = SettingsPage.MAIN }
                    )
                }

                SettingsPage.LANGUAGE_DISPLAY -> {
                    LanguageDisplaySubPage(
                        languages = languages,
                        selectedLanguage = selectedLanguage,
                        textSizes = textSizes,
                        textSize = textSize,
                        themePrimary = themePrimary,
                        onLanguageChange = onLanguageChange,
                        onTextSizeChange = onTextSizeChange,
                        onBack = { currentPage = SettingsPage.MAIN }
                    )
                }

                SettingsPage.CONNECTED_DEVICES -> {
                    ConnectedDevicesSubPage(
                        themePrimary = themePrimary,
                        onRenameDevice = { dev ->
                            deviceToRename = dev
                            newDeviceAliasInput = dev.deviceAlias.ifBlank { dev.deviceName }
                        },
                        onBack = { currentPage = SettingsPage.MAIN }
                    )
                }

                SettingsPage.PERMISSIONS_PAGE -> {
                    PermissionsSubPage(
                        themePrimary = themePrimary,
                        onBack = { currentPage = SettingsPage.MAIN }
                    )
                }

                SettingsPage.ABOUT_PAGE -> {
                    AboutAppSubPage(
                        themePrimary = themePrimary,
                        onBack = { currentPage = SettingsPage.MAIN }
                    )
                }
            }
        }
    }

    // ── Custom Salutation Dialog ────────────────────────────────────────────────
    if (showCustomSalutationDialog) {
        AlertDialog(
            onDismissRequest = { showCustomSalutationDialog = false },
            containerColor = ChatGptCard,
            title = {
                Text("Custom Salutation / Name", color = ChatGptTextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text(
                        "Type the exact title or nickname you want ISHA to address you with:",
                        color = ChatGptTextSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customSalutationInput,
                        onValueChange = { customSalutationInput = it },
                        singleLine = true,
                        placeholder = { Text("e.g. Captain, Ansh Bhai, Chachu...") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = ChatGptTextPrimary,
                            unfocusedTextColor = ChatGptTextPrimary,
                            focusedBorderColor = themePrimary,
                            unfocusedBorderColor = ChatGptBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clean = customSalutationInput.trim()
                    if (clean.isNotBlank()) {
                        onSalutationChange(SalutationTitle.CUSTOM, clean)
                    }
                    showCustomSalutationDialog = false
                }) {
                    Text("Save", color = themePrimary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomSalutationDialog = false }) {
                    Text("Cancel", color = ChatGptTextMuted)
                }
            }
        )
    }

    // ── Rename Device Dialog ───────────────────────────────────────────────────
    if (deviceToRename != null) {
        val dev = deviceToRename!!
        AlertDialog(
            onDismissRequest = { deviceToRename = null },
            containerColor = ChatGptCard,
            title = {
                Text("Rename Device", color = ChatGptTextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text(
                        "Give this phone a short name so you can command it easily (e.g. \"Phone A\", \"Phone B\", \"Office Phone\").",
                        color = ChatGptTextSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newDeviceAliasInput,
                        onValueChange = { newDeviceAliasInput = it },
                        singleLine = true,
                        placeholder = { Text("Device Name") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = ChatGptTextPrimary,
                            unfocusedTextColor = ChatGptTextPrimary,
                            focusedBorderColor = ChatGptAccentGreen,
                            unfocusedBorderColor = ChatGptBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clean = newDeviceAliasInput.trim()
                    if (clean.isNotBlank()) {
                        com.aura.assistant.sync.IshaDeviceRegistry.updateDeviceAlias(context, dev.deviceId, clean)
                    }
                    deviceToRename = null
                }) {
                    Text("Save", color = ChatGptAccentGreen, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToRename = null }) {
                    Text("Cancel", color = ChatGptTextMuted)
                }
            }
        )
    }

    // ── Clear Chats Confirmation Dialog ───────────────────────────────────────
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = ChatGptCard,
            title = {
                Text("Clear All Chats?", color = ChatGptTextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "This will permanently delete all your conversations. This cannot be undone.",
                    color = ChatGptTextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    onClearAllChats()
                    onDismiss()
                }) {
                    Text("Delete All", color = AuraAlertRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel", color = ChatGptTextSecondary)
                }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. MAIN SETTINGS ROOT VIEW (CATEGORIZED, NESTED & MODERN)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun MainSettingsView(
    userProfile: UserProfile,
    personaConfig: IshaPersonaConfig,
    themePrimary: Color,
    activeTheme: IshaThemePalette,
    selectedVoice: String,
    selectedLanguage: String,
    textSize: String,
    isWakeWordEnabled: Boolean,
    onNavigate: (SettingsPage) -> Unit,
    onOpenApiKey: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onClearChats: () -> Unit,
    onLogout: () -> Unit,
    onDone: () -> Unit
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val userCustomKey = remember { ApiKeyManager.getApiKey(context) }
    val hasCustomKeySet = userCustomKey.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 18.dp)
            .padding(bottom = 36.dp)
    ) {
        // ── User Account Card ───────────────────────────────────────────
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = ChatGptCard,
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color(userProfile.avatarColorHex)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = userProfile.avatarInitial,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = userProfile.name,
                        style = AuraTypography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = ChatGptTextPrimary,
                            fontSize = 15.sp
                        )
                    )
                    Text(
                        text = userProfile.email.ifBlank { "Personal Account • ${userProfile.provider}" },
                        style = AuraTypography.bodySmall.copy(
                            color = ChatGptTextMuted,
                            fontSize = 12.sp
                        )
                    )
                }
                Button(
                    onClick = onLogout,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2E1A1A),
                        contentColor = Color(0xFFFF5252)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Sign Out", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── Category 1: AI & INTELLIGENCE ────────────────────────────────
        SettingsSectionHeader(icon = Icons.Default.Psychology, title = "AI & Intelligence", tint = themePrimary)

        SettingsNavigationRow(
            icon = Icons.Default.Face,
            iconTint = themePrimary,
            title = "AI Persona & Custom Instructions",
            subtitle = "${personaConfig.archetype.title.substringBefore(" (")} • ${personaConfig.resolveSalutation(userProfile.name)} • ${personaConfig.ageGroup.title}",
            badgeText = personaConfig.ageGroup.ageRange,
            onClick = { onNavigate(SettingsPage.PERSONA_STUDIO) }
        )

        Spacer(modifier = Modifier.height(10.dp))

        SettingsNavigationRow(
            icon = Icons.Default.VpnKey,
            iconTint = if (hasCustomKeySet) ChatGptAccentGreen else AuraAmberGold,
            title = "ISHA Intelligence Key",
            subtitle = if (hasCustomKeySet) ApiKeyManager.maskKey(userCustomKey) else "Isha Cloud (Zero-Key Active)",
            badgeText = if (hasCustomKeySet) "Custom" else "Free",
            onClick = onOpenApiKey
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ── Category 2: LOOK & FEEL ──────────────────────────────────────
        SettingsSectionHeader(icon = Icons.Default.Palette, title = "Look & Feel", tint = themePrimary)

        SettingsNavigationRow(
            icon = Icons.Default.AutoAwesome,
            iconTint = themePrimary,
            title = "Theme & Accent Color",
            subtitle = activeTheme.displayName,
            trailingColorIndicator = themePrimary,
            onClick = { onNavigate(SettingsPage.THEME_APPEARANCE) }
        )

        Spacer(modifier = Modifier.height(10.dp))

        SettingsNavigationRow(
            icon = Icons.Default.Language,
            iconTint = AuraCyberPurple,
            title = "Language & Display",
            subtitle = "$selectedLanguage • Text size: $textSize",
            onClick = { onNavigate(SettingsPage.LANGUAGE_DISPLAY) }
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ── Category 3: VOICE & ASSISTANT ────────────────────────────────
        SettingsSectionHeader(icon = Icons.Default.RecordVoiceOver, title = "Voice & Speech", tint = themePrimary)

        SettingsNavigationRow(
            icon = Icons.Default.Mic,
            iconTint = ChatGptAccentGreen,
            title = "Voice & Speech Activation",
            subtitle = "Voice: $selectedVoice • Wake word: ${if (isWakeWordEnabled) "Enabled" else "Off"}",
            badgeText = if (isWakeWordEnabled) "Active" else "Muted",
            onClick = { onNavigate(SettingsPage.VOICE_ACTIVATION) }
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ── Category 4: SYSTEM & HARDWARE ────────────────────────────────
        SettingsSectionHeader(icon = Icons.Default.Settings, title = "System & Hardware", tint = themePrimary)

        SettingsNavigationRow(
            icon = Icons.Default.Devices,
            iconTint = ChatGptAccentGreen,
            title = "Connected Devices & Cross-Control",
            subtitle = "Pair & command remote phones with ISHA",
            onClick = { onNavigate(SettingsPage.CONNECTED_DEVICES) }
        )

        Spacer(modifier = Modifier.height(10.dp))

        SettingsNavigationRow(
            icon = Icons.Default.Accessibility,
            iconTint = AuraAmberGold,
            title = "Permissions & System Access",
            subtitle = "Accessibility, Mic, Overlay, Notifications",
            onClick = { onNavigate(SettingsPage.PERMISSIONS_PAGE) }
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ── Category 5: SYSTEM & ABOUT ───────────────────────────────────
        SettingsSectionHeader(icon = Icons.Default.Info, title = "About & Diagnostics", tint = themePrimary)

        SettingsNavigationRow(
            icon = Icons.Default.Build,
            iconTint = ChatGptAccentGreen,
            title = "ISHA 2.0 Diagnostics",
            subtitle = "Live audio telemetry, mic ownership & VAD",
            onClick = onOpenDiagnostics
        )

        Spacer(modifier = Modifier.height(10.dp))

        SettingsNavigationRow(
            icon = Icons.Default.Info,
            iconTint = ChatGptTextMuted,
            title = "About ISHA & Specifications",
            subtitle = "v5.4.0 • Autonomous Assistant • By Ansh",
            onClick = { onNavigate(SettingsPage.ABOUT_PAGE) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Clear All Chats Button (Danger Row)
        Surface(
            color = AuraAlertRed.copy(alpha = 0.08f),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, AuraAlertRed.copy(alpha = 0.35f)),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClearChats)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = null,
                    tint = AuraAlertRed,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Clear All Chats",
                        style = AuraTypography.titleMedium.copy(color = AuraAlertRed, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = "Permanently delete all conversation history",
                        style = AuraTypography.bodySmall.copy(color = AuraAlertRed.copy(alpha = 0.7f), fontSize = 11.sp)
                    )
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = AuraAlertRed.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Done button
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = themePrimary,
                contentColor = Color.Black
            )
        ) {
            Text(
                text = "Close",
                style = AuraTypography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.Black
                )
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. SUB-PAGE: AI PERSONA & PROFILE STUDIO
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun PersonaStudioSubPage(
    personaConfig: IshaPersonaConfig,
    userName: String,
    themePrimary: Color,
    themeSecondary: Color,
    onAgeGroupChange: (UserAgeGroup) -> Unit,
    onSalutationChange: (SalutationTitle, String) -> Unit,
    onArchetypeChange: (PersonaArchetype) -> Unit,
    onCustomAboutMeChange: (String) -> Unit,
    onCustomResponseStyleChange: (String) -> Unit,
    onOpenCustomSalutation: () -> Unit,
    onBack: () -> Unit
) {
    val scrollState = rememberScrollState()
    val previewGreeting = remember(personaConfig, userName) {
        personaConfig.getPreviewGreeting(userName.ifBlank { "Ansh" })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 18.dp)
            .padding(bottom = 36.dp)
    ) {
        // Live Persona Preview Card
        Surface(
            color = ChatGptCard,
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(
                width = 1.5.dp,
                brush = Brush.horizontalGradient(listOf(themePrimary, themeSecondary))
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(themePrimary.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = themePrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "LIVE PERSONA PREVIEW",
                        style = AuraTypography.labelSmall.copy(
                            color = themePrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${personaConfig.ageGroup.title} • ${personaConfig.resolveSalutation(userName)}",
                        style = AuraTypography.bodySmall.copy(
                            color = ChatGptTextMuted,
                            fontSize = 10.sp
                        )
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "\"$previewGreeting\"",
                    style = AuraTypography.bodyMedium.copy(
                        color = ChatGptTextPrimary,
                        fontSize = 13.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        lineHeight = 19.sp
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 1. Age Group Selector
        Surface(
            color = ChatGptCard,
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Age Group & Tone Model",
                    style = AuraTypography.titleMedium.copy(color = ChatGptTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "Adapts vocabulary, complexity, and storytelling automatically",
                    style = AuraTypography.bodySmall.copy(color = ChatGptTextMuted, fontSize = 11.sp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UserAgeGroup.entries.forEach { ageGroup ->
                        val isSelected = ageGroup == personaConfig.ageGroup
                        Surface(
                            color = if (isSelected) themePrimary.copy(alpha = 0.12f) else ChatGptBackground,
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) themePrimary else ChatGptBorder
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAgeGroupChange(ageGroup) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { onAgeGroupChange(ageGroup) },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = themePrimary,
                                        unselectedColor = ChatGptTextMuted
                                    ),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = ageGroup.title,
                                            style = AuraTypography.bodyMedium.copy(
                                                color = if (isSelected) themePrimary else ChatGptTextPrimary,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(if (isSelected) themePrimary.copy(alpha = 0.2f) else ChatGptBorderSubtle)
                                                .padding(horizontal = 5.dp, vertical = 1.dp)
                                        ) {
                                            Text(
                                                text = ageGroup.ageRange,
                                                style = AuraTypography.labelSmall.copy(
                                                    color = if (isSelected) themePrimary else ChatGptTextMuted,
                                                    fontSize = 9.sp
                                                )
                                            )
                                        }
                                    }
                                    Text(
                                        text = ageGroup.subtitle,
                                        style = AuraTypography.bodySmall.copy(
                                            color = if (isSelected) ChatGptTextSecondary else ChatGptTextMuted,
                                            fontSize = 11.sp
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 2. Salutation Selector
        Surface(
            color = ChatGptCard,
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "How Should ISHA Address You?",
                    style = AuraTypography.titleMedium.copy(color = ChatGptTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "Choose your title (Not everyone prefers 'Boss')",
                    style = AuraTypography.bodySmall.copy(color = ChatGptTextMuted, fontSize = 11.sp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val titles = listOf(
                        SalutationTitle.BOSS to "👑 Boss",
                        SalutationTitle.DOST to "🤝 Dost",
                        SalutationTitle.BHAI to "🤜 Bhai",
                        SalutationTitle.SIR_MAAM to "🎓 Sir",
                        SalutationTitle.NAME_ONLY to "👤 Name"
                    )
                    titles.forEach { (sal, label) ->
                        val isSelected = personaConfig.salutationTitle == sal
                        Surface(
                            color = if (isSelected) themePrimary else ChatGptBackground,
                            shape = RoundedCornerShape(20.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) themePrimary else ChatGptBorder
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onSalutationChange(sal, personaConfig.customTitle) }
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    style = AuraTypography.bodySmall.copy(
                                        fontSize = 10.sp,
                                        color = if (isSelected) Color.Black else ChatGptTextSecondary,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    ),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    // Custom Button
                    val isCustom = personaConfig.salutationTitle == SalutationTitle.CUSTOM
                    Surface(
                        color = if (isCustom) themePrimary else ChatGptBackground,
                        shape = RoundedCornerShape(20.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isCustom) themePrimary else ChatGptBorder
                        ),
                        modifier = Modifier
                            .size(36.dp)
                            .clickable(onClick = onOpenCustomSalutation)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Custom",
                                tint = if (isCustom) Color.Black else ChatGptTextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 3. Personality Archetype
        Surface(
            color = ChatGptCard,
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Personality Archetype",
                    style = AuraTypography.titleMedium.copy(color = ChatGptTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "Choose ISHA's core conversational and working stance",
                    style = AuraTypography.bodySmall.copy(color = ChatGptTextMuted, fontSize = 11.sp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PersonaArchetype.entries.forEach { arch ->
                        val isSelected = arch == personaConfig.archetype
                        Surface(
                            color = if (isSelected) themePrimary.copy(alpha = 0.12f) else ChatGptBackground,
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) themePrimary else ChatGptBorder
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onArchetypeChange(arch) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { onArchetypeChange(arch) },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = themePrimary,
                                        unselectedColor = ChatGptTextMuted
                                    ),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = arch.title,
                                        style = AuraTypography.bodyMedium.copy(
                                            color = if (isSelected) themePrimary else ChatGptTextPrimary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                    )
                                    Text(
                                        text = arch.subtitle,
                                        style = AuraTypography.bodySmall.copy(
                                            color = if (isSelected) ChatGptTextSecondary else ChatGptTextMuted,
                                            fontSize = 11.sp
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 4. Custom Profile Instructions (ChatGPT & Gemini style)
        Surface(
            color = ChatGptCard,
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = themePrimary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Custom Profile Instructions",
                            style = AuraTypography.titleMedium.copy(color = ChatGptTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "ChatGPT & Gemini style persistent instructions",
                            style = AuraTypography.bodySmall.copy(color = ChatGptTextMuted, fontSize = 11.sp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "What would you like ISHA to know about you?",
                    style = AuraTypography.bodyMedium.copy(color = ChatGptTextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                )
                Spacer(modifier = Modifier.height(6.dp))
                var aboutMeInput by remember(personaConfig.customAboutMe) { mutableStateOf(personaConfig.customAboutMe) }
                OutlinedTextField(
                    value = aboutMeInput,
                    onValueChange = {
                        aboutMeInput = it
                        onCustomAboutMeChange(it)
                    },
                    placeholder = { Text("e.g. CS Student hoon, Android apps banata hoon, coding aur AI pasand hai...", fontSize = 12.sp, color = ChatGptTextMuted) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ChatGptTextPrimary,
                        unfocusedTextColor = ChatGptTextPrimary,
                        focusedBorderColor = themePrimary,
                        unfocusedBorderColor = ChatGptBorder
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "How would you like ISHA to respond?",
                    style = AuraTypography.bodyMedium.copy(color = ChatGptTextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                )
                Spacer(modifier = Modifier.height(6.dp))
                var responseStyleInput by remember(personaConfig.customResponseStyle) { mutableStateOf(personaConfig.customResponseStyle) }
                OutlinedTextField(
                    value = responseStyleInput,
                    onValueChange = {
                        responseStyleInput = it
                        onCustomResponseStyleChange(it)
                    },
                    placeholder = { Text("e.g. Crisp aur short answers do, bullet points me samjhao, warm Hinglish...", fontSize = 12.sp, color = ChatGptTextMuted) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ChatGptTextPrimary,
                        unfocusedTextColor = ChatGptTextPrimary,
                        focusedBorderColor = themePrimary,
                        unfocusedBorderColor = ChatGptBorder
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = themePrimary,
                contentColor = Color.Black
            )
        ) {
            Text("Save & Return to Settings", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. SUB-PAGE: THEME & APPEARANCE
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ThemeAppearanceSubPage(
    personaConfig: IshaPersonaConfig,
    activeTheme: IshaThemePalette,
    themePrimary: Color,
    themeSecondary: Color,
    onThemePaletteChange: (String) -> Unit,
    onBack: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 18.dp)
            .padding(bottom = 36.dp)
    ) {
        Surface(
            color = ChatGptCard,
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, themePrimary.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Glow Accent Theme",
                        style = AuraTypography.bodyMedium.copy(color = ChatGptTextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(themePrimary.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = activeTheme.displayName,
                            style = AuraTypography.labelSmall.copy(color = themePrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IshaThemePalette.entries.forEach { palette ->
                        val isSelected = palette.id == personaConfig.themePaletteId
                        Surface(
                            color = if (isSelected) palette.primaryColor.copy(alpha = 0.2f) else ChatGptBackground,
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) palette.primaryColor else ChatGptBorder
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onThemePaletteChange(palette.id) }
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(palette.primaryColor)
                                        .border(
                                            width = if (isSelected) 2.dp else 0.dp,
                                            color = Color.White,
                                            shape = CircleShape
                                        )
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = palette.displayName.substringBefore(" "),
                                    style = AuraTypography.bodySmall.copy(
                                        fontSize = 10.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) palette.primaryColor else ChatGptTextMuted
                                    ),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Demo Card
        Surface(
            color = ChatGptCard,
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, Brush.horizontalGradient(listOf(themePrimary, themeSecondary))),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(themePrimary)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("PALETTE GLOW PREVIEW", style = AuraTypography.labelSmall.copy(color = themePrimary, fontWeight = FontWeight.Bold, letterSpacing = 1.sp))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "All chat elements, buttons, indicator dots, switches, and cards automatically adopt ${activeTheme.displayName}.",
                    style = AuraTypography.bodySmall.copy(color = ChatGptTextSecondary, fontSize = 12.sp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = themePrimary,
                contentColor = Color.Black
            )
        ) {
            Text("Done", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. SUB-PAGE: VOICE & ACTIVATION
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun VoiceActivationSubPage(
    voices: List<String>,
    selectedVoice: String,
    isWakeWordEnabled: Boolean,
    isShakeEnabled: Boolean,
    isSoundEnabled: Boolean,
    isCallAnnouncementEnabled: Boolean,
    themePrimary: Color,
    onVoiceChange: (String) -> Unit,
    onWakeWordToggle: (Boolean) -> Unit,
    onShakeToggle: (Boolean) -> Unit,
    onSoundToggle: (Boolean) -> Unit,
    onCallAnnouncementToggle: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 18.dp)
            .padding(bottom = 36.dp)
    ) {
        // Voice Model Selector
        Surface(
            color = ChatGptCard,
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "ISHA Neural Voice Persona",
                    style = AuraTypography.titleMedium.copy(color = ChatGptTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "Select active female/male acoustic profile (Default: Aoede)",
                    style = AuraTypography.bodySmall.copy(color = ChatGptTextMuted, fontSize = 11.sp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    voices.forEach { voice ->
                        val isSelected = voice == selectedVoice
                        Surface(
                            color = if (isSelected) themePrimary else ChatGptBackground,
                            shape = RoundedCornerShape(20.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) themePrimary else ChatGptBorder
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onVoiceChange(voice) }
                        ) {
                            Text(
                                text = voice,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                                style = AuraTypography.bodyMedium.copy(
                                    fontSize = 11.sp,
                                    color = if (isSelected) Color.Black else ChatGptTextSecondary,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                ),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Wake Word
        SettingsToggleRow(
            icon = Icons.Default.Mic,
            iconTint = ChatGptAccentGreen,
            title = "Wake Word — \"Hey ISHA\"",
            subtitle = if (isWakeWordEnabled) "Mic active in background (OS green dot shown)" else "Disabled (Mic closed, no green dot)",
            checked = isWakeWordEnabled,
            onCheckedChange = onWakeWordToggle
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Shake to Activate
        SettingsToggleRow(
            icon = Icons.Default.Smartphone,
            iconTint = AuraCyberPurple,
            title = "Shake to Activate",
            subtitle = "Double-shake phone to open (Uses accelerometer, no green dot)",
            checked = isShakeEnabled,
            onCheckedChange = onShakeToggle
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Sound Feedback
        SettingsToggleRow(
            icon = Icons.Default.VolumeUp,
            iconTint = AuraAmberGold,
            title = "Activation Sound",
            subtitle = "Play a chime when ISHA wakes up",
            checked = isSoundEnabled,
            onCheckedChange = onSoundToggle
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Call Announcement
        SettingsToggleRow(
            icon = Icons.Default.PhoneCallback,
            iconTint = Color(0xFF10A37F),
            title = "Caller Name Announcement",
            subtitle = if (isCallAnnouncementEnabled) "Incoming call aane par caller ka naam bol kar bataye" else "Muted (Call aane par naam nahi bolegi)",
            checked = isCallAnnouncementEnabled,
            onCheckedChange = onCallAnnouncementToggle
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = themePrimary,
                contentColor = Color.Black
            )
        ) {
            Text("Done", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. SUB-PAGE: LANGUAGE & DISPLAY
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun LanguageDisplaySubPage(
    languages: List<String>,
    selectedLanguage: String,
    textSizes: List<String>,
    textSize: String,
    themePrimary: Color,
    onLanguageChange: (String) -> Unit,
    onTextSizeChange: (String) -> Unit,
    onBack: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 18.dp)
            .padding(bottom = 36.dp)
    ) {
        // Recognition Language
        Surface(
            color = ChatGptCard,
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Recognition Language",
                    style = AuraTypography.titleMedium.copy(color = ChatGptTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "Preferred voice understanding mode",
                    style = AuraTypography.bodySmall.copy(color = ChatGptTextMuted, fontSize = 11.sp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    languages.forEach { lang ->
                        val isSelected = lang == selectedLanguage
                        Surface(
                            color = if (isSelected) themePrimary else ChatGptBackground,
                            shape = RoundedCornerShape(20.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) themePrimary else ChatGptBorder
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onLanguageChange(lang) }
                        ) {
                            Text(
                                text = lang,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                                style = AuraTypography.bodyMedium.copy(
                                    fontSize = 11.sp,
                                    color = if (isSelected) Color.Black else ChatGptTextSecondary,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                ),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Chat Text Size
        Surface(
            color = ChatGptCard,
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Chat Text Size",
                    style = AuraTypography.titleMedium.copy(color = ChatGptTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "Font scale across conversations",
                    style = AuraTypography.bodySmall.copy(color = ChatGptTextMuted, fontSize = 11.sp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    textSizes.forEach { size ->
                        val isSelected = size == textSize
                        Surface(
                            color = if (isSelected) themePrimary else ChatGptBackground,
                            shape = RoundedCornerShape(20.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) themePrimary else ChatGptBorder
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onTextSizeChange(size) }
                        ) {
                            Text(
                                text = size,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                style = AuraTypography.bodyMedium.copy(
                                    fontSize = if (size == "Small") 10.sp else if (size == "Large") 14.sp else 12.sp,
                                    color = if (isSelected) Color.Black else ChatGptTextSecondary,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                ),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = themePrimary,
                contentColor = Color.Black
            )
        ) {
            Text("Done", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 6. SUB-PAGE: CONNECTED DEVICES & CROSS-CONTROL
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ConnectedDevicesSubPage(
    themePrimary: Color,
    onRenameDevice: (com.aura.assistant.sync.IshaDeviceInfo) -> Unit,
    onBack: () -> Unit
) {
    val scrollState = rememberScrollState()
    val allRegisteredDevices by com.aura.assistant.sync.IshaDeviceRegistry.knownDevices.collectAsState()
    val currentDeviceId = remember { com.aura.assistant.sync.IshaDeviceRegistry.getDeviceId() }
    val currentAlias = remember { com.aura.assistant.sync.IshaDeviceRegistry.getDeviceAlias() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 18.dp)
            .padding(bottom = 36.dp)
    ) {
        Surface(
            color = ChatGptCard,
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Devices linked to this account can be commanded remotely by ISHA (e.g. \"Phone A par torch on karo\"). Tap ✏️ to rename.",
                    style = AuraTypography.bodySmall.copy(color = ChatGptTextMuted, fontSize = 11.sp)
                )

                if (allRegisteredDevices.isEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(ChatGptAccentGreen.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Smartphone, contentDescription = null, tint = ChatGptAccentGreen, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = currentAlias,
                                style = AuraTypography.bodyMedium.copy(color = ChatGptTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            )
                            Text(
                                text = "This Device • Ready for sync",
                                style = AuraTypography.bodySmall.copy(color = ChatGptAccentGreen, fontSize = 11.sp)
                            )
                        }
                        IconButton(
                            onClick = {
                                onRenameDevice(
                                    com.aura.assistant.sync.IshaDeviceInfo(
                                        deviceId = currentDeviceId,
                                        deviceAlias = currentAlias,
                                        isCurrentDevice = true
                                    )
                                )
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Rename", tint = ChatGptTextMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                } else {
                    allRegisteredDevices.forEach { dev ->
                        val isMe = dev.deviceId == currentDeviceId || dev.isCurrentDevice
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isMe) ChatGptBackground.copy(alpha = 0.5f) else Color.Transparent)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(if (isMe) ChatGptAccentGreen.copy(alpha = 0.15f) else ChatGptBorderSubtle),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Smartphone,
                                    contentDescription = null,
                                    tint = if (isMe) ChatGptAccentGreen else ChatGptTextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = dev.deviceAlias.ifBlank { dev.deviceName },
                                        style = AuraTypography.bodyMedium.copy(
                                            color = ChatGptTextPrimary,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp
                                        )
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    if (isMe) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(ChatGptAccentGreen.copy(alpha = 0.2f))
                                                .padding(horizontal = 5.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "This Device",
                                                style = AuraTypography.labelSmall.copy(color = ChatGptAccentGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                            )
                                        }
                                    } else if (dev.isOnline) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0xFF132F20))
                                                .padding(horizontal = 5.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "Online 🟢",
                                                style = AuraTypography.labelSmall.copy(color = ChatGptAccentGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                            )
                                        }
                                    }
                                }
                                val batteryStr = if (dev.batteryLevel >= 0) " • ⚡ ${dev.batteryLevel}%" else ""
                                Text(
                                    text = "${dev.manufacturer} ${dev.model}$batteryStr",
                                    style = AuraTypography.bodySmall.copy(color = ChatGptTextMuted, fontSize = 11.sp)
                                )
                            }
                            IconButton(
                                onClick = { onRenameDevice(dev) },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Rename", tint = ChatGptTextMuted, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = themePrimary,
                contentColor = Color.Black
            )
        ) {
            Text("Done", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 7. SUB-PAGE: PERMISSIONS & SYSTEM ACCESS
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun PermissionsSubPage(
    themePrimary: Color,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val isAccessibilityActive = com.aura.assistant.IshaAccessibilityService.instance != null
    val hasMicPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
    val hasPhonePermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_PHONE_STATE
    ) == PackageManager.PERMISSION_GRANTED
    val hasStoragePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_MEDIA_IMAGES
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
    val canDrawOverlay = Settings.canDrawOverlays(context)
    val enabledNotificationListeners = Settings.Secure.getString(
        context.contentResolver, "enabled_notification_listeners"
    ) ?: ""
    val isNotificationActive = enabledNotificationListeners.contains(context.packageName)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 18.dp)
            .padding(bottom = 36.dp)
    ) {
        Surface(
            color = ChatGptCard,
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PermissionInfoRow(
                    icon = Icons.Default.Accessibility,
                    label = "Accessibility Service",
                    status = if (isAccessibilityActive) "Allowed" else "Action Needed",
                    statusColor = if (isAccessibilityActive) ChatGptAccentGreen else AuraAmberGold,
                    onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                        } catch (_: Exception) {}
                    }
                )
                PermissionInfoRow(
                    icon = Icons.Default.Mic,
                    label = "Microphone (Live & Wake)",
                    status = if (hasMicPermission) "Allowed" else "Not Allowed",
                    statusColor = if (hasMicPermission) ChatGptAccentGreen else AuraAlertRed,
                    onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${context.packageName}")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                        } catch (_: Exception) {}
                    }
                )
                PermissionInfoRow(
                    icon = Icons.Default.PictureInPicture,
                    label = "Display Over Apps (Island)",
                    status = if (canDrawOverlay) "Allowed" else "Not Allowed",
                    statusColor = if (canDrawOverlay) ChatGptAccentGreen else AuraAmberGold,
                    onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                        } catch (_: Exception) {}
                    }
                )
                PermissionInfoRow(
                    icon = Icons.Default.Notifications,
                    label = "Notification Listener",
                    status = if (isNotificationActive) "Allowed" else "Not Allowed",
                    statusColor = if (isNotificationActive) ChatGptAccentGreen else AuraAmberGold,
                    onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                        } catch (_: Exception) {}
                    }
                )
                PermissionInfoRow(
                    icon = Icons.Default.Phone,
                    label = "Phone & Calls",
                    status = if (hasPhonePermission) "Allowed" else "Not Allowed",
                    statusColor = if (hasPhonePermission) ChatGptAccentGreen else AuraAmberGold,
                    onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${context.packageName}")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                        } catch (_: Exception) {}
                    }
                )
                PermissionInfoRow(
                    icon = Icons.Default.FolderOpen,
                    label = "Storage & Media Access",
                    status = if (hasStoragePermission) "Allowed" else "Not Allowed",
                    statusColor = if (hasStoragePermission) ChatGptAccentGreen else AuraAmberGold,
                    onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${context.packageName}")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                        } catch (_: Exception) {}
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = themePrimary,
                contentColor = Color.Black
            )
        ) {
            Text("Done", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 8. SUB-PAGE: ABOUT ISHA & PIPELINE SPECS
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun AboutAppSubPage(
    themePrimary: Color,
    onBack: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 18.dp)
            .padding(bottom = 36.dp)
    ) {
        Surface(
            color = ChatGptCard,
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow(label = "App Version", value = "v5.4.0 (Autonomous Edition)")
                InfoRow(label = "Creator & Architect", value = "Ansh Kesharwani")
                InfoRow(label = "Architecture", value = "Autonomous Universal Real-Time Assistant")
                InfoRow(label = "Native Tools", value = "70+ System Tools (100% Verified)")
                InfoRow(label = "Conversational Brain", value = "ConversationalReactionEngine")
                InfoRow(label = "Adaptive Memory", value = "ExperienceMemory & LessonStore")
                InfoRow(label = "Security Governance", value = "PolicyEngine & ConfirmationEngine")
                InfoRow(label = "Background Engine", value = "ISHA TaskManager & RoutineEngine")
                InfoRow(label = "Live Voice Model", value = "ISHA Neural Voice Native")
                InfoRow(label = "Barge-In Engine", value = "Natural Context-Preserving")
                InfoRow(label = "Wake Engine", value = "OpenWakeWord ONNX (Calibrated 0.55)")
                InfoRow(label = "Native Stack", value = "Pure Kotlin + Jetpack Compose")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSectionHeader(icon = Icons.Default.GraphicEq, title = "Audio Pipeline Specifications")

        Surface(
            color = ChatGptCard,
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow(label = "Input Sample Rate", value = "16,000 Hz (16-bit Mono PCM)")
                InfoRow(label = "Output Sample Rate", value = "24,000 Hz (High-Fidelity PCM)")
                InfoRow(label = "Acoustic Engine", value = "OpenWakeWord v0.5 ONNX")
                InfoRow(label = "Detection Threshold", value = "0.55 (0.60 Min Score, 3 Frames)")
                InfoRow(label = "VAD Mode", value = "Bidirectional Energy + Silero")
                InfoRow(label = "Echo Cancellation", value = "Hardware AcousticEchoCanceler")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = themePrimary,
                contentColor = Color.Black
            )
        ) {
            Text("Done", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// REUSABLE NAVIGATION & UI COMPONENTS
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun SettingsNavigationRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String? = null,
    badgeText: String? = null,
    trailingColorIndicator: Color? = null,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = ChatGptCard,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.12f))
                    .border(1.dp, iconTint.copy(alpha = 0.25f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = AuraTypography.titleMedium.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ChatGptTextPrimary
                    )
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = AuraTypography.bodySmall.copy(
                            fontSize = 11.sp,
                            color = ChatGptTextMuted
                        ),
                        maxLines = 1
                    )
                }
            }
            if (trailingColorIndicator != null) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(trailingColorIndicator)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            if (badgeText != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(iconTint.copy(alpha = 0.15f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badgeText,
                        style = AuraTypography.labelSmall.copy(
                            color = iconTint,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = ChatGptTextMuted.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun SettingsSectionHeader(
    icon: ImageVector,
    title: String,
    tint: Color = ChatGptTextMuted
) {
    Row(
        modifier = Modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(
            text = title.uppercase(),
            style = AuraTypography.bodyMedium.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = tint,
                letterSpacing = 1.2.sp
            )
        )
    }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        color = ChatGptCard,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = if (checked) iconTint.copy(alpha = 0.4f) else ChatGptBorder
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.12f))
                    .border(1.dp, iconTint.copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = AuraTypography.titleMedium.copy(fontSize = 13.sp))
                Text(
                    text = subtitle,
                    style = AuraTypography.bodyMedium.copy(fontSize = 11.sp, color = ChatGptTextMuted)
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = iconTint,
                    uncheckedThumbColor = ChatGptTextMuted,
                    uncheckedTrackColor = ChatGptCard,
                    uncheckedBorderColor = ChatGptBorder
                )
            )
        }
    }
}

@Composable
private fun PermissionInfoRow(
    icon: ImageVector,
    label: String,
    status: String,
    statusColor: Color,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClick() } else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = ChatGptTextMuted, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = AuraTypography.bodyMedium.copy(fontSize = 12.sp, color = ChatGptTextSecondary)
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(statusColor.copy(alpha = 0.15f))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text = status,
                style = AuraTypography.bodyMedium.copy(fontSize = 10.sp, color = statusColor, fontWeight = FontWeight.SemiBold)
            )
        }
        if (onClick != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = ChatGptTextMuted.copy(alpha = 0.6f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = label,
            style = AuraTypography.bodyMedium.copy(fontSize = 12.sp, color = ChatGptTextMuted)
        )
        Text(
            text = value,
            style = AuraTypography.bodyMedium.copy(fontSize = 12.sp, color = ChatGptAccentGreen, fontWeight = FontWeight.Medium)
        )
    }
}

@Composable
fun AuraSettingsSheet(
    isWakeWordEnabled: Boolean,
    isShakeEnabled: Boolean,
    isMessageSpeakEnabled: Boolean = false,
    userProfile: UserProfile = UserProfile(),
    onWakeWordToggle: (Boolean) -> Unit,
    onShakeToggle: (Boolean) -> Unit,
    onMessageSpeakToggle: (Boolean) -> Unit = {},
    onClearAllChats: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenApiKeyEntry: () -> Unit = {},
    onLogout: () -> Unit = {},
    onDismiss: () -> Unit
) = IshaSettingsSheet(
    isWakeWordEnabled = isWakeWordEnabled,
    isShakeEnabled = isShakeEnabled,
    isMessageSpeakEnabled = isMessageSpeakEnabled,
    isCallAnnouncementEnabled = true,
    userProfile = userProfile,
    onWakeWordToggle = onWakeWordToggle,
    onShakeToggle = onShakeToggle,
    onMessageSpeakToggle = onMessageSpeakToggle,
    onClearAllChats = onClearAllChats,
    onOpenDiagnostics = onOpenDiagnostics,
    onOpenApiKeyEntry = onOpenApiKeyEntry,
    onLogout = onLogout,
    onDismiss = onDismiss
)
