package com.wyrix.guard.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun Muted(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun ErrorText(text: String?) { if (text != null) Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

fun fmtTime(ms: Long): String = if (ms <= 0L) "never" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

val EVENT_LABELS = mapOf(
    "FAILED_UNLOCK" to "Failed unlock attempt", "SIM_REMOVED" to "SIM removed", "SIM_INSERTED" to "SIM inserted", "SIM_CHANGED" to "SIM changed",
    "GEOFENCE_ENTER" to "Entered geofence", "GEOFENCE_EXIT" to "Left geofence", "LOST_MODE_ON" to "Lost Mode on",
    "LOST_MODE_OFF" to "Lost Mode off", "ALARM_TRIGGERED" to "Alarm triggered", "TRACKING_STOPPED" to "Protection stopped",
    "ADMIN_DISABLED" to "Device admin turned off",
)
