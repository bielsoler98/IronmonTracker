package com.biel.ironmontracker.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    keyMap: Map<String, Int>,
    trackerToggleKeyCode: Int?,
    fastForwardKeyCode: Int?,
    remapTarget: String?,
    trackerAlwaysVisible: Boolean,
    audioEnabled: Boolean,
    batterySaver: Boolean,
    hasPhysicalGamepad: Boolean,
    onStartRemap: (String) -> Unit,
    onCancelRemap: () -> Unit,
    onResetKeyMap: () -> Unit,
    onClearTrackerToggleKey: () -> Unit,
    onClearFastForwardKey: () -> Unit,
    onTrackerAlwaysVisibleChanged: (Boolean) -> Unit,
    onAudioEnabledChanged: (Boolean) -> Unit,
    onBatterySaverChanged: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                TextButton(onClick = { onCancelRemap(); onClose() }) { Text("Close") }
            }
            Spacer(Modifier.height(20.dp))

            SettingsSection("Controls") {
                if (!hasPhysicalGamepad) {
                    Text(
                        "No physical gamepad detected right now — remapping still applies once one is connected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    "Reset: hold LT + RT + Select",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "D-pad/stick, LT/RT and this combo are fixed (analog axes, not remappable buttons).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                listOf("A", "B", "L", "R", "START", "SELECT").forEach { button ->
                    KeyRemapRow(
                        label = button,
                        keyCode = keyMap[button],
                        isWaiting = remapTarget == button,
                        onClick = { if (remapTarget == button) onCancelRemap() else onStartRemap(button) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onResetKeyMap) { Text("Reset to defaults") }

                Spacer(Modifier.height(16.dp))
                Text(
                    "By default, tapping RT cycles fast-forward speed. Assign a button here to use that instead:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                KeyRemapRow(
                    label = "Fast forward",
                    keyCode = fastForwardKeyCode,
                    isWaiting = remapTarget == "FAST_FORWARD",
                    onClick = { if (remapTarget == "FAST_FORWARD") onCancelRemap() else onStartRemap("FAST_FORWARD") },
                )
                if (fastForwardKeyCode != null) {
                    TextButton(onClick = onClearFastForwardKey) { Text("Unassign") }
                }
            }

            SettingsSection("Tracker") {
                SettingsSwitchRow(
                    title = "Always show tracker",
                    subtitle = if (trackerAlwaysVisible) {
                        "The tracker panel stays open all the time"
                    } else {
                        "Show/hide it with the button in the bottom bar"
                    },
                    checked = trackerAlwaysVisible,
                    onCheckedChange = onTrackerAlwaysVisibleChanged,
                )
                if (!trackerAlwaysVisible) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Optionally, a physical button to show/hide it too:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    KeyRemapRow(
                        label = "Toggle tracker",
                        keyCode = trackerToggleKeyCode,
                        isWaiting = remapTarget == "TRACKER_TOGGLE",
                        onClick = { if (remapTarget == "TRACKER_TOGGLE") onCancelRemap() else onStartRemap("TRACKER_TOGGLE") },
                    )
                    if (trackerToggleKeyCode != null) {
                        TextButton(onClick = onClearTrackerToggleKey) { Text("Unassign") }
                    }
                }
            }

            SettingsSection("Performance") {
                SettingsSwitchRow(
                    title = "Audio",
                    subtitle = "Turn off to save a bit of CPU/battery",
                    checked = audioEnabled,
                    onCheckedChange = onAudioEnabledChanged,
                )
                SettingsSwitchRow(
                    title = "Battery saver",
                    subtitle = "Lowers how often the screen/tracker refresh — the game itself still runs at full speed",
                    checked = batterySaver,
                    onCheckedChange = onBatterySaverChanged,
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun KeyRemapRow(label: String, keyCode: Int?, isWaiting: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        OutlinedButton(onClick = onClick) {
            Text(
                when {
                    isWaiting -> "Press a button…"
                    keyCode != null -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
                    else -> "Unassigned"
                },
            )
        }
    }
}
