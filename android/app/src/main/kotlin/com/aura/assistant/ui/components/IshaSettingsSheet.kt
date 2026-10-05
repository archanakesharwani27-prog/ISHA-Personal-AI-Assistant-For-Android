package com.aura.assistant.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import com.aura.assistant.config.ApiKeyManager
import com.aura.assistant.auth.UserProfile
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
import com.aura.assistant.ui.theme.*

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
    val scrollState = rememberScrollState()
    var showClearConfirm by remember { mutableStateOf(false) }
    var deviceToRename by remember { mutableStateOf<com.aura.assistant.sync.IshaDeviceInfo?>(null) }
    var newDeviceAliasInput by remember { mutableStateOf("") }

    val voices = listOf("Aoede", "Puck", "Charon", "Fenrir", "Kore")
    val languages = listOf("Auto-detect", "English", "Hindi", "Hinglish")
    val textSizes = listOf("Small", "Default", "Large")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ChatGptSidebar,
        contentColor = ChatGptTextPrimary,
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(ChatGptBorder)
                )
            }
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .verticalScroll(scrollState)
                .padding(horizontal = 18.dp)
                .padding(bottom = 36.dp)
        ) {
            // ── Header ──────────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(ChatGptAccentGreen, ChatGptAccentCyan))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "ISHA Settings",
                        style = AuraTypography.headlineMedium.copy(fontSize = 18.sp)
                    )
                    Text(
                        text = "Personalize your AI assistant",
                        style = AuraTypography.bodyMedium.copy(color = ChatGptTextMuted, fontSize = 12.sp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

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
                            .size(44.dp)
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
                        onClick = {
                            onDismiss()
                            onLogout()
                        },
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

            // ── Section 1: Voice & Activation ───────────────────────────────
            SettingsSectionHeader(icon = Icons.Default.RecordVoiceOver, title = "Voice & Activation")

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
                subtitle = "Double-shake phone to open (Uses sensor, no green dot)",
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
                subtitle = if (isCallAnnouncementEnabled) "Incoming call aane par caller ka naam bol kar bataye" else "Muted (Call aane par caller ka naam nahi bolegi)",
                checked = isCallAnnouncementEnabled,
                onCheckedChange = onCallAnnouncementToggle
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── Section 2: Voice Persona ─────────────────────────────────────
            SettingsSectionHeader(icon = Icons.Default.Face, title = "ISHA Voice Persona")

            Surface(
                color = ChatGptCard,
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Voice Model",
                        style = AuraTypography.bodyMedium.copy(color = ChatGptTextMuted, fontSize = 11.sp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    // Voice chip selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        voices.forEach { voice ->
                            val isSelected = voice == selectedVoice
                            Surface(
                                color = if (isSelected) ChatGptAccentGreen else ChatGptBackground,
                                shape = RoundedCornerShape(20.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) ChatGptAccentGreen else ChatGptBorder
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onVoiceChange(voice) }
                            ) {
                                Text(
                                    text = voice,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    style = AuraTypography.bodyMedium.copy(
                                        fontSize = 11.sp,
                                        color = if (isSelected) Color.Black else ChatGptTextSecondary,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Section 3: Language & Input ───────────────────────────────────
            SettingsSectionHeader(icon = Icons.Default.Language, title = "Language & Input")

            Surface(
                color = ChatGptCard,
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Recognition Language",
                        style = AuraTypography.bodyMedium.copy(color = ChatGptTextMuted, fontSize = 11.sp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        languages.forEach { lang ->
                            val isSelected = lang == selectedLanguage
                            Surface(
                                color = if (isSelected) AuraCyberPurple.copy(alpha = 0.85f) else ChatGptBackground,
                                shape = RoundedCornerShape(20.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) AuraCyberPurple else ChatGptBorder
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onLanguageChange(lang) }
                            ) {
                                Text(
                                    text = lang,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                                    style = AuraTypography.bodyMedium.copy(
                                        fontSize = 10.sp,
                                        color = if (isSelected) Color.White else ChatGptTextSecondary,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Section 4: Display ────────────────────────────────────────────
            SettingsSectionHeader(icon = Icons.Default.TextFields, title = "Display")

            Surface(
                color = ChatGptCard,
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Chat Text Size",
                        style = AuraTypography.bodyMedium.copy(color = ChatGptTextMuted, fontSize = 11.sp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        textSizes.forEach { size ->
                            val isSelected = size == textSize
                            Surface(
                                color = if (isSelected) AuraAmberGold.copy(alpha = 0.85f) else ChatGptBackground,
                                shape = RoundedCornerShape(20.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) AuraAmberGold else ChatGptBorder
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onTextSizeChange(size) }
                            ) {
                                Text(
                                    text = size,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    style = AuraTypography.bodyMedium.copy(
                                        fontSize = if (size == "Small") 10.sp else if (size == "Large") 14.sp else 12.sp,
                                        color = if (isSelected) Color.Black else ChatGptTextSecondary,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Section: ISHA API Key ────────────────────────────────────────
            SettingsSectionHeader(icon = Icons.Default.VpnKey, title = "ISHA Intelligence Key")

            val userCustomKey = remember { ApiKeyManager.getApiKey(context) }
            val hasCustomKeySet = userCustomKey.isNotBlank()

            Surface(
                color = ChatGptCard,
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onDismiss()
                        onOpenApiKeyEntry()
                    }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (hasCustomKeySet) ChatGptAccentGreen.copy(alpha = 0.15f) else AuraAmberGold.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = if (hasCustomKeySet) ChatGptAccentGreen else AuraAmberGold,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ISHA API Key",
                            style = AuraTypography.bodyLarge.copy(
                                color = ChatGptTextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        Text(
                            text = if (hasCustomKeySet) ApiKeyManager.maskKey(userCustomKey) else "Using default system key",
                            style = AuraTypography.bodySmall.copy(
                                color = ChatGptTextSecondary,
                                fontSize = 12.sp
                            )
                        )
                    }
                    Text(
                        text = "Edit",
                        color = ChatGptAccentGreen,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = null,
                        tint = ChatGptTextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Section: Connected Devices & Cross-Control ─────────────────────
            SettingsSectionHeader(icon = Icons.Default.Devices, title = "Connected Devices & Cross-Control")

            val allRegisteredDevices by com.aura.assistant.sync.IshaDeviceRegistry.knownDevices.collectAsState()
            val currentDeviceId = remember { com.aura.assistant.sync.IshaDeviceRegistry.getDeviceId() }
            val currentAlias = remember { com.aura.assistant.sync.IshaDeviceRegistry.getDeviceAlias() }

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
                                    deviceToRename = com.aura.assistant.sync.IshaDeviceInfo(
                                        deviceId = currentDeviceId,
                                        deviceAlias = currentAlias,
                                        isCurrentDevice = true
                                    )
                                    newDeviceAliasInput = currentAlias
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
                                    onClick = {
                                        deviceToRename = dev
                                        newDeviceAliasInput = dev.deviceAlias.ifBlank { dev.deviceName }
                                    },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = "Rename", tint = ChatGptTextMuted, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Section 5: Accessibility & Permissions ────────────────────────
            SettingsSectionHeader(icon = Icons.Default.Accessibility, title = "Permissions & Accessibility")

            // Dynamic live permission evaluation
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

            Spacer(modifier = Modifier.height(20.dp))

            // ── Section 6: About & Model Info ─────────────────────────────────
            SettingsSectionHeader(icon = Icons.Default.Info, title = "About ISHA")

            Surface(
                color = ChatGptCard,
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow(label = "App Version", value = "v5.4.0 (Autonomous Edition)")
                    InfoRow(label = "Release Stage", value = "Production Live")
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

            Spacer(modifier = Modifier.height(20.dp))

            // ── Section 6b: Audio Pipeline Specifications ────────────────────
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

            Spacer(modifier = Modifier.height(20.dp))

            // ── Section 7: Danger Zone ─────────────────────────────────────────
            SettingsSectionHeader(icon = Icons.Default.Warning, title = "Data", tint = AuraAlertRed)

            // Clear All Chats
            Surface(
                color = AuraAlertRed.copy(alpha = 0.08f),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AuraAlertRed.copy(alpha = 0.35f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showClearConfirm = true }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = null,
                        tint = AuraAlertRed,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Clear All Chats",
                            style = AuraTypography.titleMedium.copy(color = AuraAlertRed, fontSize = 14.sp)
                        )
                        Text(
                            text = "Permanently delete all conversation history",
                            style = AuraTypography.bodyMedium.copy(color = AuraAlertRed.copy(alpha = 0.7f), fontSize = 11.sp)
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = AuraAlertRed.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Section: Developer & Diagnostics ──────────────────────────────
            SettingsSectionHeader(icon = Icons.Default.Build, title = "System & Diagnostics")

            Surface(
                color = ChatGptCard,
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onDismiss()
                        onOpenDiagnostics()
                    }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(ChatGptAccentGreen.copy(alpha = 0.12f))
                            .border(1.dp, ChatGptAccentGreen.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = ChatGptAccentGreen,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ISHA 2.0 Diagnostics",
                            style = AuraTypography.titleMedium.copy(fontSize = 13.sp)
                        )
                        Text(
                            text = "Inspect mic ownership, wake word & ISHA Live telemetry",
                            style = AuraTypography.bodyMedium.copy(fontSize = 11.sp, color = ChatGptTextMuted)
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = ChatGptTextMuted
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Done Button ────────────────────────────────────────────────────
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ChatGptAccentGreen,
                    contentColor = Color.Black
                )
            ) {
                Text(
                    text = "Done",
                    style = AuraTypography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.Black
                    )
                )
            }
        }
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

// ── Settings Section Header ───────────────────────────────────────────────────
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

// ── Toggle Row ────────────────────────────────────────────────────────────────
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

// ── Permission Info Row ───────────────────────────────────────────────────────
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

// ── Info Row ──────────────────────────────────────────────────────────────────
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

