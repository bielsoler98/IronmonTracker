package com.biel.ironmontracker.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biel.ironmontracker.tracker.BagItemState
import com.biel.ironmontracker.tracker.BagItemsState

// Mirrors the reference tracker's "Heals in Bag" screen (HealsInBagScreen.lua):
// every healing/status/PP item, Poké Ball and evolution stone currently held,
// grouped by category, with the ones that would actually help your lead
// Pokémon right now highlighted -- same highlighting upstream shows in green.
@Composable
fun BagScreen(bagItems: BagItemsState, onClose: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Bag", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                TextButton(onClick = onClose) { Text("Close") }
            }
            Spacer(Modifier.height(20.dp))

            val sections = listOf(
                "HP Heals" to bagItems.hpHeals,
                "PP Heals" to bagItems.ppHeals,
                "Status Heals" to bagItems.statusHeals,
                "Poké Balls" to bagItems.pokeBalls,
                "Evolution Stones" to bagItems.evoStones,
                "Other" to bagItems.other,
            )
            val isEmpty = sections.all { it.second.isEmpty() }
            if (isEmpty) {
                Text(
                    "Bag's empty.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            sections.forEach { (title, items) ->
                if (items.isNotEmpty()) {
                    BagSection(title, items)
                }
            }
        }
    }
}

@Composable
private fun BagSection(title: String, items: List<BagItemState>) {
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Spacer(Modifier.height(4.dp))
        items.forEach { BagItemRow(it) }
    }
}

@Composable
private fun BagItemRow(item: BagItemState) {
    val bitmap = rememberItemIcon(item.icon)
    val textColor = if (item.isHelpful) {
        androidx.compose.ui.graphics.Color(0xFF4CAF50)
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                Image(bitmap, contentDescription = null, filterQuality = FilterQuality.None)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            item.name,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (item.isHelpful) FontWeight.Bold else FontWeight.Normal,
            color = textColor,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            "×${item.quantity}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = textColor,
        )
    }
}
