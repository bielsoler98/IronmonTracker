package com.biel.ironmontracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

fun typeColor(type: String): Color = when (type.uppercase().trimEnd()) {
    "NORMAL"   -> Color(0xFFA8A878)
    "FIRE"     -> Color(0xFFF08030)
    "WATER"    -> Color(0xFF6890F0)
    "ELECTRIC" -> Color(0xFFF8D030)
    "GRASS"    -> Color(0xFF78C850)
    "ICE"      -> Color(0xFF98D8D8)
    "FIGHTING" -> Color(0xFFC03028)
    "POISON"   -> Color(0xFFA040A0)
    "GROUND"   -> Color(0xFFE0C068)
    "FLYING"   -> Color(0xFFA890F0)
    "PSYCHIC"  -> Color(0xFFF85888)
    "BUG"      -> Color(0xFFA8B820)
    "ROCK"     -> Color(0xFFB8A038)
    "GHOST"    -> Color(0xFF705898)
    "DRAGON"   -> Color(0xFF7038F8)
    "DARK"     -> Color(0xFF705848)
    "STEEL"    -> Color(0xFFB8B8D0)
    "FAIRY"    -> Color(0xFFEE99AC)
    else       -> Color(0xFF888888)
}

val NATURE_NAMES = listOf(
    "Hardy", "Lonely", "Brave", "Adamant", "Naughty",
    "Bold", "Docile", "Relaxed", "Impish", "Lax",
    "Timid", "Hasty", "Serious", "Jolly", "Naive",
    "Modest", "Mild", "Quiet", "Bashful", "Rash",
    "Calm", "Gentle", "Sassy", "Careful", "Quirky",
)

private val Colors = darkColorScheme(
    primary          = Color(0xFF6CB4E4),
    onPrimary        = Color(0xFF000000),
    secondary        = Color(0xFFFFD700),
    background       = Color(0xFF0D0D1A),
    onBackground     = Color(0xFFE8E8FF),
    surface          = Color(0xFF1A1A2E),
    onSurface        = Color(0xFFE8E8FF),
    surfaceVariant   = Color(0xFF252540),
    onSurfaceVariant = Color(0xFFBBBBDD),
    error            = Color(0xFFFF6B6B),
)

@Composable
fun IronmonTrackerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
