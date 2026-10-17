package com.wyrix.guard.ui.vm

import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wyrix.guard.billing.BillingManager
import com.wyrix.guard.core.Luhn
import com.wyrix.guard.data.DeviceRepository
import com.wyrix.guard.data.SecureStore
import com.wyrix.guard.data.TrackingRepository
import com.wyrix.guard.data.crypto.DeviceKeys
import com.wyrix.guard.data.local.AppDatabase
import com.wyrix.guard.data.local.EventEntity
import com.wyrix.guard.data.local.LocationEntity
import com.wyrix.guard.data.remote.SupabaseApi
import com.wyrix.guard.report.ReportData
import com.wyrix.guard.report.ReportGenerator
import com.wyrix.guard.service.GeofenceManager
import com.wyrix.guard.service.LocationService
import com.wyrix.guard.system.Perms
import com.wyrix.guard.work.Scheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject

data class GeofenceUi(val id: String, val name: String, val radius: Int, val active: Boolean)
data class ContactUi(val id: String, val name: String, val email: String, val verified: Boolean, val sharing: Boolean)

data class UiState(
    val configured: Boolean = true, val onboarded: Boolean = false, val signedIn: Boolean = false, val email: String? = null,
    val enrolled: Boolean = false, val deviceName: String = "", val tracking: Boolean = false, val mode: String = "normal",
    val lost: Boolean = false, val tier: String = "free", val lastSyncAt: Long = 0, val dropped: Long = 0,
    val fine: Boolean = false, val bg: Boolean = false, val notif: Boolean = false, val admin: Boolean = false,
    val imei: String = "", val message: String? = null, val busy: Boolean = false,
    val geofences: List<GeofenceUi> = emptyList(), val contacts: List<ContactUi> = emptyList(),
)

fun friendlyError(e: Throwable): String = when (e) {
    is SupabaseApi.ApiException -> when {
        e.code.contains("invalid_credentials") || e.code.contains("Invalid login") -> "Incorrect email or password."
        e.code.contains("email_not_confirmed") || e.code.contains("not confirmed") -> "Verify your email first, then sign in."
        e.code.contains("device_limit_free") -> "The free plan supports one device. Upgrade for more."
        e.code.contains("reauth_required") -> "Please confirm your password."
        e.code.contains("rate") -> "Too many attempts. Try again in a minute."
        e.code.contains("email_not_configured") -> "Email delivery is not set up on the server yet."
        e.status == 401 -> "Your session expired. Sign in again."
        else -> "Request failed (${e.code})."
    }
    is IOException -> "No connection. Your data is saved on the phone and will sync when you are back online."
    else -> e.message ?: "Something went wrong."
}

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val api: SupabaseApi, private val store: SecureStore, private val devices: DeviceRepository,
    private val tracking: TrackingRepository, private val db: AppDatabase, private val keys: DeviceKeys,
    private val geofenceManager: GeofenceManager, val billing: BillingManager,
) : ViewModel() {

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui
    val locations: StateFlow<List<LocationEntity>> = db.locations().recent(300).stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val events: StateFlow<List<EventEntity>> = db.events().recent(200).stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val unsynced: StateFlow<Int> = db.locations().unsyncedCount().stateIn(viewModelScope, SharingStarted.Lazily, 0)

    init {
        refresh()
        viewModelScope.launch { billing.tier.collect { t -> _ui.update { it.copy(tier = t) } } }
        viewModelScope.launch { billing.message.collect { m -> if (m != null) _ui.update { it.copy(message = m) } } }
        if (store.deviceId != null && api.signedIn) viewModelScope.launch { runCatching { billing.refreshTier(); tracking.syncNow() } }
    }

    fun refresh() = _ui.update {
        it.copy(
            configured = api.configured, onboarded = store.getBool("onboarded"), signedIn = api.signedIn, email = store.email,
            enrolled = store.deviceId != null, tracking = LocationService.running || store.trackingEnabled, mode = store.trackingMode,
            lost = store.lostActive, lastSyncAt = store.lastSyncAt, dropped = store.droppedRecords,
            fine = Perms.fineLocation(ctx), bg = Perms.backgroundLocation(ctx), notif = Perms.notifications(ctx),
            admin = Perms.adminActive(ctx), imei = store.imei ?: "", deviceName = it.deviceName.ifBlank { Build.MODEL ?: "" })
    }

    fun message(m: String?) = _ui.update { it.copy(message = m) }
    fun finishOnboarding() { store.put("onboarded", "true"); refresh() }

    private fun launchBusy(block: suspend () -> Unit) = viewModelScope.launch {
        _ui.update { it.copy(busy = true, message = null) }
        try { block() } catch (e: Exception) { message(friendlyError(e)) }
        _ui.update { it.copy(busy = false) }; refresh()
    }

    // ---------- auth ----------
    fun signIn(email: String, pw: String) = launchBusy { api.signIn(email.trim(), pw); billing.refreshTier(); resumeAfterSignIn() }
    fun signUp(email: String, pw: String) = launchBusy {
        if (pw.length < 10) { message("Use at least 10 characters."); return@launchBusy }
        val session = api.signUp(email.trim(), pw)
        message(if (session) null else "Check your email to verify your account, then sign in.")
    }
    fun recover(email: String) = launchBusy { api.recover(email.trim()); message("If that account exists, a reset link is on its way.") }
    fun signOut() = launchBusy {
        api.signOut(); LocationService.stop(ctx); store.deviceId?.let { /* enrolment stays; re-sign-in resumes it */ }
    }
    private suspend fun resumeAfterSignIn() { if (store.deviceId != null) { Scheduler.schedulePolling(ctx); tracking.syncNow() } }

    // ---------- enrolment & tracking ----------
    fun enroll(name: String) = launchBusy {
        devices.enroll(name.ifBlank { Build.MODEL ?: "My phone" }); _ui.update { it.copy(deviceName = name) }
        Scheduler.schedulePolling(ctx)
    }
    fun startTracking() = launchBusy {
        if (!Perms.anyLocation(ctx)) { message("Location permission is required."); return@launchBusy }
        try { LocationService.start(ctx); store.trackingEnabled = true } catch (e: Exception) { message("Android blocked starting protection from here. Open the app and try again.") }
    }
    fun stopTracking() = launchBusy { LocationService.stop(ctx); tracking.syncNow() }
    fun setMode(wire: String) { store.trackingMode = wire; if (store.trackingEnabled) runCatching { LocationService.start(ctx) }
        viewModelScope.launch { runCatching { api.rest("PATCH", "devices?id=eq.${store.deviceId}", buildJsonObject { put("tracking_mode", wire) }, "return=minimal") } }
        refresh() }
    fun syncNow() = launchBusy {
        val ok = tracking.syncNow()
        message(if (ok) "Synced." else "Could not sync right now. Your records are saved and will retry automatically.")
    }

    // ---------- geofences / contacts ----------
    fun loadGeofences() = launchBusy {
        val id = store.deviceId ?: return@launchBusy
        val rows = api.restArray("geofences?device_id=eq.$id&select=id,name,radius_m,active&order=created_at")
        val list = rows.map { val o = it.jsonObject; GeofenceUi(o["id"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content, o["radius_m"]!!.jsonPrimitive.float.toInt(), o["active"]!!.jsonPrimitive.boolean) }
        _ui.update { it.copy(geofences = list) }
        val n = runCatching { geofenceManager.sync() }.getOrDefault(0)
        if (list.isNotEmpty() && !Perms.backgroundLocation(ctx)) message("Geofence alerts need 'Allow all the time' location access.")
        else if (n > 0) message("$n geofence(s) active on this phone.")
    }
    fun loadContacts() = launchBusy {
        val rows = api.restArray("emergency_contacts?select=id,name,email,status,share_enabled&order=created_at")
        _ui.update { s -> s.copy(contacts = rows.map { val o = it.jsonObject
            ContactUi(o["id"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content, o["email"]!!.jsonPrimitive.content,
                o["status"]!!.jsonPrimitive.content == "verified", o["share_enabled"]!!.jsonPrimitive.boolean) }) }
    }
    fun inviteContact(name: String, email: String) = launchBusy {
        api.fn("contact-invite", buildJsonObject { put("name", name.trim()); put("email", email.trim()) })
        message("Invitation sent. Nothing is shared until they confirm and you allow it."); loadContacts()
    }
    fun toggleShare(c: ContactUi) = launchBusy {
        api.rest("PATCH", "emergency_contacts?id=eq.${c.id}", buildJsonObject { put("share_enabled", !c.sharing) }, "return=minimal"); loadContacts()
    }
    fun removeContact(c: ContactUi) = launchBusy { api.rest("DELETE", "emergency_contacts?id=eq.${c.id}", null, "return=minimal"); loadContacts() }

    // ---------- reports ----------
    fun saveImei(v: String): Boolean {
        if (v.isNotBlank() && !Luhn.validImei(v)) { message("That IMEI is not valid. Dial *#06# to read it."); return false }
        store.imei = v.ifBlank { null }
        viewModelScope.launch { runCatching { api.rest("PATCH", "devices?id=eq.${store.deviceId}", buildJsonObject { put("imei_owner_entered", v.ifBlank { null }) }, "return=minimal") } }
        refresh(); message("Saved."); return true
    }
    private fun fingerprint(): String = MessageDigest.getInstance("SHA-256").digest(Base64.decode(keys.publicKeyB64(), Base64.NO_WRAP)).joinToString("") { "%02x".format(it) }
    suspend fun buildPdf(note: String): File {
        val last = db.locations().last()
        val ev = events.value
        return ReportGenerator.pdf(ctx, ReportData(_ui.value.deviceName, store.imei, note, fingerprint(), last, store.lastSyncAt, ev))
    }
    fun buildCsv(): File = ReportGenerator.csv(ctx, locations.value)

    // ---------- privacy & account ----------
    fun deleteLocalHistory() = launchBusy { db.locations().clear(); db.events().clear(); message("Local history deleted.") }
    fun revokeThisDevice(password: String) = launchBusy {
        api.signIn(store.email ?: "", password)              // fresh sign-in => server accepts the sensitive action
        api.fn("revoke-device", buildJsonObject { put("device_id", store.deviceId ?: return@launchBusy }); devices.wipeLocal()
        message("This device was removed from your account.")
    }
    fun deleteAccount(password: String) = launchBusy {
        api.signIn(store.email ?: "", password)
        api.fn("delete-account", buildJsonObject { put("confirm", "DELETE") })
        devices.wipeLocal(); Scheduler.cancelAll(ctx); store.clearAll(); message("Account deleted.")
    }
}
