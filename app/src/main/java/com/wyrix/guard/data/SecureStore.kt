package com.wyrix.guard.data

import android.content.Context
import com.wyrix.guard.data.crypto.DeviceKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Key/value store whose values are AES-GCM encrypted with a Keystore key (tokens, ids, lost-mode state). */
@Singleton
class SecureStore @Inject constructor(@ApplicationContext ctx: Context, private val keys: DeviceKeys) {
    private val prefs = ctx.getSharedPreferences("wyrix_secure", Context.MODE_PRIVATE)

    fun get(k: String): String? = prefs.getString(k, null)?.let { keys.decrypt(it) }
    fun put(k: String, v: String) { prefs.edit().putString(k, keys.encrypt(v)).apply() }
    fun getLong(k: String, d: Long = 0L): Long = get(k)?.toLongOrNull() ?: d
    fun putLong(k: String, v: Long) = put(k, v.toString())
    fun getBool(k: String, d: Boolean = false): Boolean = get(k)?.toBooleanStrictOrNull() ?: d
    fun putBool(k: String, v: Boolean) = put(k, v.toString())
    fun remove(vararg ks: String) { prefs.edit().apply { ks.forEach { remove(it) } }.apply() }

    fun clearSession() = remove(ACCESS, REFRESH, EXPIRES, USER_ID, EMAIL)
    fun clearAll() { prefs.edit().clear().apply() }

    var userId: String? get() = get(USER_ID); set(v) { if (v == null) remove(USER_ID) else put(USER_ID, v) }
    var email: String? get() = get(EMAIL); set(v) { if (v == null) remove(EMAIL) else put(EMAIL, v) }
    var deviceId: String? get() = get(DEVICE_ID); set(v) { if (v == null) remove(DEVICE_ID) else put(DEVICE_ID, v) }
    var trackingEnabled: Boolean get() = getBool(TRACKING); set(v) = putBool(TRACKING, v)
    var trackingMode: String get() = get(MODE) ?: "normal"; set(v) = put(MODE, v)
    var lastSyncAt: Long get() = getLong(LAST_SYNC); set(v) = putLong(LAST_SYNC, v)
    var lostMessage: String? get() = get(LOST_MSG); set(v) { if (v == null) remove(LOST_MSG) else put(LOST_MSG, v) }
    var lostContact: String? get() = get(LOST_CONTACT); set(v) { if (v == null) remove(LOST_CONTACT) else put(LOST_CONTACT, v) }
    val lostActive: Boolean get() = lostMessage != null
    var simState: String? get() = get(SIM); set(v) { if (v == null) remove(SIM) else put(SIM, v) }
    var geofenceNames: String get() = get(GEO_NAMES) ?: "{}"; set(v) = put(GEO_NAMES, v)
    var imei: String? get() = get(IMEI); set(v) { if (v == null) remove(IMEI) else put(IMEI, v) }
    var droppedRecords: Long get() = getLong(DROPPED); set(v) = putLong(DROPPED, v)

    companion object {
        const val ACCESS = "access_token"; const val REFRESH = "refresh_token"; const val EXPIRES = "expires_at"
        const val USER_ID = "user_id"; const val EMAIL = "email"; const val DEVICE_ID = "device_id"
        const val TRACKING = "tracking"; const val MODE = "mode"; const val LAST_SYNC = "last_sync"
        const val LOST_MSG = "lost_msg"; const val LOST_CONTACT = "lost_contact"; const val SIM = "sim_state"
        const val GEO_NAMES = "geo_names"; const val IMEI = "imei"; const val DROPPED = "dropped"
    }
}
