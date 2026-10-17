package com.wyrix.guard.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Looper
import android.telephony.TelephonyManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.wyrix.guard.core.TrackingMode
import com.wyrix.guard.di.entry
import com.wyrix.guard.system.Notifs
import com.wyrix.guard.system.Perms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Foreground location service (type "location") with a persistent, visible notification. Never hidden. */
class LocationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var callback: LocationCallback? = null
    private var simReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { shutdown(); return START_NOT_STICKY }
        val ep = entry()
        Notifs.ensureChannels(this)
        if (!Perms.anyLocation(this) || ep.store().deviceId == null) { shutdown(); return START_NOT_STICKY }
        try {
            ServiceCompat.startForeground(this, Notifs.ID_TRACKING, Notifs.tracking(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) { // background start not allowed / permission revoked
            scope.launch { ep.tracking().recordEvent("TRACKING_STOPPED", mapOf("reason" to (e.javaClass.simpleName))) }
            shutdown(); return START_NOT_STICKY
        }
        running = true
        ep.store().trackingEnabled = true
        startUpdates(TrackingMode.fromWire(ep.store().trackingMode))
        registerSim()
        scope.launch { runCatching { ep.geofences().sync() } }
        return START_STICKY
    }

    private fun startUpdates(mode: TrackingMode) {
        val client = LocationServices.getFusedLocationProviderClient(this)
        callback?.let { client.removeLocationUpdates(it) }
        val req = LocationRequest.Builder(if (mode.highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY, mode.intervalMs)
            .setMinUpdateIntervalMillis(mode.minIntervalMs).build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) {
                scope.launch { r.locations.forEach { entry().tracking().recordLocation(it, mode.wire) } }
            }
        }
        callback = cb
        try { client.requestLocationUpdates(req, cb, Looper.getMainLooper()) } catch (_: SecurityException) { shutdown() }
    }

    private fun simCategory(ss: String?) = if (ss == null || ss == "ABSENT") "ABSENT" else if (ss == "UNKNOWN" || ss == "NOT_READY") "UNKNOWN" else "PRESENT"

    private fun registerSim() {
        if (simReceiver != null) return
        val ep = entry()
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val initial = if (tm.simState == TelephonyManager.SIM_STATE_ABSENT) "ABSENT" else "PRESENT"
        val prev = ep.store().simState
        if (prev != null && prev != initial) scope.launch {
            ep.tracking().recordEvent(if (initial == "ABSENT") "SIM_REMOVED" else "SIM_INSERTED", mapOf("note" to "state changed since last check"))
        }
        ep.store().simState = initial
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                // Best effort: "SIM_STATE_CHANGED" is a legacy system broadcast; behaviour varies by OEM/Android version.
                val now = simCategory(i.getStringExtra("ss"))
                if (now == "UNKNOWN") return
                val before = ep.store().simState
                if (before != null && before != now) scope.launch {
                    ep.tracking().recordEvent(if (now == "ABSENT") "SIM_REMOVED" else "SIM_INSERTED")
                }
                ep.store().simState = now
            }
        }
        ContextCompat.registerReceiver(this, r, IntentFilter("android.intent.action.SIM_STATE_CHANGED"), ContextCompat.RECEIVER_NOT_EXPORTED)
        simReceiver = r
    }

    private fun cleanup() {
        callback?.let { LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(it) }
        callback = null
        simReceiver?.let { runCatching { unregisterReceiver(it) } }; simReceiver = null
        running = false
    }

    private fun shutdown() { cleanup(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }

    override fun onDestroy() { cleanup(); scope.cancel(); super.onDestroy() }

    companion object {
        const val ACTION_STOP = "com.wyrix.guard.STOP_TRACKING"
        @Volatile var running = false

        /** Throws if Android refuses a background foreground-service start; callers must handle it. */
        fun start(ctx: Context) { ContextCompat.startForegroundService(ctx, Intent(ctx, LocationService::class.java)) }
        fun stop(ctx: Context) {
            entryStore(ctx).trackingEnabled = false
            ctx.stopService(Intent(ctx, LocationService::class.java))   // safe from the background; onDestroy cleans up
        }
        private fun entryStore(ctx: Context) = ctx.entry().store()
    }
}
