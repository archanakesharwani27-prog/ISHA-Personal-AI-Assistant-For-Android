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
 * Full autonomous UI control service for ISHA.
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
open class IshaAccessibilityService : AccessibilityService() {

    companion object {
        var instance: IshaAccessibilityService? = null

        private const val TAG = "IshaA11y"

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
        private const val PKG_TELEGRAM   = "org.telegram.messenger"
        private const val PKG_TWITTER    = "com.twitter.android"
        private const val PKG_SNAPCHAT   = "com.snapchat.android"
        private const val PKG_SPOTIFY    = "com.spotify.music"
        private const val PKG_ZOMATO     = "com.application.zomato"
        private const val PKG_SWIGGY     = "in.swiggy.android"
        private const val PKG_UBER       = "com.ubercab"
        private const val PKG_OLA        = "com.olacabs.customer"
        private const val PKG_AMAZON     = "com.amazon.mShop.android.shopping"
        private const val PKG_FLIPKART   = "com.flipkart.android"
        private const val PKG_GPAY       = "com.google.android.apps.nfc.payment"
        private const val PKG_PHONEPE    = "com.phonepe.app"
        private const val PKG_PAYTM      = "net.one97.paytm"
        private const val PKG_CHROME     = "com.android.chrome"

        // Hint lists for WhatsApp / install / uninstall (enhanced for multilingual & IDs)
        private val SEND_HINTS     = listOf("send", "com.whatsapp:id/send", "com.whatsapp.w4b:id/send", "भेजें", "bhejo", "send message")
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
        private val YOUTUBE_SKIP_AD_RES_IDS = listOf(
            "skip_ad_button",
            "modern_skip_ad_button",
            "skip_ad_button_text",
            "ad_skip_button",
            "skip_button",
            "skip_ad_action"
        )
        private val YOUTUBE_SKIP_AD_STRICT_TEXTS = listOf(
            "skip ad",
            "skip ads",
            "skip",
            "विज्ञापन छोड़ें"
        )

        // General dialog, ad overlay, and popup dismissal hints
        val INTERRUPTING_DIALOG_HINTS = listOf(
            "skip ad", "skip ads", "skip in", "skip", "विज्ञापन छोड़ें",
            "not now", "dismiss", "maybe later", "cancel", "close", "no thanks",
            "later", "रद्द करें", "बंद करें", "remind me later", "ask me later",
            "deny", "don't allow", "decline", "got it", "i agree", "allow",
            "don't show again", "कभी नहीं", "बाद में"
        )
        val CLOSE_RESOURCE_HINTS = listOf(
            "close", "dismiss", "cancel", "skip", "btn_close", "close_btn", "iv_close",
            "close_button", "action_close", "skip_ad_button", "close_icon", "btn_dismiss", "img_close"
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
    private val hybridHandler = Handler(Looper.getMainLooper())
    @Volatile private var hybridSearchToken = 0
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
            performYoutubeSkipAd()
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
        val sendHints = listOf(
            "send", "com.whatsapp:id/send", "com.whatsapp.w4b:id/send",
            "भेजें", "bhejo", "send message"
        )
        val delays = listOf(800L, 1400L, 2200L, 3000L, 4200L)
        delays.forEachIndexed { index, delay ->
            stepHandler.postDelayed({
                if (pendingWhatsappSend) {
                    val root = rootInActiveWindow
                    if (root != null && clickFirstMatch(root, sendHints)) {
                        pendingWhatsappSend = false
                        Log.i(TAG, "WhatsApp Send clicked via timer at ${delay}ms")
                    } else if (index >= 2 && pendingWhatsappSend) {
                        val pkg = root?.packageName?.toString() ?: ""
                        if (pkg == PKG_WHATSAPP || pkg == PKG_WA_BIZ || pkg.contains("whatsapp")) {
                            val dm = resources.displayMetrics
                            performTap(dm.widthPixels * 0.91f, dm.heightPixels * 0.94f)
                            pendingWhatsappSend = false
                            Log.i(TAG, "WhatsApp Send FAB tapped at bottom-right at ${delay}ms")
                        }
                    }
                }
            }, delay)
        }
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

    /**
     * Types a message into WhatsApp message entry field without clicking send.
     * Brings up keyboard and leaves text ready for review.
     */
    fun armWhatsappTypeMessage(message: String, onTyped: (() -> Unit)? = null) {
        val inputHints = listOf(
            "type a message", "message", "संदेश लिखें", "संदेश",
            "com.whatsapp:id/entry", "com.whatsapp.w4b:id/entry"
        )
        val delays = listOf(600L, 1200L, 2000L)
        var typed = false
        delays.forEachIndexed { index, delay ->
            stepHandler.postDelayed({
                if (!typed) {
                    val root = rootInActiveWindow
                    if (root != null) {
                        val inputNode = findNode(root, inputHints)
                        if (inputNode != null) {
                            inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                            inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            typeIntoNode(inputNode, message)
                            typed = true
                            Log.i(TAG, "armWhatsappTypeMessage: Typed into input node at ${delay}ms")
                            onTyped?.invoke()
                        } else {
                            val edit = findFocusedEditText(root)
                            if (edit != null) {
                                typeIntoNode(edit, message)
                                typed = true
                                Log.i(TAG, "armWhatsappTypeMessage: Typed into focused edit at ${delay}ms")
                                onTyped?.invoke()
                            } else if (index == delays.size - 1) {
                                val dm = resources.displayMetrics
                                performTap(dm.widthPixels * 0.35f, dm.heightPixels * 0.94f)
                                stepHandler.postDelayed({
                                    val finalEdit = findFocusedEditText(rootInActiveWindow)
                                    if (finalEdit != null) {
                                        typeIntoNode(finalEdit, message)
                                        typed = true
                                        onTyped?.invoke()
                                    }
                                }, 500L)
                            }
                        }
                    }
                }
            }, delay)
        }
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

    fun armWhatsappDeleteLastMessage(attempt: Int = 1) {
        val delay = if (attempt == 1) 1400L else 1000L
        stepHandler.postDelayed({
            val root = rootInActiveWindow
            if (root == null) {
                if (attempt < 4) armWhatsappDeleteLastMessage(attempt + 1)
                return@postDelayed
            }

            val trashHints = listOf(
                "delete", "trash", "com.whatsapp:id/menuitem_conversations_delete",
                "हटाएं", "डिलीट", "remove"
            )
            // If already in selection mode (trash icon visible)
            if (findNode(root, trashHints) != null) {
                scheduleTrashAndConfirmCheck(1)
                return@postDelayed
            }

            val bubble = findLastMessageNode(root)
            var initiated = false

            if (bubble != null) {
                // 1. Walk up to long-clickable parent if bubble itself isn't long-clickable
                var longClickTarget: AccessibilityNodeInfo? = bubble
                while (longClickTarget != null && !longClickTarget.isLongClickable) {
                    longClickTarget = longClickTarget.parent
                }
                if (longClickTarget != null) {
                    initiated = longClickTarget.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                    Log.i(TAG, "ACTION_LONG_CLICK on parent container ($longClickTarget): $initiated")
                }

                // 2. Direct ACTION_LONG_CLICK on bubble
                if (!initiated) {
                    initiated = bubble.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                    Log.i(TAG, "ACTION_LONG_CLICK on bubble ($bubble): $initiated")
                }

                // 3. Physical gesture fallback on bubble coordinates
                if (!initiated) {
                    val rect = android.graphics.Rect()
                    (longClickTarget ?: bubble).getBoundsInScreen(rect)
                    if (!rect.isEmpty && rect.width() > 0 && rect.height() > 0) {
                        initiated = performLongPress(rect.exactCenterX(), rect.exactCenterY(), 1000L)
                        Log.i(TAG, "performLongPress at (${rect.exactCenterX()}, ${rect.exactCenterY()}): $initiated")
                    }
                }
            }

            if (!initiated) {
                // Physical fallback at lower-middle region of chat
                val dm = resources.displayMetrics
                performLongPress(dm.widthPixels * 0.55f, dm.heightPixels * 0.72f, 1000L)
                Log.i(TAG, "Fallback physical long press at 72% height executed")
            }

            // Schedule multi-stage trash icon and confirmation dialog check
            scheduleTrashAndConfirmCheck(1)
        }, delay)
    }

    private fun scheduleTrashAndConfirmCheck(checkAttempt: Int) {
        stepHandler.postDelayed({
            val currentRoot = rootInActiveWindow ?: return@postDelayed
            val trashHints = listOf(
                "delete", "trash", "com.whatsapp:id/menuitem_conversations_delete",
                "हटाएं", "डिलीट", "remove"
            )
            val trashClicked = clickFirstMatch(currentRoot, trashHints)
            Log.i(TAG, "Trash icon click (attempt $checkAttempt): $trashClicked")

            if (trashClicked) {
                scheduleConfirmDialogClicks(1)
            } else if (checkAttempt < 4) {
                scheduleTrashAndConfirmCheck(checkAttempt + 1)
            }
        }, 600L)
    }

    private fun scheduleConfirmDialogClicks(dialogAttempt: Int) {
        stepHandler.postDelayed({
            val dialogRoot = rootInActiveWindow ?: return@postDelayed
            val confirmHints = listOf(
                "delete for everyone", "sabke liye delete karein", "सभी के लिए हटाएं",
                "delete for me", "mere liye delete karein", "मेरे लिए हटाएं",
                "delete", "हटाएं", "डिलीट", "ok", "हाँ", "yes"
            )
            val confirmClicked = clickFirstMatch(dialogRoot, confirmHints)
            Log.i(TAG, "Delete confirmation clicked (attempt $dialogAttempt): $confirmClicked")

            if (!confirmClicked && dialogAttempt < 5) {
                scheduleConfirmDialogClicks(dialogAttempt + 1)
            }
        }, 500L)
    }

    private fun findLastMessageNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val dm = resources.displayMetrics
        val minTop = dm.heightPixels * 0.10f
        val maxBottom = dm.heightPixels * 0.88f

        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
        fun scan(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val resId = (n.viewIdResourceName ?: "").lowercase()
            val desc = (n.contentDescription?.toString() ?: "").lowercase()
            val rect = android.graphics.Rect()
            n.getBoundsInScreen(rect)

            val withinChatBounds = rect.top >= minTop && rect.bottom <= maxBottom && rect.height() >= 30

            if (withinChatBounds) {
                val isMessageItem = resId.contains("conversation_row") ||
                        resId.contains("message") ||
                        resId.contains("thumb") ||
                        resId.contains("image") ||
                        resId.contains("photo") ||
                        resId.contains("media") ||
                        resId.contains("row_content") ||
                        resId.contains("main_layout") ||
                        n.isLongClickable ||
                        desc.contains("photo") ||
                        desc.contains("image") ||
                        desc.contains("message")

                val isInput = resId.contains("entry") || resId.contains("voice") ||
                        resId.contains("send") || resId.contains("camera") ||
                        resId.contains("attach") || resId.contains("emoji")

                if (isMessageItem && !isInput) {
                    candidates.add(Pair(n, rect.bottom))
                }
            }
            for (i in 0 until n.childCount) {
                scan(n.getChild(i))
            }
        }
        scan(node)
        return candidates.maxByOrNull { it.second }?.first
    }

    /**
     * Search for a contact inside WhatsApp and open their conversation, then execute callback.
     */
    fun armWhatsappOpenContactChat(contact: String, onOpened: () -> Unit) {
        val searchHints = listOf("search", "com.whatsapp:id/menuitem_search", "खोजें")
        stepHandler.postDelayed({
            val root = rootInActiveWindow
            if (root != null) {
                clickFirstMatch(root, searchHints)
            }
            stepHandler.postDelayed({
                val searchRoot = rootInActiveWindow
                val edit = findFocusedEditText(searchRoot)
                if (edit != null) {
                    typeIntoNode(edit, contact)
                }
                stepHandler.postDelayed({
                    val resultRoot = rootInActiveWindow
                    if (resultRoot != null) {
                        clickFirstMatch(resultRoot, listOf(contact.lowercase()))
                    }
                    stepHandler.postDelayed({
                        onOpened()
                    }, 1000L)
                }, 1000L)
            }, 600L)
        }, 1000L)
    }

    /**
     * Autonomously empties the Recycle Bin / Trash in Gallery or File Manager.
     * Handles Samsung, Realme, ColorOS, Xiaomi, and Google Photos layouts.
     */
    fun armEmptyRecycleBin() {
        val delays = listOf(500L, 1200L, 2200L, 3500L, 5000L)
        val moreOptionsHints = listOf("more options", "more", "menu", "overflow", "अन्य विकल्प", "विकल्प")
        val emptyHints = listOf("empty", "empty recycle bin", "empty trash", "clear", "clear all", "recycle bin खाली करें", "खाली करें", "delete all")
        val selectHints = listOf("select", "चुनें")
        val selectAllHints = listOf("all", "select all", "सभी", "सभी चुनें")
        val deleteHints = listOf("delete", "delete all", "हटाएं", "सभी हटाएं")
        val confirmHints = listOf("empty", "empty recycle bin", "delete", "permanently delete", "ok", "confirm", "खाली करें", "हटाएं")

        delays.forEachIndexed { index, delay ->
            stepHandler.postDelayed({
                val root = rootInActiveWindow ?: return@postDelayed

                // Stage 1: Check for confirmation dialog first if it popped up
                if (clickFirstMatch(root, confirmHints)) {
                    Log.i(TAG, "Clicked confirmation dialog button in Recycle Bin at ${delay}ms")
                    return@postDelayed
                }

                // Stage 2: Direct 'Empty' or 'Empty Recycle bin' button
                if (clickFirstMatch(root, emptyHints)) {
                    Log.i(TAG, "Direct 'Empty' clicked at ${delay}ms")
                    return@postDelayed
                }

                // Stage 3: Click '⋮' (more options) at top right, then click Empty
                val moreClicked = clickFirstMatch(root, moreOptionsHints)
                if (moreClicked) {
                    stepHandler.postDelayed({
                        val menuRoot = rootInActiveWindow
                        if (menuRoot != null) {
                            clickFirstMatch(menuRoot, emptyHints)
                        }
                    }, 400L)
                    return@postDelayed
                }

                // Stage 4: 'Select' -> 'All' -> 'Delete'
                val selectClicked = clickFirstMatch(root, selectHints)
                if (selectClicked) {
                    stepHandler.postDelayed({
                        val selRoot = rootInActiveWindow
                        if (selRoot != null) {
                            clickFirstMatch(selRoot, selectAllHints)
                            stepHandler.postDelayed({
                                val delRoot = rootInActiveWindow
                                if (delRoot != null) {
                                    clickFirstMatch(delRoot, deleteHints)
                                }
                            }, 500L)
                        }
                    }, 500L)
                    return@postDelayed
                }

                // Coordinate fallback for top-right 3-dots on typical screens
                if (index == 2) {
                    val dm = resources.displayMetrics
                    performTap(dm.widthPixels * 0.94f, dm.heightPixels * 0.05f)
                    stepHandler.postDelayed({
                        val menuRoot = rootInActiveWindow
                        if (menuRoot != null) {
                            clickFirstMatch(menuRoot, emptyHints)
                        }
                    }, 500L)
                }
            }, delay)
        }
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

    fun isYouTubeInForeground(): Boolean {
        val rootPkg = rootInActiveWindow?.packageName?.toString() ?: ""
        if (rootPkg == PKG_YOUTUBE) return true
        return try {
            windows.any { it.root?.packageName?.toString() == PKG_YOUTUBE }
        } catch (_: Exception) {
            false
        }
    }

    fun performYoutubeSkipAd(): Boolean {
        val activeRoot = rootInActiveWindow
        val isYtActive = activeRoot?.packageName?.toString() == PKG_YOUTUBE
        val ytRoot = if (isYtActive) activeRoot else {
            try {
                windows.firstOrNull { it.root?.packageName?.toString() == PKG_YOUTUBE }?.root
            } catch (_: Exception) {
                null
            }
        }

        if (ytRoot == null) {
            Log.w(TAG, "performYoutubeSkipAd: YouTube is not visible on screen (current foreground: ${activeRoot?.packageName})")
            return false
        }

        val skipNode = findYoutubeSkipAdNode(ytRoot)
        if (skipNode != null) {
            val label = skipNode.text?.toString() ?: skipNode.contentDescription?.toString() ?: skipNode.viewIdResourceName ?: "skip button"
            val clicked = clickNodeOrTap(skipNode)
            if (clicked) {
                Log.i(TAG, "performYoutubeSkipAd: Clicked genuine YouTube skip ad button: '$label'")
                return true
            }
        }

        val (dismissed, detail) = dismissInterruptingDialogsOrAds(fallbackToBack = false)
        if (dismissed && (activeRoot?.packageName?.toString() == PKG_YOUTUBE)) {
            Log.i(TAG, "performYoutubeSkipAd: Dismissed via reflection agent ($detail)")
            return true
        }

        Log.w(TAG, "performYoutubeSkipAd: No genuine skip ad button visible in YouTube window")
        return false
    }

    private fun findYoutubeSkipAdNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val resId = (node.viewIdResourceName?.toString() ?: "").lowercase()
        val text = (node.text?.toString() ?: "").lowercase().trim()
        val desc = (node.contentDescription?.toString() ?: "").lowercase().trim()

        if (resId.isNotEmpty() && YOUTUBE_SKIP_AD_RES_IDS.any { resId.contains(it) }) {
            return node
        }

        val label = if (text.isNotEmpty()) text else desc
        if (label.isNotEmpty() && label.length <= 20) {
            val isStrictMatch = YOUTUBE_SKIP_AD_STRICT_TEXTS.any { label == it } ||
                                label.startsWith("skip in ") ||
                                label.startsWith("skip ad in ")
            if (isStrictMatch) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val match = findYoutubeSkipAdNode(node.getChild(i))
            if (match != null) return match
        }
        return null
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
        val hints = listOf("search", "find", "search_src_text", "explore", "songs, artists or podcasts")
        armAdaptiveSearch(query, hints, "media_$appName") {
            hybridHandler.postDelayed({
                val root = rootInActiveWindow ?: return@postDelayed
                val words = query.lowercase().split(" ").filter { it.length > 2 }
                val targetNode = findNode(root, words)
                if (targetNode != null) {
                    clickNodeOrTap(targetNode)
                } else {
                    val dm = resources.displayMetrics
                    performTap(dm.widthPixels * 0.5f, dm.heightPixels * 0.28f)
                }
            }, 1500)
        }
    }

    /**
     * Universal in-app autonomous search and optional click for ANY arbitrary Android app.
     */
    fun armSearchAndClick(query: String, targetClick: String? = null) {
        val hints = listOf("search", "find", "explore", "query", "search_src_text", "खोजें")
        armAdaptiveSearch(query, hints, "universal_search") {
            if (!targetClick.isNullOrBlank()) {
                hybridHandler.postDelayed({
                    performClickByText(targetClick)
                }, 1200)
            }
        }
    }

    /**
     * Clear Chrome browsing history automatically via Accessibility UI navigation.
     */
    fun armClearChromeHistory() {
        startPlan("clear_chrome_history", listOf(
            A11yStep(clickHint = "more options", delayMs = 1000),
            A11yStep(clickHint = "history", delayMs = 1200),
            A11yStep(clickHint = "clear browsing data", delayMs = 1000),
            A11yStep(clickHint = "clear data", delayMs = 800)
        ))
    }

    /**
     * Clear YouTube search/watch history automatically via Accessibility UI navigation.
     */
    fun armClearYouTubeHistory() {
        startPlan("clear_youtube_history", listOf(
            A11yStep(clickHint = "you", delayMs = 1000),
            A11yStep(clickHint = "settings", delayMs = 1200),
            A11yStep(clickHint = "manage all history", delayMs = 1200),
            A11yStep(clickHint = "delete", delayMs = 1000)
        ))
    }

    /**
     * Clear WhatsApp chat with a contact automatically via Accessibility UI navigation.
     */
    fun armClearWhatsappChat(contactName: String) {
        val cleanContact = contactName.replace(Regex("\\b(ko|ji|bhai|sahab|de|ka|ki|se|pe|par)\\b", RegexOption.IGNORE_CASE), "").trim()
        val steps = listOf(
            A11yStep(clickHint = "search", delayMs = 1000),
            A11yStep(typeText  = cleanContact, delayMs = 1200),
            A11yStep(clickHint = cleanContact.split(" ").firstOrNull() ?: cleanContact, delayMs = 1500),
            A11yStep(clickHint = "more options", delayMs = 1000),
            A11yStep(clickHint = "more", delayMs = 800),
            A11yStep(clickHint = "clear chat", delayMs = 800),
            A11yStep(clickHint = "clear chat", delayMs = 800)
        )
        startPlan("clear_wa_chat", steps)
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

    fun takeOptimizedScreenCapture(maxDim: Int = 1024, quality: Int = 75, callback: (ByteArray?) -> Unit) {
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
                                        val width = swBitmap.width
                                        val height = swBitmap.height
                                        val finalBitmap = if (width > maxDim || height > maxDim) {
                                            val ratio = minOf(maxDim.toFloat() / width, maxDim.toFloat() / height)
                                            val newW = (width * ratio).toInt()
                                            val newH = (height * ratio).toInt()
                                            val scaled = Bitmap.createScaledBitmap(swBitmap, newW, newH, true)
                                            swBitmap.recycle()
                                            scaled
                                        } else {
                                            swBitmap
                                        }
                                        val stream = ByteArrayOutputStream()
                                        finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                                        finalBitmap.recycle()
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
        val root = rootInActiveWindow
        if (root != null && clickFirstMatch(root, listOf(text.lowercase()))) {
            return true
        }
        // Fallback across all active on-screen windows (system dialogs, launcher overlays)
        try {
            for (win in windows) {
                val winRoot = win.root ?: continue
                if (clickFirstMatch(winRoot, listOf(text.lowercase()))) {
                    return true
                }
            }
        } catch (_: Exception) {}
        return false
    }

    fun scrollWindow(down: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return root.performAction(action)
    }

    fun answerIncomingCall(): Boolean {
        val answerLabels = listOf("answer", "accept", "उत्तर", "उठाओ", "call answer", "swipe up to answer", "swipe right to answer")
        for (label in answerLabels) {
            if (performClickByText(label)) return true
        }
        val root = rootInActiveWindow
        if (root != null) {
            val answerIds = listOf(
                "com.google.android.dialer:id/incall_first_button",
                "com.google.android.dialer:id/answer",
                "com.samsung.android.incallui:id/answer_button",
                "com.samsung.android.incallui:id/answer",
                "com.android.incallui:id/answer",
                "com.android.dialer:id/answer"
            )
            for (id in answerIds) {
                try {
                    val nodes = root.findAccessibilityNodeInfosByViewId(id)
                    for (node in nodes) {
                        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            return true
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        val dm = resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels
        return performSwipe(width / 2, (height * 0.82f).toInt(), width / 2, (height * 0.35f).toInt(), 280)
    }

    fun declineIncomingCall(): Boolean {
        val declineLabels = listOf("decline", "reject", "dismiss", "end call", "अस्वीकार", "काटो", "swipe down to decline")
        for (label in declineLabels) {
            if (performClickByText(label)) return true
        }
        val root = rootInActiveWindow
        if (root != null) {
            val declineIds = listOf(
                "com.google.android.dialer:id/incall_second_button",
                "com.google.android.dialer:id/decline",
                "com.samsung.android.incallui:id/decline_button",
                "com.samsung.android.incallui:id/decline",
                "com.android.incallui:id/decline",
                "com.android.dialer:id/decline"
            )
            for (id in declineIds) {
                try {
                    val nodes = root.findAccessibilityNodeInfosByViewId(id)
                    for (node in nodes) {
                        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            return true
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        val dm = resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels
        return performSwipe(width / 2, (height * 0.65f).toInt(), width / 2, (height * 0.95f).toInt(), 280)
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

    /**
     * Autonomously likes the current post or reel on Instagram.
     * Strategy 1: Finds node matching "like", "heart", "like button" and clicks it.
     * Strategy 2: Fast double-tap at the center of the screen (universal Instagram like gesture).
     */
    fun armInstagramLike(): Boolean {
        val root = rootInActiveWindow
        val likeHints = listOf("like", "like button", "heart", "like this post", "like reel", "पसंद करें")
        if (root != null && clickFirstMatch(root, likeHints)) {
            Log.i(TAG, "Instagram like button clicked via accessibility node")
            return true
        }
        // Universal center double-tap gesture fallback for Reels / Feed posts
        val dm = resources.displayMetrics
        val cx = dm.widthPixels * 0.5f
        val cy = dm.heightPixels * 0.45f
        performTap(cx, cy)
        stepHandler.postDelayed({
            performTap(cx, cy)
            Log.i(TAG, "Instagram double-tap like gesture executed at ($cx, $cy)")
        }, 120L)
        return true
    }

    /**
     * Autonomously comments on the current Instagram post or reel.
     */
    fun armInstagramComment(comment: String) {
        startPlan("instagram_comment", listOf(
            A11yStep(clickHint = "comment", delayMs = 600),
            A11yStep(clickHint = "add a comment", delayMs = 600),
            A11yStep(typeText = comment, delayMs = 700),
            A11yStep(clickHint = "post", delayMs = 500, verifyHint = comment.take(15))
        ))
    }

    /**
     * Autonomously creates and posts a story on Instagram.
     */
    fun armInstagramStory(caption: String? = null) {
        val steps = mutableListOf(
            A11yStep(clickHint = "your story", delayMs = 1200),
            A11yStep(clickHint = "create", delayMs = 1000)
        )
        if (!caption.isNullOrBlank()) {
            steps.add(A11yStep(clickHint = "type something", delayMs = 800))
            steps.add(A11yStep(typeText = caption, delayMs = 800))
        }
        steps.add(A11yStep(clickHint = "your story", delayMs = 900))
        steps.add(A11yStep(clickHint = "share", delayMs = 800))
        startPlan("instagram_story", steps)
    }

    /**
     * Autonomously navigates inside Instagram to reels, dms, profile, explore, or settings.
     */
    fun armInstagramNavigate(destination: String) {
        val dest = destination.lowercase().trim()
        when {
            dest.contains("reel") -> {
                startPlan("instagram_nav_reels", listOf(
                    A11yStep(clickHint = "reels", delayMs = 800)
                ))
            }
            dest.contains("profile") -> {
                startPlan("instagram_nav_profile", listOf(
                    A11yStep(clickHint = "profile", delayMs = 800)
                ))
            }
            dest.contains("explore") || dest.contains("search") -> {
                startPlan("instagram_nav_explore", listOf(
                    A11yStep(clickHint = "search and explore", delayMs = 800),
                    A11yStep(clickHint = "explore", delayMs = 600)
                ))
            }
            dest.contains("setting") -> {
                startPlan("instagram_nav_settings", listOf(
                    A11yStep(clickHint = "profile", delayMs = 800),
                    A11yStep(clickHint = "menu", delayMs = 800),
                    A11yStep(clickHint = "settings and privacy", delayMs = 800),
                    A11yStep(clickHint = "settings", delayMs = 600)
                ))
            }
            dest.contains("dm") || dest.contains("msg") || dest.contains("message") -> {
                startPlan("instagram_nav_dms", listOf(
                    A11yStep(clickHint = "messages", delayMs = 800),
                    A11yStep(clickHint = "direct", delayMs = 600)
                ))
            }
        }
    }

    // ── WhatsApp Full Automation Suite (Status, Backup, Settings) ─────────────

    /**
     * Autonomously posts a WhatsApp Status / Story.
     */
    fun armWhatsappStatus(statusText: String? = null) {
        val steps = mutableListOf(
            A11yStep(clickHint = "updates", delayMs = 1000),
            A11yStep(clickHint = "status", delayMs = 800)
        )
        if (!statusText.isNullOrBlank()) {
            steps.add(A11yStep(clickHint = "text status", delayMs = 900))
            steps.add(A11yStep(clickHint = "pencil", delayMs = 700))
            steps.add(A11yStep(typeText = statusText, delayMs = 800))
            steps.add(A11yStep(clickHint = "send", delayMs = 600, verifyHint = "just now"))
        } else {
            steps.add(A11yStep(clickHint = "camera", delayMs = 900))
        }
        startPlan("whatsapp_status", steps)
    }

    /**
     * Autonomously triggers WhatsApp Chat Backup (Settings > Chats > Chat backup > Back up).
     */
    fun armWhatsappBackup() {
        startPlan("whatsapp_backup", listOf(
            A11yStep(clickHint = "more options", delayMs = 900),
            A11yStep(clickHint = "settings", delayMs = 800),
            A11yStep(clickHint = "chats", delayMs = 800),
            A11yStep(clickHint = "chat backup", delayMs = 900),
            A11yStep(clickHint = "back up", delayMs = 1000, verifyHint = "backing up")
        ))
    }

    /**
     * Autonomously opens WhatsApp Settings and navigates to the requested sub-section.
     */
    fun armWhatsappOpenSettings(section: String? = null) {
        val steps = mutableListOf(
            A11yStep(clickHint = "more options", delayMs = 800),
            A11yStep(clickHint = "settings", delayMs = 800)
        )
        if (!section.isNullOrBlank()) {
            val secClean = section.lowercase().trim()
            steps.add(A11yStep(clickHint = secClean, delayMs = 800))
        }
        startPlan("whatsapp_settings", steps)
    }

    /**
     * Universal Autonomous UI Action: Millisecond element matcher and activator.
     * Searches active hierarchy for any matching button/text keyword and taps or types into it.
     */
    fun armAutonomousUiAction(targetKeywords: List<String>, typeText: String? = null): Boolean {
        val root = rootInActiveWindow
        var matched = false
        if (root != null) {
            matched = clickFirstMatch(root, targetKeywords.map { it.lowercase() })
        }
        if (!matched) {
            try {
                for (win in windows) {
                    val winRoot = win.root ?: continue
                    if (clickFirstMatch(winRoot, targetKeywords.map { it.lowercase() })) {
                        matched = true
                        break
                    }
                }
            } catch (_: Exception) {}
        }
        if (matched && !typeText.isNullOrBlank()) {
            stepHandler.postDelayed({
                val edit = findFocusedEditText(rootInActiveWindow)
                if (edit != null) {
                    typeIntoNode(edit, typeText)
                }
            }, 500L)
        }
        return matched
    }

    // ── New: Facebook Post ────────────────────────────────────────────────────

    fun armFacebookPost(text: String) {
        startPlan("facebook_post", listOf(
            A11yStep(clickHint = "what's on your mind", delayMs = 800),
            A11yStep(typeText  = text,                  delayMs = 600),
            A11yStep(clickHint = "post",                delayMs = 800),
        ))
    }

    // ── Regular Everyday Apps Autonomous Automation Suite ─────────────────────

    /** Telegram: Autonomously search contact and send message */
    fun armTelegramMessage(recipient: String, message: String) {
        startPlan("telegram_message", listOf(
            A11yStep(clickHint = "search", delayMs = 900),
            A11yStep(typeText = recipient, delayMs = 800),
            A11yStep(clickHint = recipient.lowercase(), delayMs = 1000),
            A11yStep(clickHint = "message", delayMs = 700),
            A11yStep(typeText = message, delayMs = 700),
            A11yStep(clickHint = "send", delayMs = 500, verifyHint = message.take(15))
        ))
    }

    /** YouTube & Shorts: Like, Comment, Subscribe, Next/Prev Shorts */
    fun armYoutubeComment(comment: String) {
        startPlan("youtube_comment", listOf(
            A11yStep(clickHint = "comments", delayMs = 900),
            A11yStep(clickHint = "add a comment", delayMs = 700),
            A11yStep(typeText = comment, delayMs = 800),
            A11yStep(clickHint = "comment", delayMs = 600)
        ))
    }

    /** Twitter / X: Post tweet or like */
    fun armTwitterPost(text: String) {
        startPlan("twitter_post", listOf(
            A11yStep(clickHint = "compose", delayMs = 900),
            A11yStep(clickHint = "post", delayMs = 700),
            A11yStep(typeText = text, delayMs = 800),
            A11yStep(clickHint = "post", delayMs = 600, verifyHint = text.take(15))
        ))
    }

    fun armTwitterLike(): Boolean {
        val root = rootInActiveWindow ?: return false
        val likeHints = listOf("like", "like button", "heart", "पसंद करें")
        return clickFirstMatch(root, likeHints)
    }

    /** Food Delivery (Zomato / Swiggy): Search dish, track order, open cart */
    fun armFoodSearch(dishOrRestaurant: String, appName: String = "zomato") {
        val hints = if (appName.contains("swiggy")) {
            listOf("search for food", "search for restaurants and food", "search", "search_bar", "search_widget")
        } else {
            listOf("restaurant name or a dish", "search for restaurants", "search", "edit_search", "search_bar")
        }
        armAdaptiveSearch(dishOrRestaurant, hints, "food_$appName")
    }

    fun armFoodTrackOrder(): Boolean {
        val root = rootInActiveWindow ?: return false
        val trackHints = listOf("track order", "track", "order status", "view order", "delivery status", "order tracking", "ट्रैक करें")
        return clickFirstMatch(root, trackHints)
    }

    fun armFoodOpenCart(): Boolean {
        val root = rootInActiveWindow ?: return false
        val cartHints = listOf("cart", "view cart", "view basket", "checkout", "कार्ट")
        return clickFirstMatch(root, cartHints)
    }

    /** Ride Booking (Uber / Ola / Rapido): Search ride & track */
    fun armRideSearch(destination: String, appName: String = "uber") {
        val hints = if (appName.contains("ola")) {
            listOf("search destination", "where are you going", "search", "destination_search")
        } else {
            listOf("where to", "where are you going", "search", "pickup_search")
        }
        armAdaptiveSearch(destination, hints, "ride_$appName")
    }

    fun armRideTrack(): Boolean {
        val root = rootInActiveWindow ?: return false
        val rideHints = listOf("current ride", "trip details", "track driver", "ride status", "driver on the way", "ट्रिप")
        return clickFirstMatch(root, rideHints)
    }

    /** Shopping (Amazon / Flipkart / Myntra / Meesho): Search product, track orders, cart */
    fun armShoppingSearch(productQuery: String, appName: String = "amazon") {
        val hints = if (appName.contains("flipkart")) {
            listOf("search for products", "search products", "search", "search_widget_textbox", "search_text_box", "खोजें")
        } else if (appName.contains("meesho")) {
            listOf("search by keyword or product id", "search", "search_src_text")
        } else {
            listOf("search amazon.in", "search amazon", "search", "rs_search_src_text", "chrome_search_hint_view", "nav_search_keywords", "खोजें")
        }
        armAdaptiveSearch(productQuery, hints, "shopping_$appName")
    }

    fun armShoppingTrackOrders(): Boolean {
        val root = rootInActiveWindow ?: return false
        val orderHints = listOf("your orders", "my orders", "orders", "track package", "order history", "ऑर्डर्स")
        return clickFirstMatch(root, orderHints)
    }

    fun armShoppingOpenCart(): Boolean {
        val root = rootInActiveWindow ?: return false
        val cartHints = listOf("cart", "shopping cart", "basket", "कार्ट")
        return clickFirstMatch(root, cartHints)
    }

    /** UPI / Payments (PhonePe / GPay / Paytm): Scan QR, send money, balance, history */
    fun armUpiScanQr(): Boolean {
        val root = rootInActiveWindow ?: return false
        val scanHints = listOf("scan qr", "scan any qr", "scan qr code", "qr scanner", "scanner", "स्कैन करें")
        return clickFirstMatch(root, scanHints)
    }

    fun armUpiSendMoney(recipient: String, amount: String? = null) {
        val steps = mutableListOf(
            A11yStep(clickHint = "to mobile number", delayMs = 900),
            A11yStep(clickHint = "to contact", delayMs = 700),
            A11yStep(typeText = recipient, delayMs = 800),
            A11yStep(clickHint = recipient.split(" ").firstOrNull() ?: recipient, delayMs = 1000)
        )
        if (!amount.isNullOrBlank()) {
            steps.add(A11yStep(clickHint = "enter amount", delayMs = 800))
            steps.add(A11yStep(typeText = amount, delayMs = 600))
        }
        startPlan("upi_send_money", steps)
    }

    fun armUpiCheckBalance(): Boolean {
        val root = rootInActiveWindow ?: return false
        val balHints = listOf("check balance", "check bank balance", "bank balance", "balance", "बैलेंस चेक करें")
        return clickFirstMatch(root, balHints)
    }

    fun armUpiHistory(): Boolean {
        val root = rootInActiveWindow ?: return false
        val histHints = listOf("history", "transaction history", "see all payment history", "transactions", "लेनदेन")
        return clickFirstMatch(root, histHints)
    }

    /** Snapchat: Switch between camera, stories, and chats */
    fun armSnapchatAction(action: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val hints = when (action.lowercase()) {
            "stories", "story" -> listOf("stories", "spotlight")
            "chats", "chat", "messages" -> listOf("chat", "chats", "messages")
            else -> listOf("camera", "capture", "snap")
        }
        return clickFirstMatch(root, hints)
    }

    /** Chrome / Web Browser: New tab, incognito, search, bookmark */
    fun armBrowserAction(action: String, queryOrUrl: String? = null) {
        val act = action.lowercase()
        when {
            act.contains("incognito") -> {
                startPlan("browser_incognito", listOf(
                    A11yStep(clickHint = "more options", delayMs = 800),
                    A11yStep(clickHint = "new incognito tab", delayMs = 800)
                ))
            }
            act.contains("new_tab") || act.contains("tab") -> {
                startPlan("browser_new_tab", listOf(
                    A11yStep(clickHint = "more options", delayMs = 800),
                    A11yStep(clickHint = "new tab", delayMs = 800)
                ))
            }
            act.contains("bookmark") -> {
                startPlan("browser_bookmark", listOf(
                    A11yStep(clickHint = "more options", delayMs = 800),
                    A11yStep(clickHint = "bookmark", delayMs = 600)
                ))
            }
            !queryOrUrl.isNullOrBlank() -> {
                startPlan("browser_search", listOf(
                    A11yStep(clickHint = "search or type url", delayMs = 800),
                    A11yStep(clickHint = "search or type web address", delayMs = 600),
                    A11yStep(typeText = queryOrUrl, delayMs = 700)
                ))
            }
        }
    }

    /** Spotify / Media: Like current track */
    fun armSpotifyLike(): Boolean {
        val root = rootInActiveWindow ?: return false
        val likeHints = listOf("like", "add to liked songs", "favorite", "heart", "liked")
        return clickFirstMatch(root, likeHints)
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
        startPlan("app_search_launch", listOf(
            A11yStep(clickHint = "search",            delayMs = 700),
            A11yStep(typeText  = appName,             delayMs = 600),
            A11yStep(clickHint = appName.lowercase(), delayMs = 800),
        ))
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
     * Draws a continuous pattern gesture on a 3x3 (9-dot) unlock or payment grid.
     * [dots] is a list of dot numbers 1 to 9:
     *   1 (top-left)    2 (top-mid)    3 (top-right)
     *   4 (mid-left)    5 (center)     6 (mid-right)
     *   7 (bot-left)    8 (bot-mid)    9 (bot-right)
     */
    fun drawPattern(dots: List<Int>, durationMs: Long = 750L): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        if (dots.size < 2) return false

        val root = rootInActiveWindow
        val targetBounds = android.graphics.Rect()
        var foundPatternView = false

        fun searchPatternNode(node: android.view.accessibility.AccessibilityNodeInfo?) {
            if (node == null || foundPatternView) return
            val cls = node.className?.toString()?.lowercase() ?: ""
            val resId = node.viewIdResourceName?.lowercase() ?: ""
            if (cls.contains("pattern") || resId.contains("pattern") || resId.contains("lock_pattern")) {
                node.getBoundsInScreen(targetBounds)
                if (targetBounds.width() > 200 && targetBounds.height() > 200) {
                    foundPatternView = true
                    return
                }
            }
            for (i in 0 until node.childCount) {
                searchPatternNode(node.getChild(i))
                if (foundPatternView) return
            }
        }

        searchPatternNode(root)

        // Fallback: If view not explicitly tagged, standard Android lock/payment pattern is centered in lower half
        val bounds = if (foundPatternView && targetBounds.width() > 200) {
            targetBounds
        } else {
            val dm = resources.displayMetrics
            val left = (dm.widthPixels * 0.12f).toInt()
            val right = (dm.widthPixels * 0.88f).toInt()
            val top = (dm.heightPixels * 0.44f).toInt()
            val bottom = (dm.heightPixels * 0.82f).toInt()
            android.graphics.Rect(left, top, right, bottom)
        }

        val cellW = bounds.width().toFloat() / 3f
        val cellH = bounds.height().toFloat() / 3f

        fun getDotCoord(dotIndex: Int): Pair<Float, Float> {
            val clamped = dotIndex.coerceIn(1, 9)
            val row = (clamped - 1) / 3
            val col = (clamped - 1) % 3
            val x = bounds.left + col * cellW + cellW / 2f
            val y = bounds.top + row * cellH + cellH / 2f
            return Pair(x, y)
        }

        val path = Path()
        val (firstX, firstY) = getDotCoord(dots.first())
        path.moveTo(firstX, firstY)

        for (i in 1 until dots.size) {
            val (x, y) = getDotCoord(dots[i])
            path.lineTo(x, y)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        Log.i("AuraAccessibility", "Drawing pattern across dots $dots within bounds $bounds (duration=${durationMs}ms)")
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Dispatches a real physical touch tap gesture at exact screen coordinates (x, y).
     * Simulates genuine human finger touch across all apps (Compose, Flutter, React Native, Unity).
     */
    fun performTap(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Dispatches a real physical long-press touch gesture at exact screen coordinates (x, y).
     */
    fun performLongPress(x: Float, y: Float, durationMs: Long = 1100L): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun performClickCoordinate(x: Float, y: Float): Boolean {
        val dm = resources.displayMetrics
        val realX = if (x in 0.0f..1.0f && x > 0f) x * dm.widthPixels else x
        val realY = if (y in 0.0f..1.0f && y > 0f) y * dm.heightPixels else y
        return performTap(realX, realY)
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
        val root = rootInActiveWindow ?: return "Screen is empty or inaccessible (enable ISHA Accessibility)."
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

    fun findAnyEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = findAnyEditable(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun findFocusedEditText(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        // 1. First pass: look specifically for focused + editable
        fun searchFocused(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isFocused && node.isEditable) return node
            for (i in 0 until node.childCount) {
                val found = searchFocused(node.getChild(i))
                if (found != null) return found
            }
            return null
        }
        val focused = searchFocused(root)
        if (focused != null) return focused

        // 2. Second pass fallback: any editable field
        return findAnyEditable(root)
    }

    /**
     * Click a node or traverse up to a clickable parent.
     * If Accessibility click fails, performs physical touch tap on screen coordinates.
     */
    fun clickNodeOrTap(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        val clicked = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        if (clicked) return true

        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (!rect.isEmpty && rect.width() > 0 && rect.height() > 0) {
            return performTap(rect.exactCenterX(), rect.exactCenterY())
        }
        return false
    }

    /**
     * Recursively collects all text and content descriptions from a node hierarchy.
     */
    fun collectAllText(node: AccessibilityNodeInfo?, depth: Int = 0): String {
        if (node == null || depth > 25) return ""
        val sb = StringBuilder()
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        if (!text.isNullOrEmpty()) sb.append(text).append(" ")
        if (!desc.isNullOrEmpty() && desc != text) sb.append(desc).append(" ")
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                sb.append(collectAllText(child, depth + 1))
            }
        }
        return sb.toString()
    }

    /**
     * Finds autocomplete suggestion items in search dropdown.
     */
    fun findSuggestionNodes(
        root: AccessibilityNodeInfo?,
        queryLower: String,
        firstWord: String,
        depth: Int = 0
    ): List<AccessibilityNodeInfo> {
        if (root == null || depth > 25) return emptyList()
        val list = mutableListOf<AccessibilityNodeInfo>()
        val text = (root.text?.toString() ?: "").lowercase()
        val desc = (root.contentDescription?.toString() ?: "").lowercase()
        val resId = (root.viewIdResourceName?.toString() ?: "").lowercase()

        val isSuggestion = (resId.contains("suggestion") || resId.contains("autocomplete") || resId.contains("item") || resId.contains("title") || resId.contains("query")) &&
                (text.contains(firstWord) || desc.contains(firstWord) || text.contains(queryLower) || desc.contains(queryLower))

        if (isSuggestion && (root.isClickable || root.parent?.isClickable == true)) {
            list.add(root)
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child != null) {
                list.addAll(findSuggestionNodes(child, queryLower, firstWord, depth + 1))
            }
        }
        return list
    }

    /**
     * True Multi-Layer Hybrid Autonomous Search Engine:
     * 1. Perception Check: If search results are already rendered (from deep link), leaves screen untouched!
     * 2. If on home/feed screen: Finds the search bar (hints, resourceId, or coordinate) and clicks it.
     * 3. Types the search query into the focused or editable text field.
     * 4. Submits search via:
     *    - Autocomplete suggestion click
     *    - Search/Submit icon button click
     *    - Physical tap on the soft keyboard IME Search key at bottom-right (0.91w, 0.94h).
     */
    fun armAdaptiveSearch(
        query: String,
        searchHints: List<String>,
        appTag: String = "hybrid_search",
        onFinished: ((Boolean) -> Unit)? = null
    ) {
        val clean = query.trim()
        if (clean.isBlank()) return

        val currentToken = ++hybridSearchToken
        var searchStep = 0 // 0: check or tap search bar, 1: type query, 2: submit search, 3: completed
        var isDone = false

        // Attempts scheduled at staggered intervals to handle app loading animations
        val delays = listOf(800L, 1600L, 2600L, 3800L, 5200L)
        delays.forEachIndexed { attemptIndex, delay ->
            hybridHandler.postDelayed({
                if (currentToken != hybridSearchToken || isDone) return@postDelayed
                val root = rootInActiveWindow ?: return@postDelayed

                val screenText = collectAllText(root).lowercase()
                val cleanLower = clean.lowercase()
                val queryFirstWord = cleanLower.split(" ").firstOrNull { it.length > 2 } ?: cleanLower

                // 1. Perception check: Check if query results are already rendered
                val hasResults = (screenText.contains("results for") || screenText.contains("results") ||
                        (screenText.contains(queryFirstWord) && (
                                screenText.contains("filter") || screenText.contains("sort") ||
                                screenText.contains("price") || screenText.contains("₹") ||
                                screenText.contains("delivered to") || screenText.contains("free delivery") ||
                                screenText.contains("add to cart") || screenText.contains("rating") ||
                                screenText.contains("reviews") || screenText.contains("delivery time")
                        )))

                if (hasResults && searchStep == 0) {
                    Log.i(TAG, "[$appTag] Hybrid perception: Results for \"$clean\" already rendered on screen! Skipping manual tap.")
                    isDone = true
                    onFinished?.invoke(true)
                    return@postDelayed
                }

                when (searchStep) {
                    0 -> {
                        // Check if an editable field is ALREADY focused
                        val focusedEdit = findFocusedEditText(root)
                        if (focusedEdit != null && focusedEdit.isEditable) {
                            typeIntoNode(focusedEdit, clean)
                            Log.i(TAG, "[$appTag] Search field already focused. Directly typed: \"$clean\"")
                            searchStep = 2 // Move to submit
                        } else {
                            val searchNode = findNode(root, searchHints)
                            if (searchNode != null) {
                                val clicked = clickNodeOrTap(searchNode)
                                Log.i(TAG, "[$appTag] Tapped search bar node: $clicked")
                                searchStep = 1
                            } else {
                                // Screen coordinate fallback for top search bar (12% height)
                                val dm = resources.displayMetrics
                                performTap(dm.widthPixels * 0.5f, dm.heightPixels * 0.12f)
                                Log.i(TAG, "[$appTag] Fallback tapped top search bar area at (0.5w, 0.12h)")
                                searchStep = 1
                            }
                        }
                    }

                    1 -> {
                        // After tapping search bar, locate active EditText and type query
                        val edit = findFocusedEditText(root) ?: findAnyEditable(root)
                        if (edit != null) {
                            typeIntoNode(edit, clean)
                            Log.i(TAG, "[$appTag] Typed \"$clean\" into active edit field")
                            searchStep = 2
                        } else {
                            if (attemptIndex >= 2) {
                                val dm = resources.displayMetrics
                                performTap(dm.widthPixels * 0.5f, dm.heightPixels * 0.12f)
                            }
                        }
                    }

                    2 -> {
                        // Submit search!
                        var submitted = false

                        // A. Check for matching suggestion in dropdown
                        val suggestions = findSuggestionNodes(root, cleanLower, queryFirstWord)
                        if (suggestions.isNotEmpty()) {
                            val targetSuggestion = suggestions.first()
                            if (clickNodeOrTap(targetSuggestion)) {
                                submitted = true
                                Log.i(TAG, "[$appTag] Submitted search by clicking suggestion item")
                            }
                        }

                        // B. Check for Search icon / Go button in action bar
                        if (!submitted) {
                            val submitHints = listOf("search", "icon_search", "query_search", "submit", "go")
                            val submitBtn = findNode(root, submitHints)
                            if (submitBtn != null && (submitBtn.isClickable || submitBtn.parent?.isClickable == true)) {
                                if (clickNodeOrTap(submitBtn)) {
                                    submitted = true
                                    Log.i(TAG, "[$appTag] Submitted search by clicking search button")
                                }
                            }
                        }

                        // C. Fallback: Tap bottom-right keyboard Search / Enter IME key
                        if (!submitted) {
                            val dm = resources.displayMetrics
                            performTap(dm.widthPixels * 0.91f, dm.heightPixels * 0.94f)
                            Log.i(TAG, "[$appTag] Submitted search via keyboard IME Enter key tap (0.91w, 0.94h)")
                            submitted = true
                        }

                        searchStep = 3
                        isDone = true
                        onFinished?.invoke(true)
                    }
                }
            }, delay)
        }
    }

    private fun clickFirstMatch(root: AccessibilityNodeInfo, hints: List<String>): Boolean {
        val match = findNode(root, hints) ?: return false
        return clickNodeOrTap(match)
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
        val cleanQuery = query.trim().lowercase()
        val knownPackages = mapOf(
            "whatsapp" to "com.whatsapp",
            "whatsapp business" to "com.whatsapp.w4b",
            "instagram" to "com.instagram.android",
            "telegram" to "org.telegram.messenger",
            "snapchat" to "com.snapchat.android",
            "facebook" to "com.facebook.katana",
            "messenger" to "com.facebook.orca",
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "spotify" to "com.spotify.music",
            "truecaller" to "com.truecaller",
            "zomato" to "com.application.zomato",
            "swiggy" to "in.swiggy.android",
            "paytm" to "net.one97.paytm",
            "phonepe" to "com.phonepe.app",
            "gpay" to "com.google.android.apps.nfc.payment",
            "google pay" to "com.google.android.apps.nfc.payment"
        )

        val directPkg = knownPackages[cleanQuery]
        val uri = if (directPkg != null) {
            Uri.parse("market://details?id=$directPkg")
        } else {
            Uri.parse("market://search?q=" + URLEncoder.encode(query, "UTF-8"))
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.android.vending")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (_: Exception) {
            val webUri = if (directPkg != null) {
                Uri.parse("https://play.google.com/store/apps/details?id=$directPkg")
            } else {
                Uri.parse("https://play.google.com/store/search?q=" + URLEncoder.encode(query, "UTF-8"))
            }
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

    /**
     * Visual Reflection Agent:
     * Scans active screen hierarchy for interrupting popups, ads, rating banners,
     * or consent sheets and autonomously dismisses them.
     */
    fun dismissInterruptingDialogsOrAds(fallbackToBack: Boolean = true): Pair<Boolean, String> {
        val root = rootInActiveWindow ?: return if (fallbackToBack) {
            val backed = performGlobalAction(GLOBAL_ACTION_BACK)
            Pair(backed, if (backed) "Screen window root absent, sent system BACK key" else "Failed to access screen window")
        } else Pair(false, "No active screen window")

        // 1. Direct node match on text, contentDescription, or resource-id
        val targetNode = findInterruptingNode(root)
        if (targetNode != null) {
            val label = targetNode.text?.toString() ?: targetNode.contentDescription?.toString() ?: targetNode.viewIdResourceName ?: "dismiss button"
            val clicked = clickNodeOrTap(targetNode)
            if (clicked) {
                Log.i(TAG, "dismissInterruptingDialogsOrAds: Clicked '$label'")
                return Pair(true, "Popup/Ad dismissed by clicking '$label'")
            }
        }

        // 2. Fallback to system BACK if requested
        if (fallbackToBack) {
            val backed = performGlobalAction(GLOBAL_ACTION_BACK)
            Log.i(TAG, "dismissInterruptingDialogsOrAds: Executed fallback system BACK: $backed")
            return Pair(backed, if (backed) "Popup/Ad dismissed via system BACK gesture" else "No popup or dismiss target detected")
        }

        return Pair(false, "No interrupting popup or dismiss target detected on screen")
    }

    private fun findInterruptingNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val text = (node.text?.toString() ?: "").lowercase().trim()
        val desc = (node.contentDescription?.toString() ?: "").lowercase().trim()
        val resId = (node.viewIdResourceName?.toString() ?: "").lowercase().trim()

        if (text.isNotEmpty()) {
            if (text == "x" || text == "×" || text == "✕" || text == "✖") return node
            if (INTERRUPTING_DIALOG_HINTS.any { text == it || text.contains(it) }) return node
        }
        if (desc.isNotEmpty()) {
            if (desc == "x" || desc == "×" || desc == "✕" || desc == "✖") return node
            if (INTERRUPTING_DIALOG_HINTS.any { desc == it || desc.contains(it) }) return node
        }
        if (resId.isNotEmpty()) {
            if (CLOSE_RESOURCE_HINTS.any { resId.contains(it) }) return node
        }

        for (i in 0 until node.childCount) {
            val match = findInterruptingNode(node.getChild(i))
            if (match != null) return match
        }
        return null
    }
}

/** Backward compatibility subclass for AuraAccessibilityService */
class AuraAccessibilityService : IshaAccessibilityService() {
    companion object {
        var instance: IshaAccessibilityService?
            get() = IshaAccessibilityService.instance
            set(value) { IshaAccessibilityService.instance = value }
        const val ACTION_PLAN_DONE = IshaAccessibilityService.ACTION_PLAN_DONE
        const val EXTRA_PLAN_TAG = IshaAccessibilityService.EXTRA_PLAN_TAG
        const val EXTRA_SUCCESS = IshaAccessibilityService.EXTRA_SUCCESS
    }
}


