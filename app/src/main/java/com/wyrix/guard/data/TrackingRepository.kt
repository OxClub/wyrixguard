package com.wyrix.guard.data

import android.content.Context
import android.location.Location
import android.os.Build
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.wyrix.guard.core.Backoff
import com.wyrix.guard.core.TrackingMode
import com.wyrix.guard.data.local.AppDatabase
import com.wyrix.guard.data.local.EventEntity
import com.wyrix.guard.data.local.LocationEntity
import com.wyrix.guard.data.remote.SupabaseApi
import com.wyrix.guard.service.LocationService
import com.wyrix.guard.system.DeviceInfo
import com.wyrix.guard.system.Notifs
import com.wyrix.guard.system.Perms
import com.wyrix.guard.work.Scheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.io.IOException
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackingRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val db: AppDatabase,
    private val api: SupabaseApi,
    private val store: SecureStore,
    private val devices: DeviceRepository,
    private val evidence: EvidenceRepository,
) {
    private val syncLock = Mutex()

    private fun isMock(l: Location) = if (Build.VERSION.SDK_INT >= 31) l.isMock else @Suppress("DEPRECATION") l.isFromMockProvider

    /** Persist a real fused fix locally first (offline-safe), then ask WorkManager to sync. Mock fixes are rejected. */
    suspend fun recordLocation(l: Location, mode: String): Boolean {
        if (isMock(l) || !l.hasAccuracy()) return false
        val (pct, charging) = DeviceInfo.battery(ctx)
        val id = db.locations().insert(LocationEntity(
            clientId = UUID.randomUUID().toString(), lat = l.latitude, lng = l.longitude, accuracy = l.accuracy,
            recordedAt = l.time.takeIf { it > 0 } ?: System.currentTimeMillis(), battery = pct, charging = charging,
            connection = DeviceInfo.connection(ctx), mode = mode))
        if (id > 0) Scheduler.scheduleSync(ctx)
        return id > 0
    }

    suspend fun recordEvent(type: String, details: Map<String, String> = emptyMap(), at: Long = System.currentTimeMillis()) {
        val json = JsonObject(details.mapValues { JsonPrimitive(it.value) }).toString()
        db.events().insert(EventEntity(clientId = UUID.randomUUID().toString(), type = type, detailsJson = json, occurredAt = at))
        Scheduler.scheduleSync(ctx)
    }

    /** One real, high-accuracy fix (used by REQUEST_LOCATION). Null if no permission, no fix or timeout. */
    suspend fun captureOnce(timeoutMs: Long = 25_000): Location? {
        if (!Perms.anyLocation(ctx)) return null
        return try {
            val client = LocationServices.getFusedLocationProviderClient(ctx)
            val cts = CancellationTokenSource()
            val loc = withTimeoutOrNull(timeoutMs) { client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await() }
            if (loc == null) cts.cancel()
            loc?.also { recordLocation(it, store.trackingMode) }
        } catch (_: SecurityException) { null }
    }

    /** Flushes the queue. Returns false when something should be retried later (offline, outage, auth, rate limit). */
    suspend fun syncNow(): Boolean {
        val deviceId = store.deviceId ?: return true
        if (!api.signedIn) return false
        return try {
            syncLock.withLock {
                while (true) {
                    val rows = db.locations().unsynced(100)
                    if (rows.isEmpty()) break
                    pushLocations(deviceId, rows)
                }
                while (true) {
                    val rows = db.events().unsynced(100)
                    if (rows.isEmpty()) break
                    pushEvents(deviceId, rows)
                }
                evidence.uploadPending()
                updateStatus(deviceId)
                store.lastSyncAt = System.currentTimeMillis()
                db.locations().pruneSynced(System.currentTimeMillis() - 7 * 86_400_000L)
            }
            devices.refreshConfig()
            true
        } catch (_: IOException) { false }
        catch (e: SupabaseApi.ApiException) { if (e.status in 400..499 && e.status !in setOf(401, 403, 408, 429)) true else false }
    }

    private fun locJson(deviceId: String, r: LocationEntity) = buildJsonObject {
        put("device_id", deviceId); put("client_id", r.clientId); put("latitude", r.lat); put("longitude", r.lng)
        put("accuracy_m", r.accuracy); put("recorded_at", Instant.ofEpochMilli(r.recordedAt).toString())
        put("battery_pct", r.battery); put("charging", r.charging); put("connection_type", r.connection); put("tracking_mode", r.mode)
    }

    private suspend fun pushLocations(deviceId: String, rows: List<LocationEntity>) {
        val path = "device_locations?on_conflict=device_id,client_id"
        try {
            api.rest("POST", path, buildJsonArray { rows.forEach { add(locJson(deviceId, it)) } }, "resolution=ignore-duplicates,return=minimal")
            db.locations().markSynced(rows.map { it.id })
        } catch (e: SupabaseApi.ApiException) {
            if (e.status !in 400..499 || e.status in setOf(401, 403, 408, 429)) throw e
            // A 4xx means at least one row is invalid. Isolate it so one bad record cannot block the queue forever.
            for (r in rows) {
                try { api.rest("POST", path, buildJsonArray { add(locJson(deviceId, r)) }, "resolution=ignore-duplicates,return=minimal") }
                catch (x: SupabaseApi.ApiException) { if (x.status !in 400..499 || x.status in setOf(401, 403, 408, 429)) throw x; store.droppedRecords += 1 }
                db.locations().markSynced(listOf(r.id))
            }
        }
    }

    private fun evJson(deviceId: String, r: EventEntity) = buildJsonObject {
        put("device_id", deviceId); put("client_id", r.clientId); put("type", r.type)
        put("details", runCatching { Json.parseToJsonElement(r.detailsJson) }.getOrDefault(JsonObject(emptyMap())))
        put("occurred_at", Instant.ofEpochMilli(r.occurredAt).toString())
    }

    private suspend fun pushEvents(deviceId: String, rows: List<EventEntity>) {
        val path = "security_events?on_conflict=device_id,client_id"
        try {
            api.rest("POST", path, buildJsonArray { rows.forEach { add(evJson(deviceId, it)) } }, "resolution=ignore-duplicates,return=minimal")
        } catch (e: SupabaseApi.ApiException) {
            if (e.status !in 400..499 || e.status in setOf(401, 403, 408, 429)) throw e
            store.droppedRecords += rows.size   // invalid event rows are dropped rather than blocking the queue
        }
        db.events().markSynced(rows.map { it.id })
    }

    private suspend fun updateStatus(deviceId: String) {
        val (pct, charging) = DeviceInfo.battery(ctx)
        val now = Instant.now().toString()
        val body = buildJsonObject {
            put("battery_pct", pct); put("charging", charging); put("connection_type", DeviceInfo.connection(ctx))
            put("android_version", Build.VERSION.RELEASE); put("tracking_active", LocationService.running)
            put("capabilities", DeviceInfo.capabilities(ctx)); put("last_sync_at", now); put("updated_at", now)
        }
        api.rest("PATCH", "device_status?device_id=eq.$deviceId", body, "return=minimal")
        api.rest("PATCH", "devices?id=eq.$deviceId", buildJsonObject { put("last_seen_at", now) }, "return=minimal")
    }

    fun notifyTrackingStopped(reason: String) { Notifs.alert(ctx, "Protection paused", reason) }
    fun retryDelayMs(attempt: Int) = Backoff.delayMs(attempt)
    fun mode(): TrackingMode = TrackingMode.fromWire(store.trackingMode)
}
