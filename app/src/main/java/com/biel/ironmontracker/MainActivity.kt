package com.biel.ironmontracker

import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import com.biel.ironmontracker.emulator.EmulatorUiState
import com.biel.ironmontracker.emulator.EmulatorViewModel
import com.biel.ironmontracker.tracker.BagItemsState
import com.biel.ironmontracker.ui.BagScreen
import com.biel.ironmontracker.ui.GameScreen
import com.biel.ironmontracker.ui.HomeScreen
import com.biel.ironmontracker.ui.SettingsScreen
import com.biel.ironmontracker.ui.theme.IronmonTrackerTheme

class MainActivity : ComponentActivity() {

    // Shared with the Composable tree below via viewModel() (same ViewModelStoreOwner),
    // so dispatchKeyEvent — which runs outside Compose — can forward gamepad input to it.
    private val vm: EmulatorViewModel by lazy {
        ViewModelProvider(this)[EmulatorViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()

        val hasPhysicalGamepad = InputDevice.getDeviceIds().any { id ->
            val sources = InputDevice.getDevice(id)?.sources ?: 0
            (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        }

        setContent {
            IronmonTrackerTheme {
                Surface(Modifier.fillMaxSize()) {
                    val uiState by vm.uiState.collectAsState()
                    val frame by vm.frame.collectAsState()
                    val trackerState by vm.trackerState.collectAsState()
                    val isRandomizing by vm.isRandomizing.collectAsState()
                    val hasRandomizerSettings by vm.hasRandomizerSettings.collectAsState()
                    val settingsImportError by vm.settingsImportError.collectAsState()
                    val speedMultiplier by vm.speedMultiplier.collectAsState()
                    val recentRom by vm.recentRom.collectAsState()
                    val hasSessionState by vm.hasSessionState.collectAsState()
                    val attemptNumber by vm.attemptNumber.collectAsState()
                    val attemptStartMs by vm.attemptStartMs.collectAsState()
                    val keyMap by vm.keyMap.collectAsState()
                    val remapTarget by vm.remapTarget.collectAsState()
                    val trackerAlwaysVisible by vm.trackerAlwaysVisible.collectAsState()
                    val trackerToggledOn by vm.showTracker.collectAsState()
                    val trackerToggleKeyCode by vm.trackerToggleKeyCode.collectAsState()
                    val fastForwardKeyCode by vm.fastForwardKeyCode.collectAsState()
                    val audioEnabled by vm.audioEnabled.collectAsState()
                    val batterySaver by vm.batterySaver.collectAsState()
                    var showSettings by remember { mutableStateOf(false) }
                    var showBag by remember { mutableStateOf(false) }
                    // Settings/Bag are overlay Boxes, not real back-stack
                    // entries, so without this the system back gesture falls
                    // through to the default Activity behavior (finish/exit
                    // the app) instead of just closing the overlay.
                    BackHandler(enabled = showSettings) { showSettings = false }
                    BackHandler(enabled = showBag) { showBag = false }

                    Box(Modifier.fillMaxSize()) {
                    when (val s = uiState) {
                        is EmulatorUiState.Running ->
                            GameScreen(
                                frame              = frame,
                                trackerState       = trackerState,
                                hasPhysicalGamepad = hasPhysicalGamepad,
                                isRandomizing      = isRandomizing,
                                speedMultiplier    = speedMultiplier,
                                attemptNumber      = attemptNumber,
                                attemptStartMs     = attemptStartMs,
                                trackerAlwaysVisible = trackerAlwaysVisible,
                                trackerToggledOn   = trackerToggledOn,
                                onKeysChanged      = { vm.setKeys(it) },
                                onSaveState        = { vm.saveSessionState() },
                                onNewAttempt       = { vm.triggerRandomizedReset() },
                                onOpenSettings     = { showSettings = true },
                                onOpenBag          = { showBag = true },
                                onToggleTracker    = { vm.toggleTracker() },
                            )

                        is EmulatorUiState.Error ->
                            HomeScreen(
                                isLoading    = false,
                                errorMessage = s.message,
                                hasRandomizerSettings = hasRandomizerSettings,
                                settingsImportError = settingsImportError,
                                recentRom = recentRom,
                                hasSessionState = hasSessionState,
                                onRomSelected = { uri, name -> vm.loadRom(uri, name) },
                                onRandomizerSettingsSelected = { vm.importRandomizerSettings(it) },
                                onOpenRecent = { recentRom?.let { vm.loadRom(it.uri, it.name) } },
                                onResume = { vm.resumeSession() },
                                onOpenSettings = { showSettings = true },
                            )

                        is EmulatorUiState.Loading ->
                            HomeScreen(
                                isLoading    = true,
                                errorMessage = null,
                                hasRandomizerSettings = hasRandomizerSettings,
                                settingsImportError = settingsImportError,
                                recentRom = recentRom,
                                hasSessionState = hasSessionState,
                                onRomSelected = { _, _ -> },
                                onRandomizerSettingsSelected = { vm.importRandomizerSettings(it) },
                                onOpenRecent = {},
                                onResume = {},
                                onOpenSettings = { showSettings = true },
                            )

                        EmulatorUiState.Idle ->
                            HomeScreen(
                                isLoading    = false,
                                errorMessage = null,
                                hasRandomizerSettings = hasRandomizerSettings,
                                settingsImportError = settingsImportError,
                                recentRom = recentRom,
                                hasSessionState = hasSessionState,
                                onRomSelected = { uri, name -> vm.loadRom(uri, name) },
                                onRandomizerSettingsSelected = { vm.importRandomizerSettings(it) },
                                onOpenRecent = { recentRom?.let { vm.loadRom(it.uri, it.name) } },
                                onResume = { vm.resumeSession() },
                                onOpenSettings = { showSettings = true },
                            )
                    }

                    if (showSettings) {
                        SettingsScreen(
                            keyMap = keyMap,
                            trackerToggleKeyCode = trackerToggleKeyCode,
                            fastForwardKeyCode = fastForwardKeyCode,
                            remapTarget = remapTarget,
                            trackerAlwaysVisible = trackerAlwaysVisible,
                            audioEnabled = audioEnabled,
                            batterySaver = batterySaver,
                            hasPhysicalGamepad = hasPhysicalGamepad,
                            onStartRemap = { vm.startRemap(it) },
                            onCancelRemap = { vm.cancelRemap() },
                            onResetKeyMap = { vm.resetKeyMap() },
                            onClearTrackerToggleKey = { vm.clearTrackerToggleKey() },
                            onClearFastForwardKey = { vm.clearFastForwardKey() },
                            onTrackerAlwaysVisibleChanged = { vm.setTrackerAlwaysVisible(it) },
                            onAudioEnabledChanged = { vm.setAudioEnabled(it) },
                            onBatterySaverChanged = { vm.setBatterySaver(it) },
                            onClose = { showSettings = false },
                        )
                    }
                    if (showBag) {
                        BagScreen(
                            bagItems = trackerState?.player?.bagItems ?: BagItemsState(),
                            onClose = { showBag = false },
                        )
                    }
                    }
                }
            }
        }
    }

    // Full-screen immersive mode: hide the status bar (clock/wifi/...) and nav
    // bar during the whole app. enableEdgeToEdge() alone only draws BEHIND the
    // system bars (transparent) without hiding them. Swiping from an edge
    // brings them back transiently, then they auto-hide again.
    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Immersive mode is cleared whenever the window loses then regains
        // focus (returning from background, dismissing a dialog/overlay), so
        // re-assert it here or the bars silently reappear and never re-hide.
        if (hasFocus) hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        // Snapshot the running emulator so the next launch can resume exactly
        // here instead of re-randomizing (see EmulatorViewModel.resumeSession).
        vm.saveSessionState()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (vm.handleGamepadKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (vm.handleGamepadMotionEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }
}
