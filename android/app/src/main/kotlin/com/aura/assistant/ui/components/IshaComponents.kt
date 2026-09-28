package com.aura.assistant.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.aura.assistant.auth.UserProfile
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.assistant.data.ChatMessage
import com.aura.assistant.data.ChatSession
import com.aura.assistant.data.MessageRole
import com.aura.assistant.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

// ── 1. ISHA TopBar ────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IshaTopBar(
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopAppBar(
        modifier = modifier,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = ChatGptBackground,
            titleContentColor = ChatGptTextPrimary,
            navigationIconContentColor = ChatGptTextPrimary,
            actionIconContentColor = ChatGptTextPrimary
        ),
        navigationIcon = {
            IconButton(onClick = onOpenDrawer) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Open Sidebar",
                    tint = ChatGptTextPrimary
                )
            }
        },
        title = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = ChatGptCard,
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ISHA 2.0",
                            style = AuraTypography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = ChatGptTextPrimary
                            )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(ChatGptAccentGreen)
                        )
                    }
                }
            }
        },
        actions = {
            IconButton(onClick = onNewChat) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "New Chat",
                    tint = ChatGptTextPrimary
                )
            }
        }
    )
}

@Composable
fun ChatGPTTopBar(
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier
) = IshaTopBar(onOpenDrawer, onNewChat, modifier)

// ── 2. ISHA Navigation Drawer ─────────────────────────────────────────────────
@Composable
fun IshaDrawer(
    sessions: List<ChatSession>,
    activeSessionId: String,
    onSelectSession: (String) -> Unit,
    onNewChat: () -> Unit,
    onRenameSession: (String, String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onClearAllChats: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenApiKeyEntry: () -> Unit = {},
    userProfile: UserProfile = UserProfile(),
    onLogout: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var sessionToRename by remember { mutableStateOf<ChatSession?>(null) }
    var renameText by remember { mutableStateOf("") }

    val filteredSessions = remember(sessions, searchQuery) {
        if (searchQuery.isBlank()) sessions
        else sessions.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    // Grouping by date
    val now = System.currentTimeMillis()
    val oneDay = 24 * 60 * 60 * 1000L
    val todaySessions = filteredSessions.filter { now - it.updatedAt < oneDay }
    val yesterdaySessions = filteredSessions.filter { (now - it.updatedAt) in oneDay until (2 * oneDay) }
    val olderSessions = filteredSessions.filter { now - it.updatedAt >= (2 * oneDay) }

    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(310.dp),
        color = ChatGptSidebar
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp, bottom = 12.dp, start = 12.dp, end = 12.dp)
        ) {
            // New Chat Button
            Surface(
                color = ChatGptCard,
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNewChat)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = ChatGptTextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "New chat",
                        style = AuraTypography.titleMedium.copy(
                            color = ChatGptTextPrimary,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Search input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        text = "Search chats...",
                        color = ChatGptTextMuted,
                        fontSize = 13.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = ChatGptTextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = ChatGptCard.copy(alpha = 0.5f),
                    unfocusedContainerColor = ChatGptCard.copy(alpha = 0.5f),
                    focusedBorderColor = ChatGptBorder,
                    unfocusedBorderColor = ChatGptBorderSubtle,
                    focusedTextColor = ChatGptTextPrimary,
                    unfocusedTextColor = ChatGptTextPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Chat History Section List
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (todaySessions.isNotEmpty()) {
                    item { DrawerSectionHeader("Today") }
                    items(todaySessions, key = { it.id }) { session ->
                        DrawerSessionRow(
                            session = session,
                            isActive = session.id == activeSessionId,
                            onClick = { onSelectSession(session.id) },
                            onRename = {
                                sessionToRename = session
                                renameText = session.title
                            },
                            onDelete = { onDeleteSession(session.id) }
                        )
                    }
                }

                if (yesterdaySessions.isNotEmpty()) {
                    item { DrawerSectionHeader("Yesterday") }
                    items(yesterdaySessions, key = { it.id }) { session ->
                        DrawerSessionRow(
                            session = session,
                            isActive = session.id == activeSessionId,
                            onClick = { onSelectSession(session.id) },
                            onRename = {
                                sessionToRename = session
                                renameText = session.title
                            },
                            onDelete = { onDeleteSession(session.id) }
                        )
                    }
                }

                if (olderSessions.isNotEmpty()) {
                    item { DrawerSectionHeader("Previous 7 Days & Older") }
                    items(olderSessions, key = { it.id }) { session ->
                        DrawerSessionRow(
                            session = session,
                            isActive = session.id == activeSessionId,
                            onClick = { onSelectSession(session.id) },
                            onRename = {
                                sessionToRename = session
                                renameText = session.title
                            },
                            onDelete = { onDeleteSession(session.id) }
                        )
                    }
                }
            }

            HorizontalDivider(color = ChatGptBorderSubtle, thickness = 1.dp, modifier = Modifier.padding(vertical = 10.dp))

            // ── Drawer Footer Items ────────────────────────────────────────────
            // Gemini API Key Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenApiKeyEntry)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(ChatGptCard)
                        .border(1.dp, ChatGptBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = "API Key",
                        tint = ChatGptAccentGreen,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ISHA API Key",
                        style = AuraTypography.titleMedium.copy(
                            color = ChatGptTextPrimary,
                            fontSize = 14.sp
                        )
                    )
                    Text(
                        text = "Manage AI model key",
                        style = AuraTypography.bodyMedium.copy(
                            color = ChatGptTextMuted,
                            fontSize = 11.sp
                        )
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = null,
                    tint = ChatGptTextSecondary,
                    modifier = Modifier.size(14.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Settings Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(ChatGptAccentGreen),
                    contentAlignment = Alignment.Center
                ) {
                    Text("I", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ISHA Settings",
                        style = AuraTypography.titleMedium.copy(
                            color = ChatGptTextPrimary,
                            fontSize = 14.sp
                        )
                    )
                    Text(
                        text = "ISHA Neural Engine • Native",
                        style = AuraTypography.bodyMedium.copy(
                            color = ChatGptTextMuted,
                            fontSize = 11.sp
                        )
                    )
                }
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = ChatGptTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // User Profile & Logout Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(userProfile.avatarColorHex)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = userProfile.avatarInitial,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = userProfile.name,
                        style = AuraTypography.titleMedium.copy(
                            color = ChatGptTextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = userProfile.email.ifBlank { "Personal Profile" },
                        style = AuraTypography.bodyMedium.copy(
                            color = ChatGptTextMuted,
                            fontSize = 11.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onLogout,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Logout,
                        contentDescription = "Sign Out",
                        tint = Color(0xFFFF5252),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    // Rename Dialog
    if (sessionToRename != null) {
        AlertDialog(
            onDismissRequest = { sessionToRename = null },
            title = { Text("Rename Chat", color = ChatGptTextPrimary) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ChatGptTextPrimary,
                        unfocusedTextColor = ChatGptTextPrimary
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val session = sessionToRename
                        if (session != null && renameText.isNotBlank()) {
                            onRenameSession(session.id, renameText)
                        }
                        sessionToRename = null
                    }
                ) {
                    Text("Save", color = ChatGptAccentGreen)
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToRename = null }) {
                    Text("Cancel", color = ChatGptTextSecondary)
                }
            },
            containerColor = ChatGptCard
        )
    }
}

@Composable
fun ChatGPTDrawer(
    sessions: List<ChatSession>,
    activeSessionId: String,
    onSelectSession: (String) -> Unit,
    onNewChat: () -> Unit,
    onRenameSession: (String, String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onClearAllChats: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenApiKeyEntry: () -> Unit = {},
    userProfile: UserProfile = UserProfile(),
    onLogout: () -> Unit = {},
    modifier: Modifier = Modifier
) = IshaDrawer(
    sessions = sessions,
    activeSessionId = activeSessionId,
    onSelectSession = onSelectSession,
    onNewChat = onNewChat,
    onRenameSession = onRenameSession,
    onDeleteSession = onDeleteSession,
    onClearAllChats = onClearAllChats,
    onOpenSettings = onOpenSettings,
    onOpenApiKeyEntry = onOpenApiKeyEntry,
    userProfile = userProfile,
    onLogout = onLogout,
    modifier = modifier
)

@Composable
private fun DrawerSectionHeader(title: String) {
    Text(
        text = title,
        style = AuraTypography.bodyMedium.copy(
            color = ChatGptTextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        ),
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun DrawerFooterItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    subtitle: String,
    iconTint: Color,
    onClick: () -> Unit
) {
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = AuraTypography.titleMedium.copy(color = ChatGptTextPrimary, fontSize = 13.sp)
                )
                Text(
                    text = subtitle,
                    style = AuraTypography.bodyMedium.copy(color = ChatGptTextMuted, fontSize = 10.sp)
                )
            }
        }
    }
}


@Composable
private fun DrawerSessionRow(
    session: ChatSession,
    isActive: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        color = if (isActive) ChatGptCard else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.ChatBubbleOutline,
                contentDescription = null,
                tint = if (isActive) ChatGptTextPrimary else ChatGptTextMuted,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = session.title,
                style = AuraTypography.bodyMedium.copy(
                    color = if (isActive) ChatGptTextPrimary else ChatGptTextSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = ChatGptTextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.background(ChatGptCard)
                ) {
                    DropdownMenuItem(
                        text = { Text("Rename", color = ChatGptTextPrimary, fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Default.Edit, null, tint = ChatGptTextPrimary, modifier = Modifier.size(16.dp)) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = Color(0xFFFF5252), fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color(0xFFFF5252), modifier = Modifier.size(16.dp)) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

// ── Thinking Indicator & Streaming Cursor ─────────────────────────────────────
@Composable
fun IshaThinkingIndicator(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "ThinkingAnim")

    val dot1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "Dot1"
    )
    val dot2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "Dot2"
    )
    val dot3Alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "Dot3"
    )

    Row(
        modifier = modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = ChatGptCard,
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Thinking",
                    style = AuraTypography.bodyMedium.copy(
                        color = ChatGptAccentGreen,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
                Spacer(modifier = Modifier.width(2.dp))
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(ChatGptAccentGreen.copy(alpha = dot1Alpha))
                )
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(ChatGptAccentGreen.copy(alpha = dot2Alpha))
                )
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(ChatGptAccentGreen.copy(alpha = dot3Alpha))
                )
            }
        }
    }
}

@Composable
fun ChatGPTThinkingIndicator(modifier: Modifier = Modifier) = IshaThinkingIndicator(modifier)

@Composable
fun StreamingCursor(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 0.0f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )
    Box(
        modifier = modifier
            .size(width = 3.dp, height = 15.dp)
            .clip(RoundedCornerShape(1.5.dp))
            .background(ChatGptAccentGreen.copy(alpha = alpha))
    )
}

// ── 3. ChatGPT Message Bubble / Markdown Item ────────────────────────────────
@Composable
fun ChatMessageItem(
    message: ChatMessage,
    onSpeak: (String) -> Unit,
    onRegenerate: () -> Unit,
    onEditPrompt: ((String) -> Unit)? = null,
    textSize: String = "Default",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isUser = message.role == MessageRole.USER
    val isTool = message.role == MessageRole.TOOL
    val textScale = when (textSize) {
        "Small" -> 0.85f
        "Large" -> 1.25f
        else -> 1.0f
    }

    if (isTool) {
        // Suppress tool execution pill for pristine chat aesthetic
        return
    }

    if (isUser) {
        // Right-aligned User Bubble with optional Edit button
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Bottom
        ) {
            if (onEditPrompt != null && message.text.isNotBlank() && message.text != "Attached image") {
                IconButton(
                    onClick = { onEditPrompt(message.text) },
                    modifier = Modifier
                        .size(32.dp)
                        .padding(end = 4.dp, bottom = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit message",
                        tint = ChatGptTextSecondary.copy(alpha = 0.5f),
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
            Surface(
                color = ChatGptUserBubble,
                shape = RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 20.dp,
                    bottomStart = 20.dp,
                    bottomEnd = 4.dp
                ),
                modifier = Modifier.widthIn(max = 295.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (!message.imageUri.isNullOrBlank()) {
                        AttachedImageThumbnail(
                            uriString = message.imageUri,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                        if (message.text.isNotBlank() && message.text != "Attached image") {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                    if (message.text.isNotBlank() && (message.text != "Attached image" || message.imageUri.isNullOrBlank())) {
                        Text(
                            text = message.text,
                            style = AuraTypography.bodyLarge.copy(
                                color = ChatGptTextPrimary,
                                fontSize = (15 * textScale).sp,
                                lineHeight = (22 * textScale).sp
                            )
                        )
                    }
                }
            }
        }
    } else {
        // Left-aligned Assistant Message (Authentic ChatGPT Clean Layout)
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Subtle AURA icon
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(ChatGptAccentGreen),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "I",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    if (message.isStreaming && message.text.isBlank()) {
                        ChatGPTThinkingIndicator()
                    } else {
                        SelectionContainer {
                            RenderMarkdownText(text = message.text, textScale = textScale)
                        }

                        if (message.isStreaming) {
                            Spacer(modifier = Modifier.height(4.dp))
                            StreamingCursor()
                        }
                    }

                    // Action Buttons Row (Copy, Read Aloud, Regenerate)
                    if (!message.isStreaming && message.text.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Copy button
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("ISHA Message", message.text))
                                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ContentCopy,
                                    contentDescription = "Copy",
                                    tint = ChatGptTextMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            // Read Aloud (TTS)
                            IconButton(
                                onClick = { onSpeak(message.text) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.VolumeUp,
                                    contentDescription = "Read Aloud",
                                    tint = ChatGptTextMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Regenerate
                            IconButton(
                                onClick = onRegenerate,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Refresh,
                                    contentDescription = "Regenerate",
                                    tint = ChatGptTextMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Markdown Parser & Formatter ──────────────────────────────────────────────
@Composable
fun RenderMarkdownText(text: String, textScale: Float = 1.0f) {
    val context = LocalContext.current
    val lines = text.split("\n")
    var inCodeBlock = false
    val codeBlockBuilder = remember { StringBuilder() }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                if (inCodeBlock) {
                    // Close code block
                    val code = codeBlockBuilder.toString()
                    CodeBlockCard(code = code, onCopy = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Code", code))
                        Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
                    })
                    codeBlockBuilder.clear()
                    inCodeBlock = false
                } else {
                    inCodeBlock = true
                    codeBlockBuilder.clear()
                }
                continue
            }

            if (inCodeBlock) {
                codeBlockBuilder.append(line).append("\n")
                continue
            }

            if (trimmed.startsWith("# ")) {
                Text(
                    text = trimmed.removePrefix("# ").trim(),
                    style = AuraTypography.headlineMedium.copy(
                        color = ChatGptTextPrimary,
                        fontSize = (18 * textScale).sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            } else if (trimmed.startsWith("## ")) {
                Text(
                    text = trimmed.removePrefix("## ").trim(),
                    style = AuraTypography.titleMedium.copy(
                        color = ChatGptTextPrimary,
                        fontSize = (16 * textScale).sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
            } else if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                Row(modifier = Modifier.padding(start = 4.dp)) {
                    Text(text = "• ", color = ChatGptAccentGreen, fontSize = (14 * textScale).sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = cleanInlineMarkdown(trimmed.substring(2)),
                        style = AuraTypography.bodyLarge.copy(
                            color = ChatGptTextPrimary,
                            fontSize = (14 * textScale).sp,
                            lineHeight = (20 * textScale).sp
                        )
                    )
                }
            } else if (trimmed.isNotBlank()) {
                Text(
                    text = cleanInlineMarkdown(trimmed),
                    style = AuraTypography.bodyLarge.copy(
                        color = ChatGptTextPrimary,
                        fontSize = (14 * textScale).sp,
                        lineHeight = (21 * textScale).sp
                    )
                )
            } else {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }

        // Unclosed code block fallback
        if (inCodeBlock && codeBlockBuilder.isNotEmpty()) {
            val code = codeBlockBuilder.toString()
            CodeBlockCard(code = code, onCopy = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Code", code))
                Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
            })
        }
    }
}

private fun cleanInlineMarkdown(input: String): String {
    // Strip **bold** and `code` markers for clean reading
    return input.replace("**", "").replace("`", "")
}

@Composable
private fun CodeBlockCard(code: String, onCopy: () -> Unit) {
    Surface(
        color = Color(0xFF141414),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E1E))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Code",
                    style = AuraTypography.labelSmall.copy(color = ChatGptTextMuted, fontSize = 11.sp)
                )
                Text(
                    text = "Copy code",
                    style = AuraTypography.labelSmall.copy(
                        color = ChatGptAccentGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier.clickable(onClick = onCopy)
                )
            }
            Text(
                text = code.trimEnd(),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = Color(0xFFE6E6E6),
                lineHeight = 17.sp,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

// ── 4. ISHA Empty State (Welcome Greeting + 4 Prompt Cards) ──────────────────
@Composable
fun IshaEmptyState(
    onPromptClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val promptCards = remember {
        listOf(
            "🔦 Flashlight" to "Turn on the flashlight",
            "📱 WhatsApp"   to "Open WhatsApp",
            "⏰ Alarm"      to "Set an alarm for 7:00 AM tomorrow",
            "⚡ Battery"    to "What is my current battery level?"
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(ChatGptAccentGreen),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "I",
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 32.sp
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = "What can I help with today?",
            style = AuraTypography.headlineMedium.copy(
                color = ChatGptTextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
        )

        Spacer(modifier = Modifier.height(28.dp))

        // 2x2 Grid of Prompt Cards
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PromptCard(
                    title = promptCards[0].first,
                    prompt = promptCards[0].second,
                    onClick = { onPromptClick(promptCards[0].second) },
                    modifier = Modifier.weight(1f)
                )
                PromptCard(
                    title = promptCards[1].first,
                    prompt = promptCards[1].second,
                    onClick = { onPromptClick(promptCards[1].second) },
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PromptCard(
                    title = promptCards[2].first,
                    prompt = promptCards[2].second,
                    onClick = { onPromptClick(promptCards[2].second) },
                    modifier = Modifier.weight(1f)
                )
                PromptCard(
                    title = promptCards[3].first,
                    prompt = promptCards[3].second,
                    onClick = { onPromptClick(promptCards[3].second) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun ChatGPTEmptyState(
    onPromptClick: (String) -> Unit,
    modifier: Modifier = Modifier
) = IshaEmptyState(onPromptClick, modifier)

@Composable
private fun PromptCard(
    title: String,
    prompt: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = ChatGptCard,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder),
        modifier = modifier
            .height(84.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = AuraTypography.titleMedium.copy(
                    color = ChatGptTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = prompt,
                style = AuraTypography.bodyMedium.copy(
                    color = ChatGptTextMuted,
                    fontSize = 11.sp
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ── 5. ChatGPT Capsule Bottom Input Bar ───────────────────────────────────────
// ── Thumbnail Image Loader ──────────────────────────────────────────────────
@Composable
fun AttachedImageThumbnail(
    uriString: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bitmapState = produceState<Bitmap?>(initialValue = null, uriString) {
        value = withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(uriString)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                        val maxDim = 800
                        val sample = maxOf(1, maxOf(info.size.width, info.size.height) / maxDim)
                        decoder.setTargetSampleSize(sample)
                    }
                } else {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
    }
    bitmapState.value?.let { bmp ->
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Attached Image",
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    }
}

// ── 5. ISHA Capsule Bottom Input Bar ──────────────────────────────────────────
@Composable
fun IshaBottomBar(
    onSendMessage: (String) -> Unit,
    onVoiceClick: () -> Unit,
    onStopGenerating: () -> Unit,
    onPlusClick: () -> Unit,
    isGenerating: Boolean,
    attachedMedia: com.aura.assistant.ui.contract.AttachedMedia? = null,
    onClearAttachment: () -> Unit = {},
    initialText: String = "",
    onTextConsumed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }

    LaunchedEffect(initialText) {
        if (initialText.isNotBlank()) {
            text = initialText
            onTextConsumed()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ChatGptBackground)
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // Attachment Thumbnail Preview Card (ChatGPT / Gemini style)
        if (attachedMedia != null) {
            Surface(
                color = ChatGptCard,
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AttachedImageThumbnail(
                        uriString = attachedMedia.uri.toString(),
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = attachedMedia.name,
                            style = AuraTypography.bodyMedium.copy(
                                color = ChatGptTextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Ready to send with prompt",
                            style = AuraTypography.bodySmall.copy(
                                color = ChatGptAccentGreen,
                                fontSize = 11.sp
                            )
                        )
                    }
                    IconButton(
                        onClick = onClearAttachment,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove attachment",
                            tint = ChatGptTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Input pill bar
        Surface(
            color = ChatGptInputBubble,
            shape = RoundedCornerShape(26.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Plus '+' tools / attachment button
                IconButton(
                    onClick = onPlusClick,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Attachment",
                        tint = if (attachedMedia != null) ChatGptAccentGreen else ChatGptTextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Text Input Field
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = {
                        Text(
                            text = if (attachedMedia != null) "Ask about this image..." else "Message ISHA...",
                            color = ChatGptTextMuted,
                            fontSize = 15.sp
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = ChatGptTextPrimary,
                        unfocusedTextColor = ChatGptTextPrimary
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (text.isNotBlank() || attachedMedia != null) {
                                onSendMessage(text)
                                text = ""
                            }
                        }
                    ),
                    maxLines = 4,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 2.dp)
                )

                Spacer(modifier = Modifier.width(6.dp))

                // Dynamic Action Button (Headphones / Stop / Send)
                if (isGenerating) {
                    IconButton(
                        onClick = onStopGenerating,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(ChatGptTextPrimary)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(Color.Black, RoundedCornerShape(2.dp))
                        )
                    }
                } else if (text.isNotBlank() || attachedMedia != null) {
                    IconButton(
                        onClick = {
                            if (text.isNotBlank() || attachedMedia != null) {
                                onSendMessage(text)
                                text = ""
                            }
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(ChatGptTextPrimary)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = "Send",
                            tint = Color.Black,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    IconButton(
                        onClick = onVoiceClick,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(ChatGptBorder)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Headphones,
                            contentDescription = "Voice Mode",
                            tint = ChatGptTextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChatGPTBottomBar(
    onSendMessage: (String) -> Unit,
    onVoiceClick: () -> Unit,
    onStopGenerating: () -> Unit,
    onPlusClick: () -> Unit,
    isGenerating: Boolean,
    attachedMedia: com.aura.assistant.ui.contract.AttachedMedia? = null,
    onClearAttachment: () -> Unit = {},
    initialText: String = "",
    onTextConsumed: () -> Unit = {},
    modifier: Modifier = Modifier
) = IshaBottomBar(
    onSendMessage = onSendMessage,
    onVoiceClick = onVoiceClick,
    onStopGenerating = onStopGenerating,
    onPlusClick = onPlusClick,
    isGenerating = isGenerating,
    attachedMedia = attachedMedia,
    onClearAttachment = onClearAttachment,
    initialText = initialText,
    onTextConsumed = onTextConsumed,
    modifier = modifier
)

// ── 6. Attachment & Device Tools Bottom Sheet ─────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IshaAttachmentBottomSheet(
    onTakePhoto: () -> Unit,
    onPickGallery: () -> Unit,
    onPickDocument: () -> Unit,
    onToolSelected: (prompt: String) -> Unit,
    onDismiss: () -> Unit
) = AuraAttachmentBottomSheet(onTakePhoto, onPickGallery, onPickDocument, onToolSelected, onDismiss)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuraAttachmentBottomSheet(
    onTakePhoto: () -> Unit,
    onPickGallery: () -> Unit,
    onPickDocument: () -> Unit,
    onToolSelected: (prompt: String) -> Unit,
    onDismiss: () -> Unit
) {
    var showDeviceTools by remember { mutableStateOf(false) }

    val tools = remember {
        listOf(
            Triple("🔦 Flashlight", Icons.Default.FlashlightOn, "Toggle the flashlight"),
            Triple("📱 WhatsApp", Icons.Default.PhoneAndroid, "Open WhatsApp"),
            Triple("📞 Phone Call", Icons.Default.Call, "Make a phone call"),
            Triple("⏰ Alarm", Icons.Default.Alarm, "Set an alarm for 7:00 AM"),
            Triple("🔊 Volume", Icons.Default.VolumeUp, "Set media volume to 80%"),
            Triple("📂 Files", Icons.Default.Folder, "Show recent downloaded files"),
            Triple("⚡ Battery", Icons.Default.BatteryChargingFull, "Check device battery level")
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ChatGptCard,
        contentColor = ChatGptTextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            if (!showDeviceTools) {
                Text(
                    text = "Add Attachment",
                    style = AuraTypography.titleMedium.copy(
                        color = ChatGptTextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Spacer(modifier = Modifier.height(16.dp))

                // 1. Camera
                Surface(
                    color = ChatGptSidebar,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onDismiss()
                            onTakePhoto()
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = Icons.Default.CameraAlt, contentDescription = null, tint = ChatGptAccentGreen, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(text = "Take Photo", style = AuraTypography.bodyLarge.copy(color = ChatGptTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                            Text(text = "Capture a picture to analyze or ask about", style = AuraTypography.bodySmall.copy(color = ChatGptTextSecondary, fontSize = 12.sp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 2. Photos / Gallery
                Surface(
                    color = ChatGptSidebar,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onDismiss()
                            onPickGallery()
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = Icons.Default.Image, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(text = "Photos & Gallery", style = AuraTypography.bodyLarge.copy(color = ChatGptTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                            Text(text = "Choose an image from your device storage", style = AuraTypography.bodySmall.copy(color = ChatGptTextSecondary, fontSize = 12.sp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 3. Document / Files
                Surface(
                    color = ChatGptSidebar,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onDismiss()
                            onPickDocument()
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = Icons.Default.Description, contentDescription = null, tint = Color(0xFFA855F7), modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(text = "Document & Files", style = AuraTypography.bodyLarge.copy(color = ChatGptTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                            Text(text = "Pick file, screenshot, or document", style = AuraTypography.bodySmall.copy(color = ChatGptTextSecondary, fontSize = 12.sp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 4. Device Controls Option
                Surface(
                    color = ChatGptSidebar,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showDeviceTools = true
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = null, tint = Color(0xFFFFB74D), modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Device Tools & Controls", style = AuraTypography.bodyLarge.copy(color = ChatGptTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                            Text(text = "Flashlight, volume, alarms, apps & system actions", style = AuraTypography.bodySmall.copy(color = ChatGptTextSecondary, fontSize = 12.sp))
                        }
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, tint = ChatGptTextSecondary, modifier = Modifier.size(16.dp))
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showDeviceTools = false }) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = ChatGptTextPrimary)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Device Tools & Actions",
                        style = AuraTypography.titleMedium.copy(
                            color = ChatGptTextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(tools) { (label, icon, prompt) ->
                        Surface(
                            color = ChatGptSidebar,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onToolSelected(prompt)
                                    onDismiss()
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(imageVector = icon, contentDescription = null, tint = ChatGptAccentGreen, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.width(14.dp))
                                Text(text = label, style = AuraTypography.bodyLarge.copy(color = ChatGptTextPrimary, fontSize = 14.sp))
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// Backward compatibility wrapper
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickToolsBottomSheet(
    onToolSelected: (prompt: String) -> Unit,
    onDismiss: () -> Unit
) {
    AuraAttachmentBottomSheet(
        onTakePhoto = {},
        onPickGallery = {},
        onPickDocument = {},
        onToolSelected = onToolSelected,
        onDismiss = onDismiss
    )
}
