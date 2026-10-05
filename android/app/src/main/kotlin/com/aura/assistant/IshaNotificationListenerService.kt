package com.aura.assistant

import android.app.Notification
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * ISHA Smart Notification Listener
 *
 * Features:
 *  1. Captures ALL notifications (WhatsApp, SMS, Gmail, Delivery, Payment/OTP)
 *  2. Auto-classifies by priority: PERSONAL / DELIVERY / PAYMENT / OTP / PROMO
 *  3. Speaks important notifications via TTS
 *  4. Forwards to Flutter for persistent history (survives notification bar clear)
 *  5. Media session access for transport controls
 */
open class IshaNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "IshaNotifService"
        const val ACTION_NOTIF = "com.aura.NOTIFICATION"

        const val ACTION_CALL_EVENT = "com.aura.CALL_EVENT"

        // Priority categories for TTS + UI coloring
        const val PRIORITY_PERSONAL  = "personal"   // WhatsApp personal msg, SMS
        const val PRIORITY_DELIVERY  = "delivery"   // Zomato, Swiggy, Amazon delivery
        const val PRIORITY_PAYMENT   = "payment"    // UPI, bank, OTP
        const val PRIORITY_OTP       = "otp"
        const val PRIORITY_PROMO     = "promo"      // Muted — no TTS
        const val PRIORITY_SYSTEM    = "system"

        // Active instance pointer for query
        @Volatile var instance: IshaNotificationListenerService? = null

        // In-memory cache of active posted notifications — resilient against OS unbinds
        val postedNotificationCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, Any>>()

        // Active incoming VoIP call state & PendingIntents
        @Volatile var activeCallPackage: String? = null
        @Volatile var activeCallTitle: String? = null
        @Volatile var activeCallAnswerIntent: android.app.PendingIntent? = null
        @Volatile var activeCallDeclineIntent: android.app.PendingIntent? = null

        fun answerActiveCall(context: android.content.Context): Boolean {
            val intent = activeCallAnswerIntent
            if (intent != null) {
                try {
                    intent.send()
                    activeCallAnswerIntent = null
                    return true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return false
        }

        fun declineActiveCall(context: android.content.Context): Boolean {
            val intent = activeCallDeclineIntent
            if (intent != null) {
                try {
                    intent.send()
                    activeCallDeclineIntent = null
                    return true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return false
        }

        fun clearAllNotifications(): Boolean {
            val inst = instance ?: return false
            return try {
                inst.cancelAllNotifications()
                postedNotificationCache.clear()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel all notifications", e)
                false
            }
        }

        fun dismissNotification(queryOrKey: String): Boolean {
            val inst = instance ?: return false
            return try {
                val active = inst.activeNotifications ?: return false
                val clean = queryOrKey.lowercase().trim()
                val target = active.firstOrNull { sbn ->
                    sbn.key == queryOrKey ||
                    sbn.packageName.contains(clean, ignoreCase = true) ||
                    sbn.notification.extras.getString(Notification.EXTRA_TITLE)?.contains(clean, ignoreCase = true) == true ||
                    sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.contains(clean, ignoreCase = true) == true
                }
                if (target != null) {
                    inst.cancelNotification(target.key)
                    postedNotificationCache.remove(target.key)
                    true
                } else false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dismiss notification for query: $queryOrKey", e)
                false
            }
        }
        private val BLOCKED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.google.android.gms",
            "com.aura.assistant",
            "com.isha.assistant",
        )

        // Packages that are always personal messages
        private val PERSONAL_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.facebook.orca",     // Messenger
            "com.instagram.android",
            "com.snapchat.android",
            "com.google.android.apps.messaging",  // SMS
            "com.samsung.android.messaging",
            "com.android.mms",
            "org.telegram.messenger",
            "com.discord",
            "com.skype.raider",
            "com.viber.voip",
            "com.google.android.apps.tachyon", // Google Meet
            "com.google.android.apps.meetings",
            "us.zoom.videomeetings",
        )

        // Delivery apps
        private val DELIVERY_PACKAGES = setOf(
            "in.swiggy.android",
            "app.zomato",
            "com.amazon.mShop.android.shopping",
            "com.flipkart.android",
            "com.meesho.supply",
            "in.blinkit",
            "com.bigbasket.grocery",
            "com.dunzo.user",
        )

        // Payment / banking
        private val PAYMENT_PACKAGES = setOf(
            "com.google.android.apps.nbu.paisa.user",  // Google Pay
            "net.one97.paytm",
            "com.phonepe.app",
            "com.whatsapp",   // WhatsApp Pay
            "com.amazon.pay",
        )

        fun getActiveNotificationsList(): List<Map<String, Any>> {
            val svc = instance
            val list = mutableListOf<Map<String, Any>>()
            val sbns = svc?.activeNotifications
            if (svc != null && sbns != null && sbns.isNotEmpty()) {
                val pm = svc.packageManager
                for (sbn in sbns) {
                    val pkg = sbn.packageName ?: continue
                    if (pkg in BLOCKED_PACKAGES) continue
                    val extras = sbn.notification?.extras ?: continue
                    val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
                    val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()?.trim()
                        ?: extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
                        ?: ""

                    val lines = extras.getCharSequenceArray(android.app.Notification.EXTRA_TEXT_LINES)
                    val subText = extras.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""
                    val convTitle = extras.getCharSequence("android.conversationTitle")?.toString()?.trim() ?: ""

                    val combinedText = if (lines != null && lines.isNotEmpty()) {
                        val lineList = lines.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
                        if (lineList.isNotEmpty()) {
                            lineList.joinToString(" \n• ")
                        } else text
                    } else text

                    if (title.isBlank() && combinedText.isBlank()) continue
                    val appName = try {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    } catch (_: Exception) { pkg }

                    val item = mapOf<String, Any>(
                        "package" to pkg,
                        "appName" to appName,
                        "title" to (if (convTitle.isNotEmpty() && convTitle != title) "$title ($convTitle)" else title),
                        "text" to combinedText,
                        "subText" to subText,
                        "timestamp" to sbn.postTime
                    )
                    list.add(item)
                    postedNotificationCache[sbn.key ?: (pkg + "_" + sbn.id)] = item
                }
            }
            if (list.isEmpty() && postedNotificationCache.isNotEmpty()) {
                return postedNotificationCache.values.toList()
            }
            return list
        }
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile private var lastAnnouncedCallTime: Long = 0L

    override fun onCreate() {
        super.onCreate()
        initTts()
    }

    private fun initTts() {
        if (tts != null && ttsReady) return
        tts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                try {
                    val voices = tts?.voices
                    val naturalVoice = voices?.firstOrNull { v ->
                        val loc = v.locale
                        val langMatch = loc.language == "hi" || (loc.language == "en" && loc.country == "IN")
                        val isHighQuality = v.name.contains("neural", ignoreCase = true) ||
                                            v.name.contains("network", ignoreCase = true) ||
                                            v.name.contains("wavenet", ignoreCase = true)
                        langMatch && isHighQuality
                    } ?: voices?.firstOrNull { v ->
                        v.locale.language == "hi" || (v.locale.language == "en" && v.locale.country == "IN")
                    }

                    if (naturalVoice != null) {
                        tts?.voice = naturalVoice
                    } else {
                        val inLocale = Locale("en", "IN")
                        if (tts?.isLanguageAvailable(inLocale) ?: -1 >= TextToSpeech.LANG_AVAILABLE) {
                            tts?.language = inLocale
                        } else {
                            tts?.language = Locale.getDefault()
                        }
                    }
                } catch (_: Exception) {
                    tts?.language = Locale.getDefault()
                }
                tts?.setSpeechRate(0.92f)
                tts?.setPitch(1.0f)
                ttsReady = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        // Tell WakeWordService to mute mic — prevents self-echo
                        WakeWordService.isTtsSpeaking = true
                        sendBroadcast(Intent(WakeWordService.ACTION_TTS_START).apply {
                            `package` = applicationContext.packageName
                        })
                    }
                    override fun onDone(utteranceId: String?) {
                        WakeWordService.isTtsSpeaking = false
                        sendBroadcast(Intent(WakeWordService.ACTION_TTS_END).apply {
                            `package` = applicationContext.packageName
                        })
                    }
                    @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId, errorCode)"))
                    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                    override fun onError(utteranceId: String?) {
                        WakeWordService.isTtsSpeaking = false
                        sendBroadcast(Intent(WakeWordService.ACTION_TTS_END).apply {
                            `package` = applicationContext.packageName
                        })
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        WakeWordService.isTtsSpeaking = false
                        sendBroadcast(Intent(WakeWordService.ACTION_TTS_END).apply {
                            `package` = applicationContext.packageName
                        })
                    }
                })
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        try {
            com.aura.assistant.sync.IshaCrossDeviceBridge.init(applicationContext)
        } catch (e: Exception) {
            android.util.Log.w("IshaNotifListener", "Failed to initialize CrossDeviceBridge: ${e.message}")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance == this) instance = null
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        sbn ?: return
        sbn.key?.let { postedNotificationCache.remove(it) }
        if (sbn.packageName == activeCallPackage) {
            activeCallPackage = null
            activeCallTitle = null
            activeCallAnswerIntent = null
            activeCallDeclineIntent = null
            try {
                startService(Intent(this, WakeWordService::class.java).apply { action = WakeWordService.ACTION_RESUME })
            } catch (_: Exception) {}

            val callBroadcast = Intent(ACTION_CALL_EVENT).apply {
                `package` = applicationContext.packageName
                putExtra("state", "idle")
                putExtra("package", sbn.packageName)
            }
            sendBroadcast(callBroadcast)
            IshaCallAnnouncerManager.onCallEnded(applicationContext)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return
        if (pkg in BLOCKED_PACKAGES) return

        // Skip group summary notifications (they are duplicates)
        val flags = sbn.notification?.flags ?: 0
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text  = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?: ""

        if (title.isBlank() && text.isBlank()) return

        val pm = applicationContext.packageManager
        val appName = try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }

        // ── Check if this is an incoming VoIP Call (WhatsApp, Meet, Telegram) ──
        val isCallCategory = sbn.notification?.category == Notification.CATEGORY_CALL
        val lowerText = "$title $text".lowercase()
        val actions = sbn.notification?.actions
        val hasCallActions = actions != null && actions.any {
            val at = (it.title?.toString() ?: "").lowercase()
            at.contains("answer") || at.contains("accept") || at.contains("decline") || at.contains("reject")
        }
        val isVoipIncoming = isCallCategory || hasCallActions ||
            ((pkg in PERSONAL_PACKAGES || pkg.contains("tachyon") || pkg.contains("meet") || pkg.contains("zoom")) &&
             (lowerText.contains("incoming") || lowerText.contains("calling") || lowerText.contains("voice call") || lowerText.contains("video call")))

        if (isVoipIncoming && (hasCallActions || isCallCategory)) {
            activeCallPackage = pkg
            activeCallTitle = title
            for (action in actions ?: emptyArray()) {
                val at = (action.title?.toString() ?: "").lowercase()
                if (at.contains("answer") || at.contains("accept") || at.contains("उत्तर")) {
                    activeCallAnswerIntent = action.actionIntent
                } else if (at.contains("decline") || at.contains("reject") || at.contains("dismiss") || at.contains("अस्वीकार")) {
                    activeCallDeclineIntent = action.actionIntent
                }
            }

            try {
                startService(Intent(this, WakeWordService::class.java).apply { action = WakeWordService.ACTION_PAUSE })
            } catch (_: Exception) {}

            val callBroadcast = Intent(ACTION_CALL_EVENT).apply {
                `package` = applicationContext.packageName
                putExtra("state", "ringing")
                putExtra("callerName", title.ifBlank { appName })
                putExtra("number", "")
                putExtra("type", "voip")
                putExtra("appName", appName)
                putExtra("package", pkg)
            }
            sendBroadcast(callBroadcast)
            // Immediately trigger IshaCallAnnouncerManager directly for instant pre-ringing announcement & voice mic
            IshaCallAnnouncerManager.onCallRinging(applicationContext, title.ifBlank { appName }, "")
            return
        }

        val priority = classifyPriority(pkg, title, text)

        // Broadcast to Flutter for persistent history
        val broadcast = Intent(ACTION_NOTIF).apply {
            `package` = applicationContext.packageName
            putExtra("package",   pkg)
            putExtra("appName",   appName)
            putExtra("title",     title)
            putExtra("text",      text)
            putExtra("timestamp", sbn.postTime)
            putExtra("priority",  priority)
        }
        sendBroadcast(broadcast)

    }

    private fun classifyPriority(pkg: String, title: String, text: String): String {
        val combined = "$title $text".lowercase()

        // OTP detection (highest financial priority)
        if (combined.matches(Regex(".*\\b\\d{4,8}\\b.*")) &&
            (combined.contains("otp") || combined.contains("one-time") ||
             combined.contains("verification code") || combined.contains("your code"))) {
            return PRIORITY_OTP
        }

        // Personal messages
        if (pkg in PERSONAL_PACKAGES) return PRIORITY_PERSONAL

        // Delivery status
        if (pkg in DELIVERY_PACKAGES ||
            combined.contains("delivered") || combined.contains("out for delivery") ||
            combined.contains("arriving") || combined.contains("picked up") ||
            combined.contains("order placed") || combined.contains("shipped")) {
            return PRIORITY_DELIVERY
        }

        // Payment / banking
        if (pkg in PAYMENT_PACKAGES ||
            combined.contains("debited") || combined.contains("credited") ||
            combined.contains("payment") || combined.contains("₹") ||
            combined.contains("upi") || combined.contains("transfer")) {
            return PRIORITY_PAYMENT
        }

        // Promo / spam detection
        if (combined.contains("offer") || combined.contains("discount") ||
            combined.contains("% off") || combined.contains("sale") ||
            combined.contains("exclusive deal") || combined.contains("coupon")) {
            return PRIORITY_PROMO
        }

        return PRIORITY_SYSTEM
    }

    private fun announceIncomingCall(appName: String, caller: String) {
        if (!ttsReady || tts == null) {
            initTts()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (ttsReady && tts != null) {
                    announceIncomingCall(appName, caller)
                }
            }, 800L)
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastAnnouncedCallTime < 2500L) return
        lastAnnouncedCallTime = now

        val utteranceId = "AURA_VOIP_CALL_${System.currentTimeMillis()}"
        val callerLabel = caller.ifBlank { "Unknown caller" }
        val appLabel = when {
            appName.contains("WhatsApp", ignoreCase = true) -> "WhatsApp"
            appName.contains("Telegram", ignoreCase = true) -> "Telegram"
            else -> appName
        }
        val text = "Incoming call from $callerLabel via $appLabel identified by ISHA"
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_RING)
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    private fun extractOtp(text: String): String {
        val match = Regex("\\b(\\d{4,8})\\b").find(text)
        return match?.value?.let { it.chunked(1).joinToString(" ") } ?: "check your notification"
    }
}

/** Backward compatibility subclass for AuraNotificationListenerService */
class AuraNotificationListenerService : IshaNotificationListenerService() {
    companion object {
        var instance: IshaNotificationListenerService?
            get() = IshaNotificationListenerService.instance
            set(value) { IshaNotificationListenerService.instance = value }
        const val ACTION_NOTIF = IshaNotificationListenerService.ACTION_NOTIF
        const val ACTION_CALL_EVENT = IshaNotificationListenerService.ACTION_CALL_EVENT
        fun answerActiveCall(context: android.content.Context) = IshaNotificationListenerService.answerActiveCall(context)
        fun declineActiveCall(context: android.content.Context) = IshaNotificationListenerService.declineActiveCall(context)
        fun clearAllNotifications() = IshaNotificationListenerService.clearAllNotifications()
        fun dismissNotification(key: String) = IshaNotificationListenerService.dismissNotification(key)
        fun getActiveNotificationsList() = IshaNotificationListenerService.getActiveNotificationsList()
    }
}


