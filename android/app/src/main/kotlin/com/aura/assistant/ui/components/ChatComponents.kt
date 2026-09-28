package com.aura.assistant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.assistant.ui.contract.AuraChatMessage
import com.aura.assistant.ui.contract.MessageSender
import com.aura.assistant.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ChatMessageBubble(
    message: AuraChatMessage,
    modifier: Modifier = Modifier
) {
    val isUser = message.sender == MessageSender.USER
    val isSystem = message.sender == MessageSender.TOOL_SYSTEM

    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val timeString = remember(message.timestamp) { timeFormatter.format(Date(message.timestamp)) }

    if (isSystem) {
        // Centered Tool Execution Card
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = AuraSurfaceCard.copy(alpha = 0.85f),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AuraBorderNeon)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚙️ ",
                        fontSize = 12.sp
                    )
                    Text(
                        text = message.text,
                        style = AuraTypography.bodyMedium.copy(
                            color = AuraNeonCyan,
                            fontSize = 12.sp
                        )
                    )
                }
            }
        }
        return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            // AURA Avatar Pill
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(AuraNeonCyan, AuraCyberPurple)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "I",
                    color = Color.Black,
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Surface(
            color = if (isUser) AuraCyberPurple.copy(alpha = 0.25f) else AuraSurfaceCard,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = if (isUser) AuraCyberPurple.copy(alpha = 0.6f) else AuraBorderNeon
            ),
            modifier = Modifier.widthIn(max = 290.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (!isUser) {
                    Text(
                        text = "ISHA",
                        style = AuraTypography.labelSmall.copy(
                            color = AuraNeonCyan,
                            fontSize = 10.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                }

                Text(
                    text = message.text,
                    style = AuraTypography.bodyLarge.copy(
                        color = AuraTextPrimary,
                        fontSize = 14.sp
                    )
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = timeString,
                    style = AuraTypography.bodyMedium.copy(
                        color = AuraTextMuted,
                        fontSize = 10.sp
                    ),
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

@Composable
fun QuickSuggestionPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = AuraSurfaceCard.copy(alpha = 0.7f),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, AuraBorderNeon),
        modifier = modifier
            .clickable(onClick = onClick)
    ) {
        Text(
            text = text,
            style = AuraTypography.bodyMedium.copy(
                color = AuraTextSecondary,
                fontSize = 12.sp
            ),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
        )
    }
}

@Composable
fun AuraInputBar(
    onSendMessage: (String) -> Unit,
    onMicClick: () -> Unit,
    isMicActive: Boolean,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(AuraPitchBlack)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Mic Toggle Button
        IconButton(
            onClick = onMicClick,
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(
                    if (isMicActive) AuraNeonCyan.copy(alpha = 0.2f) else AuraSurfaceCard
                )
                .border(
                    width = 1.dp,
                    color = if (isMicActive) AuraNeonCyan else AuraBorderNeon,
                    shape = CircleShape
                )
        ) {
            Icon(
                imageVector = if (isMicActive) Icons.Default.Mic else Icons.Default.MicOff,
                contentDescription = "Microphone",
                tint = if (isMicActive) AuraNeonCyan else AuraTextSecondary,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Text Field
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = {
                Text(
                    text = "Ask ISHA anything...",
                    color = AuraTextMuted,
                    fontSize = 14.sp
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = AuraSurfaceCard,
                unfocusedContainerColor = AuraObsidianGlass,
                focusedBorderColor = AuraNeonCyan,
                unfocusedBorderColor = AuraBorderNeon,
                focusedTextColor = AuraTextPrimary,
                unfocusedTextColor = AuraTextPrimary
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (text.isNotBlank()) {
                        onSendMessage(text)
                        text = ""
                    }
                }
            ),
            modifier = Modifier
                .weight(1f)
                .height(52.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Send Button
        IconButton(
            onClick = {
                if (text.isNotBlank()) {
                    onSendMessage(text)
                    text = ""
                }
            },
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(
                    if (text.isNotBlank()) AuraNeonCyan else AuraSurfaceCard
                )
        ) {
            Icon(
                imageVector = Icons.Default.Send,
                contentDescription = "Send",
                tint = if (text.isNotBlank()) Color.Black else AuraTextMuted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
