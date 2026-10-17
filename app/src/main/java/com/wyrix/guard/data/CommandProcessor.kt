package com.wyrix.guard.data

import android.content.Context
import com.wyrix.guard.core.CommandInfo
import com.wyrix.guard.core.CommandType
import com.wyrix.guard.core.CommandValidator
import com.wyrix.guard.core.Iso
import com.wyrix.guard.core.Validation
import com.wyrix.guard.data.crypto.DeviceKeys
import com.wyrix.guard.data.remote.SupabaseApi
import com.wyrix.guard.service.AlarmService
import com.wyrix.guard.service.LocationService
import com.wyrix.guard.system.DeviceInfo
import com.wyrix.guard.system.Notifs
import com.wyrix.guard.system.Perms
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches commands from Supabase over the owner's authenticated session (never from the push payload),
 * validates them, executes the supported ones, and acknowledges with a Keystore-signed message.
 */
@Singleton
class CommandProcessor @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val api: SupabaseApi,
    private val store: SecureStore,
    private val keys: DeviceKeys,
    private val tracking: TrackingRepository,
) {
    private val lock = Mutex()

    suspend fun processPending(): Int = lock.withLock {
        val deviceId = store.deviceId ?: return 0
        if (!api.signedIn) return 0
        val rows = api.restArray("remote_commands?device_id=eq.$deviceId&state=in.(requested,delivered)" +
            "&order=created_at.asc&limit=20&select=id,device_id,owner_id,type,state,payload,expires_at")
        var handled = 0
        for (el in rows) {
            val o = el.jsonObject
            val info = CommandInfo(
                id = o["id"]!!.jsonPrimitive.content, deviceId = o["device_id"]!!.jsonPrimitive.content,
                ownerId = o["owner_id"]!!.jsonPrimitive.content, type = o["type"]!!.jsonPrimitive.content,
                state = o["state"]!!.jsonPrimitive.content, expiresAtMs = Iso.toMs(o["expires_at"]!!.jsonPrimitive.content))
            if (CommandValidator.validate(info, deviceId, store.userId, System.currentTimeMillis()) !is Validation.Ok) continue
            val payload = o["payload"] as? JsonObject ?: JsonObject(emptyMap())
            if (info.state == "requested") runCatching { ack(info.id, "delivered") }
            try {
                val result = execute(CommandType.parse(info.type)!!, payload)
                ack(info.id, "executed", result)
            } catch (e: CommandFailure) {
                runCatching { ack(info.id, "failed", reason = e.reason) }
            } catch (e: SupabaseApi.ApiException) { throw e }
            catch (e: java.io.IOException) { throw e }
            catch (e: Exception) { runCatching { ack(info.id, "failed", reason = "internal_error") } }
            handled++
        }
        if (handled > 0) tracking.syncNow()
        handled
    }

    class CommandFailure(val reason: String) : Exception(reason)

    private suspend fun execute(type: CommandType, payload: JsonObject): JsonObject? = when (type) {
        CommandType.REQUEST_LOCATION -> {
            if (!Perms.anyLocation(ctx)) throw CommandFailure("location_permission_missing")
            val l = tracking.captureOnce() ?: throw CommandFailure("no_location_fix")
            buildJsonObject { put("accuracy_m", l.accuracy); put("provider", l.provider ?: "fused") }
        }
        CommandType.ACTIVATE_LOST_MODE -> {
            val msg = payload["message"]?.jsonPrimitive?.contentOrNull?.take(280) ?: throw CommandFailure("missing_message")
            store.lostMessage = msg
            store.lostContact = payload["contact"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            store.trackingMode = "recovery"
            var started = false
            if (Perms.anyLocation(ctx)) { started = runCatching { LocationService.start(ctx) }.isSuccess }
            Notifs.lost(ctx, msg)
            tracking.recordEvent("LOST_MODE_ON")
            buildJsonObject { put("tracking_started", started) }
        }
        CommandType.DEACTIVATE_LOST_MODE -> {
            store.lostMessage = null; store.lostContact = null; store.trackingMode = "normal"
            Notifs.clearLost(ctx)
            if (store.trackingEnabled) runCatching { LocationService.start(ctx) }
            tracking.recordEvent("LOST_MODE_OFF"); null
        }
        CommandType.TRIGGER_ALARM -> {
            if (!AlarmService.start(ctx)) throw CommandFailure("background_start_blocked")
            tracking.recordEvent("ALARM_TRIGGERED"); null
        }
        CommandType.STOP_ALARM -> { AlarmService.stop(ctx); null }
        CommandType.SYNC_DEVICE_STATUS -> null
        CommandType.REQUEST_BATTERY_STATUS -> DeviceInfo.battery(ctx).let { (p, c) ->
            buildJsonObject { put("battery_pct", p); put("charging", c) } }
    }

    private suspend fun ack(id: String, state: String, result: JsonObject? = null, reason: String? = null) {
        val ts = System.currentTimeMillis()
        val body = buildJsonObject {
            put("command_id", id); put("state", state); put("ts", ts)
            put("signature", keys.sign("$id|$state|$ts"))
            if (result != null) put("result", result)
            if (reason != null) put("failure_reason", reason)
        }
        api.fn("command-ack", body)
    }
}
