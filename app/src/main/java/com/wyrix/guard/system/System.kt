package com.wyrix.guard.system

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.wyrix.guard.MainActivity
import com.wyrix.guard.R
import com.wyrix.guard.service.WyrixAdminReceiver
import com.wyrix.guard.ui.AlarmActivity
import com.wyrix.guard.ui.LostModeActivity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object Perms {
    private fun has(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED
    fun fineLocation(c: Context) = has(c, Manifest.permission.ACCESS_FINE_LOCATION)
    fun anyLocation(c: Context) = fineLocation(c) || has(c, Manifest.permission.ACCESS_COARSE_LOCATION)
    fun backgroundLocation(c: Context) = has(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    fun notifications(c: Context) = Build.VERSION.SDK_INT < 33 || has(c, Manifest.permission.POST_NOTIFICATIONS)
    fun adminComponent(c: Context) = ComponentName(c, WyrixAdminReceiver::class.java)
    fun adminActive(c: Context) =
        (c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isAdminActive(adminComponent(c))
}

object DeviceInfo {
    fun battery(c: Context): Pair<Int?, Boolean?> {
        val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        val st = c.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = st?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = if (status == -1) null else status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return pct to charging
    }

    fun connection(c: Context): String {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "unknown"
        }
    }

    /** What this device can actually do right now, reported honestly to the dashboard. */
    fun capabilities(c: Context): JsonObject = buildJsonObject {
        put("foreground_location", Perms.fineLocation(c))
        put("background_location", Perms.backgroundLocation(c))
        put("notifications", Perms.notifications(c))
        put("failed_unlock_events", Perms.adminActive(c))
        put("camera_evidence", false)           // not supported: Android blocks background camera capture
        put("imei_lookup", false)               // not possible for normal apps
        put("sim_state_events", true)
        put("sdk_int", Build.VERSION.SDK_INT)
    }
}

object Notifs {
    const val CH_TRACKING = "tracking"; const val CH_ALARM = "alarm"; const val CH_ALERTS = "alerts"
    const val ID_TRACKING = 1001; const val ID_ALARM = 1002; const val ID_LOST = 1003; const val ID_ALERT = 1004

    fun ensureChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_TRACKING, "Protection status", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Shown while WYRIX GUARD is collecting this device's location." })
        nm.createNotificationChannel(NotificationChannel(CH_ALARM, "Owner alarm", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Full-screen alarm when the owner triggers it remotely."; setBypassDnd(false) })
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Security alerts", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun open(c: Context, cls: Class<*>, rc: Int) =
        PendingIntent.getActivity(c, rc, Intent(c, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun tracking(c: Context) = NotificationCompat.Builder(c, CH_TRACKING)
        .setSmallIcon(R.drawable.ic_stat_shield).setContentTitle("WYRIX GUARD is protecting this device")
        .setContentText("Location is being recorded for the account owner.")
        .setOngoing(true).setContentIntent(open(c, MainActivity::class.java, 1)).build()

    fun alarm(c: Context) = NotificationCompat.Builder(c, CH_ALARM)
        .setSmallIcon(R.drawable.ic_stat_shield).setContentTitle("Alarm triggered by the owner")
        .setContentText("Tap to open the alarm screen.").setPriority(NotificationCompat.PRIORITY_MAX)
        .setCategory(NotificationCompat.CATEGORY_ALARM).setOngoing(true)
        .setContentIntent(open(c, AlarmActivity::class.java, 2))
        .setFullScreenIntent(open(c, AlarmActivity::class.java, 3), true).build()

    fun lost(c: Context, message: String) {
        if (!Perms.notifications(c)) return
        val n = NotificationCompat.Builder(c, CH_ALARM).setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle("This phone is marked as lost").setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message)).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true).setContentIntent(open(c, LostModeActivity::class.java, 4))
            .setFullScreenIntent(open(c, LostModeActivity::class.java, 5), true).build()
        c.getSystemService(NotificationManager::class.java).notify(ID_LOST, n)
    }
    fun clearLost(c: Context) = c.getSystemService(NotificationManager::class.java).cancel(ID_LOST)

    fun alert(c: Context, title: String, text: String) {
        if (!Perms.notifications(c)) return
        val n = NotificationCompat.Builder(c, CH_ALERTS).setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(title).setContentText(text).setAutoCancel(true)
            .setContentIntent(open(c, MainActivity::class.java, 6)).build()
        c.getSystemService(NotificationManager::class.java).notify(ID_ALERT, n)
    }
}
