-- IronmonAndroidBridge.lua
-- Loaded directly by the IronmonTracker Android app after Ironmon-Tracker.lua.
-- Uses mGBA's callbacks:add("frame", ...) to hook into the frame loop.
-- Writes game state JSON to _IRONMON_FILES_DIR/ironmon_state.json every ~0.5s.

local stateFile  = (_IRONMON_FILES_DIR or ".") .. "/ironmon_state.json"
local UPDATE_EVERY = 30   -- frames between writes (~0.5 s at 60 fps)
local frameCount = 0

-- Which starter to pick this run (1=left, 2=center, 3=right), decided once
-- per fresh script load. A randomized reset reloads this whole script (a new
-- Lua VM, freshly seeded by Main.Initialize()'s math.randomseed(os.time()...)),
-- so this is naturally a new random pick every reset.
local starterChoice = math.random(1, 3)

-- GameOverScreen.checkForGameOver() (the upstream tracker's own "lead
-- Pokémon fainted" screen) is hardcoded to `if not Main.IsOnBizhawk() then
-- return false end` -- entirely disabled outside BizHawk, since the rest of
-- that screen relies on BizHawk-only APIs (memorysavestate.savecorestate(),
-- etc). Detected independently here using the same condition it uses
-- (GameOverScreen.LossConditions.LeadPokemonFaints). The flavor-text quote
-- is only re-rolled on the rising edge into game-over, not every poll, so it
-- doesn't flicker while the state persists.
local lastGameOverQuote = ""
local wasGameOver = false

-- Rising-edge event counters for the Android UI's info-center rotation: it
-- watches these ids change (rather than a plain boolean) to know exactly
-- when to inject a fresh one-shot priority slide, even if the underlying
-- condition was already true on a previous poll.
local prevBadges = { false, false, false, false, false, false, false, false }
local badgeEventId = 0
local badgeEventIndex = 0

local wasInBattle = false
local lastKnownWildEncounter = false
local battleEndEventId = 0
local battleEndWasWild = false

-- Fires once per new enemy attack (move id or accumulated damage changing),
-- so the Android UI can show it as a brief popup right after it lands
-- instead of a permanent rotation item that'd otherwise keep re-showing the
-- same old hit for the rest of the battle.
local prevAttackSignature = ""
local lastAttackEventId = 0

-- If Program.mainLoop() (the tracker's own per-frame update, called by
-- MGBA.setupActiveRunCallbacks() before this script even loads) throws an
-- uncaught error partway through, the whole frame callback aborts for that
-- frame -- including Program.stepFrames() at the very end, which is what
-- advances Program.Frames.highAccuracyUpdate/lowAccuracyUpdate/three_sec_update.
-- If the SAME error recurs every frame (a persistent bad game/bag state, not a
-- one-off), those counters get stuck at whatever value they were on, and
-- every update gated behind "counter == 0" (map location, battle detection,
-- bag items, badges, ...) silently freezes forever -- exactly what "tracker
-- stuck on the same screen" looks like from the outside. Replace the tracker's
-- own frame callback with a pcall-wrapped version: it logs the real error
-- (instead of it vanishing into mGBA's callback dispatcher) and keeps stepping
-- the frame counters even when the wrapped call fails, so a transient/
-- recurring error in one part of the update doesn't permanently stall
-- everything else.
if callbacks and callbacks.remove and Main and Main.frameCallbackId then
	local originalMainLoop = Program.mainLoop
	callbacks:remove(Main.frameCallbackId)
	Main.frameCallbackId = callbacks:add("frame", function()
		local ok, err = pcall(originalMainLoop)
		if not ok then
			console.log("[MainLoopError] " .. tostring(err))
			pcall(Program.stepFrames)
		end
	end)
end

-- Root cause of the above: Network.isConnected() does
-- "Network.CurrentConnection.State > Network.ConnectionState.Closed", but
-- CurrentConnection:new() never sets an initial .State, so it's nil until the
-- first real connection attempt sets it via updateConnectionState() -- which
-- never happens here, since the StreamerBot/Twitch integration this guards is
-- inapplicable to a standalone Android app anyway. Make it permanently
-- (and correctly) report "not connected" instead of crashing every frame.
if Network then
	Network.isConnected = function() return false end
end

-- ── Helpers ──────────────────────────────────────────────────────────────────

-- GachaMon star rating for a Pokémon. We deliberately do NOT call
-- GachaMonData.convertPokemonToGachaMon() -- besides building a collectible
-- card, it re-rolls a ~0.47% shiny chance every time it's called, which would
-- effectively guarantee a false "shiny" flag if called every ~0.5s poll tick.
-- Instead build a minimal IGachaMon by hand with the real Temp fields already
-- set, so its getStats()/getNature()/etc. just return them directly (see
-- their `if self.Temp.X == nil` guards) without touching compressed/random
-- fields at all, then call the two pure rating functions ourselves.
local function getGachaStars(p, pokeInfo)
	local ok, stars = pcall(function()
		local moveIds = {}
		for _, m in ipairs(p.moves or {}) do
			table.insert(moveIds, m.id or 0)
		end
		local gachamon = GachaMonData.IGachaMon:new({
			Personality = p.personality or 0,
			PokemonId   = p.pokemonID or 0,
			Level       = p.level or 0,
			SeedNumber  = Main.currentSeed or 0,
			AbilityId   = TrackerAPI.getAbilityIdOfPokemon(p),
			Temp = {
				Stats   = p.stats or {},
				IsShiny = p.isShiny and 1 or 0,
				Nature  = p.nature or 0,
				MoveIds = moveIds,
			},
		})
		gachamon.RatingScore = GachaMonData.calculateRatingScore(gachamon, pokeInfo.baseStats or {})
		return GachaMonData.calculateStars(gachamon)
	end)
	return ok and stars or 0
end

local function safeEncode(t)
	local ok, result = pcall(function()
		return FileManager.JsonLibrary.encode(t)
	end)
	return ok and result or nil
end

-- Moves/abilities tracked from ALL past encounters of this species this run
-- (Tracker.getMoves/getAbilities), as opposed to `moves`/`abilityName` below
-- which reflect only THIS specific Pokémon instance's real, current data.
-- `realPpById`, when given (the current battle's enemy only -- see caller),
-- overlays the real, live-decrementing current PP for whichever tracked
-- moves are ALSO part of this exact instance's actual moveset -- fair, since
-- those moves are already revealed/known either way, just showing PP is
-- stale otherwise. Moves not in realPpById (not part of this instance, or
-- outside battle) fall back to the move's static max PP as before.
local function getKnownMovesTable(pokemonID, realPpById)
	local known = {}
	for _, m in ipairs(Tracker.getMoves(pokemonID) or {}) do
		local mId = m.id or 0
		if mId > 0 then
			local mInfo = TrackerAPI.getMoveInfo(mId) or {}
			local maxPp = mInfo.pp or 0
			table.insert(known, {
				id    = mId,
				name  = mInfo.name  or "???",
				type  = tostring(mInfo.type or ""),
				power = mInfo.power or 0,
				accuracy = tostring(mInfo.accuracy or ""),
				level = m.level or 0,
				pp    = (realPpById and realPpById[mId]) or maxPp,
				maxPp = maxPp,
				category = mInfo.category or "",
			})
		end
	end
	return known
end

local function getKnownAbilitiesTable(pokemonID)
	local names = {}
	for _, a in ipairs(Tracker.getAbilities(pokemonID) or {}) do
		local aId = a.id or 0
		if aId > 0 then
			local aInfo = TrackerAPI.getAbilityInfo(aId) or {}
			table.insert(names, aInfo.name or "???")
		end
	end
	return names
end

-- Level-up moves learned so far / total in its movepool / next level a new
-- move is learned. Only the levels are static (randomizer-safe structure);
-- which move ends up at each level is still whatever gets tracked live.
local function getMoveProgress(pokemonID, level)
	if not PokemonData.isValid(pokemonID) or not level then return 0, 0, 0 end
	local allLevels = (PokemonData.Pokemon[pokemonID].movelvls or {})[GameSettings.versiongroup] or {}
	local learned, nextLevel = 0, 0
	for _, lv in ipairs(allLevels) do
		if lv <= level then
			learned = learned + 1
		elseif nextLevel == 0 then
			nextLevel = lv
		end
	end
	return learned, #allLevels, nextLevel
end

-- Direct bag-pocket scan for whether the player holds any of the given item
-- ids (e.g. evolution stones) -- Program.updateBagItems()/Program.GameData
-- .Items has turned out unreliable in this headless environment this
-- session (see the [HealsDebug] investigation), so this reads the same
-- memory directly rather than trusting that cache.
local function hasAnyItemInBag(itemIds)
	if not itemIds or #itemIds == 0 then return false end
	local wanted = {}
	for _, id in ipairs(itemIds) do wanted[id] = true end

	local key = Utils.getEncryptionKey(2)
	local saveBlock1Addr = Utils.getSaveBlock1Addr()
	for i = 0, GameSettings.bagPocket_Items_Size - 1, 1 do
		local addr = saveBlock1Addr + GameSettings.bagPocket_Items_offset + i * 4
		local itemidAndQuantity = Memory.readdword(addr)
		local itemID = Utils.getbits(itemidAndQuantity, 0, 16)
		if wanted[itemID] then
			local quantity = Utils.getbits(itemidAndQuantity, 16, 16)
			if key ~= nil then quantity = Utils.bit_xor(quantity, key) end
			if quantity > 0 then return true end
		end
	end
	return false
end

-- Direct bag-pocket scan (same addresses/decryption as Program.updateBagItems(),
-- but bypassing it entirely -- see hasAnyItemInBag above, the same unreliable-
-- cache problem) returning a flat itemID -> quantity map across every pocket
-- that can hold a healing/held item (TM/HM pocket isn't scanned, matching
-- upstream's own skip of it).
local function scanBagItems()
	local items = {}
	local key = Utils.getEncryptionKey(2)
	local saveBlock1Addr = Utils.getSaveBlock1Addr()
	local pockets = {
		{ offset = GameSettings.bagPocket_Items_offset,   size = GameSettings.bagPocket_Items_Size },
		{ offset = GameSettings.bagPocket_Berries_offset, size = GameSettings.bagPocket_Berries_Size },
		{ offset = GameSettings.bagPocket_Balls_offset,   size = GameSettings.bagPocket_Balls_Size },
	}
	for _, pocket in ipairs(pockets) do
		for i = 0, pocket.size - 1, 1 do
			local addr = saveBlock1Addr + pocket.offset + i * 4
			local itemidAndQuantity = Memory.readdword(addr)
			local itemID = Utils.getbits(itemidAndQuantity, 0, 16)
			if MiscData.Items[itemID] then
				local quantity = Utils.getbits(itemidAndQuantity, 16, 16)
				if key ~= nil then quantity = Utils.bit_xor(quantity, key) end
				if quantity > 0 then
					items[itemID] = quantity
				end
			end
		end
	end
	return items
end

local function getItemIconName(itemId)
	local item = MiscData.HealingItems[itemId]
		or MiscData.StatusItems[itemId]
		or MiscData.PPItems[itemId]
		or MiscData.EvolutionStones[itemId]
		or MiscData.BattleItems[itemId]
		or MiscData.OtherItems[itemId] or {}
	return item.icon or ""
end

-- Categorizes a raw itemID->quantity scan the same way HealsInBagScreen.lua
-- does (an item can land in more than one category -- Full Restore is both
-- a full HP heal and cures any status, same as upstream), plus the same
-- "would this actually help your lead Pokémon right now" highlighting it
-- shows in green. Also recomputes the healingTotal/healingPercentage
-- aggregate Program.recalcLeadPokemonHealingInfo() would have, from this
-- same fresh scan rather than the unreliable cache.
local function buildBagItemsTable(leadMon, rawItems)
	local maxHP, monMissingHP, monStatus, monPPEmpty
	if leadMon and (leadMon.pokemonID or 0) > 0 and leadMon.stats then
		maxHP = leadMon.stats.hp or 0
		monMissingHP = math.max(maxHP - (leadMon.curHP or 0), 0)
		monStatus = leadMon.status or 0
		if monStatus == MiscData.StatusType.Toxic then
			monStatus = MiscData.StatusType.Poison
		end
		for _, m in pairs(leadMon.moves or {}) do
			if (m.pp or 0) <= 1 and tonumber(m.id) ~= 166 then -- 166: Sketch
				monPPEmpty = true
				break
			end
		end
	end
	monStatus = monStatus or MiscData.StatusType.None

	local healingTotal, healingPercentage = 0, 0
	local hpHeals, ppHeals, statusHeals, pokeBalls, evoStones, other = {}, {}, {}, {}, {}, {}

	local function addEntry(list, itemID, quantity, isHelpful)
		table.insert(list, {
			id = itemID,
			name = MiscData.Items[itemID] or "???",
			icon = getItemIconName(itemID),
			quantity = quantity,
			isHelpful = isHelpful == true,
		})
	end

	for itemID, quantity in pairs(rawItems) do
		local isCategorized = false

		local hpItem = MiscData.HealingItems[itemID]
		if hpItem then
			isCategorized = true
			local healAmt = 0
			if hpItem.type == MiscData.HealingType.Constant then
				healAmt = hpItem.amount or 0
			elseif hpItem.type == MiscData.HealingType.Percentage and maxHP then
				healAmt = math.floor(maxHP * (hpItem.amount or 0) / 100)
			end
			addEntry(hpHeals, itemID, quantity, maxHP and monMissingHP and (healAmt * 2 / 3) <= monMissingHP)

			if quantity <= 999 and maxHP and maxHP > 0 then
				local percentageAmt = 0
				if hpItem.type == MiscData.HealingType.Constant then
					percentageAmt = quantity * math.min(hpItem.amount / maxHP * 100, 100)
				elseif hpItem.type == MiscData.HealingType.Percentage then
					percentageAmt = quantity * hpItem.amount
				end
				healingTotal = healingTotal + quantity
				healingPercentage = healingPercentage + percentageAmt
			end
		end

		local ppItem = MiscData.PPItems[itemID]
		if ppItem then
			isCategorized = true
			addEntry(ppHeals, itemID, quantity, monPPEmpty)
		end

		local statusItem = MiscData.StatusItems[itemID]
		if statusItem then
			isCategorized = true
			local statusType = statusItem.type or MiscData.StatusType.None
			local isHelpful = (monStatus == statusType and monStatus > MiscData.StatusType.None)
				or (monStatus > MiscData.StatusType.None and monStatus < MiscData.StatusType.Faint and statusType == MiscData.StatusType.All)
			addEntry(statusHeals, itemID, quantity, isHelpful)
		end

		if MiscData.PokeBalls[itemID] then
			isCategorized = true
			addEntry(pokeBalls, itemID, quantity, false)
		end

		if MiscData.EvolutionStones[itemID] then
			isCategorized = true
			addEntry(evoStones, itemID, quantity, false)
		end

		if not isCategorized then
			addEntry(other, itemID, quantity, false)
		end
	end

	local function byName(a, b) return a.name < b.name end
	table.sort(hpHeals, byName)
	table.sort(ppHeals, byName)
	table.sort(statusHeals, byName)
	table.sort(pokeBalls, byName)
	table.sort(evoStones, byName)
	table.sort(other, byName)

	local bagItems = {
		hpHeals = hpHeals, ppHeals = ppHeals, statusHeals = statusHeals,
		pokeBalls = pokeBalls, evoStones = evoStones, other = other,
	}
	return bagItems, healingTotal, healingPercentage
end

-- True if the player's lead is either one level from a level-up evolution,
-- already has the friendship needed, or already holds the stone/item its
-- evolution requires -- i.e. "ready to evolve, go do it" (matches
-- Utils.getDetailedEvolutionsInfo's own handling of each evolution shape).
local function computeEvolutionReady(p, pokeInfo)
	local evo = pokeInfo and pokeInfo.evolution
	if not evo or evo == PokemonData.Evolutions.NONE then return false end

	if type(evo) == "string" or type(evo) == "number" then
		local lvl = tonumber(evo)
		return lvl ~= nil and lvl == (p.level or 0) + 1
	elseif evo == PokemonData.Evolutions.FRIEND then
		local required = PokemonData.Values.FriendshipRequiredToEvo
		if Program.GameData.friendshipRequired and Program.GameData.friendshipRequired > 1 then
			required = Program.GameData.friendshipRequired
		end
		return (p.friendship or 0) >= required
	elseif type(evo) == "table" and evo.evoItemIds then
		return hasAnyItemInBag(evo.evoItemIds)
	end
	return false
end

local function getEvolutionText(pokemonID)
	if not PokemonData.isValid(pokemonID) then return "" end
	local evo = PokemonData.Pokemon[pokemonID].evolution or PokemonData.Evolutions.NONE
	if evo == PokemonData.Evolutions.NONE then return "" end
	return table.concat(Utils.getDetailedEvolutionsInfo(evo), ", ")
end

-- Defensive type matchup for a species -- always public info in-game (types
-- are visible for either side the moment you see the Pokémon), unlike the
-- per-individual hidden fields pokemonToTable below gates on isEnemy.
local function getDefensesTable(pokemonID)
	local eff = PokemonData.getEffectiveness(pokemonID) or {}
	local function names(mult)
		local list = {}
		for _, t in ipairs(eff[mult] or {}) do
			table.insert(list, tostring(t))
		end
		return list
	end
	return {
		weak4x   = names(4),
		weak2x   = names(2),
		resist2x = names(0.5),
		resist4x = names(0.25),
		immune   = names(0),
	}
end

local function pokemonToTable(p, isEnemy)
	if not p or (p.pokemonID or 0) <= 0 then return nil end

	local pokeInfo = TrackerAPI.getPokemonInfo(p.pokemonID) or {}
	-- TrackerAPI.getPokemonTypes() routes through Tracker.getViewedPokemon()
	-- and Battle.inActiveBattle() when not "in battle" by its own reckoning,
	-- which can disagree with which Pokémon we're actually building a table
	-- for. pokeInfo.types is read directly from GameSettings.gBaseStats in
	-- PokemonData.buildData() -- same reliable source as bstCalculated -- and
	-- is keyed to the exact pokemonID we asked for.
	local rawTypes = pokeInfo.types or {}

	-- Nature/ability aren't shown to the player for an opponent's Pokémon in a
	-- real game. Ability is only knowable once revealed in battle (Intimidate,
	-- Trace, a move-blocked-by-ability message, ...) — Tracker.getAbilities()
	-- is the tracker's own record of what's actually been revealed, as opposed
	-- to TrackerAPI.getAbilityIdOfPokemon() which reads the real value outright.
	local abilityName
	if isEnemy then
		abilityName = "???"
		local tracked = Tracker.getAbilities(p.pokemonID)
		local revealedId = (tracked[1] and tracked[1].id) or 0
		if revealedId > 0 then
			abilityName = (TrackerAPI.getAbilityInfo(revealedId) or {}).name or "???"
		end
	else
		local abilityId = TrackerAPI.getAbilityIdOfPokemon(p)
		abilityName = (TrackerAPI.getAbilityInfo(abilityId) or {}).name or "???"
	end

	-- Mono-type Pokémon report their second type as PokemonData.Types.EMPTY
	-- (a literal ""), used internally as a sentinel -- skip it or the UI
	-- renders an empty, unlabeled type chip.
	local types = {}
	for _, t in ipairs(rawTypes or {}) do
		local typeStr = tostring(t)
		if typeStr ~= "" then
			table.insert(types, typeStr)
		end
	end

	-- p.moves is the real, un-redacted current moveset -- never safe to send
	-- for an opponent (that's what knownMoves, the aggregated actually-used-
	-- in-battle list, exists for). Only build it for the player's own mon.
	local moves = {}
	if not isEnemy then
		for _, m in ipairs(p.moves or {}) do
			local mId = m.id or 0
			if mId > 0 then
				local mInfo = TrackerAPI.getMoveInfo(mId) or {}
				local moveType, movePower = tostring(mInfo.type or ""), mInfo.power or 0
				-- Hidden Power's type/power aren't static -- they're derived
				-- from this exact individual's IVs (MoveData.Moves[237] just
				-- holds an "Unknown"/0 placeholder). Own Pokémon only: we
				-- have real IVs for our own team, not the enemy's.
				if mId == MoveData.Values.HiddenPowerId and p.ivs then
					local hpType, hpPower = MoveData.calcHiddenPowerTypeAndPower(p.ivs)
					moveType, movePower = tostring(hpType or moveType), hpPower or movePower
				end
				table.insert(moves, {
					id    = mId,
					name  = mInfo.name  or "???",
					type  = moveType,
					power = movePower,
					accuracy = tostring(mInfo.accuracy or ""),
					pp    = m.pp        or 0,
					maxPp = mInfo.pp    or 0,
					category = mInfo.category or "",
				})
			end
		end
	end

	-- Real, live-decrementing PP for the enemy's actual current moveset, kept
	-- internal (never sent as-is) and only used to overlay onto knownMoves
	-- entries that are already revealed -- see getKnownMovesTable above.
	local realPpById = nil
	if isEnemy then
		realPpById = {}
		for _, m in ipairs(p.moves or {}) do
			if (m.id or 0) > 0 then
				realPpById[m.id] = m.pp or 0
			end
		end
	end

	local movesLearned, movesTotal, nextMoveLevel = getMoveProgress(p.pokemonID, p.level)
	local evolutionReady = not isEnemy and computeEvolutionReady(p, pokeInfo)

		-- Nature and the base-stat breakdown aren't visible for an opponent's
		-- Pokémon in a real game either (only your own party shows these in
		-- the menu) -- -1/zeroed out for the enemy; the Android UI hides them
		-- for natureIndex < 0.
	local statsTable = isEnemy and { hp = 0, atk = 0, def = 0, spa = 0, spd = 0, spe = 0 } or {
		hp  = (p.stats and p.stats.hp)  or 0,
		atk = (p.stats and p.stats.atk) or 0,
		def = (p.stats and p.stats.def) or 0,
		spa = (p.stats and p.stats.spa) or 0,
		spd = (p.stats and p.stats.spd) or 0,
		spe = (p.stats and p.stats.spe) or 0,
	}
	-- BST = sum of the species' BASE stats (constant, doesn't scale with
	-- level) -- NOT the sum of the current calculated stats above. bstCalculated
	-- is read live from GameSettings.gBaseStats in PokemonData.buildData(), so
	-- it already reflects a randomizer's stat changes. Unlike nature/exact
	-- current stats/ability/held item (per-individual, genuinely hidden until
	-- revealed), BST is species-level info: once you know which species it
	-- is (always visible in-game), its BST isn't secret -- shown for the
	-- enemy too, matching the reference tracker.
	local bst = pokeInfo.bstCalculated or 0

	-- Which stat the nature boosts (+10%) / lowers (-10%), if any. Nature never
	-- affects HP. Hidden for the enemy along with the rest of the nature info.
	local positiveStat, negativeStat = "", ""
	if not isEnemy and p.nature then
		for _, statKey in ipairs({ "atk", "def", "spa", "spd", "spe" }) do
			local mult = Utils.getNatureMultiplier(statKey, p.nature)
			if mult == 1.1 then
				positiveStat = statKey
			elseif mult == 0.9 then
				negativeStat = statKey
			end
		end
	end

	-- Held item: real per-instance data (decrypted from the Pokémon's own
	-- memory structure, not species-level), but same as ability/nature, an
	-- opponent's held item isn't visible in a real game unless revealed
	-- (Frisk, Trick, Covet/Thief, Pluck) -- hide it for the enemy for now.
	local heldItemName = ""
	if not isEnemy and (p.heldItem or 0) > 0 then
		heldItemName = TrackerAPI.getItemName(p.heldItem) or ""
	end

	local gachaStars = isEnemy and 0 or getGachaStars(p, pokeInfo)

	-- Gender is always visible in-game (summary/nickname screens, both
	-- sides), unlike nature/exact stats/ability/held item -- send it for
	-- the enemy too. EXP, on the other hand, is only ever shown for your
	-- own party, so it stays hidden for the enemy.
	local genderStr = ""
	if p.gender == MiscData.Gender.MALE then
		genderStr = "M"
	elseif p.gender == MiscData.Gender.FEMALE then
		genderStr = "F"
	end
	local currentExp = not isEnemy and (p.currentExp or 0) or 0
	local totalExp = not isEnemy and (p.totalExp or 100) or 0

	-- Enemy-only: the species' two possible abilities. Each slot's name is
	-- only revealed once Tracker.getAbilities() has recorded that exact
	-- ability id for this species this run (Intimidate message, a move
	-- failing due to an ability, etc); "current" additionally requires it to
	-- match this specific instance's real ability, so only the ability
	-- actually in effect this battle gets highlighted, never a slot merely
	-- known from a past encounter with a different individual.
	local abilitySlots = {}
	local encounters, wildEncounter, lastLevelSeen = 0, false, 0
	if isEnemy then
		local slotIds = pokeInfo.abilities or {}
		local trackedIds = {}
		for _, a in ipairs(Tracker.getAbilities(p.pokemonID)) do
			if (a.id or 0) > 0 then trackedIds[a.id] = true end
		end
		local actualAbilityId = TrackerAPI.getAbilityIdOfPokemon(p) or 0
		for i = 1, 2 do
			local slotId = slotIds[i] or 0
			if slotId > 0 and (i == 1 or slotId ~= (slotIds[1] or 0)) then
				local revealed = trackedIds[slotId] == true
				table.insert(abilitySlots, {
					name     = revealed and ((TrackerAPI.getAbilityInfo(slotId) or {}).name or "???") or "?",
					revealed = revealed,
					current  = revealed and slotId == actualAbilityId,
				})
			end
		end

		wildEncounter = Battle.isWildEncounter == true
		encounters = Tracker.getEncounters(p.pokemonID, wildEncounter)
		lastLevelSeen = Tracker.getLastLevelSeen(p.pokemonID) or 0
	end

	return {
		id            = p.pokemonID or 0,
		name          = pokeInfo.name or "???",
		nickname      = p.nickname   or "",
		level         = p.level      or 0,
		curHP         = p.curHP      or 0,
		maxHP         = (p.stats and p.stats.hp) or 0,
		natureIndex   = isEnemy and -1 or (p.nature or 0),
		isShiny       = p.isShiny    == true,
		abilityName   = abilityName,
		heldItem      = heldItemName,
		types         = types,
		stats         = statsTable,
		bst           = bst,
		positiveStat  = positiveStat,
		negativeStat  = negativeStat,
		gachaStars    = gachaStars,
		moves         = moves,
		knownMoves    = getKnownMovesTable(p.pokemonID, realPpById),
		knownAbilities = getKnownAbilitiesTable(p.pokemonID),
		movesLearned  = movesLearned,
		movesTotal    = movesTotal,
		nextMoveLevel = nextMoveLevel,
		evolution     = getEvolutionText(p.pokemonID),
		evolutionReady = evolutionReady,
		abilitySlots  = abilitySlots,
		encounters    = encounters,
		wildEncounter = wildEncounter,
		lastLevelSeen = lastLevelSeen,
		gender        = genderStr,
		currentExp    = currentExp,
		totalExp      = totalExp,
		defenses      = getDefensesTable(p.pokemonID),
		-- Publicly visible either side of a real battle (status icon on the
		-- HP bar, "It rose!"/"It fell!" text) -- not gated on isEnemy like
		-- the hidden per-individual fields above.
		statusCondition = MiscData.StatusCodeMap[p.status or 0] or "",
		statStages = {
			atk = (p.statStages and p.statStages.atk) or 6,
			def = (p.statStages and p.statStages.def) or 6,
			spa = (p.statStages and p.statStages.spa) or 6,
			spd = (p.statStages and p.statStages.spd) or 6,
			spe = (p.statStages and p.statStages.spe) or 6,
			acc = (p.statStages and p.statStages.acc) or 6,
			eva = (p.statStages and p.statStages.eva) or 6,
		},
	}
end

-- Aggregated across every encounter area (walking, surfing, fishing, ...) for
-- the given map. RouteData's per-area slot COUNT is structural (unaffected by
-- species randomization); only which species fills each slot is randomized,
-- which is exactly why "X/Y seen" stays meaningful on a randomized ROM.
local function getRouteProgress(mapId)
	local seen, total = 0, 0
	for _, area in ipairs(RouteData.OrderedEncounters or {}) do
		if RouteData.hasRouteEncounterArea(mapId, area) then
			total = total + RouteData.countPokemonInArea(mapId, area)
			seen  = seen + #Tracker.getRouteEncounters(mapId, area)
		end
	end
	return seen, total
end

-- Matches TrackerScreen.lua's own trainer-count logic exactly: multi-floor
-- buildings/caves (Mt. Moon, Rocket Hideout, Victory Road, ...) share one
-- "combined area" (RouteData.Info[mapId].area, a table when applicable) and
-- their trainer counts are meant to be summed across every floor in it, not
-- just the single floor you're currently standing on -- TrackerAPI
-- .getTrainersOnRoute(mapId) only ever looked at the current mapId, which is
-- why counts could read lower than what BizHawk showed for these locations.
local function getTrainerProgress(mapId)
	local route = RouteData.Info[mapId or false]
	if not route then return 0, 0 end
	local defeatedList, total
	if type(route.area) == "table" then
		defeatedList, total = Program.getDefeatedTrainersByCombinedArea(route.area)
	else
		defeatedList, total = Program.getDefeatedTrainersByLocation(mapId)
	end
	return #defeatedList, total
end

-- Deduped list of species actually seen (via Tracker.getRouteEncounters) on
-- this map, across every encounter area.
local function getRouteSpeciesTable(mapId)
	local seenIds = {}
	local list = {}
	for _, area in ipairs(RouteData.OrderedEncounters or {}) do
		if RouteData.hasRouteEncounterArea(mapId, area) then
			for _, pokemonID in ipairs(Tracker.getRouteEncounters(mapId, area)) do
				if not seenIds[pokemonID] then
					seenIds[pokemonID] = true
					local info = TrackerAPI.getPokemonInfo(pokemonID) or {}
					table.insert(list, { id = pokemonID, name = info.name or "???" })
				end
			end
		end
	end
	return list
end

-- Lightweight full-team strip (icon/level/HP/status per slot), mirroring
-- TeamViewArea.lua's always-on party bar -- distinct from playerPokemon
-- above, which is only ever the single currently-viewed/battling slot but
-- with full detail (moves, ability, nature, ...). An egg's contents are
-- genuinely hidden by the game itself until it hatches, so eggs are sent as
-- a bare placeholder, same as TeamViewArea's own HIDDEN_INFO/egg-icon
-- handling -- never real species data.
local function getPartyTable()
	local party = {}
	for i = 1, 6 do
		local p = Tracker.getPokemon(i, true, false)
		if p and p.isEgg == 1 then
			table.insert(party, {
				slot = i, isEgg = true,
				id = PokemonData.Values.EggId,
				name = "Egg", nickname = "",
				level = 0, curHP = 0, maxHP = 0,
				status = "", types = {},
			})
		elseif p and PokemonData.isValid(p.pokemonID) then
			local pokeInfo = TrackerAPI.getPokemonInfo(p.pokemonID) or {}
			local types = {}
			for _, t in ipairs(pokeInfo.types or {}) do
				local typeStr = tostring(t)
				if typeStr ~= "" then table.insert(types, typeStr) end
			end
			table.insert(party, {
				slot = i, isEgg = false,
				id = p.pokemonID,
				name = pokeInfo.name or "???",
				nickname = p.nickname or "",
				level = p.level or 0,
				curHP = p.curHP or 0,
				maxHP = (p.stats and p.stats.hp) or 0,
				status = MiscData.StatusCodeMap[p.status or 0] or "",
				types = types,
			})
		end
	end
	return party
end

-- Program.updateBadgesObtained() (which sets TrackerScreen.Buttons["badgeN"]
-- .badgeState, what TrackerAPI.getBadgeList() reads) only runs inside
-- Program.update()'s "every 3 seconds" block -- the same block that also
-- runs updateBagItems()/PCHeals/CrashRecoveryScreen's backup save, all of
-- which have already turned out to be unreliable in this headless/mGBA
-- environment this session. Read the raw badge bits directly instead,
-- mirroring Program.readBadgeBits()'s own per-game-version logic exactly.
local function computeBadges()
	local badges = {}
	for i = 1, 8 do badges[i] = false end
	if not Program.isValidMapLocation() then return badges end

	local saveblock1Addr = Utils.getSaveBlock1Addr()
	local badgeBits = 0
	if GameSettings.game == 1 or GameSettings.game == 2 then -- Ruby/Sapphire/Emerald
		badgeBits = Utils.getbits(Memory.readword(saveblock1Addr + GameSettings.badgeOffset), 7, 8)
	elseif GameSettings.game == 3 then -- FireRed/LeafGreen
		badgeBits = Memory.readbyte(saveblock1Addr + GameSettings.badgeOffset)
	end

	for i = 1, 8 do
		badges[i] = Utils.getbits(badgeBits, i - 1, 1) == 1
	end
	return badges
end

-- Battle.inBattleScreen (what TrackerAPI.inActiveBattle() reports) never
-- latches true in this headless setup, even though the underlying memory and
-- Tracker.getPokemon(1, false) already have valid data the moment a battle
-- starts — confirmed by direct memory reads during diagnosis. Rather than
-- depend on that internal flag, compute "in battle" ourselves from the same
-- raw checks Battle.updateBattleStatus() uses.

-- Rain/Sandstorm/Sun/Hail + turns remaining, matching BattleDetailsScreen
-- .lua's own gBattleWeather bitmask read -- but using the real bit position
-- directly rather than replicating that screen's own off-by-one bit-scan
-- (which, traced through, makes its "is this the temporary variant" check
-- never actually match for Rain). Only one weather bit is ever set at a time.
local function getBattleWeather()
	local weatherByte = Memory.readbyte(GameSettings.gBattleWeather) or 0
	if weatherByte == 0 then return "", 0 end
	local names = { [0] = "Rain", [1] = "Rain", [2] = "Rain", [3] = "Sandstorm", [4] = "Sandstorm", [5] = "Sun", [6] = "Sun", [7] = "Hail" }
	local isTemporary = { [0] = true, [3] = true, [5] = true, [7] = true }
	for bit = 0, 7 do
		if Utils.getbits(weatherByte, bit, 1) == 1 then
			local turns = 0
			if isTemporary[bit] then
				turns = Memory.readbyte(GameSettings.gWishFutureKnock + 0x28) or 0
			end
			return names[bit] or "", turns
		end
	end
	return "", 0
end

-- Trainer intel for the opponent you're CURRENTLY fighting (never for
-- browsing other trainers ahead of time -- that's the spoiler-adjacent part
-- of TrainerInfoScreen.lua we don't replicate). Level range/avg IVs/AI
-- label/usable items are trainer-wide metadata, not per-species, so they're
-- safe to reveal outright (same precedent as upstream's own in-battle info
-- shortcut, Input.lua's Input.infoShortcutPressed()). Each party slot's
-- actual species is only revealed once that slot has fainted THIS battle or
-- you've already beaten this trainer before -- exactly TrainerInfoScreen
-- .lua's own getIconId() gating, never a preview of the team ahead of when
-- you'd actually see it in a real match.
local function getTrainerIntelTable(trainerId)
	local ok, trainerGame = pcall(Program.readTrainerGameData, trainerId)
	if not ok or not trainerGame or not trainerGame.party then return nil end

	local minLv, maxLv, ivTotal = 100, 0, 0
	for _, mon in ipairs(trainerGame.party) do
		minLv = math.min(minLv, mon.level or 0)
		maxLv = math.max(maxLv, mon.level or 0)
		ivTotal = ivTotal + (mon.ivs or 0)
	end
	if #trainerGame.party == 0 then minLv, maxLv = 0, 0 end
	local avgIVs = #trainerGame.party > 0 and math.floor(ivTotal / #trainerGame.party) or 0

	local aiFlags = trainerGame.aiFlags or 0
	local aiLabel
	if Utils.getbits(aiFlags, 2, 1) == 1 then aiLabel = "Smart"
	elseif Utils.getbits(aiFlags, 1, 1) == 1 then aiLabel = "Semi-Smart"
	elseif Utils.getbits(aiFlags, 0, 1) == 1 then aiLabel = "Normal"
	elseif aiFlags == 0 then aiLabel = "Dumb"
	else aiLabel = "Complex" end

	local itemCounts = {}
	for _, itemId in ipairs(trainerGame.items or {}) do
		if MiscData.Items[itemId] then
			itemCounts[itemId] = (itemCounts[itemId] or 0) + 1
		end
	end
	local itemParts = {}
	for itemId, count in pairs(itemCounts) do
		local name = MiscData.Items[itemId]
		table.insert(itemParts, count > 1 and (count .. " " .. name) or name)
	end
	table.sort(itemParts)

	-- Both level and species stay hidden for a slot that hasn't fainted yet
	-- this battle (and wasn't already beaten in a past encounter) -- a real
	-- blind player only learns a trainer's next Pokémon's level once it's
	-- actually sent out, never the whole bench's levels upfront.
	local slots = {}
	for i, mon in ipairs(trainerGame.party) do
		local revealed = trainerGame.defeated == true
		if not revealed then
			local enemySlot = Tracker.getPokemon(i, false)
			revealed = enemySlot ~= nil and (enemySlot.curHP or 0) <= 0
		end
		table.insert(slots, {
			level = revealed and (mon.level or 0) or 0,
			hasItem = (mon.heldItem or 0) ~= 0,
			id = revealed and (mon.pokemonID or 0) or 0,
		})
	end

	return {
		levelRange = (minLv == maxLv) and tostring(minLv) or string.format("%s-%s", minLv, maxLv),
		avgIVs = avgIVs,
		aiLabel = aiLabel,
		items = table.concat(itemParts, ", "),
		slots = slots,
	}
end

local function computeInBattle()
	local numBattlers = Memory.readbyte(GameSettings.gBattlersCount)
	local firstMonID  = Memory.readword(GameSettings.gBattleMons)
	local statusOrOutcome = Memory.readbyte(GameSettings.gBattleOutcome)
	local isFakeBattle = numBattlers == 0 or not PokemonData.isValid(firstMonID)
	local enemySlot1 = Tracker.getPokemon(1, false)
	local result = statusOrOutcome == 0 and not isFakeBattle and enemySlot1 ~= nil
	return result
end

local function buildAndWrite()
	local inBattle = computeInBattle()

	local playerMon = TrackerAPI.getPlayerPokemon()
	local playerPokemon = pokemonToTable(playerMon, false)
	local badges        = computeBadges()

	-- Same condition as GameOverScreen.LossConditions.LeadPokemonFaints, but
	-- always party slot 1 specifically (not "whatever's currently viewed",
	-- which playerMon above can be mid-battle).
	local leadMon = TrackerAPI.getPlayerPokemon(1)
	local gameOver = leadMon ~= nil and (leadMon.pokemonID or 0) > 0
		and leadMon.curHP == 0 and leadMon.isEgg ~= 1
	if gameOver and not wasGameOver then
		local quotes = Resources.GameOverScreenQuotes or {}
		lastGameOverQuote = #quotes > 0 and quotes[math.random(#quotes)] or ""
	end
	wasGameOver = gameOver

	-- Raw bag scan, categorized against leadMon -- see buildBagItemsTable's
	-- own comment for why this bypasses Program.GameData.Items entirely.
	local bagItems, healingTotal, healingPercentage = buildBagItemsTable(leadMon, scanBagItems())
	local party = getPartyTable()

	-- Battle.wonFinalBattle() itself isn't gated behind Main.IsOnBizhawk() --
	-- only the GameOverScreen wrapper around it is -- so it's safe to call
	-- directly. True once BattleOutcome==1 (won) against whichever trainer
	-- ID this game's TrainerData.FinalTrainer table marks as the champion.
	local gameWon = Battle.wonFinalBattle() == true

	local enemyPokemon  = nil
	local trainerState  = nil
	local trainerPartyRemaining, trainerPartyTotal = 0, 0
	local lastEnemyMoveName, lastEnemyDamage = "", 0
	local catchRatePercent = 0
	local catchRates = {}
	local weatherName, weatherTurns = "", 0
	if inBattle then
		weatherName, weatherTurns = getBattleWeather()

		local enemyMon = TrackerAPI.getEnemyPokemon()
		enemyPokemon = pokemonToTable(enemyMon, true)

		-- Catch rate with a standard Poké Ball -- uses the enemy's real max
		-- HP as a calculation input only (never displayed), same as
		-- PokemonData.calcCatchRate's own caller in DataHelper.lua. catchRates
		-- additionally covers every ball type actually owned right now
		-- (CatchRatesScreen.lua's own per-ball breakdown), reusing the bag
		-- scan already done above instead of re-reading memory.
		if Battle.isWildEncounter and enemyMon and enemyMon.stats then
			catchRatePercent = PokemonData.calcCatchRate(
				enemyMon.pokemonID, enemyMon.stats.hp, enemyMon.curHP, enemyMon.level, enemyMon.status
			)
			local terrain = Memory.readword(GameSettings.gBattleTerrain)
			for _, ball in ipairs(bagItems.pokeBalls) do
				local rate = PokemonData.calcCatchRate(
					enemyMon.pokemonID, enemyMon.stats.hp, enemyMon.curHP, enemyMon.level, enemyMon.status,
					ball.id, terrain, Battle.turnCount
				)
				table.insert(catchRates, { id = ball.id, name = ball.name, quantity = ball.quantity, catchRate = rate })
			end
		end

		local tId = TrackerAPI.getOpponentTrainerId()
		if tId and tId > 0 then
			local tInfo = TrackerAPI.getTrainerInfo(tId) or {}
			trainerState = { id = tId, name = tInfo.name or "???" }
			local intel = getTrainerIntelTable(tId)
			if intel then
				trainerState.levelRange = intel.levelRange
				trainerState.avgIVs = intel.avgIVs
				trainerState.aiLabel = intel.aiLabel
				trainerState.items = intel.items
				trainerState.slots = intel.slots
			end
		end

		if not Battle.isWildEncounter then
			for i = 1, 6 do
				local mon = Program.GameData.EnemyTeam[i]
				if mon and (mon.pokemonID or 0) > 0 then
					trainerPartyTotal = trainerPartyTotal + 1
					if (mon.curHP or 0) > 0 then trainerPartyRemaining = trainerPartyRemaining + 1 end
				end
			end
		end

		local moveId = Battle.lastEnemyMoveId or 0
		if moveId > 0 then
			lastEnemyMoveName = (TrackerAPI.getMoveInfo(moveId) or {}).name or ""
		end
		lastEnemyDamage = Battle.damageReceived or 0

		-- Type effectiveness + STAB for each of your own moves against the
		-- enemy's (now-visible) types, same calc DataHelper.lua's screen
		-- display uses (Utils.netEffectiveness/isSTAB). Mutates the moves
		-- already built onto playerPokemon above.
		if playerPokemon and enemyPokemon and #enemyPokemon.types > 0 then
			for _, move in ipairs(playerPokemon.moves) do
				local moveForCalc = { id = move.id, category = move.category, power = tostring(move.power) }
				move.effectiveness = Utils.netEffectiveness(moveForCalc, move.type, enemyPokemon.types)
				move.isStab = Utils.isSTAB(moveForCalc, move.type, playerPokemon.types)
			end
		end

		local attackSignature = tostring(moveId) .. ":" .. tostring(lastEnemyDamage)
		if lastEnemyDamage > 0 and attackSignature ~= prevAttackSignature then
			lastAttackEventId = lastAttackEventId + 1
		end
		prevAttackSignature = attackSignature
	else
		prevAttackSignature = ""
	end

	-- One-shot events for the Android UI's info-center: it watches these ids
	-- change to know exactly when a battle just ended (and whether it was a
	-- wild or trainer encounter, since Battle's own flags reset once the
	-- battle screen closes) or a new badge was just obtained, rather than
	-- polling a plain boolean that could already have been true last poll.
	if inBattle then lastKnownWildEncounter = Battle.isWildEncounter == true end
	if wasInBattle and not inBattle then
		battleEndEventId = battleEndEventId + 1
		battleEndWasWild = lastKnownWildEncounter
	end
	wasInBattle = inBattle

	for i = 1, 8 do
		if badges[i] and not prevBadges[i] then
			badgeEventId = badgeEventId + 1
			badgeEventIndex = i
		end
	end
	prevBadges = badges

	local mapId     = TrackerAPI.getMapId()
	local routeInfo = TrackerAPI.getRouteInfo(mapId) or {}
	local routeSeen, routeTotal        = getRouteProgress(mapId)
	local trainersDefeated, trainersTotal = getTrainerProgress(mapId)

	-- Steps left on an active Repel, read directly rather than through
	-- Program.updateRepelSteps() -- that one's gated behind both the
	-- "Display repel usage" option and the broken in-battle flag family,
	-- same reliability problem this session kept running into elsewhere.
	local repelStepsRemaining = 0
	if not inBattle then
		local repelOffset = GameSettings.game == 3
			and Program.Addresses.offsetRepelStepCountFRLG
			or Program.Addresses.offsetRepelStepCountRSE
		repelStepsRemaining = Memory.readbyte(Utils.getSaveBlock1Addr() + GameSettings.gameVarsOffset + repelOffset) or 0
	end

	-- Only meaningful before the player has any Pokémon at all (the lab
	-- pick); harmless to keep sending afterward, the app stops showing it.
	local showStarterHint = playerMon == nil or (playerMon.pokemonID or 0) <= 0

	-- Which badge icon set the Android UI should use -- GameSettings.game:
	-- 1=Ruby/Sapphire, 2=Emerald, 3=FireRed/LeafGreen (GameSettings.lua).
	-- RS and Emerald share the same badge sprite set upstream ("RSE_badge*").
	local gameVersion = GameSettings.game == 3 and "FRLG" or "RSE"

	local state = {
		version  = 1,
		gameVersion = gameVersion,
		inBattle = inBattle,
		starterChoice = showStarterHint and starterChoice or 0,
		gameOver      = gameOver,
		gameOverQuote = lastGameOverQuote,
		gameWon       = gameWon,
		badgeEventId    = badgeEventId,
		badgeEventIndex = badgeEventIndex,
		battleEndEventId = battleEndEventId,
		battleEndWasWild = battleEndWasWild,
		-- healPercent/healCount come from the same fresh raw bag scan as
		-- bagItems below now, not Program.GameData.Items (see
		-- buildBagItemsTable's comment -- that cache was confirmed stale/stuck
		-- in this headless environment, same family of bug as badges/repel).
		player   = {
			pokemon    = playerPokemon,
			badges     = badges,
			healPercent = math.min(9999, healingPercentage or 0),
			healCount   = math.min(99, healingTotal or 0),
			bagItems    = bagItems,
			party       = party,
		},
		-- "inBattle" here is redundant with the top-level field, but it keeps
		-- this table from ever being completely empty when not in battle. A
		-- Lua table with zero keys is indistinguishable from an empty array to
		-- the JSON encoder, so it comes out as "[]" instead of "{}" and breaks
		-- strongly-typed JSON parsing on the Android side.
		enemy    = {
			inBattle = inBattle,
			pokemon  = enemyPokemon,
			trainer  = trainerState,
			trainerPartyRemaining = trainerPartyRemaining,
			trainerPartyTotal     = trainerPartyTotal,
			lastMoveName = lastEnemyMoveName,
			lastDamage   = lastEnemyDamage,
			lastAttackEventId = lastAttackEventId,
			catchRatePercent = catchRatePercent,
			catchRates = catchRates,
			weather = weatherName,
			weatherTurns = weatherTurns,
		},
		location = {
			mapId = mapId,
			name  = routeInfo.name or "",
			routeSeen         = routeSeen,
			routeTotal        = routeTotal,
			routeSpecies      = getRouteSpeciesTable(mapId),
			trainersDefeated  = trainersDefeated,
			trainersTotal     = trainersTotal,
			repelStepsRemaining = repelStepsRemaining,
		},
	}

	local json = safeEncode(state)
	if not json then return end

	local f = io.open(stateFile, "w")
	if f then
		f:write(json)
		f:close()
	end
end

-- ── Register frame callback ───────────────────────────────────────────────────

if callbacks then
	callbacks:add("frame", function()
		frameCount = frameCount + 1
		if frameCount >= UPDATE_EVERY then
			frameCount = 0
			local ok, err = pcall(buildAndWrite)
			if not ok then
				console.log("[BuildAndWriteError] " .. tostring(err))
			end
		end
	end)
	print("[AndroidBridge] Registered. Writing to " .. stateFile)
else
	print("[AndroidBridge] Warning: callbacks not available")
end
