package com.wyrix.guard.data

import android.content.Context
import android.os.Build
import com.google.firebase.messaging.FirebaseMessaging
import com.wyrix.guard.core.Consent
import com.wyrix.guard.data.crypto.DeviceKeys
import com.wyrix.guard.data.local.AppDatabase
import com.wyrix.guard.data.remote.SupabaseApi
import com.wyrix.guard.service.LocationService
import com.wyrix.guard.system.Notifs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.*
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val api: SupabaseApi,
    private val store: SecureStore,
    private val keys: DeviceKeys,
    private val db: AppDatabase,
) {
    suspend fun fcmToken(): String? = try { FirebaseMessaging.getInstance().token.await() } catch (_: Exception) { null }

    /** Enrolment: only after the user ticked consent. Creates a hardware-backed key whose public half is the device identity. */
    suspend fun enroll(name: String) {
        suspend fun attempt() {
            keys.ensureSigningKey()
            val body = buildJsonObject {
                put("name", name.trim()); put("model", Build.MODEL); put("android_version", Build.VERSION.RELEASE)
                put("public_key", keys.publicKeyB64()); put("fcm_token", fcmToken())
                put("consent_at", Instant.now().toString()); put("consent_version", Consent.VERSION); put("tracking_mode", "normal")
            }
            val res = api.rest("POST", "devices?select=id", body, "return=representation")
            val id = Json.parseToJsonElement(res).jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content
            store.deviceId = id
            api.rest("POST", "device_status", buildJsonObject { put("device_id", id); put("android_version", Build.VERSION.RELEASE); put("tracking_active", false) }, "return=minimal")
        }
        try { attempt() } catch (e: SupabaseApi.ApiException) {
            if (e.code.contains("duplicate", true)) { keys.deleteSigningKey(); attempt() } else throw e  // stale key from a revoked enrolment
        }
    }

    suspend fun updateFcmToken(token: String) {
        val id = store.deviceId ?: return
        if (!api.signedIn) return
        api.rest("PATCH", "devices?id=eq.$id", buildJsonObject { put("fcm_token", token) }, "return=minimal")
    }

    /** Pulls owner-side changes (mode, lost state, revocation). The server is authoritative. */
    suspend fun refreshConfig() {
        val id = store.deviceId ?: return
        val rows = api.restArray("devices?id=eq.$id&select=tracking_mode,revoked_at,lost_mode,lost_message,lost_contact")
        val d = rows.firstOrNull()?.jsonObject ?: return
        if (d["revoked_at"]?.jsonPrimitive?.contentOrNull != null) { wipeLocal(); return }
        val mode = d["tracking_mode"]?.jsonPrimitive?.contentOrNull ?: "normal"
        if (mode != store.trackingMode && !store.lostActive) {
            store.trackingMode = mode
            if (store.trackingEnabled) runCatching { LocationService.start(ctx) }
        }
        val lost = d["lost_mode"]?.jsonPrimitive?.booleanOrNull == true
        if (lost && !store.lostActive) {
            store.lostMessage = d["lost_message"]?.jsonPrimitive?.contentOrNull ?: "This phone is lost."
            store.lostContact = d["lost_contact"]?.jsonPrimitive?.contentOrNull
            Notifs.lost(ctx, store.lostMessage!!)
        } else if (!lost && store.lostActive) { store.lostMessage = null; store.lostContact = null; Notifs.clearLost(ctx) }
    }

    /** Stops everything and forgets this enrolment locally (revoked or user-initiated removal). */
    suspend fun wipeLocal() {
        LocationService.stop(ctx)
        store.deviceId = null; store.trackingEnabled = false; store.lostMessage = null; store.lostContact = null
        keys.deleteSigningKey()
        db.locations().clear(); db.events().clear()
        Notifs.clearLost(ctx)
    }
}
