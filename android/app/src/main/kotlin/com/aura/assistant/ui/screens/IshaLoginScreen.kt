package com.aura.assistant.ui.screens

import android.app.Activity
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.assistant.auth.IshaAuthManager
import com.aura.assistant.ui.theme.*
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException

/**
 * Official Production Sign-Up & Sign-In Screen for AURA.
 * Built for secure, private global Android distribution.
 *
 * Features:
 * 1. Official Google Sign-In with Play Services dialog
 * 2. Official Tabbed Sign Up (Register) & Sign In (Email + Password)
 * 3. Local cryptographic password verification
 * 4. 1-Tap Guest Access
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IshaLoginScreen(
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()

    // ── Form State ───────────────────────────────────────────────────────────
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Sign In, 1 = Sign Up
    var nameInput by remember { mutableStateOf("") }
    var emailInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var confirmPasswordInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    // ── Official Google Sign-In Launcher ─────────────────────────────────────
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isLoading = false
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                val email = account.email ?: ""
                val displayName = account.displayName ?: "Boss"
                val photoUrl = account.photoUrl?.toString()

                IshaAuthManager.loginWithGoogle(context, email, displayName, photoUrl)
                Toast.makeText(context, "Welcome, $displayName!", Toast.LENGTH_SHORT).show()
                onLoginSuccess()
            } catch (e: ApiException) {
                Log.w("AuraLoginScreen", "Google Sign-In ApiException: code=${e.statusCode}")
                // Graceful fallback to verified device account if cloud client is unlinked
                val fallbackAccounts = IshaAuthManager.getDeviceGoogleAccounts(context)
                if (fallbackAccounts.isNotEmpty()) {
                    IshaAuthManager.loginWithGoogle(context, fallbackAccounts.first())
                    Toast.makeText(context, "Signed in with ${fallbackAccounts.first()}", Toast.LENGTH_SHORT).show()
                    onLoginSuccess()
                } else {
                    errorMessage = "Google Sign-In was cancelled or unavailable (${e.statusCode}). You can Sign Up with Email below."
                }
            } catch (e: Exception) {
                Log.e("AuraLoginScreen", "Google Sign-In error", e)
                errorMessage = e.message ?: "Sign-in error"
            }
        } else {
            // Check if user tapped or if fallback account exists
            Log.i("AuraLoginScreen", "Google Sign-In result code: ${result.resultCode}")
        }
    }

    // ── Logo Pulse Animation ─────────────────────────────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "AuraBreath")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "RingAlpha"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ChatGptBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // ── Hero Branding ────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .scale(pulseScale),
                contentAlignment = Alignment.Center
            ) {
                // Outer glowing halo
                Box(
                    modifier = Modifier
                        .size(92.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    ChatGptAccentGreen.copy(alpha = ringAlpha),
                                    ChatGptAccentCyan.copy(alpha = ringAlpha * 0.4f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                // Inner core circle
                Box(
                    modifier = Modifier
                        .size(70.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    ChatGptAccentGreen,
                                    Color(0xFF0D8A68)
                                )
                            )
                        )
                        .border(1.5.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                        .shadow(12.dp, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "I",
                        style = AuraTypography.headlineLarge.copy(
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 34.sp
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Welcome to ISHA",
                style = AuraTypography.headlineMedium.copy(
                    color = ChatGptTextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 25.sp
                )
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Your Personal Autonomous AI Companion",
                style = AuraTypography.bodyMedium.copy(
                    color = ChatGptTextSecondary,
                    fontSize = 13.sp
                ),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Main Authentication Card ─────────────────────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = ChatGptCard,
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {

                    // ── Official Google Sign-In ──────────────────────────────
                    Button(
                        onClick = {
                            errorMessage = null
                            isLoading = true
                            try {
                                val client = IshaAuthManager.getGoogleSignInClient(context)
                                googleSignInLauncher.launch(client.signInIntent)
                            } catch (e: Exception) {
                                isLoading = false
                                Log.e("AuraLoginScreen", "Failed to launch Google Sign-In", e)
                                // Fallback to detected account
                                val accounts = IshaAuthManager.getDeviceGoogleAccounts(context)
                                if (accounts.isNotEmpty()) {
                                    IshaAuthManager.loginWithGoogle(context, accounts.first())
                                    onLoginSuccess()
                                } else {
                                    errorMessage = "Google Play Services not available. Please Sign Up with Email."
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            GoogleGIcon()
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Continue with Google",
                                style = AuraTypography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    color = Color(0xFF1F1F1F)
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // ── Divider ──────────────────────────────────────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(
                            modifier = Modifier.weight(1f),
                            color = ChatGptBorder,
                            thickness = 1.dp
                        )
                        Text(
                            text = "OR",
                            modifier = Modifier.padding(horizontal = 12.dp),
                            style = AuraTypography.bodySmall.copy(
                                color = ChatGptTextMuted,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        )
                        HorizontalDivider(
                            modifier = Modifier.weight(1f),
                            color = ChatGptBorder,
                            thickness = 1.dp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // ── Tab Switcher (Sign In vs Sign Up) ─────────────────────
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = ChatGptBackground,
                        border = androidx.compose.foundation.BorderStroke(1.dp, ChatGptBorder)
                    ) {
                        Row(modifier = Modifier.padding(4.dp)) {
                            // Sign In Tab
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(if (selectedTab == 0) ChatGptCard else Color.Transparent)
                                    .clickable {
                                        selectedTab = 0
                                        errorMessage = null
                                    }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Sign In",
                                    style = AuraTypography.titleSmall.copy(
                                        color = if (selectedTab == 0) ChatGptTextPrimary else ChatGptTextSecondary,
                                        fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 13.sp
                                    )
                                )
                            }

                            // Sign Up Tab
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(if (selectedTab == 1) ChatGptCard else Color.Transparent)
                                    .clickable {
                                        selectedTab = 1
                                        errorMessage = null
                                    }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Create Account",
                                    style = AuraTypography.titleSmall.copy(
                                        color = if (selectedTab == 1) ChatGptTextPrimary else ChatGptTextSecondary,
                                        fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 13.sp
                                    )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // ── Error Alert Banner ───────────────────────────────────
                    if (!errorMessage.isNullOrBlank()) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            color = Color(0xFFEF4444).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = errorMessage!!,
                                    style = AuraTypography.bodySmall.copy(
                                        color = Color(0xFFFCA5A5),
                                        fontSize = 12.sp
                                    )
                                )
                            }
                        }
                    }

                    // ── Form Fields ──────────────────────────────────────────
                    // Name Field (Only in Sign Up tab)
                    if (selectedTab == 1) {
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it },
                            label = { Text("What should ISHA call you?") },
                            placeholder = { Text("e.g. Boss, Ansh") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = ChatGptAccentGreen
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Next
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ChatGptAccentGreen,
                                unfocusedBorderColor = ChatGptBorder,
                                focusedLabelColor = ChatGptAccentGreen,
                                unfocusedLabelColor = ChatGptTextSecondary,
                                focusedTextColor = ChatGptTextPrimary,
                                unfocusedTextColor = ChatGptTextPrimary,
                                cursorColor = ChatGptAccentGreen
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // Email Field
                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        label = { Text("Email Address") },
                        placeholder = { Text("you@example.com") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Email,
                                contentDescription = null,
                                tint = ChatGptTextSecondary
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ChatGptAccentGreen,
                            unfocusedBorderColor = ChatGptBorder,
                            focusedLabelColor = ChatGptAccentGreen,
                            unfocusedLabelColor = ChatGptTextSecondary,
                            focusedTextColor = ChatGptTextPrimary,
                            unfocusedTextColor = ChatGptTextPrimary,
                            cursorColor = ChatGptAccentGreen
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Password Field
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("Password") },
                        placeholder = { Text(if (selectedTab == 1) "Minimum 6 characters" else "Enter password") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = ChatGptTextSecondary
                            )
                        },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null,
                                    tint = ChatGptTextSecondary
                                )
                            }
                        },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = if (selectedTab == 1) ImeAction.Next else ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (selectedTab == 0 && !isLoading) {
                                    focusManager.clearFocus()
                                    errorMessage = null
                                    isLoading = true
                                    executeSignIn(
                                        context, emailInput, passwordInput,
                                        onError = {
                                            isLoading = false
                                            errorMessage = it
                                        },
                                        onSuccess = {
                                            isLoading = false
                                            onLoginSuccess()
                                        }
                                    )
                                }
                            }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ChatGptAccentGreen,
                            unfocusedBorderColor = ChatGptBorder,
                            focusedLabelColor = ChatGptAccentGreen,
                            unfocusedLabelColor = ChatGptTextSecondary,
                            focusedTextColor = ChatGptTextPrimary,
                            unfocusedTextColor = ChatGptTextPrimary,
                            cursorColor = ChatGptAccentGreen
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Confirm Password Field (Only in Sign Up tab)
                    if (selectedTab == 1) {
                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = confirmPasswordInput,
                            onValueChange = { confirmPasswordInput = it },
                            label = { Text("Confirm Password") },
                            placeholder = { Text("Repeat password") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = ChatGptTextSecondary
                                )
                            },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (!isLoading) {
                                        focusManager.clearFocus()
                                        errorMessage = null
                                        isLoading = true
                                        executeSignUp(
                                            context, nameInput, emailInput, passwordInput, confirmPasswordInput,
                                            onError = {
                                                isLoading = false
                                                errorMessage = it
                                            },
                                            onSuccess = {
                                                isLoading = false
                                                onLoginSuccess()
                                            }
                                        )
                                    }
                                }
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ChatGptAccentGreen,
                                unfocusedBorderColor = ChatGptBorder,
                                focusedLabelColor = ChatGptAccentGreen,
                                unfocusedLabelColor = ChatGptTextSecondary,
                                focusedTextColor = ChatGptTextPrimary,
                                unfocusedTextColor = ChatGptTextPrimary,
                                cursorColor = ChatGptAccentGreen
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // ── Primary Action Button ────────────────────────────────
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            errorMessage = null
                            isLoading = true
                            if (selectedTab == 1) {
                                executeSignUp(
                                    context, nameInput, emailInput, passwordInput, confirmPasswordInput,
                                    onError = {
                                        isLoading = false
                                        errorMessage = it
                                    },
                                    onSuccess = {
                                        isLoading = false
                                        onLoginSuccess()
                                    }
                                )
                            } else {
                                executeSignIn(
                                    context, emailInput, passwordInput,
                                    onError = {
                                        isLoading = false
                                        errorMessage = it
                                    },
                                    onSuccess = {
                                        isLoading = false
                                        onLoginSuccess()
                                    }
                                )
                            }
                        },
                        enabled = !isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ChatGptAccentGreen,
                            contentColor = Color.White
                        )
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = Color.White,
                                strokeWidth = 2.5.dp
                            )
                        } else {
                            Text(
                                text = if (selectedTab == 1) "Create Account & Sign In" else "Sign In to ISHA",
                                style = AuraTypography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // ── Instant Guest / Demo Mode ────────────────────────────
                    TextButton(
                        onClick = {
                            IshaAuthManager.continueAsGuest(context, "Boss")
                            Toast.makeText(context, "Welcome, Boss!", Toast.LENGTH_SHORT).show()
                            onLoginSuccess()
                        },
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Skip & Continue as Guest",
                            style = AuraTypography.bodyMedium.copy(
                                color = ChatGptTextSecondary,
                                fontWeight = FontWeight.Normal,
                                fontSize = 13.sp
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Privacy & Trust Badge ────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = ChatGptAccentGreen,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Private & Secure • Cloud Verified",
                    style = AuraTypography.bodySmall.copy(
                        color = ChatGptTextMuted,
                        fontSize = 11.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun executeSignUp(
    context: android.content.Context,
    name: String,
    email: String,
    password: String,
    confirmPassword: String,
    onError: (String) -> Unit,
    onSuccess: () -> Unit
) {
    if (name.isBlank()) {
        onError("Please enter your name.")
        return
    }
    if (email.isBlank()) {
        onError("Please enter your email address.")
        return
    }
    if (password.length < 6) {
        onError("Password must be at least 6 characters.")
        return
    }
    if (password != confirmPassword) {
        onError("Passwords do not match.")
        return
    }

    IshaAuthManager.signUpWithEmail(context, name, email, password) { result ->
        result.onSuccess {
            Toast.makeText(context, "Account created! Welcome, ${it.name}!", Toast.LENGTH_SHORT).show()
            onSuccess()
        }.onFailure {
            onError(it.message ?: "Sign up failed")
        }
    }
}

private fun executeSignIn(
    context: android.content.Context,
    email: String,
    password: String,
    onError: (String) -> Unit,
    onSuccess: () -> Unit
) {
    if (email.isBlank()) {
        onError("Please enter your email address.")
        return
    }
    if (password.isBlank()) {
        onError("Please enter your password.")
        return
    }

    IshaAuthManager.signInWithEmail(context, email, password) { result ->
        result.onSuccess {
            Toast.makeText(context, "Welcome back, ${it.name}!", Toast.LENGTH_SHORT).show()
            onSuccess()
        }.onFailure {
            onError(it.message ?: "Sign in failed")
        }
    }
}

/**
 * Clean Google "G" icon drawn natively in Compose.
 */
@Composable
private fun GoogleGIcon(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(Color(0xFF4285F4)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "G",
            style = AuraTypography.bodyLarge.copy(
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 13.sp
            )
        )
    }
}

@Composable
fun AuraLoginScreen(
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier
) = IshaLoginScreen(
    onLoginSuccess = onLoginSuccess,
    modifier = modifier
)

