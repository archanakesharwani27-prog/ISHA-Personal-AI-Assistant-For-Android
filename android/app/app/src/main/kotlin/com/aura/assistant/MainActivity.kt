package com.aura.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.provider.Settings
import android.telephony.SmsManager
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val smsChannel = "aura/sms"
    private val mediaChannel = "aura/media"
    private val accessibilityChannel = "aura/accessibility"

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, smsChannel).setMethodCallHandler { call, result ->
            when (call.method) {
                "sendSms" -> {
                    val number = call.argument<String>("number") ?: ""
                    val message = call.argument<String>("message") ?: ""
                    if (number.isBlank()) {
                        result.error("BAD_ARGS", "missing number", null)
                        return@setMethodCallHandler
                    }
                    try {
                        val smsManager = applicationContext.getSystemService(SmsManager::class.java)
                            ?: SmsManager.getDefault()
                        val parts = smsManager.divideMessage(message)
                        smsManager.sendMultipartTextMessage(number, null, parts, null, null)
                        result.success("sent")
                    } catch (e: Exception) {
                        result.error("SMS_FAILED", e.message, null)
                    }
                }
                else -> result.notImplemented()
            }
        }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, mediaChannel).setMethodCallHandler { call, result ->
            when (call.method) {
                "transportControl" -> {
                    val action = call.argument<String>("action") ?: ""
                    val ok = controlActiveMedia(action)
                    if (ok) result.success("ok")
                    else result.error(
                        "NO_SESSION",
                        "No active media session, or notification access not granted",
                        null,
                    )
                }
                "hasNotificationAccess" -> result.success(hasNotificationAccess())
                "openNotificationAccessSettings" -> {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, accessibilityChannel).setMethodCallHandler { call, result ->
            when (call.method) {
                "isEnabled" -> result.success(AuraAccessibilityService.instance != null)
                "armWhatsappAutoSend" -> {
                    val svc = AuraAccessibilityService.instance
                    svc?.armWhatsappAutoSend()
                    result.success(svc != null)
                }
                "armAutoInstall" -> {
                    val svc = AuraAccessibilityService.instance
                    svc?.armAutoInstall()
                    result.success(svc != null)
                }
                "tapLike" -> result.success(AuraAccessibilityService.instance?.tapLike() ?: false)
                "clickByText" -> {
                    val text = call.argument<String>("text") ?: ""
                    result.success(AuraAccessibilityService.instance?.performClickByText(text) ?: false)
                }
                "scroll" -> {
                    val down = call.argument<Boolean>("down") ?: true
                    result.success(AuraAccessibilityService.instance?.scrollWindow(down) ?: false)
                }
                "openAccessibilitySettings" -> {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabled != null && enabled.contains(packageName)
    }

    private fun controlActiveMedia(action: String): Boolean {
        if (!hasNotificationAccess()) return false
        val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, AuraNotificationListenerService::class.java)
        val controllers: List<MediaController> = try {
            manager.getActiveSessions(component)
        } catch (e: SecurityException) {
            return false
        }
        val controller = controllers.firstOrNull() ?: return false
        val transport = controller.transportControls
        when (action) {
            "play" -> transport.play()
            "pause" -> transport.pause()
            "stop" -> transport.stop()
            "next" -> transport.skipToNext()
            "previous" -> transport.skipToPrevious()
            else -> return false
        }
        return true
    }
}
