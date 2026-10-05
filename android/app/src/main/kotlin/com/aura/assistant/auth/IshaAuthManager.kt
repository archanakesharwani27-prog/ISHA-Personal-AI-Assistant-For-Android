package com.aura.assistant.auth

import android.accounts.AccountManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.security.MessageDigest

/**
 * User Profile representation in AURA.
 */
data class UserProfile(
    val isLoggedIn: Boolean = false,
    val uid: String = "",
    val name: String = "Boss",
    val email: String = "",
    val provider: String = "GUEST",
    val avatarInitial: String = "B",
    val avatarColorHex: Long = 0xFF10A37F,
    val photoUrl: String? = null,
    val loginTimestamp: Long = 0L
)

/**
 * Central Authentication & Real-Time Cloud User Telemetry Manager for ISHA.
 * Backed by Firebase Cloud Backend (Auth + Firestore + Analytics)
 * Supports:
 * 1. Official Cloud Email/Password Sign Up & Sign In with Firebase Authentication
 * 2. Real-time active user tracking & presence monitoring in Cloud Firestore
 * 3. Official Google Sign-In with Play Services Auth & Firestore telemetry
 * 4. Device metadata (model, OS version) and active session logging
 * 5. Instant offline cache fallback
 */
object IshaAuthManager {

    private const val TAG = "IshaAuthManager"
    private const val PREFS_SESSION = "isha_auth_session"
    private const val PREFS_ACCOUNTS = "isha_registered_accounts"

    private const val KEY_IS_LOGGED_IN = "is_logged_in"
    private const val KEY_USER_UID = "user_uid"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_USER_EMAIL = "user_email"
    private const val KEY_AUTH_PROVIDER = "auth_provider"
    private const val KEY_PHOTO_URL = "photo_url"
    private const val KEY_LOGIN_TIME = "login_time"

    private var sessionPrefs: SharedPreferences? = null
    private var accountPrefs: SharedPreferences? = null

    private val _sessionState = MutableStateFlow(UserProfile())
    val sessionState: StateFlow<UserProfile> = _sessionState.asStateFlow()

    fun init(context: Context) {
        val app = context.applicationContext
        if (sessionPrefs == null) {
            sessionPrefs = app.getSharedPreferences(PREFS_SESSION, Context.MODE_PRIVATE)
            accountPrefs = app.getSharedPreferences(PREFS_ACCOUNTS, Context.MODE_PRIVATE)
            loadSession()
        }
    }

    private fun loadSession() {
        val p = sessionPrefs ?: return
        val isLoggedIn = p.getBoolean(KEY_IS_LOGGED_IN, false)
        val uid = p.getString(KEY_USER_UID, "") ?: ""
        val name = p.getString(KEY_USER_NAME, "Boss")?.ifBlank { "Boss" } ?: "Boss"
        val email = p.getString(KEY_USER_EMAIL, "") ?: ""
        val provider = p.getString(KEY_AUTH_PROVIDER, "GUEST") ?: "GUEST"
        val photoUrl = p.getString(KEY_PHOTO_URL, null)
        val loginTime = p.getLong(KEY_LOGIN_TIME, 0L)

        val initial = if (name.isNotBlank()) name.first().uppercase() else "B"
        val color = pickColorForName(name)

        _sessionState.value = UserProfile(
            isLoggedIn = isLoggedIn,
            uid = uid,
            name = name,
            email = email,
            provider = provider,
            avatarInitial = initial,
            avatarColorHex = color,
            photoUrl = photoUrl,
            loginTimestamp = loginTime
        )
        Log.i(TAG, "Loaded session: isLoggedIn=$isLoggedIn, user=$name ($provider, uid=$uid)")

        // Update presence to online if user is logged in
        if (isLoggedIn && uid.isNotBlank()) {
            syncPresenceToCloud(uid, true)
        }
    }

    /**
     * Build Official GoogleSignInClient for ActivityResult launcher.
     */
    fun getGoogleSignInClient(context: Context): GoogleSignInClient {
        val webClientId = "489681943195-i19ffgmn8oj5fo1gv7nbsi3qj7vio2nh.apps.googleusercontent.com"
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .requestProfile()
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    /**
     * Authenticate via Official Google Account with Cloud Firestore user logging.
     */
    fun loginWithGoogle(
        context: Context,
        email: String,
        displayName: String? = null,
        photoUrl: String? = null,
        onResult: ((Result<UserProfile>) -> Unit)? = null
    ) {
        init(context)
        val cleanEmail = email.trim().lowercase()
        val resolvedName = when {
            !displayName.isNullOrBlank() -> displayName
            cleanEmail.contains("@") -> cleanEmail.substringBefore("@").replace(".", " ").split(" ")
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            else -> "Boss"
        }
        val uid = "google_" + hashPassword(cleanEmail).take(16)

        saveSession(
            context = context,
            name = resolvedName,
            email = cleanEmail,
            provider = "GOOGLE",
            photoUrl = photoUrl,
            uid = uid
        )

        // Cloud Firestore telemetry & active user logging
        recordUserInFirestore(
            uid = uid,
            name = resolvedName,
            email = cleanEmail,
            provider = "GOOGLE",
            photoUrl = photoUrl
        )

        logAnalyticsEvent(context, FirebaseAnalytics.Event.LOGIN, "google", uid)
        onResult?.invoke(Result.success(_sessionState.value))
    }

    /**
     * Real-time Cloud Sign Up with Name, Email & Password via Firebase Auth & Firestore.
     */
    fun signUpWithEmail(
        context: Context,
        name: String,
        email: String,
        password: String,
        onResult: (Result<UserProfile>) -> Unit
    ) {
        init(context)
        val cleanName = name.trim().ifBlank { "Boss" }
        val cleanEmail = email.trim().lowercase()

        if (!Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
            onResult(Result.failure(IllegalArgumentException("Please enter a valid email address.")))
            return
        }
        if (password.length < 6) {
            onResult(Result.failure(IllegalArgumentException("Password must be at least 6 characters.")))
            return
        }

        // Cache locally for offline resilience
        cacheAccountLocally(cleanName, cleanEmail, password)

        try {
            val auth = FirebaseAuth.getInstance()
            auth.createUserWithEmailAndPassword(cleanEmail, password)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val firebaseUser = auth.currentUser
                        val uid = firebaseUser?.uid ?: ("user_" + hashPassword(cleanEmail).take(16))

                        // Update Firebase Auth display name
                        try {
                            firebaseUser?.updateProfile(
                                UserProfileChangeRequest.Builder()
                                    .setDisplayName(cleanName)
                                    .build()
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not update Firebase displayName: ${e.message}")
                        }

                        // Write to Cloud Firestore for real-time developer tracking
                        recordUserInFirestore(
                            uid = uid,
                            name = cleanName,
                            email = cleanEmail,
                            provider = "EMAIL",
                            isNewUser = true
                        )

                        // Save session locally
                        saveSession(
                            context = context,
                            name = cleanName,
                            email = cleanEmail,
                            provider = "EMAIL",
                            photoUrl = null,
                            uid = uid
                        )

                        logAnalyticsEvent(context, FirebaseAnalytics.Event.SIGN_UP, "email", uid)
                        logAnalyticsEvent(context, FirebaseAnalytics.Event.LOGIN, "email", uid)

                        Log.i(TAG, "Firebase cloud registration successful: $uid ($cleanEmail)")
                        onResult(Result.success(_sessionState.value))
                    } else {
                        val ex = task.exception
                        Log.e(TAG, "Firebase createUser failed", ex)
                        val message = when (ex) {
                            is FirebaseAuthUserCollisionException ->
                                "An account with this email already exists. Please Sign In."
                            else -> ex?.localizedMessage ?: "Sign up failed. Please check internet connection."
                        }
                        onResult(Result.failure(Exception(message, ex)))
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Firebase Auth not available, saving session locally", e)
            val fallbackUid = "local_" + hashPassword(cleanEmail).take(16)
            saveSession(context, cleanName, cleanEmail, "EMAIL", null, fallbackUid)
            onResult(Result.success(_sessionState.value))
        }
    }

    /**
     * Synchronous fallback overload for backward compatibility.
     */
    fun signUpWithEmail(
        context: Context,
        name: String,
        email: String,
        password: String
    ): Result<UserProfile> {
        init(context)
        val cleanName = name.trim().ifBlank { "Boss" }
        val cleanEmail = email.trim().lowercase()

        if (!Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
            return Result.failure(IllegalArgumentException("Please enter a valid email address."))
        }
        if (password.length < 6) {
            return Result.failure(IllegalArgumentException("Password must be at least 6 characters."))
        }

        cacheAccountLocally(cleanName, cleanEmail, password)
        val uid = "user_" + hashPassword(cleanEmail).take(16)
        saveSession(context, cleanName, cleanEmail, "EMAIL", null, uid)
        recordUserInFirestore(uid, cleanName, cleanEmail, "EMAIL", isNewUser = true)
        return Result.success(_sessionState.value)
    }

    /**
     * Real-time Cloud Sign In with Email & Password via Firebase Auth & Firestore.
     */
    fun signInWithEmail(
        context: Context,
        email: String,
        password: String,
        onResult: (Result<UserProfile>) -> Unit
    ) {
        init(context)
        val cleanEmail = email.trim().lowercase()

        if (!Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
            onResult(Result.failure(IllegalArgumentException("Please enter a valid email address.")))
            return
        }
        if (password.isEmpty()) {
            onResult(Result.failure(IllegalArgumentException("Please enter your password.")))
            return
        }

        try {
            val auth = FirebaseAuth.getInstance()
            auth.signInWithEmailAndPassword(cleanEmail, password)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val firebaseUser = auth.currentUser
                        val uid = firebaseUser?.uid ?: ("user_" + hashPassword(cleanEmail).take(16))
                        val resolvedName = firebaseUser?.displayName?.ifBlank { null }
                            ?: cleanEmail.substringBefore("@").replaceFirstChar { it.uppercase() }

                        // Update presence & active user record in Cloud Firestore
                        recordUserInFirestore(
                            uid = uid,
                            name = resolvedName,
                            email = cleanEmail,
                            provider = "EMAIL",
                            isNewUser = false
                        )

                        saveSession(
                            context = context,
                            name = resolvedName,
                            email = cleanEmail,
                            provider = "EMAIL",
                            photoUrl = firebaseUser?.photoUrl?.toString(),
                            uid = uid
                        )

                        logAnalyticsEvent(context, FirebaseAnalytics.Event.LOGIN, "email", uid)
                        Log.i(TAG, "Firebase cloud sign-in successful: $uid ($cleanEmail)")
                        onResult(Result.success(_sessionState.value))
                    } else {
                        val ex = task.exception
                        Log.w(TAG, "Firebase signIn failed: ${ex?.message}. Checking local credentials...")
                        // Check local account fallback if offline
                        val localResult = verifyLocalAccount(cleanEmail, password)
                        if (localResult.isSuccess) {
                            val localName = localResult.getOrNull() ?: "Boss"
                            val localUid = "user_" + hashPassword(cleanEmail).take(16)
                            saveSession(context, localName, cleanEmail, "EMAIL", null, localUid)
                            recordUserInFirestore(localUid, localName, cleanEmail, "EMAIL", isNewUser = false)
                            onResult(Result.success(_sessionState.value))
                        } else {
                            val msg = ex?.localizedMessage ?: "Invalid credentials or account does not exist."
                            onResult(Result.failure(Exception(msg, ex)))
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Firebase Auth not available, checking local credentials", e)
            val localResult = verifyLocalAccount(cleanEmail, password)
            if (localResult.isSuccess) {
                val localName = localResult.getOrNull() ?: "Boss"
                val localUid = "user_" + hashPassword(cleanEmail).take(16)
                saveSession(context, localName, cleanEmail, "EMAIL", null, localUid)
                onResult(Result.success(_sessionState.value))
            } else {
                onResult(Result.failure(e))
            }
        }
    }

    /**
     * Synchronous fallback overload for backward compatibility.
     */
    fun signInWithEmail(
        context: Context,
        email: String,
        password: String
    ): Result<UserProfile> {
        init(context)
        val cleanEmail = email.trim().lowercase()
        val localResult = verifyLocalAccount(cleanEmail, password)
        if (localResult.isSuccess) {
            val localName = localResult.getOrNull() ?: "Boss"
            val localUid = "user_" + hashPassword(cleanEmail).take(16)
            saveSession(context, localName, cleanEmail, "EMAIL", null, localUid)
            recordUserInFirestore(localUid, localName, cleanEmail, "EMAIL", isNewUser = false)
            return Result.success(_sessionState.value)
        }
        return Result.failure(localResult.exceptionOrNull() ?: Exception("Sign in failed"))
    }

    /**
     * Instant Guest / Demo Mode (continue immediately as Boss).
     */
    fun continueAsGuest(context: Context, name: String = "Boss") {
        init(context)
        val cleanName = name.trim().ifBlank { "Boss" }
        val guestUid = "guest_boss_ecosystem"

        saveSession(
            context = context,
            name = cleanName,
            email = "guest@aura.local",
            provider = "GUEST",
            uid = guestUid
        )

        recordUserInFirestore(
            uid = guestUid,
            name = cleanName,
            email = "guest@aura.local",
            provider = "GUEST"
        )
    }

    private fun saveSession(
        context: Context,
        name: String,
        email: String,
        provider: String,
        photoUrl: String? = null,
        uid: String = ""
    ) {
        init(context)
        val now = System.currentTimeMillis()
        val resolvedUid = uid.ifBlank { "user_" + hashPassword(email).take(16) }

        sessionPrefs?.edit()
            ?.putBoolean(KEY_IS_LOGGED_IN, true)
            ?.putString(KEY_USER_UID, resolvedUid)
            ?.putString(KEY_USER_NAME, name)
            ?.putString(KEY_USER_EMAIL, email)
            ?.putString(KEY_AUTH_PROVIDER, provider)
            ?.putString(KEY_PHOTO_URL, photoUrl)
            ?.putLong(KEY_LOGIN_TIME, now)
            ?.apply()

        val initial = if (name.isNotBlank()) name.first().uppercase() else "B"
        val color = pickColorForName(name)

        _sessionState.value = UserProfile(
            isLoggedIn = true,
            uid = resolvedUid,
            name = name,
            email = email,
            provider = provider,
            avatarInitial = initial,
            avatarColorHex = color,
            photoUrl = photoUrl,
            loginTimestamp = now
        )
        Log.i(TAG, "User logged in: $name ($email, uid=$resolvedUid) via $provider")
    }

    /**
     * Real-time Cloud Firestore User Telemetry.
     * Stored in the "users" collection so the developer can see every active user,
     * device model, login timestamps, and online status live in the Firebase Console!
     */
    private fun recordUserInFirestore(
        uid: String,
        name: String,
        email: String,
        provider: String,
        photoUrl: String? = null,
        isNewUser: Boolean = false
    ) {
        try {
            val firestore = FirebaseFirestore.getInstance()
            val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
            val osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

            val data = hashMapOf<String, Any>(
                "uid" to uid,
                "name" to name,
                "email" to email,
                "provider" to provider,
                "deviceModel" to deviceModel,
                "osVersion" to osVersion,
                "isOnline" to true,
                "lastLoginAt" to FieldValue.serverTimestamp(),
                "lastSeenAt" to FieldValue.serverTimestamp(),
                "appVersion" to "2.0.0",
                "distribution" to "Official"
            )
            if (photoUrl != null) {
                data["photoUrl"] = photoUrl
            }
            if (isNewUser) {
                data["createdAt"] = FieldValue.serverTimestamp()
            }

            firestore.collection("users").document(uid)
                .set(data, SetOptions.merge())
                .addOnSuccessListener {
                    Log.i(TAG, "Real-time user record updated in Firestore: $uid")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Firestore write warning: ${e.message}")
                }
        } catch (e: Exception) {
            Log.w(TAG, "Firestore unavailable: ${e.message}")
        }
    }

    /**
     * Updates real-time user online presence in Cloud Firestore.
     */
    fun syncPresenceToCloud(uid: String, isOnline: Boolean) {
        if (uid.isBlank()) return
        try {
            val firestore = FirebaseFirestore.getInstance()
            val updates = hashMapOf<String, Any>(
                "isOnline" to isOnline,
                "lastSeenAt" to FieldValue.serverTimestamp()
            )
            if (!isOnline) {
                updates["lastLogoutAt"] = FieldValue.serverTimestamp()
            }
            firestore.collection("users").document(uid).set(updates, SetOptions.merge())
        } catch (e: Exception) {
            Log.w(TAG, "Presence sync error: ${e.message}")
        }
    }

    /**
     * Logout and reset session, updating Firestore presence to offline.
     */
    fun logout(context: Context) {
        init(context)
        val currentUid = _sessionState.value.uid.ifBlank {
            sessionPrefs?.getString(KEY_USER_UID, "") ?: ""
        }

        // Mark user as offline in Firebase Firestore
        if (currentUid.isNotBlank()) {
            syncPresenceToCloud(currentUid, false)
        }

        // Firebase Auth sign out
        try {
            FirebaseAuth.getInstance().signOut()
        } catch (_: Exception) {}

        // Google Sign-In client sign out
        try {
            getGoogleSignInClient(context).signOut()
        } catch (_: Exception) {}

        // Clear local session preferences
        sessionPrefs?.edit()?.clear()?.apply()

        _sessionState.value = UserProfile(isLoggedIn = false)
        Log.i(TAG, "User logged out (uid=$currentUid)")
    }

    private fun logAnalyticsEvent(context: Context, eventName: String, method: String, uid: String) {
        try {
            val analytics = FirebaseAnalytics.getInstance(context)
            analytics.setUserId(uid)
            val bundle = Bundle().apply {
                putString(FirebaseAnalytics.Param.METHOD, method)
            }
            analytics.logEvent(eventName, bundle)
        } catch (e: Exception) {
            Log.w(TAG, "Analytics event logging error: ${e.message}")
        }
    }

    private fun cacheAccountLocally(name: String, email: String, password: String) {
        try {
            val ap = accountPrefs ?: return
            val userRecord = JSONObject().apply {
                put("name", name)
                put("email", email)
                put("passwordHash", hashPassword(password))
                put("createdAt", System.currentTimeMillis())
            }
            ap.edit().putString(email, userRecord.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed caching account locally: ${e.message}")
        }
    }

    private fun verifyLocalAccount(email: String, password: String): Result<String> {
        val ap = accountPrefs ?: return Result.failure(IllegalStateException("Storage unavailable"))
        val recordJson = ap.getString(email, null)
            ?: return Result.failure(NoSuchElementException("No account found with this email."))

        val record = JSONObject(recordJson)
        val storedHash = record.optString("passwordHash")
        val inputHash = hashPassword(password)

        if (storedHash != inputHash) {
            return Result.failure(SecurityException("Incorrect password."))
        }
        return Result.success(record.optString("name", "Boss"))
    }

    /**
     * Discovers registered Google accounts on the Android device for 1-tap fast access.
     */
    fun getDeviceGoogleAccounts(context: Context): List<String> {
        return try {
            val accountManager = AccountManager.get(context)
            val accounts = accountManager.getAccountsByType("com.google")
            accounts.mapNotNull { it.name?.trim() }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not query device Google accounts: ${e.message}")
            emptyList()
        }
    }

    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(password.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun pickColorForName(name: String): Long {
        val colors = listOf(
            0xFF10A37F, // Emerald Green
            0xFF0EA5E9, // Sky Blue
            0xFF8B5CF6, // Purple
            0xFFEC4899, // Pink
            0xFFF59E0B, // Amber
            0xFF059669  // Emerald
        )
        val hash = kotlin.math.abs(name.hashCode())
        return colors[hash % colors.size]
    }
}

/** Backward compatibility alias for AuraAuthManager */
val AuraAuthManager = IshaAuthManager

