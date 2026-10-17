package com.wyrix.guard.ui
import androidx.compose.runtime.saveable.rememberSaveable

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.wyrix.guard.core.Accuracy
import com.wyrix.guard.data.local.EventEntity
import com.wyrix.guard.data.local.LocationEntity
import com.wyrix.guard.report.ReportGenerator
import com.wyrix.guard.ui.vm.MainViewModel
import com.wyrix.guard.ui.vm.UiState
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MapScreen(points: List<LocationEntity>) {
    val last = points.firstOrNull()
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Map", style = MaterialTheme.typography.headlineMedium)
        if (last == null) { Muted("No location recorded yet."); return }
        Muted("Last confirmed fix: ${fmtTime(last.recordedAt)} · ${Accuracy.label(last.accuracy)}. This is not live tracking.")
        val pts = points.take(50).joinToString(",") { "[${it.lat},${it.lng},${it.accuracy}]" }  // numbers only: no injection surface
        val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.css">
<script src="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.js"></script>
<style>html,body,#m{height:100%;margin:0}</style></head><body><div id="m"></div><script>
var p=[$pts];var m=L.map('m').setView([p[0][0],p[0][1]],16);
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'&copy; OpenStreetMap contributors'}).addTo(m);
L.circle([p[0][0],p[0][1]],{radius:p[0][2],color:'#1677FF',weight:1,fillOpacity:.15}).addTo(m);
L.circleMarker([p[0][0],p[0][1]],{radius:8,color:'#fff',weight:2,fillColor:'#FF3547',fillOpacity:1}).addTo(m);
for(var i=1;i<p.length;i++){L.circleMarker([p[i][0],p[i][1]],{radius:4,color:'#1677FF',weight:1,fillOpacity:.7}).addTo(m);}
</script></body></html>"""
        AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false; settings.allowContentAccess = false
                webViewClient = WebViewClient()
                loadDataWithBaseURL("https://wyrix.invalid/", html, "text/html", "utf-8", null)
            }
        }, update = { it.loadDataWithBaseURL("https://wyrix.invalid/", html, "text/html", "utf-8", null) })
        Muted("Map tiles need an internet connection; the coordinates above stay available offline.")
    }
}

@Composable
fun HistoryScreen(vm: MainViewModel, rows: List<LocationEntity>) {
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Location history", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = rows.isNotEmpty(), onClick = { ctx.startActivity(ReportGenerator.shareIntent(ctx, vm.buildCsv(), "text/csv")) }) { Text("Export CSV") }
            OutlinedButton(enabled = rows.isNotEmpty(), onClick = { confirm = true }) { Text("Delete on phone") }
        }
        Muted("Showing the latest ${rows.size} records stored on this phone. Full history is on your dashboard.")
        if (rows.isEmpty()) Muted("No records yet.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(rows, key = { it.id }) { r ->
                Card { Column(Modifier.padding(12.dp)) {
                    Text("%.5f, %.5f".format(r.lat, r.lng))
                    Muted("${fmtTime(r.recordedAt)} · ${Accuracy.label(r.accuracy)} · ${if (r.synced) "uploaded" else "waiting to upload"}")
                } }
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Delete local history?") },
        text = { Text("Removes records stored on this phone, including any not yet uploaded.") },
        confirmButton = { TextButton(onClick = { vm.deleteLocalHistory(); confirm = false }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}

@Composable
fun AlertsScreen(events: List<EventEntity>, simOnly: Boolean) {
    val list = if (simOnly) events.filter { it.type.startsWith("SIM_") } else events
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (simOnly) "SIM alerts" else "Security alerts", style = MaterialTheme.typography.headlineMedium)
        if (simOnly) Muted("Detects only that a SIM was inserted or removed while protection is running. Android does not let apps read a new SIM's number or owner, and WYRIX GUARD has no access to your operator.")
        else Muted("Failed-unlock alerts need the optional device-admin setting and are not delivered on every Android version.")
        if (list.isEmpty()) Muted("Nothing recorded.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list, key = { it.id }) { e ->
                Card { Column(Modifier.padding(12.dp)) {
                    Text(EVENT_LABELS[e.type] ?: e.type); Muted(fmtTime(e.occurredAt))
                    if (e.detailsJson != "{}") Muted(e.detailsJson.trim('{', '}').replace("\"", ""))
                } }
            }
        }
    }
}

@Composable
fun MoreScreen(onOpen: (String) -> Unit) {
    val entries = listOf("sim" to "SIM alerts", "geofences" to "Geofences", "reports" to "Recovery reports & IMEI", "contacts" to "Emergency contacts",
        "privacy" to "Privacy & permissions", "plan" to "Subscription", "settings" to "Account settings")
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("More", style = MaterialTheme.typography.headlineMedium) }
        items(entries) { (route, label) -> Card(onClick = { onOpen(route) }, modifier = Modifier.fillMaxWidth()) { Text(label, Modifier.padding(16.dp)) } }
    }
}

@Composable
fun GeofencesScreen(vm: MainViewModel, s: UiState) {
    LaunchedEffect(Unit) { vm.loadGeofences() }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Geofences", style = MaterialTheme.typography.headlineMedium)
        Muted("Create and edit geofences on the dashboard (Premium). They are installed on this phone with Android's Geofencing API and need 'all the time' location. Alerts can be delayed when the phone is idle.")
        ErrorText(s.message)
        if (s.geofences.isEmpty()) Muted("No geofences.")
        s.geofences.forEach { Card { Column(Modifier.padding(12.dp)) { Text(it.name); Muted("${it.radius} m · ${if (it.active) "active" else "paused"}") } } }
    }
}

@Composable
fun ReportsScreen(vm: MainViewModel, s: UiState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var imei by rememberSaveable { mutableStateOf(s.imei) }
    var note by rememberSaveable { mutableStateOf("") }
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Recovery reports", style = MaterialTheme.typography.headlineMedium)
        Section("IMEI (your own record)") {
            OutlinedTextField(imei, { imei = it.filter(Char::isDigit).take(15) }, label = { Text("15-digit IMEI (dial *#06#)") }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.saveImei(imei) }) { Text("Save") }
            Muted("Stored only to help you file reports. Apps cannot locate a phone from its IMEI, and WYRIX GUARD does not read it.")
        }
        Section("Report") {
            OutlinedTextField(note, { note = it.take(500) }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { scope.launch { ctx.startActivity(ReportGenerator.shareIntent(ctx, vm.buildPdf(note), "application/pdf")) } }) { Text("Generate PDF") }
            Muted("Separates owner claims from verified app records. Not an official police document.")
        }
        Section("If your phone is stolen") {
            Text("1. Report it to the police and keep the complaint number.\n2. Ask your operator to block the SIM.\n3. In India, block the phone by IMEI at Sanchar Saathi (CEIR).")
            OutlinedButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://sancharsaathi.gov.in/"))) }) { Text("Open Sanchar Saathi") }
        }
        ErrorText(s.message)
    }
}

@Composable
fun ContactsScreen(vm: MainViewModel, s: UiState) {
    LaunchedEffect(Unit) { vm.loadContacts() }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Emergency contacts", style = MaterialTheme.typography.headlineMedium)
        Muted("A contact sees your last confirmed location only after they confirm by email AND you switch sharing on. Remove them any time.")
        Section("Invite") {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Email))
            Button(enabled = name.isNotBlank() && email.isNotBlank() && !s.busy, onClick = { vm.inviteContact(name, email); name = ""; email = "" }) { Text("Send invitation") }
        }
        ErrorText(s.message)
        s.contacts.forEach { c ->
            Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${c.name} · ${c.email}"); Muted(if (c.verified) "Confirmed · sharing ${if (c.sharing) "ON" else "off"}" else "Waiting for confirmation")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = c.verified, onClick = { vm.toggleShare(c) }) { Text(if (c.sharing) "Stop sharing" else "Allow sharing") }
                    OutlinedButton(onClick = { vm.removeContact(c) }) { Text("Remove") }
                }
            } }
        }
    }
}

@Composable
fun PrivacyScreen(vm: MainViewModel, s: UiState) {
    val ctx = LocalContext.current
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Privacy & permissions", style = MaterialTheme.typography.headlineMedium)
        Section("What is collected") {
            Text("• Location (with accuracy and time), battery level, connection type, Android version.\n• Security events you enable: failed unlock attempts, SIM inserted/removed, geofence crossings, Lost Mode and alarm activity.\n• Your IMEI only if you type it in.")
            Text("Collection happens in the background while protection is on, with a permanent notification. Data goes to your private WYRIX account over TLS; only you can read it.")
        }
        Section("Not collected") { Text("Camera photos, microphone, contacts, messages, call logs, IMSI/ICCID, or other people's data. No accessibility service, no hidden icon.") }
        Section("Permission status") {
            Text("Location: ${if (s.fine) "granted" else "not granted"}\nAll-the-time location: ${if (s.bg) "granted" else "not granted"}\nNotifications: ${if (s.notif) "granted" else "not granted"}\nDevice admin: ${if (s.admin) "on" else "off"}")
            OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }) { Text("Open Android app settings") }
        }
        Section("Your controls") {
            OutlinedButton(onClick = { vm.deleteLocalHistory() }) { Text("Delete history stored on this phone") }
            Muted("Delete cloud data, revoke this device or delete your account under Account settings.")
        }
        ErrorText(s.message)
    }
}

@Composable
fun PlanScreen(vm: MainViewModel, s: UiState) {
    val activity = LocalActivity.current
    val plans by vm.billing.plans.collectAsState()
    LaunchedEffect(Unit) { vm.billing.loadPlans(); vm.billing.restore(); vm.billing.refreshTier() }
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Subscription", style = MaterialTheme.typography.headlineMedium)
        Text(if (s.tier == "premium") "Premium is active" else "Free plan", style = MaterialTheme.typography.titleMedium)
        Muted("Free: 1 device, last-known location, basic status, alarm, basic Lost Mode, essential alerts. Premium: multiple devices, longer history, enhanced reports, geofences, event history, longer evidence retention. Privacy and deletion controls are always free.")
        plans.forEach { p ->
            Card { Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) { Text(p.title); Muted(p.price) }
                Button(enabled = activity != null, onClick = { activity?.let { vm.billing.launch(it, p) } }) { Text("Subscribe") }
            } }
        }
        if (plans.isEmpty()) Muted("Plans will appear once the subscription products exist in Google Play Console.")
        Muted("Access is granted only after our server verifies your Google Play purchase.")
        ErrorText(s.message)
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel, s: UiState) {
    var dialog by remember { mutableStateOf<String?>(null) }
    var pw by remember { mutableStateOf("") }
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Account settings", style = MaterialTheme.typography.headlineMedium)
        Section("Account") { Text(s.email ?: ""); OutlinedButton(onClick = { vm.signOut() }) { Text("Sign out") } }
        Section("This device") {
            Muted("Removing this device stops tracking and deletes its key. You'll confirm your password.")
            OutlinedButton(onClick = { dialog = "revoke" }) { Text("Remove this device from my account") }
        }
        Section("Delete account") {
            Muted("Permanently deletes your account, devices, locations, events and reports. Cannot be undone.")
            Button(onClick = { dialog = "delete" }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete my account") }
        }
        ErrorText(s.message)
    }
    dialog?.let { d ->
        AlertDialog(onDismissRequest = { dialog = null; pw = "" }, title = { Text("Confirm your password") },
            text = { OutlinedTextField(pw, { pw = it }, singleLine = true, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation()) },
            confirmButton = { TextButton(enabled = pw.isNotEmpty(), onClick = { if (d == "revoke") vm.revokeThisDevice(pw) else vm.deleteAccount(pw); dialog = null; pw = "" }) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { dialog = null; pw = "" }) { Text("Cancel") } })
    }
}
