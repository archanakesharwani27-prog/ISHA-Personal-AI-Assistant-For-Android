package com.aura.assistant

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Handles the two automations ANDROID_BRIDGE.md calls out as needing
 * accessibility rather than a public API:
 *  - WhatsApp auto-send: after device_bridge.dart opens a wa.me chat with the
 *    message pre-filled, this taps the Send button so the user doesn't have to.
 *  - media_control "like": taps whatever heart/like control the foreground
 *    music app exposes, since there's no MediaSession API for it.
 *
 * The user must turn this on manually under Settings > Accessibility > Aura.
 * No app can enable its own accessibility service — that's an OS-level
 * anti-abuse boundary, not something any amount of code here can route
 * around.
 */
class AuraAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AuraAccessibilityService? = null
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
        private const val PLAY_STORE_PACKAGE = "com.android.vending"
        private const val PACKAGE_INSTALLER_PACKAGE = "com.google.android.packageinstaller"
        private const val PACKAGE_INSTALLER_ALT = "com.android.packageinstaller"

        private val SEND_HINTS = listOf("send")
        private val LIKE_HINTS = listOf("like", "heart", "favorite", "save to your library", "add to library")
        private val INSTALL_HINTS = listOf("install", "download", "update")
    }

    @Volatile
    private var pendingWhatsappSend = false

    @Volatile
    private var pendingAutoInstall = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            return
        }

        val root = rootInActiveWindow ?: return

        if (pendingWhatsappSend && (pkg == WHATSAPP_PACKAGE || pkg == WHATSAPP_BUSINESS_PACKAGE)) {
            if (clickFirstMatch(root, SEND_HINTS)) {
                pendingWhatsappSend = false
            }
        }

        if (pendingAutoInstall && (pkg == PLAY_STORE_PACKAGE || pkg == PACKAGE_INSTALLER_PACKAGE || pkg == PACKAGE_INSTALLER_ALT)) {
            if (clickFirstMatch(root, INSTALL_HINTS)) {
                pendingAutoInstall = false
            }
        }
    }

    /** Called right after Dart opens the wa.me chat: watch for the send button and tap it. */
    fun armWhatsappAutoSend() {
        pendingWhatsappSend = true
    }

    /** Called when opening Play Store: watch for the Install/Download button and tap it. */
    fun armAutoInstall() {
        pendingAutoInstall = true
    }

    /** Attempts to tap a like/heart control in whatever app is currently in front. */
    fun tapLike(): Boolean {
        val root = rootInActiveWindow ?: return false
        return clickFirstMatch(root, LIKE_HINTS)
    }

    /** Attempts to tap any button on screen matching the specified text. */
    fun performClickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return clickFirstMatch(root, listOf(text.lowercase()))
    }

    /** Scrolls the active window up (false) or down (true). */
    fun scrollWindow(down: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return root.performAction(action)
    }

    private fun clickFirstMatch(root: AccessibilityNodeInfo, hints: List<String>): Boolean {
        val match = findNode(root, hints) ?: return false
        var target: AccessibilityNodeInfo? = match
        // The matched node is often just an icon/label; walk up to the
        // nearest clickable ancestor to find the actual tappable target.
        while (target != null && !target.isClickable) {
            target = target.parent
        }
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    private fun findNode(node: AccessibilityNodeInfo?, hints: List<String>): AccessibilityNodeInfo? {
        if (node == null) return null
        val label = (node.contentDescription?.toString() ?: node.text?.toString() ?: "").lowercase()
        if (label.isNotEmpty() && hints.any { label.contains(it) }) return node
        for (i in 0 until node.childCount) {
            val found = findNode(node.getChild(i), hints)
            if (found != null) return found
        }
        return null
    }
}
