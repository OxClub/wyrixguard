package com.wyrix.guard.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wyrix.guard.MainActivity
import com.wyrix.guard.di.entry
import com.wyrix.guard.service.AlarmService
import com.wyrix.guard.service.AlarmState
import com.wyrix.guard.system.PhotoCapture
import com.wyrix.guard.ui.theme.EmergencyRed
import com.wyrix.guard.ui.theme.WyrixTheme

class AlarmActivity : ComponentActivity() {
    private var shot = false
    override fun onStart() { super.onStart(); if (!shot) { shot = true; PhotoCapture.capture(this) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WyrixTheme {
                val running by AlarmState.running.collectAsState()
                LaunchedEffect(running) { if (!running) finish() }
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ALARM", style = MaterialTheme.typography.headlineLarge, color = EmergencyRed)
                    Spacer(Modifier.height(12.dp))
                    Text("The owner of this phone triggered this alarm.", textAlign = TextAlign.Center)
                    if (entry().store().photoEnabled) {
                        Spacer(Modifier.height(8.dp))
                        Text("A photo may be taken to help the owner recover this phone.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(32.dp))
                    Button(onClick = { stopWithUnlock() }, colors = ButtonDefaults.buttonColors(containerColor = EmergencyRed),
                        modifier = Modifier.fillMaxWidth().height(64.dp)) { Text("Stop alarm (requires unlock)") }
                }
            }
        }
    }

    private fun stopWithUnlock() {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isKeyguardLocked) { AlarmService.stop(this); return }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { AlarmService.stop(this@AlarmActivity) }
        })
    }
}

class LostModeActivity : ComponentActivity() {
    private var shot = false
    override fun onStart() { super.onStart(); if (!shot && entry().store().lostActive) { shot = true; PhotoCapture.capture(this) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = entry().store()
        setContent {
            WyrixTheme {
                val msg = store.lostMessage
                val contact = store.lostContact
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("This phone is lost", style = MaterialTheme.typography.headlineLarge, color = EmergencyRed)
                    Spacer(Modifier.height(16.dp))
                    Text(msg ?: "Lost Mode is no longer active.", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    if (store.photoEnabled && msg != null) {
                        Spacer(Modifier.height(8.dp))
                        Text("A photo may be taken to help the owner recover this phone.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    }
                    if (!contact.isNullOrBlank()) {
                        Spacer(Modifier.height(12.dp)); Text("Contact: $contact", style = MaterialTheme.typography.titleMedium)
                        if (contact.count { it.isDigit() } >= 6 && !contact.contains('@')) {
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + contact.filter { it.isDigit() || it == '+' }))) }) { Text("Call owner") }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(onClick = { startActivity(Intent(this@LostModeActivity, MainActivity::class.java)); finish() }) { Text("Open WYRIX GUARD") }
                }
            }
        }
    }
}
