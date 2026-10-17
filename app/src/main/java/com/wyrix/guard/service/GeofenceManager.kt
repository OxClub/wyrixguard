package com.wyrix.guard.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.wyrix.guard.data.SecureStore
import com.wyrix.guard.data.remote.SupabaseApi
import com.wyrix.guard.di.entry
import com.wyrix.guard.system.Perms
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeofenceManager @Inject constructor(
    @ApplicationContext private val ctx: Context, private val api: SupabaseApi, private val store: SecureStore,
) {
    private val client by lazy { LocationServices.getGeofencingClient(ctx) }
    private fun pi() = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, GeofenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

    /** Registers the owner's active geofences with Android's Geofencing API (needs background location). */
    suspend fun sync(): Int {
        val id = store.deviceId ?: return 0
        if (!Perms.backgroundLocation(ctx)) return 0
        val rows = api.restArray("geofences?device_id=eq.$id&active=eq.true&select=id,name,latitude,longitude,radius_m,notify_enter,notify_exit&limit=90")
        runCatching { client.removeGeofences(pi()).await() }
        val names = mutableMapOf<String, JsonElement>()
        val list = rows.mapNotNull { e ->
            val o = e.jsonObject
            var t = 0
            if (o["notify_enter"]?.jsonPrimitive?.booleanOrNull != false) t = t or Geofence.GEOFENCE_TRANSITION_ENTER
            if (o["notify_exit"]?.jsonPrimitive?.booleanOrNull != false) t = t or Geofence.GEOFENCE_TRANSITION_EXIT
            if (t == 0) return@mapNotNull null
            val gid = o["id"]!!.jsonPrimitive.content
            names[gid] = JsonPrimitive(o["name"]!!.jsonPrimitive.content)
            Geofence.Builder().setRequestId(gid)
                .setCircularRegion(o["latitude"]!!.jsonPrimitive.double, o["longitude"]!!.jsonPrimitive.double, o["radius_m"]!!.jsonPrimitive.float)
                .setExpirationDuration(Geofence.NEVER_EXPIRE).setTransitionTypes(t).build()
        }
        store.geofenceNames = JsonObject(names).toString()
        if (list.isEmpty()) return 0
        val req = GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(list).build()
        try { client.addGeofences(req, pi()).await() } catch (_: SecurityException) { return 0 }
        return list.size
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ev = GeofencingEvent.fromIntent(intent) ?: return
        if (ev.hasError()) return
        val type = when (ev.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "GEOFENCE_ENTER"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "GEOFENCE_EXIT"
            else -> return
        }
        val ep = context.entry()
        val names = runCatching { Json.parseToJsonElement(ep.store().geofenceNames).jsonObject }.getOrNull()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                for (g in ev.triggeringGeofences.orEmpty()) {
                    ep.tracking().recordEvent(type, mapOf("geofence" to (names?.get(g.requestId)?.jsonPrimitive?.contentOrNull ?: g.requestId)))
                }
            } finally { pending.finish() }
        }
    }
}
