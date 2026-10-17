package com.wyrix.guard.ui.screens
import androidx.compose.runtime.saveable.rememberSaveable

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private data class Page(val icon: ImageVector, val title: String, val body: String)

private val pages = listOf(
    Page(Icons.Filled.VerifiedUser, "Only your devices", "WYRIX GUARD protects phones you personally enrol and consent to. It cannot track other people's devices."),
    Page(Icons.Filled.LocationOn, "Honest location", "You will always see the last confirmed position, its accuracy and when it was recorded. Nothing is guessed."),
    Page(Icons.Filled.Notifications, "Clear limits", "Power-off, factory reset, uninstall or force-stop stop updates. WYRIX GUARD tells you the real status of every action and never hides itself."),
)

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val page = pages[index]
    val last = index == pages.lastIndex
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (!last) TextButton(onClick = onDone) { Text("Skip") }
        }
        Spacer(Modifier.weight(1f))
        Icon(page.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(96.dp))
        Spacer(Modifier.height(24.dp))
        Text(page.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(page.body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pages.indices.forEach { i ->
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (i == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(width = if (i == index) 24.dp else 8.dp, height = 8.dp),
                ) {}
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { if (last) onDone() else index++ },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (last) "Get started" else "Next") }
    }
}
