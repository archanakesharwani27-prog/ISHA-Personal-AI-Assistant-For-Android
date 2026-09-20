package com.aura.assistant.compat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Universal Device & OEM Compatibility Engine for AURA.
 * Prevents background service termination by aggressive OEM battery managers
 * (Xiaomi HyperOS/MIUI, Vivo Funtouch, Oppo/Realme ColorOS, Samsung OneUI).
 */
object DeviceCompatibilityHelper {

    private const val TAG = "AuraCompat"

    val manufacturer: String = Build.MANUFACTURER.lowercase()
    val model: String = Build.MODEL
    val androidVersion: Int = Build.VERSION.SDK_INT

    /**
     * Checks if AURA is whitelisted from battery optimization.
     */
    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking battery optimization status", e)
            true
        }
    }

    /**
     * Prompts the user to whitelist AURA so wake word & background automation
     * are never killed by Android's Doze mode.
     */
    fun requestIgnoreBatteryOptimization(context: Context) {
        if (isBatteryOptimizationIgnored(context)) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.i(TAG, "Requested ignore battery optimization.")
        } catch (e: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallback)
            } catch (ex: Exception) {
                Log.e(TAG, "Could not open battery optimization settings", ex)
            }
        }
    }

    /**
     * Launches the OEM-specific Autostart settings page on Chinese ROMs.
     */
    fun openOemAutostartSettings(context: Context): Boolean {
        val oemIntents = listOf(
            // Xiaomi / Poco / Redmi (MIUI / HyperOS)
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
            
            // Oppo / Realme (ColorOS)
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),

            // Vivo (FuntouchOS / OriginOS)
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")),

            // Huawei / Honor (EMUI / MagicOS)
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.bootstart.BootStartActivity")),

            // Samsung (OneUI Device Care / Smart Manager)
            Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
            Intent().setComponent(ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"))
        )

        for (intent in oemIntents) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                Log.i(TAG, "Successfully opened OEM autostart page with intent: $intent")
                return true
            } catch (_: Exception) {
                // Try next OEM intent
            }
        }

        // Generic App details fallback
        return try {
            val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(appDetails)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open any settings activity", e)
            false
        }
    }
}
