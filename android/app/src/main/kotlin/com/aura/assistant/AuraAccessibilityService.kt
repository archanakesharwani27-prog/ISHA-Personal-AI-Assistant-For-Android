package com.aura.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.net.URLEncoder

/**
 * Full autonomous UI control service for AURA.
 *
 * Capabilities:
 *  - WhatsApp auto-send (existing)
 *  - Play Store install/uninstall (existing)
 *  - Gmail compose + send (NEW)
 *  - Instagram DM (NEW)
 *  - Facebook post (NEW)
 *  - YouTube like, subscribe, and skip-ad automation (NEW)
 *  - System-wide screen capture without prompt on Android 11+ (NEW)
 *  - Swipe gestures via GestureDescription (NEW)
 *  - Type into focused text fields (NEW)
 */
class AuraAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AuraAccessibilityService? = null

        private const val TAG = "AuraA11y"

        const val ACTION_PLAN_DONE = "com.aura.A11Y_PLAN_DONE"
        const val EXTRA_PLAN_TAG   = "planTag"
        const val EXTRA_SUCCESS    = "success"

        // Package names
        private const val PKG_WHATSAPP   = "com.whatsapp"
        private const val PKG_WA_BIZ     = "com.whatsapp.w4b"
        private const val PKG_PLAY       = "com.android.vending"
        private const val PKG_INSTALLER  = "com.google.android.packageinstaller"
        private const val PKG_INSTALLER2 = "com.android.packageinstaller"
        private const val PKG_GMAIL      = "com.google.android.gm"
        private const val PKG_INSTAGRAM  = "com.instagram.android"
        private const val PKG_FACEBOOK   = "com.facebook.katana"
        private const val PKG_YOUTUBE    = "com.google.android.youtube"

        // Hint lists for WhatsApp / install / uninstall (enhanced for multilingual & IDs)
        private val SEND_HINTS     = listOf("send", "भेजें", "bhejo")
        private val LIKE_HINTS     = listOf("like", "heart", "favorite", "save to your library", "add to library")
        private val INSTALL_HINTS  = listOf(
            // English — Google Play Store button labels on all OEMs
            "install", "download", "update", "get", "buy",
            // Hindi (Play Store localized labels)
            "स्थापित करें", "डाउनलोड करें", "अपडेट करें", "प्राप्त करें",
            // Tamil, Telugu, Bengali (common in India)
            "நிறுவு", "డౌన్లోడ్", "ইনস্টল করুন",
        )
        private val UNINSTALL_HINTS = listOf("ok", "uninstall", "delete", "remove", "un-install", "uninstall anyway", "theek hai")

        // YouTube hints
        private val YOUTUBE_LIKE_HINTS = listOf(
            "like this video", "like", "i like this", "पसंद करें", "like button", "thumbs up"
        )
        private val YOUTUBE_SUBSCRIBE_HINTS = listOf(
            "subscribe", "subscribed", "सदस्यता लें"
        )
        private val YOUTUBE_SKIP_AD_HINTS = listOf(
            "skip ad", "skip ads", "skip", "विज्ञापन छोड़ें", "skip in"
        )
    }

    // ── Legacy pending booleans ────────────────────────────────────────────────
    @Volatile private var pendingWhatsappSend  = false
    @Volatile private var pendingAutoInstall   = false
    @Volatile private var pendingAutoUninstall = false

    // ── Hands-Free Mobile Agent Pending States (Panda-Style Engine) ───────────
    @Volatile var pendingYouTubeAutoPlay = false
    @Volatile var pendingYouTubeQuery = ""
    @Volatile var pendingHotspotToggle: Boolean? = null
    @Volatile var pendingNavigationMode: String? = null
    @Volatile var pendingWifiToggle: Boolean? = null
    @Volatile var pendingBluetoothToggle: Boolean? = null

    // ── New action queue ───────────────────────────────────────────────────────
    private data class A11yStep(
        val clickHint:  String? = null,    // Text/contentDescription to find & click
        val typeText:   String? = null,    // Text to type into focused field
        val verifyHint: String? = null,    // Optional: confirm this text is now visible (plan success marker)
        val delayMs:    Long    = 600,
    )

    @Volatile private var pendingPlan: List<A11yStep>? = null
    @Volatile private var planTag: String = ""
    private var planIndex = 0
    private val stepHandler = Handler(Looper.getMainLooper())
    // Timeout runnable: if plan doesn't complete in 25s → broadcast failure
    private val planTimeoutRunnable = Runnable { onPlanFinished(false) }

    private val stepRunnable = Runnable { executeNextStep() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AuraAccessibilityService connected")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ── Event Listener ────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val root = rootInActiveWindow ?: return

        // Legacy handlers
        if (pendingWhatsappSend && (pkg == PKG_WHATSAPP || pkg == PKG_WA_BIZ)) {
            if (clickFirstMatch(root, SEND_HINTS)) pendingWhatsappSend = false
        }
        if (pendingAutoInstall) {
            // Only tap Install on the Play Store or Package Installer screens
            val isPlayStore = pkg == "com.android.vending" || pkg == PKG_INSTALLER || pkg == PKG_INSTALLER2
            if (isPlayStore && clickFirstMatch(root, INSTALL_HINTS)) {
                pendingAutoInstall = false
                Log.i(TAG, "Auto-install tapped Install button in $pkg")
            }
        }
        if (pendingAutoUninstall) {
            if (clickFirstMatch(root, UNINSTALL_HINTS)) pendingAutoUninstall = false
        }
        if (pkg == PKG_YOUTUBE) {
            // Auto skip ads if skip button is visible on YouTube
            clickFirstMatch(root, YOUTUBE_SKIP_AD_HINTS)
            if (pendingYouTubeAutoPlay) {
                if (clickYouTubeFirstVideo(root, pendingYouTubeQuery)) {
                    pendingYouTubeAutoPlay = false
                    Log.i(TAG, "YouTube first video clicked successfully via onAccessibilityEvent!")
                }
            }
        }

        // Settings Automations (Hotspot, Navigation Mode, Wi-Fi, Bluetooth)
        if (pkg.contains("settings") || pkg == "com.android.settings") {
            if (pendingHotspotToggle != null) {
                if (performHotspotToggle(root, pendingHotspotToggle!!)) {
                    pendingHotspotToggle = null
                    Log.i(TAG, "Hotspot toggled successfully via onAccessibilityEvent!")
                }
            }
            if (pendingNavigationMode != null) {
                if (performNavigationModeClick(root, pendingNavigationMode!!)) {
                    pendingNavigationMode = null
                    Log.i(TAG, "Navigation mode switched successfully via onAccessibilityEvent!")
                }
            }
            if (pendingWifiToggle != null) {
                if (findAndToggleSwitch(listOf("wi-fi", "wifi", "internet"), pendingWifiToggle)) {
                    pendingWifiToggle = null
                }
            }
            if (pendingBluetoothToggle != null) {
                if (findAndToggleSwitch(listOf("bluetooth"), pendingBluetoothToggle)) {
                    pendingBluetoothToggle = null
                }
            }
        }

        // Queue-based plan execution
        val plan = pendingPlan ?: return
        if (planIndex >= plan.size) {
            // All steps done — check verifyHint on the last step if present
            val lastStep = plan.lastOrNull()
            if (lastStep?.verifyHint != null) {
                val verified = findNode(root, listOf(lastStep.verifyHint.lowercase())) != null
                onPlanFinished(verified)
            } else {
                onPlanFinished(true)
            }
            return
        }
        val step = plan[planIndex]

        // Try click step
        if (step.clickHint != null) {
            if (clickFirstMatch(root, listOf(step.clickHint.lowercase()))) {
                planIndex++
                scheduleNextStep(plan)
            }
        }

        // Try type step (fires once per event after a click)
        if (step.typeText != null) {
            val focusedNode = findFocusedEditText(root)
            if (focusedNode != null) {
                typeIntoNode(focusedNode, step.typeText)
                planIndex++
                scheduleNextStep(plan)
            }
        }
    }

    /** Broadcasts plan completion result to Flutter via local broadcast. */
    private fun onPlanFinished(success: Boolean) {
        stepHandler.removeCallbacks(planTimeoutRunnable)
        stepHandler.removeCallbacks(stepRunnable)
        val tag = planTag
        pendingPlan = null
        planIndex = 0
        planTag = ""
        Log.i(TAG, "Plan [$tag] finished — success=$success")
        try {
            val intent = Intent(ACTION_PLAN_DONE).apply {
                `package` = applicationContext.packageName
                putExtra(EXTRA_PLAN_TAG, tag)
                putExtra(EXTRA_SUCCESS, success)
            }
            applicationContext.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast plan done", e)
        }
    }

    private fun scheduleNextStep(plan: List<A11yStep>) {
        if (planIndex >= plan.size) {
            onPlanFinished(true)
            return
        }
        stepHandler.removeCallbacks(stepRunnable)
        stepHandler.postDelayed(stepRunnable, plan[planIndex].delayMs)
    }

    private var stepRetryCount = 0

    private fun executeNextStep() {
        val plan = pendingPlan ?: return
        if (planIndex >= plan.size) { onPlanFinished(true); return }
        val step = plan[planIndex]
        val root = rootInActiveWindow
        if (root == null) {
            if (stepRetryCount < 4) {
                stepRetryCount++
                stepHandler.postDelayed(stepRunnable, 500)
                return
            }
            onPlanFinished(false)
            return
        }

        if (step.clickHint != null) {
            val clicked = clickFirstMatch(root, listOf(step.clickHint.lowercase()))
            if (!clicked) {
                if (stepRetryCount < 4) {
                    stepRetryCount++
                    stepHandler.postDelayed(stepRunnable, 600)
                    return
                }
            }
        }
        if (step.typeText != null) {
            val focusedNode = findFocusedEditText(root)
            if (focusedNode != null) {
                typeIntoNode(focusedNode, step.typeText)
            } else {
                if (stepRetryCount < 4) {
                    stepRetryCount++
                    stepHandler.postDelayed(stepRunnable, 600)
                    return
                }
            }
        }

        if (step.verifyHint != null) {
            val verified = findNode(root, listOf(step.verifyHint.lowercase())) != null
            if (!verified) {
                if (stepRetryCount < 4) {
                    stepRetryCount++
                    stepHandler.postDelayed(stepRunnable, 800)
                    return
                }
                // Verification failed — do not declare false success!
                onPlanFinished(false)
                return
            }
        }

        stepRetryCount = 0
        planIndex++
        scheduleNextStep(plan)
    }

    /** Start a new plan with a tag for completion identification. */
    private fun startPlan(tag: String, steps: List<A11yStep>) {
        stepHandler.removeCallbacks(planTimeoutRunnable)
        stepHandler.removeCallbacks(stepRunnable)
        pendingPlan = steps
        planIndex = 0
        planTag = tag
        stepRetryCount = 0
        // 25-second hard timeout — if plan doesn't finish, broadcast failure
        stepHandler.postDelayed(planTimeoutRunnable, 25_000L)
        scheduleNextStep(steps)
    }

    // ── WhatsApp & Automation Helpers ──────────────────────────────────────────

    fun armWhatsappAutoSendPreFilled() {
        pendingWhatsappSend = true
        // Try multiple timed taps in case window state event already fired
        stepHandler.postDelayed({
            val root = rootInActiveWindow
            if (root != null && clickFirstMatch(root, SEND_HINTS)) {
                pendingWhatsappSend = false
                Log.i(TAG, "WhatsApp Send clicked via primary timer")
            }
        }, 1200)
        stepHandler.postDelayed({
            if (pendingWhatsappSend) {
                val root = rootInActiveWindow
                if (root != null && clickFirstMatch(root, SEND_HINTS)) {
                    pendingWhatsappSend = false
                    Log.i(TAG, "WhatsApp Send clicked via fallback timer")
                }
            }
        }, 2500)
    }

    fun armWhatsappSearchAndSend(contact: String, message: String) {
        pendingWhatsappSend = false
        startPlan("whatsapp_search_send", listOf(
            A11yStep(clickHint = "search", delayMs = 1200),
            A11yStep(typeText  = contact, delayMs = 800),
            A11yStep(clickHint = contact.lowercase(), delayMs = 1000),
            A11yStep(clickHint = "type a message", delayMs = 900),
            A11yStep(typeText  = message, delayMs = 800),
            A11yStep(clickHint = "send", delayMs = 600, verifyHint = "message sent")
        ))
    }

    fun armWhatsappAutoSend(message: String = "") {
        pendingWhatsappSend = false  // Disable legacy single-tap handler
        if (message.isBlank()) {
            pendingWhatsappSend = true
            return
        }
        // Robust WhatsApp flow: focus message field → type text → click send
        startPlan("whatsapp_send", listOf(
            A11yStep(clickHint = "type a message", delayMs = 1200),
            A11yStep(clickHint = "message", delayMs = 1000),
            A11yStep(typeText  = message, delayMs = 900),
            A11yStep(clickHint = "send", delayMs = 600, verifyHint = "message sent"),
        ))
    }

    fun armWhatsappSendImage() {
        val delays = listOf(800L, 1600L, 2600L, 3800L)
        val imageSendHints = listOf("send", "com.whatsapp:id/send", "भेजें", "send photo", "send image")
        delays.forEachIndexed { index, delay ->
            stepHandler.postDelayed({
                val root = rootInActiveWindow
                if (root != null) {
                    val clicked = clickFirstMatch(root, imageSendHints)
                    if (clicked) {
                        Log.i(TAG, "WhatsApp Image Send button clicked at ${delay}ms")
                    } else if (index >= 2) {
                        // Physical FAB tap fallback at bottom right (where WhatsApp send button sits)
                        val dm = resources.displayMetrics
                        performTap(dm.widthPixels * 0.90f, dm.heightPixels * 0.93f)
                        Log.i(TAG, "WhatsApp Image Send FAB tap fallback at bottom-right executed")
                    }
                }
            }, delay)
        }
    }

    fun armWhatsappDeleteLastMessage() {
        stepHandler.postDelayed({
            val root = rootInActiveWindow
            if (root != null) {
                val bubble = findLastMessageNode(root)
                var initiated = false
                if (bubble != null) {
                    val rect = android.graphics.Rect()
                    bubble.getBoundsInScreen(rect)
                    val clicked = bubble.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                    if (clicked) {
                        initiated = true
                        Log.i(TAG, "ACTION_LONG_CLICK succeeded on WhatsApp message bubble")
                    } else if (!rect.isEmpty && rect.width() > 0) {
                        initiated = performLongPress(rect.exactCenterX(), rect.exactCenterY(), 900L)
                        Log.i(TAG, "performLongPress touch gesture executed on WhatsApp message bubble: $initiated")
                    }
                }
                if (!initiated) {
                    // Touch fallback: long-press the middle-lower region of chat screen
                    val dm = resources.displayMetrics
                    performLongPress(dm.widthPixels * 0.5f, dm.heightPixels * 0.72f, 900L)
                    Log.i(TAG, "Fallback physical long press at 72% screen height executed")
                }

                // Wait for top action bar to appear with trash icon
                stepHandler.postDelayed({
                    val currentRoot = rootInActiveWindow
                    if (currentRoot != null) {
                        val trashHints = listOf("delete", "trash", "com.whatsapp:id/menuitem_conversations_delete", "हटाएं", "remove")
                        val trashClicked = clickFirstMatch(currentRoot, trashHints)
                        Log.i(TAG, "Trash icon click: $trashClicked")

                        // Wait for confirmation dialog
                        stepHandler.postDelayed({
                            val dialogRoot = rootInActiveWindow
                            if (dialogRoot != null) {
                                val confirmHints = listOf("delete for everyone", "delete for me", "sabke liye delete karein", "mere liye delete karein", "ok", "हटाएं", "delete")
                                val confirmClicked = clickFirstMatch(dialogRoot, confirmHints)
                                Log.i(TAG, "Delete confirmation clicked: $confirmClicked")
                            }
                        }, 700)
                    }
                }, 800)
            }
        }, 1200)
    }

    private fun findLastMessageNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
        fun scan(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val resId = (n.viewIdResourceName ?: "").lowercase()
            val desc = (n.contentDescription?.toString() ?: "").lowercase()
            val rect = android.graphics.Rect()
            n.getBoundsInScreen(rect)

            val isMsg = resId.contains("conversation_row") || resId.contains("message") ||
                    resId.contains("thumb") || resId.contains("image") || resId.contains("photo") ||
                    desc.contains("photo") || desc.contains("image") || desc.contains("message")
            if (isMsg && rect.bottom > 100) {
                candidates.add(Pair(n, rect.bottom))
            }
            for (i in 0 until n.childCount) {
                scan(n.getChild(i))
            }
        }
        scan(node)
        return candidates.maxByOrNull { it.second }?.first
    }

    fun armAutoInstall()      { pendingAutoInstall = true }
    fun armAutoUninstall()    { pendingAutoUninstall = true }
    fun tapLike(): Boolean {
        val root = rootInActiveWindow ?: return false
        return clickFirstMatch(root, LIKE_HINTS)
    }

    fun performYoutubeLike(): Boolean {
        val root = rootInActiveWindow ?: return false
        return clickFirstMatch(root, YOUTUBE_LIKE_HINTS)
    }

    fun performYoutubeSubscribe(): Boolean {
        val root = rootInActiveWindow ?: return false
        return clickFirstMatch(root, YOUTUBE_SUBSCRIBE_HINTS)
    }

    fun performYoutubeSkipAd(): Boolean {
        val root = rootInActiveWindow ?: return false
        return clickFirstMatch(root, YOUTUBE_SKIP_AD_HINTS)
    }

    // ── Hands-Free YouTube Autonomous Playback (Panda-Style) ─────────────────

    fun armYouTubeAutoPlayFirstResult(query: String) {
        armYouTubeAutoPlay(query)
    }

    fun armYouTubeAutoPlay(query: String) {
        pendingYouTubeAutoPlay = true
        pendingYouTubeQuery = query

        // Staggered attempts: YouTube network loading can take 1 to 5 seconds
        val delays = listOf(1000L, 1800L, 2800L, 4000L, 5500L)
        delays.forEachIndexed { index, delay ->
            val isFinal = (index == delays.size - 1)
            stepHandler.postDelayed({
                if (pendingYouTubeAutoPlay) {
                    val root = rootInActiveWindow
                    if (root != null) {
                        if (clickYouTubeFirstVideo(root, pendingYouTubeQuery, isFinal)) {
                            pendingYouTubeAutoPlay = false
                            Log.i(TAG, "YouTube video auto-play clicked at ${delay}ms")
                        }
                    }
                }
            }, delay)
        }
    }

    fun clickYouTubeFirstVideo(root: AccessibilityNodeInfo, query: String = "", isFinalAttempt: Boolean = false): Boolean {
        val dm = resources.displayMetrics
        val minTop = dm.heightPixels * 0.10f // Below top search bar
        val maxBottom = dm.heightPixels * 0.85f

        // Strategy 1: Find node whose text or description contains query words
        val words = query.lowercase().split(" ").filter { it.length > 2 }
        if (words.isNotEmpty()) {
            val titleNode = findVideoTitleNode(root, words, minTop, maxBottom)
            if (titleNode != null) {
                var clickTarget: AccessibilityNodeInfo? = titleNode
                while (clickTarget != null && !clickTarget.isClickable) clickTarget = clickTarget.parent
                val clicked = clickTarget?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
                if (clicked) return true

                val rect = android.graphics.Rect()
                titleNode.getBoundsInScreen(rect)
                if (!rect.isEmpty && rect.top >= minTop) return performTap(rect.exactCenterX(), rect.exactCenterY())
            }
        }

        // Strategy 2: Find first video card node with duration / views description
        val videoCard = findFirstVideoCardNode(root, minTop, maxBottom)
        if (videoCard != null) {
            var clickTarget: AccessibilityNodeInfo? = videoCard
            while (clickTarget != null && !clickTarget.isClickable) clickTarget = clickTarget.parent
            val clicked = clickTarget?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
            if (clicked) return true

            val rect = android.graphics.Rect()
            videoCard.getBoundsInScreen(rect)
            if (!rect.isEmpty && rect.top >= minTop) return performTap(rect.exactCenterX(), rect.exactCenterY())
        }

        // Strategy 3: Find any clickable item in the search results area
        val clickableItem = findFirstClickableResult(root, minTop, maxBottom)
        if (clickableItem != null) {
            val clicked = clickableItem.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) return true
            val rect = android.graphics.Rect()
            clickableItem.getBoundsInScreen(rect)
            if (!rect.isEmpty) return performTap(rect.exactCenterX(), rect.exactCenterY())
        }

        // Strategy 4: Coordinate tap ONLY on final fallback attempt (after several seconds have elapsed)
        if (isFinalAttempt) {
            return performTap(dm.widthPixels / 2f, dm.heightPixels * 0.35f)
        }
        return false
    }

    private fun findFirstClickableResult(
        node: AccessibilityNodeInfo?,
        minTop: Float,
        maxBottom: Float
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (rect.top >= minTop && rect.bottom <= maxBottom && rect.height() > 60 && node.isClickable) {
            val text = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()
            if (text.isNotBlank() && !text.contains("search", ignoreCase = true) && !text.contains("filter", ignoreCase = true)) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val found = findFirstClickableResult(node.getChild(i), minTop, maxBottom)
            if (found != null) return found
        }
        return null
    }

    private fun findVideoTitleNode(
        node: AccessibilityNodeInfo?,
        words: List<String>,
        minTop: Float,
        maxBottom: Float
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        val text = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").lowercase()
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (rect.top >= minTop && rect.bottom <= maxBottom && words.any { text.contains(it) }) {
            return node
        }
        for (i in 0 until node.childCount) {
            val found = findVideoTitleNode(node.getChild(i), words, minTop, maxBottom)
            if (found != null) return found
        }
        return null
    }

    private fun findFirstVideoCardNode(
        node: AccessibilityNodeInfo?,
        minTop: Float,
        maxBottom: Float
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        val resId = (node.viewIdResourceName ?: "").lowercase()
        val desc = (node.contentDescription?.toString() ?: "").lowercase()
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (rect.top >= minTop && rect.bottom <= maxBottom && rect.height() > 40) {
            if (resId.contains("video_title") || resId.contains("compact_video_item") ||
                resId.contains("thumbnail") || resId.contains("details") || resId.contains("media_item")) {
                return node
            }
            if (desc.contains("minute") || desc.contains("second") || desc.contains("views") ||
                desc.contains("play video") || desc.contains("channel") || desc.length > 25) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val found = findFirstVideoCardNode(node.getChild(i), minTop, maxBottom)
            if (found != null) return found
        }
        return null
    }

    // ── Hands-Free System Settings Automations (Panda-Style Engine) ───────────

    fun armHotspotToggle(enable: Boolean): Boolean {
        pendingHotspotToggle = enable
        val intents = listOf(
            Intent("android.settings.TETHER_SETTINGS"),
            Intent("android.settings.WIFI_TETHER_SETTINGS"),
            Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS),
            Intent(android.provider.Settings.ACTION_SETTINGS)
        )
        var started = false
        for (intent in intents) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                    started = true
                    break
                }
            } catch (_: Exception) {}
        }
        if (!started) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            } catch (_: Exception) {}
        }

        val delays = listOf(700L, 1400L, 2200L, 3400L, 5000L)
        delays.forEach { delay ->
            stepHandler.postDelayed({
                if (pendingHotspotToggle != null) {
                    val root = rootInActiveWindow
                    if (root != null) {
                        if (performHotspotToggle(root, pendingHotspotToggle!!)) {
                            pendingHotspotToggle = null
                            Log.i(TAG, "Hotspot toggled at ${delay}ms")
                        }
                    }
                }
            }, delay)
        }
        return true
    }

    fun performHotspotToggle(root: AccessibilityNodeInfo, enable: Boolean): Boolean {
        val hotspotHints = listOf(
            "portable hotspot", "personal hotspot", "wi-fi hotspot", "wifi hotspot",
            "share mobile network", "mobile hotspot", "hotspot", "हॉटस्पॉट", "टैदरिंग"
        )
        return findAndToggleSwitch(hotspotHints, enable)
    }

    fun armNavigationMode(mode: String): Boolean {
        pendingNavigationMode = mode
        val intents = listOf(
            Intent("android.settings.SYSTEM_NAVIGATION_SETTINGS"),
            Intent("com.android.settings.SYSTEM_NAVIGATION_SETTINGS")
        )
        var started = false
        for (intent in intents) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                    started = true
                    break
                }
            } catch (_: Exception) {}
        }

        if (!started) {
            val settingsIntent = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                startActivity(settingsIntent)
            } catch (_: Exception) {}
            stepHandler.postDelayed({
                armSearchAndClick("navigation", if (mode == "buttons") "3-button" else "gestures")
            }, 800)
        }

        val delays = listOf(800L, 1500L, 2400L, 3600L, 5000L)
        delays.forEach { delay ->
            stepHandler.postDelayed({
                if (pendingNavigationMode != null) {
                    val root = rootInActiveWindow
                    if (root != null) {
                        if (performNavigationModeClick(root, pendingNavigationMode!!)) {
                            pendingNavigationMode = null
                            Log.i(TAG, "Navigation mode switched at ${delay}ms")
                        }
                    }
                }
            }, delay)
        }
        return true
    }

    fun performNavigationModeClick(root: AccessibilityNodeInfo, mode: String): Boolean {
        val buttonHints = listOf(
            "3-button navigation", "3-button", "3 button", "three-button",
            "virtual buttons", "buttons", "button navigation", "classic navigation",
            "तीन बटन", "बटन"
        )
        val gestureHints = listOf(
            "gesture navigation", "gestures", "gesture", "full screen gestures",
            "full-screen gestures", "swipe gestures", "full screen", "जेस्चर"
        )
        val targetHints = if (mode == "buttons") buttonHints else gestureHints

        val match = findNode(root, targetHints)
        if (match != null) {
            val radio = findRadioInHierarchy(match)
            if (radio != null) {
                if (radio.isChecked) return true // Already selected
                val radioClicked = radio.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (radioClicked) return true
            }

            var clickableTarget: AccessibilityNodeInfo? = match
            while (clickableTarget != null && !clickableTarget.isClickable) {
                clickableTarget = clickableTarget.parent
            }
            val clicked = clickableTarget?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
            if (clicked) return true

            val rect = android.graphics.Rect()
            match.getBoundsInScreen(rect)
            if (!rect.isEmpty) return performTap(rect.exactCenterX(), rect.exactCenterY())
        }

        // If currently on Settings search result page or main menu, click into System Navigation first
        val navSettingItem = findNode(root, listOf("system navigation", "navigation bar", "navigation mode", "navigation gestures", "navigation buttons", "navigation type", "navigation"))
        if (navSettingItem != null) {
            var target: AccessibilityNodeInfo? = navSettingItem
            while (target != null && !target.isClickable) target = target.parent
            val clicked = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
            if (!clicked) {
                val rect = android.graphics.Rect()
                navSettingItem.getBoundsInScreen(rect)
                if (!rect.isEmpty) performTap(rect.exactCenterX(), rect.exactCenterY())
            }
        }
        return false
    }

    private fun findRadioInHierarchy(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.className?.toString()?.contains("RadioButton", ignoreCase = true) == true ||
            node.isCheckable) {
            return node
        }
        for (i in 0 until node.childCount) {
            val r = findRadioInHierarchy(node.getChild(i))
            if (r != null) return r
        }
        val parent = node.parent
        if (parent != null) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChild(i)
                if (child != node && (child?.className?.toString()?.contains("RadioButton", ignoreCase = true) == true || child?.isCheckable == true)) {
                    return child
                }
            }
        }
        return null
    }

    fun armWifiToggle(enable: Boolean): Boolean {
        pendingWifiToggle = enable
        val intent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try { startActivity(intent) } catch (_: Exception) {}
        val delays = listOf(700L, 1500L, 2500L, 3800L)
        delays.forEach { delay ->
            stepHandler.postDelayed({
                if (pendingWifiToggle != null) {
                    val root = rootInActiveWindow
                    if (root != null) {
                        if (findAndToggleSwitch(listOf("wi-fi", "wifi", "internet", "use wi-fi"), pendingWifiToggle)) {
                            pendingWifiToggle = null
                        }
                    }
                }
            }, delay)
        }
        return true
    }

    fun armBluetoothToggle(enable: Boolean): Boolean {
        pendingBluetoothToggle = enable
        val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try { startActivity(intent) } catch (_: Exception) {}
        val delays = listOf(700L, 1500L, 2500L, 3800L)
        delays.forEach { delay ->
            stepHandler.postDelayed({
                if (pendingBluetoothToggle != null) {
                    val root = rootInActiveWindow
                    if (root != null) {
                        if (findAndToggleSwitch(listOf("bluetooth", "use bluetooth"), pendingBluetoothToggle)) {
                            pendingBluetoothToggle = null
                        }
                    }
                }
            }, delay)
        }
        return true
    }

    fun findAndToggleSwitch(labelHints: List<String>, targetState: Boolean? = null): Boolean {
        val root = rootInActiveWindow ?: return false
        val labelNode = findNode(root, labelHints)
        if (labelNode != null) {
            val switchNode = findSwitchInHierarchy(labelNode)
            if (switchNode != null) {
                if (targetState == null || switchNode.isChecked != targetState) {
                    val clicked = switchNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                                  (switchNode.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false)
                    if (clicked) return true
                    val rect = android.graphics.Rect()
                    switchNode.getBoundsInScreen(rect)
                    if (!rect.isEmpty) return performTap(rect.exactCenterX(), rect.exactCenterY())
                } else {
                    return true // Already in target state
                }
            }
            // If no separate switch widget was found, click the row to enter the sub-page
            var row: AccessibilityNodeInfo? = labelNode
            while (row != null && !row.isClickable) row = row.parent
            val clicked = row?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
            if (!clicked) {
                val rect = android.graphics.Rect()
                labelNode.getBoundsInScreen(rect)
                if (!rect.isEmpty) performTap(rect.exactCenterX(), rect.exactCenterY())
            }
            return false // Keep pending active so sub-page can be checked on next tick
        }

        // Fallback: If on an isolated sub-setting page (e.g. TetherSettings), look for any active Switch widget
        if (targetState != null) {
            val genericSwitch = findAnySwitch(root)
            if (genericSwitch != null) {
                if (genericSwitch.isChecked != targetState) {
                    val clicked = genericSwitch.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                                  (genericSwitch.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false)
                    if (clicked) return true
                    val rect = android.graphics.Rect()
                    genericSwitch.getBoundsInScreen(rect)
                    if (!rect.isEmpty) return performTap(rect.exactCenterX(), rect.exactCenterY())
                } else {
                    return true // Already in target state
                }
            }
        }
        return false
    }

    private fun findAnySwitch(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.className?.toString()?.contains("Switch", ignoreCase = true) == true ||
            node.className?.toString()?.contains("CompoundButton", ignoreCase = true) == true ||
            node.isCheckable) {
            return node
        }
        for (i in 0 until node.childCount) {
            val s = findAnySwitch(node.getChild(i))
            if (s != null) return s
        }
        return null
    }

    private fun findSwitchInHierarchy(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.className?.toString()?.contains("Switch", ignoreCase = true) == true ||
            node.className?.toString()?.contains("CompoundButton", ignoreCase = true) == true ||
            node.isCheckable) {
            return node
        }
        for (i in 0 until node.childCount) {
            val s = findSwitchInHierarchy(node.getChild(i))
            if (s != null) return s
        }
        val parent = node.parent
        if (parent != null) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChild(i)
                if (child != node) {
                    if (child?.className?.toString()?.contains("Switch", ignoreCase = true) == true ||
                        child?.isCheckable == true) {
                        return child
                    }
                }
            }
        }
        return null
    }

    /**
     * Universal in-app autonomous search & play for ANY music/media app (Spotify, Melodify, JioSaavn, Wynk, etc.)
     */
    fun armAutoSearchAndPlayInApp(appName: String, query: String) {
        val queryFirstWord = query.split(" ").firstOrNull { it.length > 2 }?.lowercase() ?: query.lowercase()
        startPlan("app_search_play", listOf(
            A11yStep(clickHint = "search", delayMs = 1200),
            A11yStep(typeText  = query, delayMs = 900),
            A11yStep(clickHint = queryFirstWord, delayMs = 1000)
        ))
    }

    /**
     * Universal in-app autonomous search and optional click for ANY arbitrary Android app.
     */
    fun armSearchAndClick(query: String, targetClick: String? = null) {
        val steps = mutableListOf(
            A11yStep(clickHint = "search", delayMs = 1000),
            A11yStep(typeText  = query, delayMs = 800)
        )
        if (!targetClick.isNullOrBlank()) {
            steps.add(A11yStep(clickHint = targetClick.lowercase(), delayMs = 900))
        }
        startPlan("search_action", steps)
    }

    fun takeScreenCapture(callback: (ByteArray?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    applicationContext.mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            val buffer = screenshotResult.hardwareBuffer
                            try {
                                val colorSpace = screenshotResult.colorSpace
                                val hwBitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                                if (hwBitmap != null) {
                                    val swBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    hwBitmap.recycle()
                                    if (swBitmap != null) {
                                        val stream = ByteArrayOutputStream()
                                        swBitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                                        swBitmap.recycle()
                                        val bytes = stream.toByteArray()
                                        if (bytes.isNotEmpty()) {
                                            callback(bytes)
                                            return
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to process screenshot buffer: $e")
                            } finally {
                                try { buffer.close() } catch (_: Exception) {}
                            }
                            callback(null)
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.e(TAG, "takeScreenshot failed code: $errorCode")
                            callback(null)
                        }
                    }
                )
                return
            } catch (e: Exception) {
                Log.e(TAG, "takeScreenshot exception: $e")
            }
        }
        callback(null)
    }

    fun takeSystemScreenshot(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        } else {
            false
        }
    }

    fun openNotifications(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }

    fun openQuickSettings(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
    }

    fun performHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun performBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun performRecents(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    fun scrollVideo(direction: String = "down"): Boolean {
        val dm = resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels
        val x = width / 2
        val startY: Int
        val endY: Int
        if (direction.lowercase() == "up" || direction.lowercase() == "previous") {
            // Scroll down gesture to reveal previous video
            startY = (height * 0.25f).toInt()
            endY = (height * 0.75f).toInt()
        } else {
            // Scroll up gesture to reveal next video (Shorts, Reels, TikTok)
            startY = (height * 0.75f).toInt()
            endY = (height * 0.22f).toInt()
        }
        return performSwipe(x, startY, x, endY, 260)
    }

    fun performClickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return clickFirstMatch(root, listOf(text.lowercase()))
    }

    fun scrollWindow(down: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return root.performAction(action)
    }

    // ── New: Gmail Compose + Send ─────────────────────────────────────────────

    /**
     * Queues a plan to compose and send a Gmail.
     * Expected flow (once Gmail is open):
     *   1. Click "Compose" button
     *   2. Type recipient in To: field
     *   3. Click Subject field (tab/next)
     *   4. Type subject
     *   5. Click body area
     *   6. Type body
     *   7. Click Send button
     */
    fun armGmailSend(to: String, subject: String, body: String) {
        startPlan("gmail", listOf(
            // Gmail Compose button - multiple possible labels
            A11yStep(clickHint = "compose",         delayMs = 1500),
            A11yStep(clickHint = "new message",     delayMs = 1200),
            A11yStep(clickHint = "new mail",        delayMs = 1200),
            // Recipient field
            A11yStep(clickHint = "to",              delayMs = 900),
            A11yStep(clickHint = "recipient",       delayMs = 900),
            A11yStep(clickHint = "email",           delayMs = 900),
            // Type recipient email
            A11yStep(typeText  = to,                delayMs = 900),
            // Subject field
            A11yStep(clickHint = "subject",         delayMs = 900),
            A11yStep(typeText  = subject,           delayMs = 900),
            // Body area
            A11yStep(clickHint = "message body",    delayMs = 900),
            A11yStep(clickHint = "compose email",   delayMs = 900),
            A11yStep(typeText  = body,              delayMs = 900),
            // Send button with verification
            A11yStep(clickHint = "send",            delayMs = 800,
                     verifyHint = "sent"),
        ))
    }

    // ── Instagram DM ─────────────────────────────────────────────────────────

    /**
     * Sends an Instagram Direct Message autonomously.
     *
     * Instagram UI flow (verified against Instagram 300+):
     *  1. DM inbox is already open (launched via instagram://direct-inbox)
     *  2. Tap the compose/new-message pencil icon (top-right, label "New message")
     *  3. Search field auto-focuses — type username
     *  4. Wait for list → tap first result matching username
     *  5. Tap "Chat" button to open conversation (if prompted)
     *  6. Tap the message input field (label "Message...")
     *  7. Type the message text
     *  8. Tap "Send" button → verifyHint checks for sent message in view
     */
    fun armInstagramDM(username: String, message: String) {
        startPlan("instagram_dm", listOf(
            // Step 1: Tap compose new DM icon (Instagram labels it "New message")
            A11yStep(clickHint = "new message",       delayMs = 900),
            // Step 2: Type username into the search box (auto-focused after tap)
            A11yStep(typeText  = username,            delayMs = 1000),
            // Step 3: Tap the user from the search result list
            A11yStep(clickHint = username.lowercase(), delayMs = 1200),
            // Step 4: Tap "Chat" if Instagram shows a "Chat" button first
            A11yStep(clickHint = "chat",              delayMs = 800),
            // Step 5: Tap the message compose field
            A11yStep(clickHint = "message",           delayMs = 700),
            // Step 6: Type the message text
            A11yStep(typeText  = message,             delayMs = 600),
            // Step 7: Tap Send — verifyHint checks the sent message now appears
            A11yStep(clickHint = "send",              delayMs = 400,
                     verifyHint = message.take(20)),
        ))
    }

    // ── New: Facebook Post ────────────────────────────────────────────────────

    fun armFacebookPost(text: String) {
        pendingPlan = listOf(
            A11yStep(clickHint = "what's on your mind", delayMs = 800),
            A11yStep(typeText  = text,                  delayMs = 600),
            A11yStep(clickHint = "post",                delayMs = 800),
        )
        planIndex = 0
    }

    // ── New: App Search Bar Launch Fallback ──────────────────────────────────

    /**
     * Fallback when an app's package ID is unknown:
     * 1. Navigates to home screen (GLOBAL_ACTION_HOME)
     * 2. Finds and taps the system / launcher search bar ("Search", "Search apps", "Google")
     * 3. Types the app name
     * 4. Taps the matched app from the search results
     */
    fun armAppSearchAndLaunch(appName: String) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        pendingPlan = listOf(
            A11yStep(clickHint = "search",            delayMs = 700),
            A11yStep(typeText  = appName,             delayMs = 600),
            A11yStep(clickHint = appName.lowercase(), delayMs = 800),
        )
        planIndex = 0
        scheduleNextStep(pendingPlan!!)
    }

    // ── New: Swipe Gesture ────────────────────────────────────────────────────

    fun performSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.toLong())
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Dispatches a real physical touch tap gesture at exact screen coordinates (x, y).
     * Simulates genuine human finger touch across all apps (Compose, Flutter, React Native, Unity).
     */
    fun performTap(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 75)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Dispatches a real physical long-press touch gesture at exact screen coordinates (x, y).
     */
    fun performLongPress(x: Float, y: Float, durationMs: Long = 1000L): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun performClickCoordinate(x: Float, y: Float): Boolean {
        return performTap(x, y)
    }

    /**
     * Types text into the currently focused or first available editable field.
     */
    fun performTypeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val edit = findFocusedEditText(root) ?: return false
        typeIntoNode(edit, text)
        return true
    }

    /**
     * Recursively extracts all visible text, message bubbles, button labels, and titles
     * from the active window hierarchy into a clean structured string.
     */
    fun getScreenTextHierarchy(): String {
        val root = rootInActiveWindow ?: return "Screen is empty or inaccessible (enable AURA Accessibility)."
        val sb = StringBuilder()
        val seen = mutableSetOf<String>()
        collectTextNodes(root, sb, seen, 0)
        val result = sb.toString().trim()
        return if (result.isEmpty()) "No readable text or messages found on current screen." else result
    }

    private fun collectTextNodes(
        node: AccessibilityNodeInfo?,
        sb: StringBuilder,
        seen: MutableSet<String>,
        depth: Int
    ) {
        if (node == null || depth > 30) return
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val label = when {
            !text.isNullOrEmpty() && !desc.isNullOrEmpty() && text != desc -> "$text ($desc)"
            !text.isNullOrEmpty() -> text
            !desc.isNullOrEmpty() -> desc
            else -> null
        }
        if (!label.isNullOrEmpty()) {
            val key = label.lowercase()
            if (!seen.contains(key)) {
                seen.add(key)
                val isClickable = node.isClickable || (node.parent?.isClickable ?: false)
                if (isClickable) {
                    sb.appendLine("🔘 [Button] $label")
                } else {
                    sb.appendLine("📄 $label")
                }
            }
        }
        for (i in 0 until node.childCount) {
            collectTextNodes(node.getChild(i), sb, seen, depth + 1)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun typeIntoNode(node: AccessibilityNodeInfo, text: String) {
        node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFocusedEditText(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.isFocused && root.isEditable) return root
        for (i in 0 until root.childCount) {
            val found = findFocusedEditText(root.getChild(i))
            if (found != null) return found
        }
        // Fallback: find any editable field
        if (root.isEditable) return root
        for (i in 0 until root.childCount) {
            val found = findFocusedEditText(root.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun clickFirstMatch(root: AccessibilityNodeInfo, hints: List<String>): Boolean {
        val match = findNode(root, hints) ?: return false
        var target: AccessibilityNodeInfo? = match
        while (target != null && !target.isClickable) target = target.parent
        val clicked = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        if (clicked) return true

        // Physical human-touch tap fallback using element bounding box on screen
        val rect = android.graphics.Rect()
        match.getBoundsInScreen(rect)
        if (!rect.isEmpty && rect.width() > 0 && rect.height() > 0) {
            return performTap(rect.exactCenterX(), rect.exactCenterY())
        }
        return false
    }

    private fun findNode(node: AccessibilityNodeInfo?, hints: List<String>): AccessibilityNodeInfo? {
        if (node == null) return null
        val label = (node.contentDescription?.toString() ?: node.text?.toString() ?: "").lowercase()
        val resId = (node.viewIdResourceName?.toString() ?: "").lowercase()
        if ((label.isNotEmpty() && hints.any { label.contains(it) }) || (resId.isNotEmpty() && hints.any { resId.contains(it) })) return node
        for (i in 0 until node.childCount) {
            val found = findNode(node.getChild(i), hints)
            if (found != null) return found
        }
        return null
    }

    // ── Universal Panda HandsFree Mobile Agent Primitives ────────────────────

    fun performGlobalActionByKey(key: String): Boolean {
        return when (key.lowercase().trim()) {
            "lock", "lock_screen", "screen_lock" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                } else false
            }
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents", "recent_apps" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications", "notification_shade" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            "power_dialog", "power_menu" -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            "screenshot", "take_screenshot" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                } else false
            }
            else -> false
        }
    }

    /**
     * Autonomous App Cache Clearing (Panda-Style):
     * Opens App Details -> Taps "Storage & cache" -> Taps "Clear cache".
     */
    fun armClearAppCache(pkgName: String) {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$pkgName")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app details for $pkgName", e)
            return
        }

        // Multi-stage autonomous progression
        stepHandler.postDelayed({
            val root = rootInActiveWindow
            if (root != null) {
                clickFirstMatch(root, listOf("storage & cache", "storage", "स्टोरेज", "मेमोरी"))
            }
        }, 900)

        stepHandler.postDelayed({
            val root = rootInActiveWindow
            if (root != null) {
                clickFirstMatch(root, listOf("clear cache", "कैश साफ़ करें", "empty cache"))
            }
        }, 1800)
    }

    /**
     * Autonomous App Uninstallation (Panda-Style):
     * Opens uninstall dialog or App Details -> Taps Uninstall -> Confirms OK.
     */
    fun armUninstallApp(pkgName: String) {
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$pkgName")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            startActivity(intent)
            pendingAutoUninstall = true
            stepHandler.postDelayed({
                val root = rootInActiveWindow
                if (root != null) {
                    clickFirstMatch(root, UNINSTALL_HINTS)
                }
            }, 1000)
        } catch (_: Exception) {}
    }

    /**
     * Autonomous Play Store App Installation (Panda-Style):
     * Supports:
     * - "top": Installs first matching result.
     * - "lowest_mb": Scans top 10 results, extracts MB sizes, and installs the smallest app.
     * - "highest_rated": Installs highest rated visible app.
     */
    fun armPlayStoreInstall(query: String, criteria: String = "top") {
        try {
            val uri = Uri.parse("market://search?q=" + URLEncoder.encode(query, "UTF-8"))
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.android.vending")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (_: Exception) {
            val webUri = Uri.parse("https://play.google.com/store/search?q=" + URLEncoder.encode(query, "UTF-8"))
            startActivity(Intent(Intent.ACTION_VIEW, webUri).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
        }

        val delays = listOf(1400L, 2500L, 3800L)
        delays.forEach { delay ->
            stepHandler.postDelayed({
                val root = rootInActiveWindow
                if (root != null) {
                    if (criteria.contains("lowest", ignoreCase = true) || criteria.contains("kam", ignoreCase = true) || criteria.contains("small", ignoreCase = true)) {
                        if (clickSmallestMbApp(root)) {
                            Log.i(TAG, "Lowest MB app clicked at ${delay}ms")
                        }
                    } else {
                        val installButtons = listOf("install", "get", "इंस्टॉल करें", "डाउनलोड करें")
                        if (clickFirstMatch(root, installButtons)) {
                            Log.i(TAG, "Play store install button clicked at ${delay}ms")
                        }
                    }
                }
            }, delay)
        }
    }

    private fun clickSmallestMbApp(root: AccessibilityNodeInfo): Boolean {
        // Collect candidate cards with size info
        val mbRegex = Regex("""([0-9]+(\.[0-9]+)?)\s*(MB|GB|KB)""", RegexOption.IGNORE_CASE)
        val candidateNodes = mutableListOf<Pair<AccessibilityNodeInfo, Double>>()
        collectNodesWithSize(root, mbRegex, candidateNodes)

        if (candidateNodes.isNotEmpty()) {
            val smallest = candidateNodes.minByOrNull { it.second }
            if (smallest != null) {
                // Find install button near this node or click the smallest item card
                val card = smallest.first
                var clickTarget: AccessibilityNodeInfo? = card
                while (clickTarget != null && !clickTarget.isClickable) clickTarget = clickTarget.parent
                val clicked = clickTarget?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
                if (clicked) return true

                val rect = android.graphics.Rect()
                card.getBoundsInScreen(rect)
                if (!rect.isEmpty) return performTap(rect.exactCenterX(), rect.exactCenterY())
            }
        }
        // Fallback: click first install button
        return clickFirstMatch(root, listOf("install", "get", "इंस्टॉल करें"))
    }

    private fun collectNodesWithSize(
        node: AccessibilityNodeInfo?,
        regex: Regex,
        results: MutableList<Pair<AccessibilityNodeInfo, Double>>
    ) {
        if (node == null) return
        val text = (node.text?.toString() ?: node.contentDescription?.toString() ?: "")
        val match = regex.find(text)
        if (match != null) {
            val value = match.groupValues[1].toDoubleOrNull() ?: 999.0
            val unit = match.groupValues[3].uppercase()
            val sizeMb = when (unit) {
                "KB" -> value / 1024.0
                "GB" -> value * 1024.0
                else -> value
            }
            results.add(Pair(node, sizeMb))
        }
        for (i in 0 until node.childCount) {
            collectNodesWithSize(node.getChild(i), regex, results)
        }
    }

    /**
     * Autonomous Dark Mode Toggle (Panda-Style):
     * Opens Display Settings -> Automatically toggles "Dark theme" / "Dark mode".
     */
    fun armToggleDarkMode(enable: Boolean): Boolean {
        val intent = Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try { startActivity(intent) } catch (_: Exception) {}
        val delays = listOf(800L, 1600L, 2600L)
        delays.forEach { delay ->
            stepHandler.postDelayed({
                val root = rootInActiveWindow
                if (root != null) {
                    val darkModeHints = listOf("dark theme", "dark mode", "night mode", "डार्क थीम", "डार्क मोड")
                    findAndToggleSwitch(darkModeHints, enable)
                }
            }, delay)
        }
        return true
    }
}
