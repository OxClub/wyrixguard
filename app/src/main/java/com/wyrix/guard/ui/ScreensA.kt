package com.wyrix.guard.ui
import androidx.compose.runtime.saveable.rememberSaveable

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Build
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.wyrix.guard.R
import com.wyrix.guard.BuildConfig
import com.wyrix.guard.core.Accuracy
import com.wyrix.guard.core.Consent
import com.wyrix.guard.core.Links
import com.wyrix.guard.core.TrackingMode
import com.wyrix.guard.data.local.LocationEntity
import com.wyrix.guard.system.Perms
import com.wyrix.guard.ui.vm.MainViewModel
import com.wyrix.guard.ui.vm.UiState

@Composable
fun NotConfiguredScreen() = Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
    Text("Not configured", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Muted("This build has no Supabase URL / anon key. Set SUPABASE_URL and SUPABASE_ANON_KEY (GitHub Secrets or gradle properties) and rebuild.")
}

@Composable
fun AuthScreen(vm: MainViewModel, s: UiState) {
    var mode by rememberSaveable { mutableStateOf("signin") }
    var email by rememberSaveable { mutableStateOf("") }
    var pw by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("WYRIX GUARD", style = MaterialTheme.typography.headlineLarge)
        Muted("Your Device. Your Security. Your Control.")
        Text(when (mode) { "signin" -> "Sign in"; "signup" -> "Create account"; else -> "Reset password" }, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Email))
        if (mode != "reset") OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password))
        ErrorText(s.message)
        Button(enabled = !s.busy && email.isNotBlank() && (mode == "reset" || pw.isNotEmpty()), modifier = Modifier.fillMaxWidth().height(52.dp), onClick = {
            when (mode) { "signin" -> vm.signIn(email, pw); "signup" -> vm.signUp(email, pw); else -> vm.recover(email) }
        }) { Text(if (s.busy) "Please wait…" else when (mode) { "signin" -> "Sign in"; "signup" -> "Create account"; else -> "Send reset link" }) }
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { vm.message(null); mode = if (mode == "signin") "signup" else "signin" }) { Text(if (mode == "signin") "Create account" else "Back to sign in") }
            if (mode == "signin") TextButton(onClick = { vm.message(null); mode = "reset" }) { Text("Forgot password?") }
        }
        Muted("WYRIX GUARD protects only phones you personally enrol. It cannot locate anyone else's phone or any phone by number or IMEI.")
    }
}

@Composable
fun EnrollScreen(vm: MainViewModel, s: UiState) {
    var name by rememberSaveable { mutableStateOf(Build.MODEL ?: "My phone") }
    var consent by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Enrol this phone", style = MaterialTheme.typography.headlineMedium)
        Muted("Signed in as ${s.email ?: "your account"}")
        OutlinedTextField(name, { name = it.take(60) }, label = { Text("Device name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Section("What you are agreeing to (policy ${Consent.VERSION})") {
            Text("• This phone's location, with accuracy and time, plus battery and connection status, will be recorded and sent to your WYRIX account, including in the background while protection is on.")
            Text("• A permanent notification is shown whenever location is being collected. There is no hidden mode.")
            Text("• Only you (the account owner) can see this data. You can pause, revoke this device, export or delete everything at any time.")
            Text("• Optional features (failed-unlock alerts via device administration, geofence alerts, evidence photos) are off until you turn them on.")
            Text("• No microphone, contacts, messages, IMEI or SIM numbers are collected. A front-camera photo is only ever taken if you opt in, and only while the Lost Mode or alarm screen is visible.")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(consent, { consent = it })
            Text("I own this phone and I agree to the above.", Modifier.weight(1f))
        }
        ErrorText(s.message)
        Button(enabled = consent && !s.busy && name.isNotBlank(), onClick = { vm.enroll(name) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (s.busy) "Enrolling…" else "Enrol this phone")
        }
        TextButton(onClick = { vm.signOut() }) { Text("Sign out") }
    }
}

private fun FragmentActivity.confirmOwner(title: String, onOk: () -> Unit) {
    val authenticators = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
    if (androidx.biometric.BiometricManager.from(this).canAuthenticate(authenticators) != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) { onOk(); return }
    val prompt = androidx.biometric.BiometricPrompt(this, androidx.core.content.ContextCompat.getMainExecutor(this),
        object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) { onOk() }
        })
    prompt.authenticate(androidx.biometric.BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(authenticators).build())
}

@Composable
fun HomeScreen(vm: MainViewModel, s: UiState, last: LocationEntity?, unsynced: Int) {
    val ctx = LocalContext.current
    val activity = ctx as? FragmentActivity
    val fgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refresh() }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
    val adminLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refresh() }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Security dashboard", style = MaterialTheme.typography.headlineMedium)
        if (s.lost) Section("Lost Mode is active") { Muted("Your owner marked this phone as lost. Recovery tracking is on.") }
        Section("Web dashboard") {
            Muted("See your phone on a map, send an alarm or Lost Mode, and view photos from any browser. Sign in with the same account.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Links.dashboard(BuildConfig.DASHBOARD_URL)))) }) { Text("Open dashboard") }
                OutlinedButton(onClick = { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Links.dashboard(BuildConfig.DASHBOARD_URL)), "Share dashboard link")) }) { Text("Share link") }
            }
        }

        Section("Protection") {
            Text(if (s.tracking) "ON: location is being recorded" else "OFF: this phone is not reporting its location", color = if (s.tracking) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
            Muted("Mode: ${s.mode.replace('_', ' ')}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TrackingMode.entries.forEach { m ->
                    FilterChip(selected = s.mode == m.wire, onClick = { vm.setMode(m.wire) }, label = { Text(m.wire.replace('_', ' ')) })
                }
            }
            Muted("Normal ≈ every minute. Battery saving ≈ every 10 min. Recovery ≈ every 15 s (uses more battery).")
            if (!s.tracking) Button(onClick = { vm.startTracking() }, enabled = s.fine && !s.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Start protection") }
            else OutlinedButton(onClick = { activity?.confirmOwner("Confirm to stop protection") { vm.stopTracking() } ?: vm.stopTracking() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Stop protection") }
        }

        Section("Set-up checklist") {
            CheckRow("Location while the app is open", s.fine) {
                Button(onClick = { fgLauncher.launch(buildList { add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION); if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS) }.toTypedArray()) }) { Text("Allow") }
            }
            CheckRow("Location all the time (needed when the app is closed)", s.bg) {
                Button(enabled = s.fine, onClick = { bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) { Text("Allow") }
            }
            CheckRow("Notifications (shows the protection notice and alerts)", s.notif) {
                Button(onClick = { if (Build.VERSION.SDK_INT >= 33) fgLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }) { Text("Allow") }
            }
            CheckRow("Failed-unlock alerts (optional device admin)", s.admin) {
                Button(onClick = {
                    adminLauncher.launch(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, Perms.adminComponent(ctx))
                        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, ctx.getString(R.string.admin_explanation)))
                }) { Text("Enable") }
            }
            Muted("Android 11+ asks for 'all the time' location on a separate screen. Optional evidence photos are under More > Evidence photos.")
        }

        Section("Last confirmed location") {
            if (last == null) Muted("Nothing recorded yet. Start protection and wait for a GPS fix.")
            else {
                Text("%.5f, %.5f".format(last.lat, last.lng))
                Muted("${Accuracy.label(last.accuracy)} · ${fmtTime(last.recordedAt)}")
            }
        }

        Section("Sync") {
            Muted("Last successful sync: ${fmtTime(s.lastSyncAt)}")
            Muted(if (unsynced == 0) "All records are uploaded." else "$unsynced record(s) saved on the phone, waiting for a connection.")
            if (s.dropped > 0) Muted("${s.dropped} invalid record(s) were discarded.")
            ErrorText(s.message)
            OutlinedButton(onClick = { vm.syncNow() }, enabled = !s.busy) { Text(if (s.busy) "Syncing…" else "Sync now") }
        }
        Muted("Honest limits: no updates while the phone is off, in airplane mode, factory-reset, uninstalled, force-stopped, or has no GPS/permission.")
    }
}

@Composable
private fun CheckRow(label: String, ok: Boolean, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text((if (ok) "✓ " else "○ ") + label, Modifier.weight(1f))
        if (!ok) action()
    }
}
