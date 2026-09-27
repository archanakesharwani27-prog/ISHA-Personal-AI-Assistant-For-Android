package com.aura.assistant.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.assistant.config.ApiKeyManager
import com.aura.assistant.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Dedicated API Key Entry & Management Screen for AURA.
 * Allows entering, verifying, pasting, and saving Google Gemini API keys.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IshaApiKeyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var currentSavedKey by remember { mutableStateOf(ApiKeyManager.getApiKey(context)) }
    var inputKey by remember { mutableStateOf(currentSavedKey) }
    var isPasswordVisible by remember { mutableStateOf(false) }

    var isValidating by remember { mutableStateOf(false) }
    var validationSuccess by remember { mutableStateOf<String?>(null) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val hasCustomKey = currentSavedKey.isNotBlank()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ChatGptBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "ISHA API Key",
                        style = AuraTypography.titleMedium.copy(
                            color = ChatGptTextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = ChatGptTextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ChatGptBackground
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // ── Glowing Header Icon ───────────────────────────────────────────
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                ChatGptAccentGreen.copy(alpha = 0.35f),
                                Color.Transparent
                            )
                        )
                    )
                    .border(1.5.dp, ChatGptAccentGreen.copy(alpha = 0.6f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    tint = ChatGptAccentGreen,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "ISHA Brain Activation",
                style = AuraTypography.titleLarge.copy(
                    color = ChatGptTextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                )
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Enter your API key to power ISHA's intelligence, voice duplex mode, and device automation.",
                style = AuraTypography.bodyMedium.copy(
                    color = ChatGptTextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                ),
                modifier = Modifier.padding(horizontal = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Current Key Status Card ───────────────────────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = ChatGptCard,
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (hasCustomKey) ChatGptAccentGreen.copy(alpha = 0.15f)
                                else AuraAmberGold.copy(alpha = 0.15f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (hasCustomKey) Icons.Default.CheckCircle else Icons.Default.Info,
                            contentDescription = null,
                            tint = if (hasCustomKey) ChatGptAccentGreen else AuraAmberGold,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (hasCustomKey) "Custom API Key Active" else "Default System Key In Use",
                            style = AuraTypography.bodyMedium.copy(
                                color = ChatGptTextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                        )
                        Text(
                            text = if (hasCustomKey) ApiKeyManager.maskKey(currentSavedKey) else "Using built-in verified key",
                            style = AuraTypography.bodySmall.copy(
                                color = ChatGptTextMuted,
                                fontSize = 12.sp
                            )
                        )
                    }

                    if (hasCustomKey) {
                        TextButton(
                            onClick = {
                                ApiKeyManager.clearApiKey(context)
                                currentSavedKey = ""
                                inputKey = ""
                                validationSuccess = null
                                validationError = null
                                Toast.makeText(context, "Reverted to default key", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text(
                                text = "Reset",
                                color = AuraAlertRed,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── API Key Input Field ───────────────────────────────────────────
            OutlinedTextField(
                value = inputKey,
                onValueChange = {
                    inputKey = it
                    validationSuccess = null
                    validationError = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("ISHA API Key", color = ChatGptTextMuted) },
                placeholder = { Text("Paste your API key here...", color = ChatGptTextMuted) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.VpnKey,
                        contentDescription = null,
                        tint = ChatGptAccentGreen
                    )
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Paste from clipboard button
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clip = clipboard?.primaryClip
                                if (clip != null && clip.itemCount > 0) {
                                    val text = clip.getItemAt(0)?.text?.toString()?.trim()
                                    if (!text.isNullOrBlank()) {
                                        inputKey = text
                                        validationSuccess = null
                                        validationError = null
                                        Toast.makeText(context, "Pasted from clipboard", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = "Paste",
                                tint = ChatGptTextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Toggle visibility
                        IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                            Icon(
                                imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (isPasswordVisible) "Hide Key" else "Show Key",
                                tint = ChatGptTextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Clear input
                        if (inputKey.isNotEmpty()) {
                            IconButton(onClick = { inputKey = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear",
                                    tint = ChatGptTextMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                },
                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = ChatGptInputBubble,
                    unfocusedContainerColor = ChatGptInputBubble,
                    focusedBorderColor = ChatGptAccentGreen,
                    unfocusedBorderColor = ChatGptBorder,
                    focusedTextColor = ChatGptTextPrimary,
                    unfocusedTextColor = ChatGptTextPrimary,
                    cursorColor = ChatGptAccentGreen
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() })
            )

            // ── Validation Feedback Banners ───────────────────────────────────
            AnimatedVisibility(visible = validationSuccess != null) {
                Surface(
                    color = ChatGptAccentGreen.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptAccentGreen.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = ChatGptAccentGreen,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = validationSuccess ?: "",
                            style = AuraTypography.bodySmall.copy(
                                color = ChatGptAccentGreen,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                }
            }

            AnimatedVisibility(visible = validationError != null) {
                Surface(
                    color = AuraAlertRed.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AuraAlertRed.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = AuraAlertRed,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = validationError ?: "",
                            style = AuraTypography.bodySmall.copy(
                                color = AuraAlertRed,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ── Save & Verify Button ──────────────────────────────────────────
            Button(
                onClick = {
                    keyboardController?.hide()
                    val keyToSave = inputKey.trim()
                    if (keyToSave.isBlank()) {
                        validationError = "Please enter an API key"
                        return@Button
                    }

                    isValidating = true
                    validationError = null
                    validationSuccess = null

                    coroutineScope.launch {
                        val result = ApiKeyManager.validateKeyOnline(keyToSave)
                        isValidating = false
                        result.onSuccess {
                            ApiKeyManager.saveApiKey(context, keyToSave)
                            currentSavedKey = keyToSave
                            validationSuccess = "✅ API Key verified with Google & saved successfully!"
                            Toast.makeText(context, "API Key updated successfully!", Toast.LENGTH_SHORT).show()
                        }.onFailure { e ->
                            validationError = e.message ?: "Verification failed"
                        }
                    }
                },
                enabled = !isValidating && inputKey.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ChatGptAccentGreen,
                    contentColor = Color.White,
                    disabledContainerColor = ChatGptCard,
                    disabledContentColor = ChatGptTextMuted
                )
            ) {
                if (isValidating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Verifying with Google...",
                        style = AuraTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Verified,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Verify & Save Key",
                        style = AuraTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ── Get Free API Key Card (Google AI Studio Link) ──────────────────
            Surface(
                color = ChatGptCard,
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        try {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://aistudio.google.com/app/apikey")
                            )
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Could not open browser", Toast.LENGTH_SHORT).show()
                        }
                    }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF4285F4).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = Color(0xFF4285F4),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Get Free API Key",
                            style = AuraTypography.bodyLarge.copy(
                                color = ChatGptTextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                        )
                        Text(
                            text = "Google AI Studio • 100% Free Tier",
                            style = AuraTypography.bodySmall.copy(
                                color = ChatGptTextSecondary,
                                fontSize = 12.sp
                            )
                        )
                    }

                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "Open",
                        tint = ChatGptTextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Step-by-Step Instructions ─────────────────────────────────────
            Surface(
                color = ChatGptSidebar,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Quick Setup Guide:",
                        style = AuraTypography.titleMedium.copy(
                            color = ChatGptTextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "1. Tap above to open Google AI Studio\n2. Sign in with any Google account\n3. Click 'Create API key'\n4. Copy and paste it here & tap 'Verify & Save'",
                        style = AuraTypography.bodySmall.copy(
                            color = ChatGptTextMuted,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun AuraApiKeyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) = IshaApiKeyScreen(
    onBack = onBack,
    modifier = modifier
)

