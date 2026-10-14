package com.biel.ironmontracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsMma
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biel.ironmontracker.emulator.EmulatorEngine
import com.biel.ironmontracker.tracker.AbilitySlotState
import com.biel.ironmontracker.tracker.CatchRateState
import com.biel.ironmontracker.tracker.DefensesState
import com.biel.ironmontracker.tracker.KnownMoveState
import com.biel.ironmontracker.tracker.LocationState
import com.biel.ironmontracker.tracker.MoveState
import com.biel.ironmontracker.tracker.PartyMemberState
import com.biel.ironmontracker.tracker.PokemonState
import com.biel.ironmontracker.tracker.Stats
import com.biel.ironmontracker.tracker.StatStagesState
import com.biel.ironmontracker.tracker.TrackerState
import com.biel.ironmontracker.tracker.TrainerPartySlotState
import com.biel.ironmontracker.ui.theme.NATURE_NAMES
import com.biel.ironmontracker.ui.theme.typeColor
import kotlinx.coroutines.delay

@Composable
fun GameScreen(
    frame: android.graphics.Bitmap?,
    trackerState: TrackerState?,
    hasPhysicalGamepad: Boolean,
    isRandomizing: Boolean,
    speedMultiplier: Int,
    attemptNumber: Int,
    attemptStartMs: Long,
    trackerAlwaysVisible: Boolean,
    trackerToggledOn: Boolean,
    onKeysChanged: (Int) -> Unit,
    onSaveState: () -> Unit,
    onNewAttempt: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBag: () -> Unit,
    onToggleTracker: () -> Unit,
) {
    // Hidden by default so the game gets the full screen; tap the bottom bar
    // (or a remapped physical button, see Settings) to reveal the tracker
    // panel alongside it. Forced on regardless of the toggle when the
    // "always show tracker" setting is on. trackerToggledOn/onToggleTracker
    // live in the ViewModel (not local Compose state) so a physical button
    // press captured outside the Compose tree in MainActivity can drive it.
    val showTracker = trackerAlwaysVisible || trackerToggledOn

    // Resets whenever gameOver/gameWon flips false -> true (a fresh faint or
    // a fresh champion win), so dismissing it with "Continue playing" doesn't
    // bring it right back while the same condition still holds, but a later
    // faint/win reopens it. The attempt is over at that same moment, so the
    // displayed play time is frozen then too -- captured once here rather
    // than ticking live, which would keep climbing for as long as the dialog
    // stays up or the player keeps exploring after the run already ended.
    val gameOverActive = trackerState?.gameOver == true || trackerState?.gameWon == true
    var gameOverDismissed by remember(trackerState?.gameOver, trackerState?.gameWon) { mutableStateOf(false) }
    val frozenPlayTimeMs = remember(trackerState?.gameOver, trackerState?.gameWon) {
        if (gameOverActive) System.currentTimeMillis() - attemptStartMs else 0L
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // ── Game (always full width) with tracker as an overlay panel ──────────
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                GameDisplay(
                    frame = frame?.asImageBitmap(),
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(240f / 160f),
                )
            }

            if (showTracker) {
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .fillMaxWidth(0.5f)
                        .background(MaterialTheme.colorScheme.surface)
                        .verticalScroll(rememberScrollState()),
                ) {
                    TrackerOverlay(state = trackerState, onOpenBag = onOpenBag)
                }
            }

            if (isRandomizing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text("Randomizing new seed…", color = Color.White)
                    }
                }
            }

            if (gameOverActive && !gameOverDismissed && !isRandomizing && trackerState != null) {
                GameOverDialog(
                    won = trackerState.gameWon,
                    fainted = trackerState.player?.pokemon,
                    quote = trackerState.gameOverQuote,
                    attemptNumber = attemptNumber,
                    playTimeMs = frozenPlayTimeMs,
                    onContinue = { gameOverDismissed = true },
                    onNewAttempt = { gameOverDismissed = true; onNewAttempt() },
                )
            }
        }

        // ── Controls ─────────────────────────────────────────────────────────
        // On devices with a physical gamepad (e.g. Ayaneo Pocket Air), the touch
        // D-pad/buttons are redundant with the hardware — skip them and hand the
        // space back to the tracker toggle.
        if (hasPhysicalGamepad) {
            PhysicalGamepadBar(
                showTracker = showTracker,
                showTrackerToggle = !trackerAlwaysVisible,
                speedMultiplier = speedMultiplier,
                trackerState = trackerState,
                onToggleTracker = onToggleTracker,
                onSaveState = onSaveState,
                onOpenSettings = onOpenSettings,
            )
        } else {
            GbaControls(
                onKeysChanged = onKeysChanged,
                showTracker = showTracker,
                onToggleTracker = onToggleTracker,
            )
        }
    }
}

// Compact echo of StarterHintCard for the bottom bar, so the chosen pokeball
// is visible at a glance without opening the tracker panel.
@Composable
private fun CompactStarterHint(choice: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (position in 1..3) {
            PokeballIcon(highlighted = position == choice, size = 18.dp)
        }
    }
}

// ── Bottom-bar info center ──────────────────────────────────────────────────
// The center of the bottom bar rotates through small pieces of context (fade
// transition between them): route/wild-seen/trainers-defeated out of battle,
// or hidden own-stats/rival-party-remaining/last-attack-taken in battle.
// Right after a battle ends (or a badge is won), a one-shot priority
// sequence plays once -- the just-updated trainers/wild-seen count first,
// then evolution/move-learn readiness if applicable -- before the ongoing
// rotation resumes.

private data class InfoSlide(val key: String, val durationMs: Long, val content: @Composable () -> Unit)

private data class PriorityKey(
    val battleEnd: Int,
    val badge: Int,
    val hasPokemon: Boolean,
    val lastAttack: Int,
)

@Composable
private fun InfoCenter(state: TrackerState) {
    val hasPokemon = (state.player?.pokemon?.id ?: 0) > 0
    val priorityKey = PriorityKey(state.battleEndEventId, state.badgeEventId, hasPokemon, state.enemy?.lastAttackEventId ?: 0)
    var seenPriorityKey by remember { mutableStateOf(priorityKey) }
    var priorityQueue by remember { mutableStateOf<List<InfoSlide>>(emptyList()) }

    LaunchedEffect(priorityKey) {
        if (priorityKey == seenPriorityKey) return@LaunchedEffect
        // Each "just happened" flag compares against the *previous* key,
        // captured before it gets overwritten below -- otherwise every one
        // of these would silently re-fire on every unrelated key change
        // (e.g. a new attack landing would re-queue an old badge popup,
        // since badgeEventIndex never resets back to 0 once set).
        val previous = seenPriorityKey
        // hasPokemon only ever goes false -> true once (picking your starter
        // in the lab), so this only fires the very first time it happens.
        val justGotPokemon = hasPokemon && !previous.hasPokemon
        val justGotAttacked = priorityKey.lastAttack != previous.lastAttack
        val justGotBadge = priorityKey.badge != previous.badge
        val justEndedBattle = priorityKey.battleEnd != previous.battleEnd
        seenPriorityKey = priorityKey
        val queue = mutableListOf<InfoSlide>()
        if (justGotAttacked) {
            state.enemy?.let { enemy ->
                if (enemy.lastDamage > 0) {
                    queue += InfoSlide("lastattack", 3000) {
                        InfoLine(enemy.lastMoveName.ifBlank { "Attack" }, "-${enemy.lastDamage} HP")
                    }
                }
            }
        }
        if (justGotPokemon) {
            state.player?.pokemon?.let { mon ->
                queue += InfoSlide("starter-typeitem", 3000) { StarterIntroTypeItemSlide(mon) }
                queue += InfoSlide("starter-stats", 3000) { StarterIntroStatsSlide(mon) }
                if (mon.moves.any { it.id != 0 }) {
                    queue += InfoSlide("starter-moves", 3000) { StarterIntroMovesSlide(mon) }
                }
            }
        }
        if (justGotBadge && state.badgeEventIndex in 1..8) {
            val badges = state.player?.badges ?: List(8) { false }
            queue += InfoSlide("badge", 3500) { BadgeCelebrationSlide(badges, state.badgeEventIndex) }
        }
        if (justEndedBattle) {
            state.location?.let { loc ->
                if (state.battleEndWasWild && loc.routeSeen > 0) {
                    queue += InfoSlide("wildseen-priority", 2200) { InfoLine("Wild seen", "${loc.routeSeen}/${loc.routeTotal}") }
                } else if (!state.battleEndWasWild && loc.trainersDefeated > 0) {
                    queue += InfoSlide("trainers-priority", 2200) { InfoLine("Trainers", "${loc.trainersDefeated}/${loc.trainersTotal}") }
                }
            }
        }
        if (justEndedBattle) {
            state.player?.pokemon?.let { lead ->
                if (lead.evolutionReady) {
                    queue += InfoSlide("evo", 2500) { InfoLine("Ready to evolve!", null, emphasize = true) }
                }
                // Not naming the move: in a real game you don't find out
                // which move it'll be until you actually level up, so
                // showing the name ahead of time would be a spoiler the same
                // way enemy data is.
                if (lead.nextMoveLevel > 0 && lead.nextMoveLevel == lead.level + 1) {
                    queue += InfoSlide("move", 2500) { InfoLine("Learns a new move next level!", null, emphasize = true) }
                }
            }
        }
        priorityQueue = queue
    }

    // Rendered *after* the state tracking above (not gated from the call
    // site) so InfoCenter stays mounted the whole time, including while the
    // starter pick is still showing pokeballs -- otherwise it would only
    // ever start existing right as hasPokemon becomes true, and could never
    // observe the "before" state needed to detect that transition.
    if (state.starterChoice in 1..3) {
        CompactStarterHint(state.starterChoice)
        return
    }

    val ongoingSlides = if (state.inBattle) {
        // Stats stay up much longer than the rival's remaining count -- the
        // last-attack popup isn't part of this rotation at all, it's a brief
        // one-shot triggered separately right when a hit lands (see above).
        buildList {
            state.player?.pokemon?.let { mon ->
                add(InfoSlide("battle-stats", 8000) { StatsLine(mon) })
                add(InfoSlide("battle-ability-item", 3500) { AbilityItemLine(mon) })
                if (mon.moves.any { it.id != 0 }) {
                    add(InfoSlide("battle-effectiveness", 4000) { MoveEffectivenessLine(mon) })
                }
                if (mon.defenses.weak4x.isNotEmpty() || mon.defenses.weak2x.isNotEmpty()) {
                    add(InfoSlide("battle-weakness", 3500) { WeaknessLine(mon.defenses) })
                }
            }
            state.enemy?.let { enemy ->
                if (enemy.weather.isNotBlank()) {
                    add(InfoSlide("battle-weather", 2500) {
                        InfoLine(enemy.weather, if (enemy.weatherTurns > 0) "${enemy.weatherTurns} turns left" else null, emphasize = true)
                    })
                }
                if (enemy.trainerPartyTotal > 0) {
                    add(InfoSlide("battle-party", 3000) { InfoLine("Rival", "${enemy.trainerPartyRemaining}/${enemy.trainerPartyTotal}") })
                }
                enemy.trainer?.let { trainer ->
                    if (trainer.levelRange.isNotBlank()) {
                        add(InfoSlide("battle-trainerintel", 3000) {
                            InfoLine("Lv${trainer.levelRange}", "${trainer.aiLabel} AI · IV ${trainer.avgIVs}")
                        })
                    }
                    if (trainer.items.isNotBlank()) {
                        add(InfoSlide("battle-traineritems", 3000) { InfoLine("Carries", trainer.items) })
                    }
                }
                if (enemy.pokemon?.wildEncounter == true && enemy.catchRatePercent > 0) {
                    if (enemy.catchRates.isNotEmpty()) {
                        add(InfoSlide("battle-catchrates", 3000) { CatchRatesLine(enemy.catchRates) })
                    } else {
                        add(InfoSlide("battle-catchrate", 2500) { InfoLine("Catch rate", "${enemy.catchRatePercent}%") })
                    }
                }
            }
        }
    } else {
        buildList {
            state.location?.let { loc ->
                if (loc.name.isNotBlank()) add(InfoSlide("route", 4000) { InfoLine(loc.name, null, emphasize = true) })
                // Only shown once at least one has actually happened here --
                // "0/4 wild seen" or "0/2 trainers" before you've found/beaten
                // any of them isn't useful information yet.
                if (loc.routeSeen > 0) add(InfoSlide("wildseen", 2200) { InfoLine("Seen", "${loc.routeSeen}/${loc.routeTotal}") })
                if (loc.trainersDefeated > 0) add(InfoSlide("trainers", 2200) { InfoLine("Trainers", "${loc.trainersDefeated}/${loc.trainersTotal}") })
                if (loc.repelStepsRemaining > 0) {
                    add(InfoSlide("repel", 2200) { InfoLine("Repel", "${loc.repelStepsRemaining} steps left") })
                }
            }
        }
    }

    val activeSlides = priorityQueue.ifEmpty { ongoingSlides }
    if (activeSlides.isEmpty()) return
    val slideKeys = activeSlides.map { it.key }

    var index by remember(slideKeys) { mutableStateOf(0) }
    LaunchedEffect(slideKeys, index) {
        delay(activeSlides[index % activeSlides.size].durationMs)
        if (priorityQueue.isNotEmpty()) {
            priorityQueue = priorityQueue.drop(1) // consumed once, not looped
        } else {
            index++
        }
    }

    Crossfade(targetState = index % activeSlides.size, label = "infoCenter") { i ->
        activeSlides.getOrNull(i)?.content?.invoke()
    }
}

@Composable
private fun InfoLine(primary: String, secondary: String?, emphasize: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            primary,
            style = if (emphasize) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (emphasize) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        )
        if (!secondary.isNullOrBlank()) {
            Text(
                secondary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, softWrap = false,
            )
        }
    }
}

@Composable
private fun AbilityItemLine(mon: PokemonState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            mon.abilityName,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1, softWrap = false,
        )
        Text(
            mon.heldItem.ifBlank { "No item" },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, softWrap = false,
        )
    }
}

@Composable
private fun StatsLine(mon: PokemonState) {
    val s = mon.stats
    val stages = mon.statStages
    val defaultColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Triple: (nature key, value, current in-battle stage 0-12/6=neutral)
        val entries = listOf(
            "Atk" to Triple("atk", s.atk, stages.atk), "Def" to Triple("def", s.def, stages.def),
            "SpA" to Triple("spa", s.spa, stages.spa), "SpD" to Triple("spd", s.spd, stages.spd),
            "Spe" to Triple("spe", s.spe, stages.spe),
        )
        entries.forEach { (label, entry) ->
            val (key, value, stage) = entry
            val color = when {
                key.isNotEmpty() && key == mon.positiveStat -> Color(0xFF4CAF50)
                key.isNotEmpty() && key == mon.negativeStat -> Color(0xFFF44336)
                else -> defaultColor
            }
            val stageDelta = stage - 6
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$label $value",
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = if (color != defaultColor) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1, softWrap = false,
                )
                // In-battle stage change (Growl, Swords Dance, ...) -- separate
                // from and on top of the nature coloring above, which is
                // permanent and unrelated to this battle's stage changes.
                if (stageDelta != 0) {
                    Text(
                        if (stageDelta > 0) "+$stageDelta" else "$stageDelta",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (stageDelta > 0) Color(0xFF4CAF50) else Color(0xFFF44336),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1, softWrap = false,
                    )
                }
            }
        }
        if (mon.bst > 0) {
            Text(
                "BST ${mon.bst}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1, softWrap = false,
            )
        }
    }
}

// Net type-effectiveness of each of your own moves against the enemy's
// current (now-visible) types, plus a STAB star -- matches DataHelper.lua's
// own Utils.netEffectiveness/isSTAB calc, done bridge-side per move.
@Composable
private fun MoveEffectivenessLine(mon: PokemonState) {
    val moves = mon.moves.filter { it.id != 0 }
    if (moves.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        moves.forEach { move ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TypeChip(move.type, compact = true)
                Text(
                    effectivenessLabel(move.effectiveness),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = effectivenessColor(move.effectiveness),
                    maxLines = 1, softWrap = false,
                )
                if (move.isStab) {
                    Text(
                        "★",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private fun effectivenessLabel(mult: Double): String = when {
    mult <= 0.0 -> "0×"
    mult < 0.5 -> "¼×"
    mult < 1.0 -> "½×"
    mult == 1.0 -> "1×"
    mult < 4.0 -> "2×"
    else -> "4×"
}

private fun effectivenessColor(mult: Double): Color = when {
    mult <= 0.0 -> Color(0xFF616161)
    mult < 1.0 -> Color(0xFFF44336)
    mult > 1.0 -> Color(0xFF4CAF50)
    else -> Color(0xFF9E9E9E)
}

// What CAN hurt your own active Pokémon, from PokemonData.getEffectiveness()
// -- the reverse direction of MoveEffectivenessLine above. Only the
// dangerous side (4x/2x) matters enough to show in a rotating bottom bar;
// resists/immunities are nice-to-know but not actionable mid-battle.
@Composable
private fun WeaknessLine(defenses: DefensesState) {
    if (defenses.weak4x.isEmpty() && defenses.weak2x.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Weak to",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        defenses.weak4x.forEach { type ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                TypeChip(type, compact = true)
                Text("4×", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFF44336))
            }
        }
        defenses.weak2x.forEach { type ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                TypeChip(type, compact = true)
                Text("2×", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFFF9800))
            }
        }
    }
}

// Catch rate for every ball type actually owned right now, best first --
// CatchRatesScreen.lua's per-ball breakdown, but only for balls in the bag.
@Composable
private fun CatchRatesLine(rates: List<CatchRateState>) {
    val sorted = rates.sortedByDescending { it.catchRate }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        sorted.forEach { ball ->
            val color = when {
                ball.catchRate >= 70 -> Color(0xFF4CAF50)
                ball.catchRate >= 35 -> Color(0xFFFFC107)
                else -> Color(0xFFF44336)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ball.name.removeSuffix(" Ball"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, softWrap = false,
                )
                Text(
                    "${ball.catchRate}%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = color,
                    maxLines = 1, softWrap = false,
                )
            }
        }
    }
}

// One-shot slides right after picking your starter in the lab.
@Composable
private fun StarterIntroTypeItemSlide(mon: PokemonState) {
    // Nature is left out here since it's already conveyed by the green/red
    // stat coloring on the next slide -- no need to name it twice.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        mon.types.distinct().forEach { TypeChip(it, compact = true) }
        Text(
            mon.abilityName,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1, softWrap = false,
        )
        Text(
            mon.heldItem.ifBlank { "No item" },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, softWrap = false,
        )
    }
}

@Composable
private fun StarterIntroStatsSlide(mon: PokemonState) {
    val defaultColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // "" for HP: nature never affects it, so it never matches
        // positiveStat/negativeStat and always stays the default color.
        val entries = listOf(
            "HP" to ("" to mon.stats.hp), "Atk" to ("atk" to mon.stats.atk), "Def" to ("def" to mon.stats.def),
            "SpA" to ("spa" to mon.stats.spa), "SpD" to ("spd" to mon.stats.spd), "Spe" to ("spe" to mon.stats.spe),
        )
        entries.forEach { (label, keyAndValue) ->
            val (key, value) = keyAndValue
            val color = when {
                key.isNotEmpty() && key == mon.positiveStat -> Color(0xFF4CAF50)
                key.isNotEmpty() && key == mon.negativeStat -> Color(0xFFF44336)
                else -> defaultColor
            }
            Text(
                "$label $value",
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = if (color != defaultColor) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1, softWrap = false,
            )
        }
        if (mon.bst > 0) {
            Text(
                "BST ${mon.bst}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1, softWrap = false,
            )
        }
    }
}

@Composable
private fun StarterIntroMovesSlide(mon: PokemonState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        mon.moves.filter { it.id != 0 }.forEach { move ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                TypeChip(move.type, compact = true)
                Text(
                    move.name,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun BadgeCelebrationSlide(obtained: List<Boolean>, justObtainedIndex: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(8) { i ->
            val isNew = (i + 1) == justObtainedIndex
            val scale by animateFloatAsState(
                targetValue = if (isNew) 1.35f else 1f,
                animationSpec = if (isNew) {
                    infiniteRepeatable(tween(450), RepeatMode.Reverse)
                } else {
                    snap()
                },
                label = "badgePulse",
            )
            val bitmap = rememberBadgeIcon(i, obtained.getOrElse(i) { false })
            if (bitmap != null) {
                Image(
                    bitmap,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp).scale(scale),
                    filterQuality = FilterQuality.None,
                )
            }
        }
    }
}

@Composable
private fun PhysicalGamepadBar(
    showTracker: Boolean,
    showTrackerToggle: Boolean,
    speedMultiplier: Int,
    trackerState: TrackerState?,
    onToggleTracker: () -> Unit,
    onSaveState: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (speedMultiplier != 1) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        "${speedMultiplier}x",
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            var justSaved by remember { mutableStateOf(false) }
            LaunchedEffect(justSaved) {
                if (justSaved) {
                    delay(1500)
                    justSaved = false
                }
            }
            IconButton(
                onClick = { onSaveState(); justSaved = true },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    if (justSaved) Icons.Default.Check else Icons.Default.Save,
                    contentDescription = "Save state",
                    tint = if (justSaved) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (trackerState != null) InfoCenter(trackerState)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showTrackerToggle) {
                IconButton(onClick = onToggleTracker, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (showTracker) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showTracker) "Hide tracker" else "Show tracker",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// Replaces the upstream tracker's GameOverScreen (hardcoded BizHawk-only, see
// the bridge's gameOver computation) with an Android-native equivalent:
// "Continue playing" just dismisses it (matches upstream); "New attempt" maps
// to our re-randomize reset, since there's no BizHawk-style mid-battle
// savestate to "retry the battle" from, and attempt logging/notes/log
// inspection aren't features this app has built.
@Composable
private fun GameOverDialog(
    won: Boolean,
    fainted: PokemonState?,
    quote: String,
    attemptNumber: Int,
    // Frozen at the moment the run ended (faint/win), not live-ticking --
    // the attempt is over, so this shouldn't keep climbing while the dialog
    // stays open or the player keeps exploring afterward.
    playTimeMs: Long,
    onContinue: () -> Unit,
    onNewAttempt: () -> Unit,
) {
    val totalSeconds = (playTimeMs / 1000).coerceAtLeast(0)
    val playTime = "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.85f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(8.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (won) "CONGRATULATIONS!!" else "GAME OVER",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (won) Color(0xFFFFC107) else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                Text("Attempt: $attemptNumber", style = MaterialTheme.typography.bodyMedium)
                Text("Play Time: $playTime", style = MaterialTheme.typography.bodyMedium)
                if (fainted != null && fainted.id != 0) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PokemonIcon(fainted.id, Modifier.size(28.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (won) "${fainted.name} · Lv${fainted.level}" else "${fainted.name} · Lv${fainted.level} fainted",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if (!won && quote.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        quote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                    Text("Continue playing")
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onNewAttempt, modifier = Modifier.fillMaxWidth()) {
                    Text("New attempt (re-randomize)")
                }
            }
        }
    }
}

// ── Sprite assets ────────────────────────────────────────────────────────────
// The tracker's bundled images/ aren't extracted to the device filesystem at
// runtime (trackerSkipPrefixes skips them -- Lua/the bridge never needs
// them), but they're still packaged as app assets and readable directly from
// there for display in Compose. Decoded bitmaps are cached process-wide since
// there are only ~414 possible Pokémon icons and 8 badges, all tiny PNGs.

private val pokemonIconCache = mutableMapOf<Int, ImageBitmap?>()
private val badgeIconCache = mutableMapOf<String, ImageBitmap?>()
private val typeIconCache = mutableMapOf<String, ImageBitmap?>()
private val statusIconCache = mutableMapOf<String, ImageBitmap?>()
private val itemIconCache = mutableMapOf<String, ImageBitmap?>()

private fun loadAssetBitmap(context: android.content.Context, assetPath: String): ImageBitmap? =
    runCatching {
        context.assets.open(assetPath).use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
    }.getOrNull()

@Composable
private fun rememberPokemonIcon(pokemonId: Int): ImageBitmap? {
    val context = LocalContext.current
    return remember(pokemonId) {
        pokemonIconCache.getOrPut(pokemonId) {
            if (pokemonId <= 0) null
            else loadAssetBitmap(context, "ironmon_tracker/ironmon_tracker/images/pokemonUpdated/$pokemonId.png")
        }
    }
}

// Only the FireRed/LeafGreen badge set is bundled here since that's the only
// game this app has been used with; RSE has its own "RSE_badge*" set in the
// same folder if a Ruby/Sapphire/Emerald ROM ever needs it.
@Composable
private fun rememberBadgeIcon(index: Int, obtained: Boolean): ImageBitmap? {
    val context = LocalContext.current
    val key = "FRLG_badge${index + 1}${if (obtained) "" else "_OFF"}"
    return remember(key) {
        badgeIconCache.getOrPut(key) {
            loadAssetBitmap(context, "ironmon_tracker/ironmon_tracker/images/badges/$key.png")
        }
    }
}

// Real in-game type/status pill sprites (30x12 / 16x8 px), same assets
// Drawing.drawTypeIcon()/drawStatusIcon() use -- filenames are the lowercase
// type name ("fire.png", ...) or the 3-letter status code ("SLP.png", ...).
@Composable
private fun rememberTypeIcon(type: String): ImageBitmap? {
    val context = LocalContext.current
    val key = type.lowercase()
    return remember(key) {
        if (key.isBlank()) null
        else typeIconCache.getOrPut(key) { loadAssetBitmap(context, "ironmon_tracker/ironmon_tracker/images/types/$key.png") }
    }
}

@Composable
private fun rememberStatusIcon(status: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(status) {
        if (status.isBlank()) null
        else statusIconCache.getOrPut(status) { loadAssetBitmap(context, "ironmon_tracker/ironmon_tracker/images/status/$status.png") }
    }
}

// Real bag-item sprites, same assets MiscData.getItemIcon()/Drawing.drawImage
// use -- filename is the item's "icon" field (e.g. "potion.png"), bare (no
// extension) in the bridge's JSON. Not every item has one (Poké Balls, for
// instance) -- callers fall back to text-only in that case.
@Composable
internal fun rememberItemIcon(icon: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(icon) {
        if (icon.isBlank()) null
        else itemIconCache.getOrPut(icon) { loadAssetBitmap(context, "ironmon_tracker/ironmon_tracker/images/icons/$icon.png") }
    }
}

@Composable
private fun PokemonIcon(pokemonId: Int, modifier: Modifier = Modifier.size(32.dp)) {
    val bitmap = rememberPokemonIcon(pokemonId)
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = modifier, filterQuality = FilterQuality.None)
    }
}

// ── Game canvas ───────────────────────────────────────────────────────────────

@Composable
private fun GameDisplay(frame: ImageBitmap?, modifier: Modifier) {
    Canvas(modifier = modifier.background(Color.Black)) {
        if (frame != null) {
            drawImage(
                image = frame,
                dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
            )
        }
    }
}

// ── Tracker overlay ───────────────────────────────────────────────────────────

@Composable
private fun TrackerOverlay(state: TrackerState?, onOpenBag: () -> Unit) {
    if (state == null) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            Alignment.Center,
        ) {
            Text("Loading tracker…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.starterChoice in 1..3) {
            StarterHintCard(state.starterChoice)
        }

        // Location + battle status row
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.inBattle) {
                    Surface(
                        color = MaterialTheme.colorScheme.error,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            "BATTLE",
                            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onError,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    state.location?.name.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            IconButton(onClick = onOpenBag, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Inventory2, contentDescription = "Bag", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Pokemon cards. In battle, only the opponent's card is shown — your
        // own Pokémon's name/level/HP is already visible in the GBA battle
        // screen itself, matching the reference (BizHawk) tracker's layout.
        if (state.inBattle) {
            PokemonCard(
                state.enemy?.pokemon,
                state.enemy?.trainer?.name?.let { "VS $it" } ?: "OPPONENT",
                Modifier.fillMaxWidth(),
                isBattleEnemy = true,
                trainerSlots = state.enemy?.trainer?.slots ?: emptyList(),
            )
        } else {
            state.player?.pokemon?.let {
                PokemonCard(
                    it, "", Modifier.fillMaxWidth(), showProgress = true,
                    healPercent = state.player.healPercent,
                    healCount = state.player.healCount,
                )
            }
        }

        // Full team strip, shown regardless of battle state (matches
        // upstream's TeamViewArea, a persistent area separate from the main
        // card above) -- lets you check the bench's HP/status without
        // switching to them in-game. Out of battle, the main card above is
        // always party slot 1 (TrackerAPI.getPlayerPokemon() defaults to it),
        // so that slot is dropped here to avoid showing the same Pokémon
        // twice; in battle the main card shows the ENEMY instead, so every
        // party slot here is new information.
        state.player?.party?.let { party ->
            val slots = if (state.inBattle) party else party.filterNot { it.slot == 1 }
            PartyStrip(slots)
        }

        if (!state.inBattle) {
            state.location?.let { RouteProgressCard(it) }
        }

        // Badges
        state.player?.badges?.let { BadgesRow(it) }
    }
}

@Composable
private fun PartyStrip(party: List<PartyMemberState>) {
    if (party.isEmpty()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        party.forEach { mon -> PartyMemberBox(mon, Modifier.weight(1f)) }
    }
}

@Composable
private fun PartyMemberBox(mon: PartyMemberState, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(
            Modifier.padding(3.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PokemonIcon(mon.id, Modifier.size(26.dp))
            if (mon.isEgg) {
                Text("Egg", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
            } else {
                Text(
                    "Lv${mon.level}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, softWrap = false,
                )
                // The raw status byte doesn't flip to "fainted" on its own --
                // same curHP<=0 check TeamViewArea.lua uses to override it.
                val displayStatus = if (mon.curHP <= 0) "FNT" else mon.status
                if (displayStatus.isNotBlank()) {
                    StatusBadge(displayStatus)
                }
                MiniHpBar(mon.curHP, mon.maxHP)
            }
        }
    }
}

@Composable
private fun MiniHpBar(current: Int, max: Int) {
    val frac = (current.toFloat() / max.coerceAtLeast(1)).coerceIn(0f, 1f)
    val color = when {
        frac > 0.5f  -> Color(0xFF4CAF50)
        frac > 0.25f -> Color(0xFFFFC107)
        else         -> Color(0xFFF44336)
    }
    Box(
        Modifier.fillMaxWidth().height(3.dp).padding(top = 1.dp).clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(color))
    }
}

@Composable
private fun StarterHintCard(choice: Int) {
    val label = when (choice) {
        1 -> "LEFT"
        2 -> "CENTER"
        else -> "RIGHT"
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(10.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                for (position in 1..3) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (position == choice) "▼" else " ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        PokeballIcon(highlighted = position == choice, size = 32.dp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Randomly chosen: $label",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PokeballIcon(highlighted: Boolean, size: androidx.compose.ui.unit.Dp) {
    val topColor = if (highlighted) Color(0xFFE3350D) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    Canvas(modifier = Modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        // Top half (red when highlighted, muted otherwise)
        drawArc(
            color = topColor,
            startAngle = 180f, sweepAngle = 180f, useCenter = true,
            topLeft = Offset(center.x - r, center.y - r),
            size = Size(r * 2, r * 2),
        )
        // Bottom half
        drawArc(
            color = Color.White,
            startAngle = 0f, sweepAngle = 180f, useCenter = true,
            topLeft = Offset(center.x - r, center.y - r),
            size = Size(r * 2, r * 2),
        )
        // Outline + center band
        drawCircle(color = Color.Black, radius = r, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = r * 0.12f))
        drawLine(Color.Black, Offset(center.x - r, center.y), Offset(center.x + r, center.y), strokeWidth = r * 0.18f)
        drawCircle(color = Color.White, radius = r * 0.32f, center = center)
        drawCircle(color = Color.Black, radius = r * 0.32f, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = r * 0.12f))
    }
}

@Composable
private fun RouteProgressCard(location: LocationState) {
    if (location.routeTotal == 0 && location.trainersTotal == 0) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (location.routeTotal > 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "Wild Pokémon seen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${location.routeSeen}/${location.routeTotal}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                ProgressBar(location.routeSeen, location.routeTotal, MaterialTheme.colorScheme.tertiary)
                if (location.routeSpecies.isNotEmpty()) {
                    var showSpecies by remember(location.mapId) { mutableStateOf(false) }
                    Text(
                        if (showSpecies) "Hide species ▲" else "Show species seen ▼",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.clickable { showSpecies = !showSpecies },
                    )
                    if (showSpecies) {
                        Text(
                            location.routeSpecies.joinToString(", ") { it.name },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (location.trainersTotal > 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "Trainers defeated",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${location.trainersDefeated}/${location.trainersTotal}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                ProgressBar(location.trainersDefeated, location.trainersTotal, MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ProgressBar(current: Int, total: Int, color: Color) {
    val frac = (current.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
    Box(
        Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(3.dp))
                .background(color),
        )
    }
}

@Composable
private fun PokemonCard(
    pokemon: PokemonState?,
    label: String,
    modifier: Modifier,
    showMoves: Boolean = true,
    showProgress: Boolean = false,
    showKnownHistory: Boolean = false,
    healPercent: Double = 0.0,
    healCount: Int = 0,
    isBattleEnemy: Boolean = false,
    trainerSlots: List<TrainerPartySlotState> = emptyList(),
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            if (label.isNotBlank()) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                )
            }
            if (pokemon == null || pokemon.id == 0) {
                Box(Modifier.fillMaxWidth().padding(12.dp), Alignment.Center) {
                    Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Column
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PokemonIcon(pokemon.id, Modifier.size(56.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (pokemon.nickname.isNotBlank() &&
                        !pokemon.nickname.equals(pokemon.name, ignoreCase = true)
                    ) pokemon.nickname else pokemon.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (pokemon.gender.isNotBlank()) {
                    Spacer(Modifier.width(2.dp))
                    GenderSymbol(pokemon.gender)
                }
                if (pokemon.statusCondition.isNotBlank()) {
                    Spacer(Modifier.width(4.dp))
                    StatusBadge(pokemon.statusCondition)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    "Lv${pokemon.level}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            StatStagesLine(pokemon.statStages)
            if (pokemon.gachaStars > 0) {
                Text(
                    "★".repeat(pokemon.gachaStars) + "☆".repeat(6 - pokemon.gachaStars),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFFC107),
                    maxLines = 1, softWrap = false,
                )
            }
            Row(
                Modifier.padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                pokemon.types.distinct().forEach { TypeChip(it) }
            }
            if (pokemon.heldItem.isNotBlank()) {
                Text(
                    pokemon.heldItem,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (isBattleEnemy) {
                Text(
                    if (pokemon.lastLevelSeen > 0) "Last seen Lv.${pokemon.lastLevelSeen}" else "New encounter",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AbilitySlotsText(pokemon.abilitySlots)
                Text(
                    "Seen (${if (pokemon.wildEncounter) "Wild" else "Trainer"}): ${pokemon.encounters}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (trainerSlots.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    TrainerPartyRow(trainerSlots)
                }
            } else {
                // Nature and base stats aren't visible for an opponent's Pokémon in
                // a real game (natureIndex < 0 is the bridge's "hidden" sentinel for
                // that case) — only show them when we're actually allowed to know them.
                val natureText = if (pokemon.natureIndex >= 0) {
                    "${NATURE_NAMES.getOrElse(pokemon.natureIndex) { "???" }} · ${pokemon.abilityName}"
                } else {
                    pokemon.abilityName
                }
                Text(
                    natureText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (pokemon.maxHP > 0) {
                Spacer(Modifier.height(4.dp))
                HpBar(pokemon.curHP, pokemon.maxHP, showExactValue = !isBattleEnemy)
            }
            // EXP is never shown for an opponent in a real game, only your
            // own party (totalExp comes back 0 for the enemy from the bridge).
            if (!isBattleEnemy && pokemon.totalExp > 0) {
                ExpBar(pokemon.currentExp, pokemon.totalExp)
            }
            if (pokemon.natureIndex >= 0) {
                Spacer(Modifier.height(4.dp))
                StatsRow(pokemon)
            } else if (isBattleEnemy && pokemon.bst > 0) {
                Spacer(Modifier.height(4.dp))
                BstRow(pokemon.bst)
            }
            if (isBattleEnemy && pokemon.knownMoves.any { it.id != 0 }) {
                Spacer(Modifier.height(4.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(Modifier.height(4.dp))
                // movesTotal (this species' level-up movepool size) can come
                // back 0 for some species/version combos -- still show the
                // moves table in that case, just without the "(Z)" total.
                val totalSuffix = if (pokemon.movesTotal > 0) " (${pokemon.movesTotal})" else ""
                Text(
                    "Moves ${pokemon.knownMoves.count { it.id != 0 }}/${pokemon.movesLearned}$totalSuffix",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                BattleMovesHeaderRow()
                pokemon.knownMoves.filter { it.id != 0 }.forEach { BattleKnownMoveRow(it) }
            }
            if (showProgress) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Heals: ${"%.0f".format(healPercent)}% HP ($healCount)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // pokemon.moves is the real, un-redacted current moveset -- never
            // safe to show for an opponent (that's exactly what knownMoves /
            // the isBattleEnemy block above exists to avoid leaking).
            if (showMoves && !isBattleEnemy && pokemon.moves.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(Modifier.height(4.dp))
                MovesHeaderRow()
                pokemon.moves.filter { it.id != 0 }.forEach { MoveRow(it) }
            }
            if (showProgress && pokemon.movesTotal > 0) {
                Spacer(Modifier.height(4.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(Modifier.height(4.dp))
                val nextText = if (pokemon.nextMoveLevel > 0) " (next @ Lv${pokemon.nextMoveLevel})" else ""
                Text(
                    "Moves learned: ${pokemon.movesLearned}/${pokemon.movesTotal}$nextText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showProgress && pokemon.evolution.isNotBlank()) {
                Text(
                    "Evolves: ${pokemon.evolution}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showKnownHistory && (pokemon.knownAbilities.isNotEmpty() || pokemon.knownMoves.isNotEmpty())) {
                Spacer(Modifier.height(4.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Known from past encounters",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                if (pokemon.knownAbilities.isNotEmpty()) {
                    Text(
                        pokemon.knownAbilities.distinct().joinToString(" / "),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                pokemon.knownMoves.filter { it.id != 0 }.forEach { KnownMoveRow(it) }
            }
        }
    }
}

@Composable
private fun HpBar(current: Int, max: Int, showExactValue: Boolean = true) {
    val frac = (current.toFloat() / max.coerceAtLeast(1)).coerceIn(0f, 1f)
    val color = when {
        frac > 0.5f  -> Color(0xFF4CAF50)
        frac > 0.25f -> Color(0xFFFFC107)
        else         -> Color(0xFFF44336)
    }
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
        Text("HP", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // The real game never shows the opponent's exact HP numbers, only a
        // proportional bar -- only your own party shows the "X/Y" fraction.
        if (showExactValue) {
            Text("$current/$max", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
    }
    Box(
        Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(3.dp))
                .background(color),
        )
    }
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun ExpBar(current: Int, total: Int) {
    val frac = (current.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
        Text("EXP", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$current/$total", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Box(
        Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF42A5F5)),
        )
    }
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun StatsRow(pokemon: PokemonState) {
    val s = pokemon.stats
    // Nature never affects HP, so it has no key to match against.
    val entries = listOf(
        Triple("HP", "", s.hp), Triple("Atk", "atk", s.atk), Triple("Def", "def", s.def),
        Triple("SpA", "spa", s.spa), Triple("SpD", "spd", s.spd), Triple("Spe", "spe", s.spe),
    )
    val maxVal = entries.maxOf { it.third }.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        entries.forEach { (lbl, key, v) ->
            val statColor = when {
                key.isNotEmpty() && key == pokemon.positiveStat -> Color(0xFF4CAF50)
                key.isNotEmpty() && key == pokemon.negativeStat -> Color(0xFFF44336)
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(14.dp)) {
                Text(lbl, Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall, color = statColor, maxLines = 1, softWrap = false)
                Text(v.toString(), Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = statColor, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
                Spacer(Modifier.width(4.dp))
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(v.toFloat() / maxVal).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(statColor.copy(alpha = 0.7f)))
                }
            }
        }
        if (pokemon.bst > 0) {
            BstRow(pokemon.bst)
        }
    }
}

@Composable
private fun BstRow(bst: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(14.dp).padding(top = 2.dp)) {
        Text("BST", Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        Text(bst.toString(), Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun AbilitySlotsText(slots: List<AbilitySlotState>) {
    val text = if (slots.isEmpty()) {
        buildAnnotatedString { append("?") }
    } else {
        buildAnnotatedString {
            slots.forEachIndexed { i, slot ->
                if (i > 0) append(" / ")
                if (slot.current) {
                    withStyle(SpanStyle(color = Color(0xFFFFC107), fontWeight = FontWeight.Bold)) {
                        append(slot.name)
                    }
                } else {
                    append(slot.name)
                }
            }
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

// One slot per trainer party member: a Poké Ball placeholder + level until
// that slot has fainted this battle (or you've already beaten this trainer
// before), then the real species icon -- exactly TrainerInfoScreen.lua's own
// getIconId() reveal gating, done in the bridge instead of here.
@Composable
private fun TrainerPartyRow(slots: List<TrainerPartySlotState>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        slots.forEach { slot ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    if (slot.id > 0) {
                        PokemonIcon(slot.id, Modifier.size(20.dp))
                    } else {
                        PokeballIcon(highlighted = false, size = 14.dp)
                    }
                }
                Text(
                    if (slot.level > 0) "${slot.level}" else "?",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun MovesHeaderRow() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(28.dp)) // roughly the width of a compact TypeChip + gap
        Text("Move", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
        Text("Pow", Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
        Text("Acc", Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
        Text("PP", Modifier.width(46.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
    }
}

@Composable
private fun MoveRow(move: MoveState) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TypeChip(move.type, compact = true)
        CategoryBadge(move.category)
        Spacer(Modifier.width(4.dp))
        Text(move.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(formatPower(move.power), Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
        Text(move.accuracy.ifBlank { "-" }, Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
        Text("${move.pp}/${move.maxPp}", Modifier.width(46.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
    }
}

// Power is usually numeric but can be a sentinel like ">HP" (Eruption, Water
// Spout, ...) or "0" for status moves / not-yet-revealed slots.
private fun formatPower(power: String): String = if (power.isBlank() || power == "0") "-" else power

@Composable
private fun KnownMoveRow(move: KnownMoveState) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TypeChip(move.type, compact = true)
        CategoryBadge(move.category)
        Spacer(Modifier.width(4.dp))
        Text(move.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (move.power != "0") Text(formatPower(move.power), Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
        if (move.accuracy.isNotBlank()) Text(move.accuracy, Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
        if (move.level > 0) Text("Lv${move.level}", Modifier.width(36.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
    }
}

// Dedicated header/row for the battle-enemy card's revealed-moves table,
// matching the reference tracker's "PP  Pow  Acc" column order (not the same
// order as the player's own overworld moves table).
@Composable
private fun BattleMovesHeaderRow() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(28.dp))
        Text("Move", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
        Text("PP", Modifier.width(42.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
        Text("Pow", Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
        Text("Acc", Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
    }
}

@Composable
private fun BattleKnownMoveRow(move: KnownMoveState) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TypeChip(move.type, compact = true)
        CategoryBadge(move.category)
        Spacer(Modifier.width(4.dp))
        Text(move.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            if (move.maxPp > 0) "${move.pp}/${move.maxPp}" else "-",
            Modifier.width(42.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1, softWrap = false,
        )
        Text(formatPower(move.power), Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
        Text(move.accuracy.ifBlank { "-" }, Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun GenderSymbol(gender: String) {
    val (symbol, color) = when (gender) {
        "M" -> "♂" to Color(0xFF3498DB)
        "F" -> "♀" to Color(0xFFE91E8C)
        else -> return
    }
    Text(symbol, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
}

private fun statusColor(status: String): Color = when (status) {
    "SLP" -> Color(0xFF9E9E9E)
    "PSN" -> Color(0xFFA040A0)
    "BRN" -> Color(0xFFF08030)
    "PAR" -> Color(0xFFF8D030)
    "FRZ" -> Color(0xFF98D8D8)
    "FNT" -> Color(0xFF707070)
    else -> Color(0xFF9E9E9E)
}

@Composable
private fun StatusBadge(status: String) {
    val bitmap = rememberStatusIcon(status)
    if (bitmap != null) {
        // Real in-game status pill sprite (16x8) instead of a hand-drawn label.
        Image(
            bitmap,
            contentDescription = status,
            modifier = Modifier.height(10.dp).aspectRatio(16f / 8f),
            filterQuality = FilterQuality.None,
        )
        return
    }
    val color = statusColor(status)
    Box(
        Modifier.clip(RoundedCornerShape(3.dp)).background(color).padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(status, style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 9.sp)
    }
}

// In-battle stat stage boosts/drops (Growl, Swords Dance, ...) -- 6 is
// neutral in the game's own encoding, so only non-neutral stats are shown.
@Composable
private fun StatStagesLine(stages: StatStagesState) {
    val entries = listOf(
        "Atk" to stages.atk, "Def" to stages.def, "SpA" to stages.spa,
        "SpD" to stages.spd, "Spe" to stages.spe, "Acc" to stages.acc, "Eva" to stages.eva,
    ).filter { it.second != 6 }
    if (entries.isEmpty()) return
    Row(
        Modifier.padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        entries.forEach { (label, stage) ->
            val delta = stage - 6
            val color = if (delta > 0) Color(0xFF4CAF50) else Color(0xFFF44336)
            Text(
                "$label ${if (delta > 0) "+" else ""}$delta",
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Bold,
                maxLines = 1, softWrap = false,
            )
        }
    }
}

// Physical/special split is by-type in Gen 1-3 (not per-move like Gen 4+),
// but MoveData already has it precomputed -- this just renders it. Nothing
// shown for an unrevealed/unknown slot.
@Composable
private fun CategoryBadge(category: String) {
    val (icon, color) = when (category) {
        "Physical" -> Icons.Default.SportsMma to Color(0xFFC0392B)
        "Special" -> Icons.Default.RemoveRedEye to Color(0xFF2980B9)
        // Status moves don't deal direct damage (stat changes, conditions,
        // ...) -- a sparkle reads as "effect" rather than a hit.
        "Status" -> Icons.Default.AutoAwesome to Color(0xFF9E9E9E)
        else -> return
    }
    Spacer(Modifier.width(3.dp))
    Box(
        Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(color.copy(alpha = 0.25f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = category, tint = color, modifier = Modifier.size(10.dp))
    }
}

@Composable
private fun TypeChip(type: String, compact: Boolean = false) {
    val bitmap = rememberTypeIcon(type)
    if (bitmap != null) {
        // Real in-game pill sprite (30x12) instead of a hand-drawn label.
        Image(
            bitmap,
            contentDescription = type,
            modifier = Modifier.height(if (compact) 10.dp else 12.dp).aspectRatio(30f / 12f),
            filterQuality = FilterQuality.None,
        )
        return
    }
    // Fallback if the asset is ever missing/fails to decode.
    val color = typeColor(type)
    val label = type.lowercase().replaceFirstChar { it.uppercase() }.take(if (compact) 3 else 10)
    Box(
        Modifier.clip(RoundedCornerShape(3.dp)).background(color.copy(alpha = 0.2f)).padding(horizontal = if (compact) 3.dp else 5.dp, vertical = 1.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold, fontSize = if (compact) 8.sp else 10.sp)
    }
}

@Composable
private fun BadgesRow(badges: List<Boolean>) {
    Column {
        Text(
            "Badges (${badges.count { it }}/8)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            repeat(8) { i ->
                val obtained = badges.getOrElse(i) { false }
                val bitmap = rememberBadgeIcon(i, obtained)
                if (bitmap != null) {
                    Image(
                        bitmap,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        filterQuality = FilterQuality.None,
                    )
                } else {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
                }
            }
        }
    }
}

// ── GBA Controls ─────────────────────────────────────────────────────────────

@Composable
private fun GbaControls(
    onKeysChanged: (Int) -> Unit,
    showTracker: Boolean,
    onToggleTracker: () -> Unit,
) {
    var heldKeys by remember { mutableIntStateOf(0) }

    fun press(bit: Int) { heldKeys = heldKeys or bit; onKeysChanged(heldKeys) }
    fun release(bit: Int) { heldKeys = heldKeys and bit.inv(); onKeysChanged(heldKeys) }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).height(100.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // D-pad
        Box(Modifier.size(90.dp)) {
            // Up
            GbaButton("▲", Modifier.align(Alignment.TopCenter).size(28.dp), EmulatorEngine.Keys.UP, ::press, ::release)
            // Down
            GbaButton("▼", Modifier.align(Alignment.BottomCenter).size(28.dp), EmulatorEngine.Keys.DOWN, ::press, ::release)
            // Left
            GbaButton("◀", Modifier.align(Alignment.CenterStart).size(28.dp), EmulatorEngine.Keys.LEFT, ::press, ::release)
            // Right
            GbaButton("▶", Modifier.align(Alignment.CenterEnd).size(28.dp), EmulatorEngine.Keys.RIGHT, ::press, ::release)
        }

        // Select / Start
        Column(
            Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GbaButton("SEL", Modifier.size(40.dp, 22.dp), EmulatorEngine.Keys.SELECT, ::press, ::release)
                GbaButton("STA", Modifier.size(40.dp, 22.dp), EmulatorEngine.Keys.START, ::press, ::release)
            }
            // L / R shoulder buttons
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GbaButton("L", Modifier.size(36.dp, 22.dp), EmulatorEngine.Keys.L, ::press, ::release)
                GbaButton("R", Modifier.size(36.dp, 22.dp), EmulatorEngine.Keys.R, ::press, ::release)
            }
            TextButton(onClick = onToggleTracker, modifier = Modifier.height(24.dp)) {
                Text(if (showTracker) "Hide tracker" else "Tracker", fontSize = 10.sp)
            }
        }

        // A / B buttons
        Box(Modifier.size(80.dp)) {
            GbaButton("A", Modifier.align(Alignment.CenterEnd).size(36.dp), EmulatorEngine.Keys.A, ::press, ::release)
            GbaButton("B", Modifier.align(Alignment.CenterStart).size(36.dp), EmulatorEngine.Keys.B, ::press, ::release)
        }
    }
}

@Composable
private fun GbaButton(
    label: String,
    modifier: Modifier,
    keyBit: Int,
    onPress: (Int) -> Unit,
    onRelease: (Int) -> Unit,
) {
    Box(
        modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(keyBit) {
                detectTapGestures(
                    onPress = { _ ->
                        onPress(keyBit)
                        tryAwaitRelease()
                        onRelease(keyBit)
                    },
                )
            },
        Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 9.sp)
    }
}
