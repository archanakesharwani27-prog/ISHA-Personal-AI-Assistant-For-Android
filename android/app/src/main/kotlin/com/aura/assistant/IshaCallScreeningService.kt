package com.aura.assistant

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * High-priority Pre-Ringing Call Screening Service (Android 10+).
 *
 * Intercepts incoming cellular calls at the telecom layer BEFORE the default dialer
 * plays the ringtone or displays the full incoming call UI.
 *
 * Resolves caller details and broadcasts ACTION_PRE_CALL_SCREENED so Aura speaks
 * the caller's name cleanly before loud ringtone interference!
 */
@RequiresApi(Build.VERSION_CODES.Q)
open class IshaCallScreeningService : CallScreeningService() {

    companion object {
        private const val TAG = "IshaCallScreening"
        const val ACTION_PRE_CALL_SCREENED = "com.aura.PRE_CALL_SCREENED"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_CALLER_NAME = "callerName"
    }

    override fun onScreenCall(callDetails: Call.Details) {
        val handle = callDetails.handle
        val rawNumber = handle?.schemeSpecificPart ?: ""
        val number = Uri.decode(rawNumber).replace(" ", "").replace("-", "")

        Log.i(TAG, "Pre-ringing incoming call screened: $number")

        val callerName = resolveContactName(number)

        // Immediately trigger IshaCallAnnouncerManager directly for instant 0ms pre-ringing announcement & voice mic
        IshaCallAnnouncerManager.onCallRinging(this, callerName, number)

        // Broadcast to MainActivity so it immediately updates if active
        val intent = Intent(ACTION_PRE_CALL_SCREENED).apply {
            `package` = packageName
            putExtra(EXTRA_NUMBER, number)
            putExtra(EXTRA_CALLER_NAME, callerName)
        }
        sendBroadcast(intent)

        // Allow the call to proceed through the normal system ringer
        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSilenceCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()
        respondToCall(callDetails, response)
    }

    private fun resolveContactName(number: String): String {
        return IshaCallAnnouncerManager.resolveContactName(this, number).first
    }
}

/** Backward compatibility subclass for AuraCallScreeningService */
@RequiresApi(Build.VERSION_CODES.Q)
class AuraCallScreeningService : IshaCallScreeningService()

