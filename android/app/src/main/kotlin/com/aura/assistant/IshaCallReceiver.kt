package com.aura.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Manifest-registered BroadcastReceiver for incoming cellular calls.
 * Ensures zero-latency detection even when MainActivity is closed or device is locked.
 */
class IshaCallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "IshaCallReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        if (intent?.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""
            Log.i(TAG, "Cellular call state changed: $stateStr, number: $incomingNumber")

            when (stateStr) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    val (callerName, _) = IshaCallAnnouncerManager.resolveContactName(ctx, incomingNumber)
                    IshaCallAnnouncerManager.onCallRinging(ctx, callerName, incomingNumber)
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK,
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    IshaCallAnnouncerManager.onCallEnded(ctx)
                }
            }
        }
    }
}
