package com.biel.ironmontracker.emulator

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.biel.ironmontracker.randomizer.RandomizerBridge
import com.biel.ironmontracker.tracker.TrackerState
import com.dabomstew.pkrandom.Settings
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

sealed class EmulatorUiState {
    data object Idle : EmulatorUiState()
    data object Loading : EmulatorUiState()
    data class Running(val romName: String) : EmulatorUiState()
    data class Error(val message: String) : EmulatorUiState()
}

data class RecentRom(val uri: Uri, val name: String)

class EmulatorViewModel(app: Application) : AndroidViewModel(app) {

    private val gson = Gson()

    private val _uiState = MutableStateFlow<EmulatorUiState>(EmulatorUiState.Idle)
    val uiState = _uiState.asStateFlow()

    private val _frame = MutableStateFlow<Bitmap?>(null)
    val frame = _frame.asStateFlow()

    private val _trackerState = MutableStateFlow<TrackerState?>(null)
    val trackerState = _trackerState.asStateFlow()

    private val _isRandomizing = MutableStateFlow(false)
    val isRandomizing = _isRandomizing.asStateFlow()

    private val prefs = app.getSharedPreferences("ironmon_prefs", Context.MODE_PRIVATE)
    private val _recentRom = MutableStateFlow(loadRecentRomFromPrefs())
    val recentRom = _recentRom.asStateFlow()

    private fun loadRecentRomFromPrefs(): RecentRom? {
        val uriStr = prefs.getString("recent_rom_uri", null) ?: return null
        val name = prefs.getString("recent_rom_name", null) ?: return null
        return RecentRom(Uri.parse(uriStr), name)
    }

    private fun saveRecentRom(uri: Uri, name: String) {
        prefs.edit().putString("recent_rom_uri", uri.toString()).putString("recent_rom_name", name).apply()
        _recentRom.value = RecentRom(uri, name)
    }

    // "Attempt" counter and play-time clock for the Game Over screen. An
    // "attempt" is one randomized seed: it increments on every fresh
    // randomize (loadRom, the reset combo), but NOT on resumeSession (that's
    // the same attempt continuing, not a new one) -- matching what "Attempt"
    // means in the upstream (BizHawk-only) GameOverScreen.
    private val _attemptNumber = MutableStateFlow(prefs.getInt("attempt_number", 0))
    val attemptNumber = _attemptNumber.asStateFlow()

    private val _attemptStartMs = MutableStateFlow(
        prefs.getLong("attempt_start_ms", System.currentTimeMillis())
    )
    val attemptStartMs = _attemptStartMs.asStateFlow()

    private fun beginNewAttempt() {
        val next = _attemptNumber.value + 1
        val now = System.currentTimeMillis()
        prefs.edit().putInt("attempt_number", next).putLong("attempt_start_ms", now).apply()
        _attemptNumber.value = next
        _attemptStartMs.value = now
    }

    private var frameJob: Job? = null
    private var trackerJob: Job? = null

    // The untouched ROM the user originally picked. Every reset re-randomizes
    // from this, never from a previously-randomized "active" ROM, so seeds
    // don't compound/pollute each other.
    private var sourceRomPath: String? = null

    private val filesDir: File get() = getApplication<Application>().filesDir
    private val activeRomFile: File get() = File(filesDir, "active.gba")
    private val randomizerSettingsFile: File get() = File(filesDir, "randomizer_settings.rnqs")
    private val sessionStateFile: File get() = File(filesDir, "session.state")

    // Only meaningful together with activeRomFile as it existed at the moment
    // it was saved -- invalidated (see invalidateSessionState()) any time
    // active.gba gets overwritten, since a snapshot taken against a
    // different ROM's bytes than what's currently on disk would desync.
    private val _hasSessionState = MutableStateFlow(sessionStateFile.exists() && activeRomFile.exists())
    val hasSessionState = _hasSessionState.asStateFlow()

    private fun invalidateSessionState() {
        if (sessionStateFile.exists()) sessionStateFile.delete()
        _hasSessionState.value = false
    }

    // ── Setup ──────────────────────────────────────────────────────────────────

    init {
        EmulatorEngine.init()
    }

    fun loadRom(uri: Uri, displayName: String) {
        viewModelScope.launch {
            _uiState.value = EmulatorUiState.Loading
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val ctx: Context = getApplication()
                    // Keep read access across app restarts so "recent ROM" can
                    // reopen it later without going through the picker again.
                    runCatching {
                        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    // Copy ROM from SAF Uri to app's private files dir. Kept
                    // forever, untouched, as the input for every future
                    // randomized reset.
                    val sourceFile = File(filesDir, "source.gba")
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(sourceFile).use { input.copyTo(it) }
                    } ?: error("Cannot read ROM")
                    sourceRomPath = sourceFile.absolutePath

                    // If randomizer settings are already loaded, every (re)load
                    // — first pick or reopening "recent" — starts on a freshly
                    // randomized seed, same as the reset combo. Falls back to
                    // the plain source ROM otherwise (e.g. before any .rnqs is
                    // imported, or the source is already pre-randomized).
                    if (randomizerSettingsFile.exists()) {
                        runCatching {
                            val settings = FileInputStream(randomizerSettingsFile).use { Settings.read(it) }
                            RandomizerBridge.randomize(sourceFile.absolutePath, activeRomFile.absolutePath, settings)
                        }.onFailure {
                            sourceFile.copyTo(activeRomFile, overwrite = true)
                        }
                    } else {
                        sourceFile.copyTo(activeRomFile, overwrite = true)
                    }
                    // active.gba just changed -- any previously saved session
                    // snapshot no longer matches it.
                    invalidateSessionState()

                    // Prepare script assets
                    val scriptsOk = ensureScripts()
                    if (!scriptsOk) error("Ironmon-Tracker scripts missing — see app/src/main/assets/ironmon_tracker/")

                    loadRomIntoCore(activeRomFile.absolutePath)
                    displayName
                }
            }
            result.fold(
                onSuccess = { name ->
                    saveRecentRom(uri, name)
                    beginNewAttempt()
                    EmulatorEngine.start()
                    _uiState.value = EmulatorUiState.Running(name)
                    startFrameLoop()
                    if (_audioEnabled.value) startAudioLoop()
                    startTrackerPolling()
                },
                onFailure = { e ->
                    _uiState.value = EmulatorUiState.Error(e.message ?: "Unknown error")
                },
            )
        }
    }

    private fun loadRomIntoCore(romPath: String) {
        EmulatorEngine.loadRom(romPath) || error("ROM not supported")
        EmulatorEngine.loadScripts(
            initScript    = File(filesDir, "ironmon_init.lua").absolutePath,
            trackerScript = File(filesDir, "ironmon_tracker/Ironmon-Tracker.lua").absolutePath,
            bridgeScript  = File(filesDir, "IronmonAndroidBridge.lua").absolutePath,
        )
    }

    /**
     * Resumes exactly where the app was closed: reloads the same already-
     * randomized active.gba as-is (no re-randomization) and restores the
     * full core snapshot saved by [saveSessionState]. This is the "don't
     * re-randomize on every app restart" path — [loadRom] always
     * re-randomizes by design, this is the alternative to it.
     */
    fun resumeSession() {
        if (!sessionStateFile.exists() || !activeRomFile.exists()) return
        viewModelScope.launch {
            _uiState.value = EmulatorUiState.Loading
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val sourceFile = File(filesDir, "source.gba")
                    if (sourceFile.exists()) sourceRomPath = sourceFile.absolutePath

                    val scriptsOk = ensureScripts()
                    if (!scriptsOk) error("Ironmon-Tracker scripts missing — see app/src/main/assets/ironmon_tracker/")

                    loadRomIntoCore(activeRomFile.absolutePath)
                    EmulatorEngine.loadState(sessionStateFile.absolutePath) || error("Could not restore the saved session state")
                }
            }
            result.fold(
                onSuccess = {
                    EmulatorEngine.start()
                    _uiState.value = EmulatorUiState.Running(recentRom.value?.name ?: "Resumed")
                    startFrameLoop()
                    if (_audioEnabled.value) startAudioLoop()
                    startTrackerPolling()
                },
                onFailure = { e ->
                    invalidateSessionState()
                    _uiState.value = EmulatorUiState.Error(e.message ?: "Could not resume the saved session")
                },
            )
        }
    }

    /**
     * Snapshots the full emulator state (RAM/CPU/PPU/...) so [resumeSession]
     * can restore it later without re-randomizing. Deliberately synchronous
     * (not launched on a coroutine) -- called from Activity.onPause, which
     * Android expects to complete quickly but does guarantee runs to
     * completion before the process can be killed; a fire-and-forget
     * coroutine here could lose the race against process death.
     */
    fun saveSessionState() {
        if (_uiState.value !is EmulatorUiState.Running) return
        val ok = runCatching { EmulatorEngine.saveState(sessionStateFile.absolutePath) }.getOrDefault(false)
        _hasSessionState.value = ok
    }

    // True while the activity is not visible (another app on top, screen off).
    // The polling loops below idle on it instead of spinning at 4 ms.
    @Volatile
    private var backgrounded = false

    /**
     * Freezes emulation while the app is not visible. Without this the mGBA
     * thread and the audio stream kept running at full speed with the screen
     * off, holding the device awake and burning CPU for hours. The native
     * paused flag survives stop()/start(), so this is safe to call in any UI
     * state (e.g. mid-randomize) and is always undone by [onAppForegrounded].
     */
    fun onAppBackgrounded() {
        backgrounded = true
        EmulatorEngine.pause(true)
        runCatching { audioTrack?.pause() }
    }

    fun onAppForegrounded() {
        backgrounded = false
        runCatching { audioTrack?.play() }
        EmulatorEngine.pause(false)
    }

    private val _hasRandomizerSettings = MutableStateFlow(randomizerSettingsFile.exists())
    val hasRandomizerSettings = _hasRandomizerSettings.asStateFlow()

    private val _settingsImportError = MutableStateFlow<String?>(null)
    val settingsImportError = _settingsImportError.asStateFlow()

    /** Imports a PokemonRandomizerZX/UPR-ZX .rnqs preset — the same rules used for every future randomized reset. */
    fun importRandomizerSettings(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _settingsImportError.value = null
            val result = runCatching {
                val ctx: Context = getApplication()
                val tempFile = File(filesDir, "import_settings.tmp")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { input.copyTo(it) }
                } ?: error("Cannot read settings file")

                // .rnqs on disk is NOT plain base64 text: Settings.write() prefixes
                // an 8-byte header (4-byte version + 4-byte length) before the
                // UTF-8 toString() payload, so it must go through Settings.read(),
                // not fromString() on the raw file bytes. Read it back here too —
                // both to validate before committing, and because read() applies
                // SettingsUpdater for presets from an older randomizer version.
                FileInputStream(tempFile).use { Settings.read(it) }

                tempFile.copyTo(randomizerSettingsFile, overwrite = true)
                tempFile.delete()
            }
            result.onSuccess {
                _hasRandomizerSettings.value = true
            }.onFailure { e ->
                Log.e("EmulatorViewModel", "Failed to import randomizer settings", e)
                _settingsImportError.value = "${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    fun setKeys(keys: Int) = EmulatorEngine.setKeys(keys)

    /**
     * Re-randomizes a fresh ROM from the original source and reloads it —
     * this is what the reset combo triggers when a .rnqs preset has been
     * imported. Falls back to a plain core reset (same ROM) when no preset
     * is set, so the reset combo still does *something* useful either way.
     */
    fun triggerRandomizedReset() {
        if (_isRandomizing.value || _uiState.value !is EmulatorUiState.Running) return
        val srcPath = sourceRomPath ?: return
        if (!randomizerSettingsFile.exists()) {
            EmulatorEngine.reset()
            return
        }

        viewModelScope.launch {
            _isRandomizing.value = true
            frameJob?.cancel()
            trackerJob?.cancel()
            stopAudioLoop()

            // Drop the previous run's tracker snapshot. startTrackerPolling()
            // restarts with lastModified=0, so without this it would re-read
            // the stale ironmon_state.json still on disk and briefly show the
            // old run's party/badges/location (even a game-over flash) before
            // the reloaded Lua bridge writes the fresh state.
            _trackerState.value = null
            File(filesDir, "ironmon_state.json").delete()

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    EmulatorEngine.stop()
                    val settings = FileInputStream(randomizerSettingsFile).use { Settings.read(it) }
                    RandomizerBridge.randomize(srcPath, activeRomFile.absolutePath, settings)
                    invalidateSessionState()
                    loadRomIntoCore(activeRomFile.absolutePath)
                }
            }
            result.onFailure { e -> Log.e("EmulatorViewModel", "Randomized reset failed", e) }

            if (result.isSuccess) {
                beginNewAttempt()
                EmulatorEngine.start()
                startFrameLoop()
                if (_audioEnabled.value) startAudioLoop()
                startTrackerPolling()
            } else {
                val e = result.exceptionOrNull()
                _uiState.value = EmulatorUiState.Error(
                    "Randomization failed (${e?.javaClass?.simpleName}: ${e?.message}) — reselect the ROM to try again"
                )
            }
            _isRandomizing.value = false
        }
    }

    // ── Settings: button remapping ──────────────────────────────────────────────
    // Only the discrete buttons are remappable -- D-pad/stick, LT/RT, and the
    // LT+RT+Select reset combo are analog-axis-driven special cases handled
    // directly in handleGamepadMotionEvent, not through this keycode map.
    private val defaultKeyMap = mapOf(
        "A" to KeyEvent.KEYCODE_BUTTON_A,
        "B" to KeyEvent.KEYCODE_BUTTON_B,
        "L" to KeyEvent.KEYCODE_BUTTON_L1,
        "R" to KeyEvent.KEYCODE_BUTTON_R1,
        "START" to KeyEvent.KEYCODE_BUTTON_START,
        "SELECT" to KeyEvent.KEYCODE_BUTTON_SELECT,
    )
    private fun loadKeyMap(): Map<String, Int> =
        defaultKeyMap.mapValues { (name, default) -> prefs.getInt("keymap_$name", default) }

    private val _keyMap = MutableStateFlow(loadKeyMap())
    val keyMap = _keyMap.asStateFlow()

    // Non-null while the settings screen is waiting for the next physical
    // button press to assign to this GBA button (e.g. "A"). Checked first in
    // handleGamepadKeyEvent, ahead of the running-state guard, so remapping
    // works from the settings screen regardless of emulator state.
    private val _remapTarget = MutableStateFlow<String?>(null)
    val remapTarget = _remapTarget.asStateFlow()

    fun startRemap(button: String) { _remapTarget.value = button }
    fun cancelRemap() { _remapTarget.value = null }

    fun resetKeyMap() {
        val editor = prefs.edit()
        defaultKeyMap.keys.forEach { editor.remove("keymap_$it") }
        editor.apply()
        _keyMap.value = loadKeyMap()
    }

    // Optional: a physical button that toggles the tracker panel on/off,
    // independent of the GBA button map above (this isn't a GBA input at
    // all, just a UI action) -- unassigned (null) by default. Stored as -1
    // in prefs since SharedPreferences has no nullable-Int getter.
    private val _trackerToggleKeyCode = MutableStateFlow(
        prefs.getInt("keymap_TRACKER_TOGGLE", -1).takeIf { it != -1 },
    )
    val trackerToggleKeyCode = _trackerToggleKeyCode.asStateFlow()

    fun clearTrackerToggleKey() {
        prefs.edit().remove("keymap_TRACKER_TOGGLE").apply()
        _trackerToggleKeyCode.value = null
    }

    // Optional single-key actions (not GBA buttons) remappable through the
    // same capture-next-press flow -- unassigned (null) by default, each
    // backed by its own "keymap_<NAME>" prefs entry.
    private val extraActionNames = listOf("TRACKER_TOGGLE", "FAST_FORWARD")

    private fun reloadExtraActionFlows() {
        _trackerToggleKeyCode.value = prefs.getInt("keymap_TRACKER_TOGGLE", -1).takeIf { it != -1 }
        _fastForwardKeyCode.value = prefs.getInt("keymap_FAST_FORWARD", -1).takeIf { it != -1 }
    }

    private fun assignRemap(keyCode: Int) {
        val target = _remapTarget.value ?: return
        val editor = prefs.edit()
        // Never let two actions silently end up sharing the same physical
        // input: clear this keycode off whichever other GBA button or extra
        // action currently holds it.
        for ((name, code) in _keyMap.value) {
            if (name != target && code == keyCode) editor.putInt("keymap_$name", defaultKeyMap.getValue(name))
        }
        for (name in extraActionNames) {
            if (name == target) continue
            val current = prefs.getInt("keymap_$name", -1)
            if (current == keyCode) editor.remove("keymap_$name")
        }
        editor.putInt("keymap_$target", keyCode)
        editor.apply()
        _keyMap.value = loadKeyMap()
        reloadExtraActionFlows()
        _remapTarget.value = null
    }

    // ── Settings: tracker display mode ──────────────────────────────────────────
    private val _trackerAlwaysVisible = MutableStateFlow(prefs.getBoolean("tracker_always_visible", false))
    val trackerAlwaysVisible = _trackerAlwaysVisible.asStateFlow()
    fun setTrackerAlwaysVisible(value: Boolean) {
        prefs.edit().putBoolean("tracker_always_visible", value).apply()
        _trackerAlwaysVisible.value = value
    }

    // Lives here (not as local Compose state in GameScreen) so a physical
    // button press captured in MainActivity.dispatchKeyEvent -- outside the
    // Compose tree -- can toggle it too.
    private val _showTracker = MutableStateFlow(false)
    val showTracker = _showTracker.asStateFlow()
    fun toggleTracker() { _showTracker.value = !_showTracker.value }

    // ── Settings: performance ───────────────────────────────────────────────────
    private val _audioEnabled = MutableStateFlow(prefs.getBoolean("audio_enabled", true))
    val audioEnabled = _audioEnabled.asStateFlow()
    fun setAudioEnabled(value: Boolean) {
        prefs.edit().putBoolean("audio_enabled", value).apply()
        _audioEnabled.value = value
        if (_uiState.value is EmulatorUiState.Running) {
            if (value) startAudioLoop() else stopAudioLoop()
        }
    }

    // Lowers frame/tracker polling rates to reduce CPU/battery use. The core
    // itself still simulates a full 60fps regardless (that's not adjustable
    // without a deeper frame-skip change to the emulation loop itself) -- this
    // only reduces how often we redundantly pull frames/state that haven't
    // changed since the last poll.
    private val _batterySaver = MutableStateFlow(prefs.getBoolean("battery_saver", false))
    val batterySaver = _batterySaver.asStateFlow()
    fun setBatterySaver(value: Boolean) {
        prefs.edit().putBoolean("battery_saver", value).apply()
        _batterySaver.value = value
    }

    // ── Physical gamepad input ─────────────────────────────────────────────────
    private val heldKeys = AtomicInteger(0)

    /** Returns true if the event was a mapped GBA button and was consumed. */
    fun handleGamepadKeyEvent(event: KeyEvent): Boolean {
        if (_remapTarget.value != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) assignRemap(event.keyCode)
            return true
        }
        if (_uiState.value !is EmulatorUiState.Running) return false
        if (_trackerToggleKeyCode.value == event.keyCode) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) toggleTracker()
            return true
        }
        if (_fastForwardKeyCode.value == event.keyCode) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) cycleSpeed()
            return true
        }
        val bit = gbaKeyFor(event.keyCode) ?: return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return true // ignore OS auto-repeat
                EmulatorEngine.setKeys(heldKeys.updateAndGet { it or bit })
            }
            KeyEvent.ACTION_UP -> {
                EmulatorEngine.setKeys(heldKeys.updateAndGet { it and bit.inv() })
            }
        }
        return true
    }

    // The AYANEO Controller (and most USB/BT gamepads) report the D-pad as an
    // analog HAT_X/HAT_Y axis via MotionEvent, not as KEYCODE_DPAD_* KeyEvents.
    // The left stick is a second, independent analog source for the same
    // directions — merge both into the same bits so either one works.
    private val dpadMask = EmulatorEngine.Keys.UP or EmulatorEngine.Keys.DOWN or
        EmulatorEngine.Keys.LEFT or EmulatorEngine.Keys.RIGHT
    private val stickDeadzone = 0.5f

    // Reset combo: LT + RT + Select — deliberately analog triggers instead of
    // the L/R shoulder buttons, so it can never fire from normal L+R gameplay.
    // Edge-triggered (only fires once per fresh press) since these are analog
    // axes that keep re-reporting every sample while held.
    private var triggerComboActive = false

    // RT alone cycles speed: 1x -> 2x -> 3x -> 4x -> 1x. Edge-triggered off a
    // plain RT press (not part of the LT+RT+Select reset combo) so it doesn't
    // fire as a side effect of going for a reset.
    private var rtPressed = false
    private val speedSteps = intArrayOf(1, 2, 3, 4)
    private var speedIndex = 0
    private val _speedMultiplier = MutableStateFlow(1)
    val speedMultiplier = _speedMultiplier.asStateFlow()

    private fun cycleSpeed() {
        speedIndex = (speedIndex + 1) % speedSteps.size
        val mult = speedSteps[speedIndex]
        _speedMultiplier.value = mult
        EmulatorEngine.setSpeedMultiplier(mult)
    }

    // Optional: a discrete button that also cycles speed, alongside the
    // fixed RT-tap gesture above (which always still works) -- same
    // unassigned-by-default, capture-next-press pattern as the tracker
    // toggle. Useful on gamepads where RT isn't comfortable to tap
    // repeatedly, or for a second, easier-to-reach shortcut.
    private val _fastForwardKeyCode = MutableStateFlow(
        prefs.getInt("keymap_FAST_FORWARD", -1).takeIf { it != -1 },
    )
    val fastForwardKeyCode = _fastForwardKeyCode.asStateFlow()

    fun clearFastForwardKey() {
        prefs.edit().remove("keymap_FAST_FORWARD").apply()
        _fastForwardKeyCode.value = null
    }

    fun handleGamepadMotionEvent(event: MotionEvent): Boolean {
        if (_uiState.value !is EmulatorUiState.Running) return false
        if ((event.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) return false

        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val stickX = event.getAxisValue(MotionEvent.AXIS_X)
        val stickY = event.getAxisValue(MotionEvent.AXIS_Y)

        var dpadBits = 0
        if (hatX <= -0.5f || stickX <= -stickDeadzone) dpadBits = dpadBits or EmulatorEngine.Keys.LEFT
        if (hatX >= 0.5f  || stickX >= stickDeadzone)  dpadBits = dpadBits or EmulatorEngine.Keys.RIGHT
        if (hatY <= -0.5f || stickY <= -stickDeadzone) dpadBits = dpadBits or EmulatorEngine.Keys.UP
        if (hatY >= 0.5f  || stickY >= stickDeadzone)  dpadBits = dpadBits or EmulatorEngine.Keys.DOWN

        val newKeys = heldKeys.updateAndGet { (it and dpadMask.inv()) or dpadBits }
        EmulatorEngine.setKeys(newKeys)

        // This device reports L2/R2 as the GAS/BRAKE axes (confirmed via
        // `adb shell dumpsys input`), not AXIS_LTRIGGER/AXIS_RTRIGGER.
        val rt = event.getAxisValue(MotionEvent.AXIS_GAS)
        val lt = event.getAxisValue(MotionEvent.AXIS_BRAKE)
        val selectHeld = (newKeys and EmulatorEngine.Keys.SELECT) != 0

        val rtDown = rt > 0.5f
        // RT-tap is only the fast-forward trigger while no custom button has
        // been assigned in Settings -- once one is, it replaces RT rather
        // than adding a second way to trigger it.
        if (rtDown && !rtPressed && !(lt > 0.5f && selectHeld) && _fastForwardKeyCode.value == null) cycleSpeed()
        rtPressed = rtDown

        val comboActive = lt > 0.5f && rt > 0.5f && selectHeld
        if (comboActive && !triggerComboActive) triggerRandomizedReset()
        triggerComboActive = comboActive

        return true
    }

    private fun gbaKeyFor(keyCode: Int): Int? {
        val map = _keyMap.value
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP    -> EmulatorEngine.Keys.UP
            KeyEvent.KEYCODE_DPAD_DOWN  -> EmulatorEngine.Keys.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT  -> EmulatorEngine.Keys.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> EmulatorEngine.Keys.RIGHT
            map["A"]      -> EmulatorEngine.Keys.A
            map["B"]      -> EmulatorEngine.Keys.B
            map["L"]      -> EmulatorEngine.Keys.L
            map["R"]      -> EmulatorEngine.Keys.R
            map["START"]  -> EmulatorEngine.Keys.START
            map["SELECT"] -> EmulatorEngine.Keys.SELECT
            else -> null
        }
    }

    fun stopEmulator() {
        frameJob?.cancel()
        trackerJob?.cancel()
        stopAudioLoop()
        heldKeys.set(0)
        speedIndex = 0
        _speedMultiplier.value = 1
        EmulatorEngine.stop()
        _uiState.value = EmulatorUiState.Idle
        _frame.value = null
        _trackerState.value = null
        _showTracker.value = false
    }

    override fun onCleared() {
        super.onCleared()
        stopAudioLoop()
        EmulatorEngine.destroy()
    }

    // ── Frame polling loop ─────────────────────────────────────────────────────

    private fun startFrameLoop() {
        frameJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (backgrounded) { delay(250); continue }
                val bmp = EmulatorEngine.getFrame()
                if (bmp != null) _frame.value = bmp
                // Battery saver polls at ~60 Hz, matching the core's actual
                // frame rate exactly (no lower-fidelity redundant polling);
                // the default over-polls slightly for lower input-to-display
                // latency at the cost of some extra CPU wakeups.
                delay(if (_batterySaver.value) 16L else 4L)
            }
        }
    }

    // ── Audio playback ───────────────────────────────────────────────────────

    private var audioJob: Job? = null
    private var audioTrack: AudioTrack? = null

    private fun startAudioLoop() {
        val minBuffer = AudioTrack.getMinBufferSize(
            EmulatorEngine.AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(EmulatorEngine.AUDIO_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            // A few frames' worth of headroom beyond the platform minimum so
            // brief scheduling hiccups on this poll loop don't underrun.
            maxOf(minBuffer, minBuffer * 2),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        // Created while hidden (e.g. a resume finishing after the user left):
        // stay silent until onAppForegrounded() starts playback.
        if (!backgrounded) track.play()
        audioTrack = track

        audioJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (backgrounded) { delay(250); continue }
                val samples = EmulatorEngine.getAudioSamples()
                if (samples != null && samples.isNotEmpty()) {
                    // Blocking write: paces this loop to real playback speed,
                    // which is exactly the backpressure we want here.
                    track.write(samples, 0, samples.size)
                } else {
                    delay(4)
                }
            }
        }
    }

    private fun stopAudioLoop() {
        audioJob?.cancel()
        audioJob = null
        audioTrack?.let {
            it.pause()
            it.flush()
            it.release()
        }
        audioTrack = null
    }

    // ── Tracker state polling ──────────────────────────────────────────────────

    private fun startTrackerPolling() {
        trackerJob = viewModelScope.launch(Dispatchers.IO) {
            val stateFile = File(filesDir, "ironmon_state.json")
            var lastModified = 0L
            while (isActive) {
                if (stateFile.exists() && stateFile.lastModified() != lastModified) {
                    lastModified = stateFile.lastModified()
                    runCatching {
                        val json = stateFile.readText()
                        gson.fromJson(json, TrackerState::class.java)
                    }.onFailure {
                        Log.e("EmulatorViewModel", "Failed to parse tracker state JSON", it)
                    }.getOrNull()?.let { _trackerState.value = it }
                }
                delay(if (_batterySaver.value) 600L else 300L)
            }
        }
    }

    // ── Asset extraction ───────────────────────────────────────────────────────

    // Subdirectories and files inside the tracker bundle that are not needed on
    // Android: images (binary sprites never rendered), extra language packs, and
    // large data tables the bridge doesn't use.  Skipping them saves ~10-15 MB
    // of file-system writes and the equivalent Lua heap when the tracker loads.
    // SpriteData.lua, GachaMonData.lua, and RandomizerLog.lua are NOT safe to
    // skip: they're in FileManager.LuaCode, the mandatory file list that
    // Main.Initialize() loads in a loop, aborting (returning false, so
    // Main.Run() is never called and the tracker's frame loop never starts)
    // if even one entry is missing. Only skip files nothing in that list
    // references: the actual sprite images, and non-English language tables
    // (Resources.lua loads only the active language, on demand, after init).
    private val trackerSkipPrefixes = setOf(
        "ironmon_tracker/ironmon_tracker/images",
        "ironmon_tracker/ironmon_tracker/Languages/German.lua",
        "ironmon_tracker/ironmon_tracker/Languages/French.lua",
        "ironmon_tracker/ironmon_tracker/Languages/Italian.lua",
        "ironmon_tracker/ironmon_tracker/Languages/Spanish.lua",
        "ironmon_tracker/ironmon_tracker/Languages/Japanese.lua",
    )

    private suspend fun ensureScripts(): Boolean = withContext(Dispatchers.IO) {
        val ctx: Context = getApplication()
        val assets = ctx.assets

        // Write init script (sets Lua globals the bridge needs)
        val filesPath = filesDir.absolutePath
        val initFile = File(filesDir, "ironmon_init.lua")
        initFile.writeText("""
            _IRONMON_FILES_DIR = "$filesPath"
            _IRONMON_TRACKER_DIR = "$filesPath/ironmon_tracker"
            package.path = _IRONMON_TRACKER_DIR .. "/?.lua;" ..
                           _IRONMON_TRACKER_DIR .. "/?/init.lua;" ..
                           (package.path or "")

            -- Provide console object if mGBA didn't expose it (headless setup)
            if console == nil then
                local function _log(...)
                    local t = {}
                    for i = 1, select('#', ...) do t[i] = tostring(select(i, ...)) end
                    print(table.concat(t, "\t"))
                end
                console = {
                    log = _log,
                    error = _log,
                    warn = _log,
                    createBuffer = function() return nil end,
                    clear = function() end,
                }
            end

            -- mGBA's real console:createBuffer() creates a GUI text-buffer meant
            -- for the Qt scripting window. There is no such window on Android, and
            -- MGBA.ScreenUtils.createTextBuffers() calls it ~20 times during
            -- Main.Run() startup -- on this headless build that runs away in
            -- memory instead of failing gracefully. The tracker already treats a
            -- nil textBuffer as "no display for this screen" everywhere it reads
            -- one, so disabling it here is safe.
            console.createBuffer = function() return nil end

            -- DEBUG: mGBA's console:log/warn/error write to a GUI text buffer that
            -- never reaches Android logcat, so we're otherwise blind to every
            -- print() the tracker does. Tee everything to a plain file instead so
            -- it can be pulled after a crash to see exactly where it got stuck.
            do
                local logFile = io.open(_IRONMON_FILES_DIR .. "/tracker_debug.log", "w")
                local function makeLogger()
                    return function(...)
                        local n = select('#', ...)
                        local startIdx = 1
                        if n > 0 and (select(1, ...)) == console then startIdx = 2 end
                        local parts = {}
                        for i = startIdx, n do parts[#parts + 1] = tostring(select(i, ...)) end
                        if logFile then
                            logFile:write(table.concat(parts, "\t"), "\n")
                            logFile:flush()
                        end
                    end
                end
                console.log   = makeLogger()
                console.warn  = makeLogger()
                console.error = makeLogger()
            end
        """.trimIndent())

        // Copy bridge script from assets
        val bridgeDest = File(filesDir, "IronmonAndroidBridge.lua")
        copyAsset(assets, "bridge/IronmonAndroidBridge.lua", bridgeDest)

        // Skip the full tracker copy if it was already extracted in a previous
        // session (the sentinel file exists).  Re-extract only when the app is
        // updated (install clears the files dir) or the user clears app data.
        val sentinel = File(filesDir, "ironmon_tracker/.extracted")
        if (!sentinel.exists()) {
            val trackerAssetsDir = "ironmon_tracker"
            val trackerDestDir   = File(filesDir, "ironmon_tracker")
            if (!copyAssetDir(assets, trackerAssetsDir, trackerDestDir)) {
                return@withContext false
            }
            sentinel.writeText("ok")
        }

        File(filesDir, "ironmon_tracker/Ironmon-Tracker.lua").exists()
    }

    private fun copyAsset(assets: android.content.res.AssetManager, src: String, dest: File) {
        runCatching {
            assets.open(src).use { input ->
                FileOutputStream(dest).use { input.copyTo(it) }
            }
        }
    }

    private fun copyAssetDir(
        assets: android.content.res.AssetManager,
        srcDir: String,
        destDir: File,
    ): Boolean {
        val list = runCatching { assets.list(srcDir) }.getOrNull() ?: return false
        if (list.isEmpty()) return false
        destDir.mkdirs()
        var anyFile = false
        for (item in list) {
            val srcPath  = "$srcDir/$item"
            if (trackerSkipPrefixes.any { srcPath == it || srcPath.startsWith("$it/") }) continue
            val destFile = File(destDir, item)
            val children = runCatching { assets.list(srcPath) }.getOrNull()
            if (!children.isNullOrEmpty()) {
                copyAssetDir(assets, srcPath, destFile)
            } else {
                copyAsset(assets, srcPath, destFile)
                anyFile = true
            }
        }
        return anyFile
    }
}
