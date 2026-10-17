package com.wyrix.guard.core

import java.time.OffsetDateTime

object Consent { const val VERSION = "2026-10-17" }

enum class CommandType {
    REQUEST_LOCATION, ACTIVATE_LOST_MODE, DEACTIVATE_LOST_MODE, TRIGGER_ALARM,
    STOP_ALARM, SYNC_DEVICE_STATUS, REQUEST_BATTERY_STATUS;
    companion object { fun parse(s: String): CommandType? = entries.firstOrNull { it.name == s } }
}

data class CommandInfo(
    val id: String, val deviceId: String, val ownerId: String,
    val type: String, val state: String, val expiresAtMs: Long,
)

sealed interface Validation {
    data object Ok : Validation
    data class Rejected(val reason: String) : Validation
}

/**
 * A command is only acted on if it was read from Supabase over the owner's authenticated session
 * AND it names this device, this owner, a known type, a live state, and has not expired.
 * Push payloads are never authorization.
 */
object CommandValidator {
    fun validate(c: CommandInfo, deviceId: String?, userId: String?, nowMs: Long): Validation = when {
        deviceId == null || userId == null -> Validation.Rejected("not_enrolled")
        !c.deviceId.equals(deviceId, true) -> Validation.Rejected("wrong_device")
        !c.ownerId.equals(userId, true) -> Validation.Rejected("wrong_owner")
        CommandType.parse(c.type) == null -> Validation.Rejected("unknown_type")
        c.state != "requested" && c.state != "delivered" -> Validation.Rejected("terminal_state")
        c.expiresAtMs <= nowMs -> Validation.Rejected("expired")
        else -> Validation.Ok
    }
}

enum class TrackingMode(val wire: String, val intervalMs: Long, val minIntervalMs: Long, val highAccuracy: Boolean) {
    NORMAL("normal", 60_000, 30_000, true),
    BATTERY_SAVING("battery_saving", 600_000, 300_000, false),
    RECOVERY("recovery", 15_000, 10_000, true);
    companion object { fun fromWire(s: String?) = entries.firstOrNull { it.wire == s } ?: NORMAL }
}

object Accuracy {
    /** Never present a coarse fix as exact. */
    fun label(m: Float): String = when {
        m <= 50f -> "±${m.toInt()} m"
        m <= 500f -> "±${m.toInt()} m (approximate)"
        else -> "±${m.toInt()} m (low precision: an area, not a point)"
    }
}

object Iso {
    fun toMs(s: String): Long = OffsetDateTime.parse(s).toInstant().toEpochMilli()
}

object Luhn {
    fun validImei(v: String): Boolean {
        if (v.length != 15 || !v.all { it.isDigit() }) return false
        var sum = 0
        for (i in 0 until 15) {
            var d = v[14 - i] - '0'
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9 }
            sum += d
        }
        return sum % 10 == 0
    }
}

object Csv {
    private fun cell(v: Any?): String {
        var s = v?.toString() ?: ""
        if (s.isNotEmpty() && s[0] in "=+-@\t\r") s = "'$s"
        return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
    fun build(header: List<String>, rows: List<List<Any?>>): String =
        (listOf(header.joinToString(",")) + rows.map { r -> r.joinToString(",") { cell(it) } }).joinToString("\n")
}

/** Retry policy for the offline sync queue (exponential, capped). */
object Backoff {
    fun delayMs(attempt: Int): Long = (30_000L shl attempt.coerceIn(0, 6)).coerceAtMost(30 * 60_000L)
}
