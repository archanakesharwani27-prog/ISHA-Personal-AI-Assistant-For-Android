package com.aura.assistant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.assistant.ui.theme.*

// ── Settings Data ─────────────────────────────────────────────────────────────
data class AuraSettingsState(
    val isWakeWordEnabled: Boolean = true,
    val isShakeEnabled: Boolean = true,
    val selectedVoice: String = "Aoede",
    val selectedLanguage: String = "Auto-detect",
    val textSize: String = "Default",
    val isSoundEnabled: Boolean = true
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuraSettingsSheet(
    isWakeWordEnabled: Boolean,
    isShakeEnabled: Boolean,
    onWakeWordToggle: (Boolean) -> Unit,
    onShakeToggle: (Boolean) -> Unit,
    onClearAllChats: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()
    var selectedVoice by remember { mutableStateOf("Aoede") }
    var selectedLanguage by remember { mutableStateOf("Auto-detect") }
    var textSize by remember { mutableStateOf("Default") }
    var soundEnabled by remember { mutableStateOf(true) }
    var showClearConfirm by remember { mutableStateOf(false) }

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
                        text = "AURA Settings",
                        style = AuraTypography.headlineMedium.copy(fontSize = 18.sp)
                    )
                    Text(
                        text = "Personalize your AI assistant",
                        style = AuraTypography.bodyMedium.copy(color = ChatGptTextMuted, fontSize = 12.sp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Section 1: Voice & Activation ───────────────────────────────
            SettingsSectionHeader(icon = Icons.Default.RecordVoiceOver, title = "Voice & Activation")

            // Wake Word
            SettingsToggleRow(
                icon = Icons.Default.Mic,
                iconTint = ChatGptAccentGreen,
                title = "Wake Word — \"Hey AURA\"",
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
                subtitle = "Play a chime when AURA wakes up",
                checked = soundEnabled,
                onCheckedChange = { soundEnabled = it }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── Section 2: Voice Persona ─────────────────────────────────────
            SettingsSectionHeader(icon = Icons.Default.Face, title = "AURA Voice Persona")

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
                                    .clickable { selectedVoice = voice }
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
                                    .clickable { selectedLanguage = lang }
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
                                    .clickable { textSize = size }
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

            // ── Section 5: Accessibility & Permissions ────────────────────────
            SettingsSectionHeader(icon = Icons.Default.Accessibility, title = "Permissions & Accessibility")

            Surface(
                color = ChatGptCard,
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PermissionInfoRow(
                        icon = Icons.Default.Mic,
                        label = "Microphone",
                        status = "Granted",
                        statusColor = ChatGptAccentGreen
                    )
                    PermissionInfoRow(
                        icon = Icons.Default.Accessibility,
                        label = "Accessibility Service",
                        status = "Required for Screen Control",
                        statusColor = AuraAmberGold
                    )
                    PermissionInfoRow(
                        icon = Icons.Default.Phone,
                        label = "Phone & Contacts",
                        status = "Granted",
                        statusColor = ChatGptAccentGreen
                    )
                    PermissionInfoRow(
                        icon = Icons.Default.FolderOpen,
                        label = "Storage Access",
                        status = "Granted",
                        statusColor = ChatGptAccentGreen
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Section 6: About & Model Info ─────────────────────────────────
            SettingsSectionHeader(icon = Icons.Default.Info, title = "About AURA")

            Surface(
                color = ChatGptCard,
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow(label = "AI Model", value = "Gemini 2.5 Flash")
                    InfoRow(label = "Live Voice", value = "Gemini 2.0 Flash Live")
                    InfoRow(label = "Audio I/O", value = "16 kHz → 24 kHz PCM")
                    InfoRow(label = "Active Voice", value = selectedVoice)
                    InfoRow(label = "App Version", value = "AURA 2.0 Native")
                    InfoRow(label = "Build", value = "Kotlin + Compose")
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
    statusColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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
