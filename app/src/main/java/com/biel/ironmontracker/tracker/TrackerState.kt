package com.biel.ironmontracker.tracker

// Mirrors the JSON structure written by IronmonAndroidBridge.lua
data class TrackerState(
    val version: Int = 1,
    // "FRLG" or "RSE" -- which badge icon set to use (RS and Emerald share
    // one upstream sprite set, distinct from FireRed/LeafGreen's).
    val gameVersion: String = "FRLG",
    val inBattle: Boolean = false,
    val starterChoice: Int = 0, // 1=left, 2=center, 3=right; 0 = not applicable right now
    // True once the lead (party slot 1) Pokémon's curHP hits 0 -- the upstream
    // tracker's own GameOverScreen is hardcoded BizHawk-only, so this is
    // computed independently in the bridge instead.
    val gameOver: Boolean = false,
    val gameOverQuote: String = "",
    // True once you've beaten the game's final/champion trainer (same
    // condition upstream's GameOverScreen uses for its "CONGRATULATIONS!!"
    // message, also BizHawk-only there and reimplemented independently).
    val gameWon: Boolean = false,
    // Rising-edge event counters for the bottom-bar info center: the UI
    // watches these ids change (not the plain values) to know exactly when
    // to inject a fresh one-shot priority slide -- a badge just obtained, or
    // a battle that just ended (and whether it was wild or a trainer fight).
    val badgeEventId: Int = 0,
    val badgeEventIndex: Int = 0,
    val battleEndEventId: Int = 0,
    val battleEndWasWild: Boolean = false,
    val player: PlayerState? = null,
    val enemy: EnemyState? = null,
    val location: LocationState? = null,
)

data class PlayerState(
    val pokemon: PokemonState? = null,
    val badges: List<Boolean> = List(8) { false },
    val healPercent: Double = 0.0,
    val healCount: Int = 0,
    val bagItems: BagItemsState = BagItemsState(),
    // Always all 6 party slots (as many as are filled), regardless of which
    // one is currently active/battling -- distinct from `pokemon` above,
    // which is only ever that single currently-viewed slot (with much more
    // detail: moves, ability, nature, ...).
    val party: List<PartyMemberState> = emptyList(),
)

data class PartyMemberState(
    val slot: Int = 0,
    val id: Int = 0,
    val name: String = "???",
    val nickname: String = "",
    val level: Int = 0,
    val curHP: Int = 0,
    val maxHP: Int = 0,
    val status: String = "",
    val types: List<String> = emptyList(),
    // The game itself hides an egg's contents until it hatches -- every
    // other field above is left at its default/zeroed when this is true.
    val isEgg: Boolean = false,
)

// A raw bag-pocket scan, categorized the same way the reference tracker's
// "Heals in Bag" screen does -- an item can land in more than one category
// (Full Restore is both a full HP heal and cures any status).
data class BagItemsState(
    val hpHeals: List<BagItemState> = emptyList(),
    val ppHeals: List<BagItemState> = emptyList(),
    val statusHeals: List<BagItemState> = emptyList(),
    val pokeBalls: List<BagItemState> = emptyList(),
    val evoStones: List<BagItemState> = emptyList(),
    val other: List<BagItemState> = emptyList(),
)

data class BagItemState(
    val id: Int = 0,
    val name: String = "???",
    // Asset filename (no extension) under images/icons/, or "" if the
    // reference tracker has no icon for this item either (e.g. Poké Balls).
    val icon: String = "",
    val quantity: Int = 0,
    // True if this item would meaningfully help your lead Pokémon right now
    // (would heal back >= 2/3 of its missing HP, cures its current status,
    // one of its moves is out of PP, ...) -- same highlighting as upstream.
    val isHelpful: Boolean = false,
)

data class EnemyState(
    val pokemon: PokemonState? = null,
    val trainer: TrainerState? = null,
    // Trainer battles only (0/0 for wild encounters): how many of the
    // trainer's Pokémon are still standing, out of how many they have total.
    val trainerPartyRemaining: Int = 0,
    val trainerPartyTotal: Int = 0,
    // The enemy's most recent move against you this battle and how much
    // damage it dealt (Battle.lastEnemyMoveId/damageReceived).
    val lastMoveName: String = "",
    val lastDamage: Int = 0,
    // Rising-edge counter: increments once per new attack (not per poll), so
    // the UI can show it as a brief popup right after it lands instead of a
    // permanent rotation item.
    val lastAttackEventId: Int = 0,
    // Wild encounters only (0 for trainer battles): catch rate with a
    // standard Poké Ball at the enemy's current HP/status, 0-100.
    val catchRatePercent: Int = 0,
    // Wild encounters only: same calc, but for every ball type actually in
    // the bag right now (CatchRatesScreen.lua's per-ball breakdown).
    val catchRates: List<CatchRateState> = emptyList(),
    // "" when no weather is active. Only meaningful while inBattle.
    val weather: String = "",
    // 0 for permanent/no weather or a variant that doesn't expire on its own.
    val weatherTurns: Int = 0,
)

data class CatchRateState(
    val id: Int = 0,
    val name: String = "???",
    val quantity: Int = 0,
    val catchRate: Int = 0,
)

data class PokemonState(
    val id: Int = 0,
    val name: String = "???",
    val nickname: String = "",
    val level: Int = 0,
    val curHP: Int = 0,
    val maxHP: Int = 0,
    val natureIndex: Int = 0,
    val isShiny: Boolean = false,
    val abilityName: String = "???",
    val heldItem: String = "",
    val types: List<String> = emptyList(),
    val stats: Stats = Stats(),
    val bst: Int = 0,
    val positiveStat: String = "",
    val negativeStat: String = "",
    val gachaStars: Int = 0,
    val moves: List<MoveState> = emptyList(),
    // Aggregated from every past encounter of this species this run — distinct
    // from `moves`/`abilityName` above, which are this specific instance's
    // real, currently-visible data.
    val knownMoves: List<KnownMoveState> = emptyList(),
    val knownAbilities: List<String> = emptyList(),
    // Level-up moveset progress and evolution requirement (own Pokémon, out of battle).
    val movesLearned: Int = 0,
    val movesTotal: Int = 0,
    val nextMoveLevel: Int = 0,
    val evolution: String = "",
    // True if one level from a level-up evolution, friendship is already
    // high enough, or the required stone/item is already in the bag.
    val evolutionReady: Boolean = false,
    // Enemy-in-battle-only fields: the species' two possible abilities, each
    // shown by name only once revealed this run, with the one confirmed to be
    // this exact instance's actual ability flagged separately for highlighting.
    val abilitySlots: List<AbilitySlotState> = emptyList(),
    val encounters: Int = 0,
    val wildEncounter: Boolean = false,
    val lastLevelSeen: Int = 0,
    // Status condition (SLP/PSN/BRN/PAR/FRZ) and in-battle stat stage boosts
    // (Growl, Swords Dance, ...) -- both publicly visible in a real game for
    // either side, unlike exact stats/nature/ability/held item, so these are
    // sent for the enemy too.
    val statusCondition: String = "",
    val statStages: StatStagesState = StatStagesState(),
    // "M"/"F"/"" (genderless or unknown) -- always visible in-game, sent for
    // both sides.
    val gender: String = "",
    // Own Pokémon only (0/0 for the enemy, where EXP is never shown in-game).
    val currentExp: Int = 0,
    val totalExp: Int = 0,
    // Species-level type matchup -- always public (types are visible for
    // either side), unlike the per-individual fields above.
    val defenses: DefensesState = DefensesState(),
)

data class DefensesState(
    val weak4x: List<String> = emptyList(),
    val weak2x: List<String> = emptyList(),
    val resist2x: List<String> = emptyList(),
    val resist4x: List<String> = emptyList(),
    val immune: List<String> = emptyList(),
)

data class StatStagesState(
    val atk: Int = 6, val def: Int = 6, val spa: Int = 6,
    val spd: Int = 6, val spe: Int = 6, val acc: Int = 6, val eva: Int = 6,
)

data class AbilitySlotState(
    val name: String = "?",
    val revealed: Boolean = false,
    val current: Boolean = false,
)

data class Stats(
    val hp: Int = 0, val atk: Int = 0, val def: Int = 0,
    val spa: Int = 0, val spd: Int = 0, val spe: Int = 0,
)

data class MoveState(
    val id: Int = 0, val name: String = "---", val type: String = "",
    // Usually numeric, but some moves (Eruption, Water Spout, ...) report a
    // non-numeric power like ">HP" -- same reason accuracy is a String too.
    val power: String = "0", val accuracy: String = "", val pp: Int = 0, val maxPp: Int = 0,
    // "Physical"/"Special"/"Status" -- Gen 1-3 determine this by type, not
    // per-move, but MoveData.Moves already has it precomputed either way.
    val category: String = "",
    // Own moves, in battle only (0.0 otherwise): net type-effectiveness
    // multiplier against the enemy's current types (0/0.25/0.5/1/2/4).
    val effectiveness: Double = 1.0,
    val isStab: Boolean = false,
)

data class KnownMoveState(
    val id: Int = 0, val name: String = "---", val type: String = "",
    val power: String = "0", val accuracy: String = "", val level: Int = 0,
    // pp: current remaining (real, live-decrementing for the current battle
    // enemy's already-revealed moves; falls back to maxPp otherwise).
    val pp: Int = 0, val maxPp: Int = 0,
    val category: String = "",
)

data class TrainerState(
    val id: Int = 0,
    val name: String = "???",
    // Trainer-wide metadata about the opponent you're CURRENTLY fighting --
    // never a preview of a trainer you haven't engaged yet. "" / 0 /
    // emptyList() when not in a trainer battle.
    val levelRange: String = "",
    val avgIVs: Int = 0,
    val aiLabel: String = "",
    val items: String = "",
    // One entry per party slot (always `level`/`hasItem`), but `id` is only
    // the real species once that slot has fainted this battle or you've
    // already beaten this trainer before -- 0 otherwise.
    val slots: List<TrainerPartySlotState> = emptyList(),
)

data class TrainerPartySlotState(
    val level: Int = 0,
    val hasItem: Boolean = false,
    val id: Int = 0,
)

data class LocationState(
    val mapId: Int = 0,
    val name: String = "",
    val routeSeen: Int = 0,
    val routeTotal: Int = 0,
    val routeSpecies: List<RouteSpeciesState> = emptyList(),
    val trainersDefeated: Int = 0,
    val trainersTotal: Int = 0,
    // 0 when no Repel/Super Repel/Max Repel is currently active.
    val repelStepsRemaining: Int = 0,
)

data class RouteSpeciesState(val id: Int = 0, val name: String = "???")
