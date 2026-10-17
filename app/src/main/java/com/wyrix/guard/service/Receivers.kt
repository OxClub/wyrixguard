package com.wyrix.guard.service

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.wyrix.guard.di.entry
import com.wyrix.guard.system.Notifs
import com.wyrix.guard.work.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = context.entry().store()
        if (store.deviceId == null) return
        Scheduler.schedulePolling(context)
        if (store.trackingEnabled) {
            // Android may refuse a background start; if so, tell the owner instead of failing silently.
            runCatching { LocationService.start(context) }.onFailure {
                Notifs.ensureChannels(context)
                Notifs.alert(context, "Protection paused after restart", "Open WYRIX GUARD to resume location protection.")
            }
        }
    }
}

/** Optional device admin: used only to be informed of failed unlock attempts. Support varies by Android version. */
class WyrixAdminReceiver : DeviceAdminReceiver() {
    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        val count = (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).currentFailedPasswordAttempts
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { context.entry().tracking().recordEvent("FAILED_UNLOCK", mapOf("attempts" to count.toString(), "photo" to "not_captured")) }
            finally { pending.finish() }
        }
    }
    override fun onDisabled(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { context.entry().tracking().recordEvent("ADMIN_DISABLED") } finally { pending.finish() }
        }
    }
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Turning this off stops failed-unlock alerts for your account."
}

class WyrixMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val ep = entry()
        CoroutineScope(Dispatchers.IO).launch { runCatching { ep.devices().updateFcmToken(token) } }
    }

    /** The push is only a wake-up. Everything is re-fetched from Supabase and validated before acting. */
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["kind"] != "command") return
        val ep = entry()
        val done = runCatching {
            runBlocking(Dispatchers.IO) { withTimeoutOrNull(8_000) { ep.commands().processPending(); true } }
        }.getOrNull()
        if (done != true) Scheduler.scheduleCommandPoll(this)
    }
}

