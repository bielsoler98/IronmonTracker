package com.biel.ironmontracker.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biel.ironmontracker.emulator.RecentRom

@Composable
fun HomeScreen(
    isLoading: Boolean,
    errorMessage: String?,
    hasRandomizerSettings: Boolean,
    settingsImportError: String?,
    recentRom: RecentRom?,
    hasSessionState: Boolean,
    onRomSelected: (android.net.Uri, String) -> Unit,
    onRandomizerSettingsSelected: (android.net.Uri) -> Unit,
    onOpenRecent: () -> Unit,
    onResume: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val romLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment ?: "ROM"
            onRomSelected(uri, name)
        }
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onRandomizerSettingsSelected(uri)
    }

    Box(Modifier.fillMaxSize()) {
    IconButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
        Icon(Icons.Default.Settings, contentDescription = "Settings")
    }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.SportsEsports,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Ironmon Tracker",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "GBA emulator with built-in Ironmon-Tracker",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 32.dp),
        )

        if (hasSessionState) {
            Button(
                onClick = onResume,
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Loading…", fontSize = 16.sp)
                } else {
                    Text("Resume where you left off", fontSize = 16.sp)
                }
            }
            Text(
                "Continues the exact game state from last time — no re-randomization.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
            )
        }

        if (recentRom != null) {
            if (hasSessionState) {
                OutlinedButton(
                    onClick = onOpenRecent,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    Text(
                        "Start fresh: ${recentRom.name}",
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            } else {
                Button(
                    onClick = onOpenRecent,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Loading…", fontSize = 16.sp)
                    } else {
                        Text(
                            "Open recent: ${recentRom.name}",
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { romLauncher.launch(arrayOf("*/*")) },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth().height(44.dp),
            ) {
                Text("Open a different ROM", fontSize = 13.sp)
            }
        } else {
            Button(
                onClick = { romLauncher.launch(arrayOf("*/*")) },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Loading…", fontSize = 16.sp)
                } else {
                    Text("Open GBA ROM", fontSize = 16.sp)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = { settingsLauncher.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth().height(44.dp),
        ) {
            Text(
                if (hasRandomizerSettings) "Randomizer settings loaded — change (.rnqs)"
                else "Import randomizer settings (.rnqs) for reset",
                fontSize = 13.sp,
            )
        }
        Text(
            "Without this, the reset combo just restarts the same ROM. With it, " +
                "every reset generates a fresh randomized seed from the same rules " +
                "you use in PokemonRandomizerZX/BizHawk.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )

        if (settingsImportError != null) {
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Couldn't load .rnqs: $settingsImportError",
                    Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (errorMessage != null) {
            Spacer(Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    errorMessage,
                    Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("Setup required", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Download the Ironmon-Tracker from github.com/besteon/Ironmon-Tracker and place the contents of the zip into:\n\napp/src/main/assets/ironmon_tracker/\n\nthen rebuild the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp,
                )
            }
        }
    }
    }
}
