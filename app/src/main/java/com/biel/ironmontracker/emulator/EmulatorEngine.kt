package com.biel.ironmontracker.emulator

import android.graphics.Bitmap

object EmulatorEngine {

    init {
        System.loadLibrary("ironmon_jni")
    }

    // mGBA's GBA audio core hardcodes its synthesis rate to 96 kHz
    // (src/gba/audio.c) -- not derived from anything queryable at runtime,
    // so this mirrors the native GBA_AUDIO_SAMPLE_RATE constant exactly.
    const val AUDIO_SAMPLE_RATE = 96000

    // GBA button bitmask constants
    object Keys {
        const val A      = 1
        const val B      = 2
        const val SELECT = 4
        const val START  = 8
        const val RIGHT  = 16
        const val LEFT   = 32
        const val UP     = 64
        const val DOWN   = 128
        const val R      = 256
        const val L      = 512
    }

    fun init(): Boolean = nativeInit()

    fun loadRom(path: String): Boolean = nativeLoadRom(path)

    fun loadScripts(initScript: String, trackerScript: String, bridgeScript: String): Boolean =
        nativeLoadScripts(initScript, trackerScript, bridgeScript)

    fun start() = nativeStart()

    fun stop() = nativeStop()

    fun setKeys(keys: Int) = nativeSetKeys(keys)

    fun reset() = nativeReset()

    fun setSpeedMultiplier(multiplier: Int) = nativeSetSpeedMultiplier(multiplier)

    fun pause(paused: Boolean) = nativePause(paused)

    /** Full core snapshot (RAM/CPU/PPU/...), distinct from the .sav cart save. Emulator must be running. */
    fun saveState(path: String): Boolean = nativeSaveState(path)

    /** Restores a snapshot saved via [saveState]. Must be called after [loadRom] succeeds and before [start]. */
    fun loadState(path: String): Boolean = nativeLoadState(path)

    fun destroy() = nativeDestroy()

    /**
     * Returns a 240x160 ARGB Bitmap if a new frame is ready, null otherwise.
     * Must not be called from the main thread if the emulator is running.
     */
    fun getFrame(): Bitmap? {
        val pixels = nativeGetFrame() ?: return null
        val bmp = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, 240, 0, 0, 240, 160)
        return bmp
    }

    /** Interleaved stereo PCM16 samples at [AUDIO_SAMPLE_RATE], or null if none are ready yet. */
    fun getAudioSamples(): ShortArray? = nativeGetAudioSamples()

    // ── JNI declarations ──────────────────────────────────────────────────────

    private external fun nativeInit(): Boolean
    private external fun nativeLoadRom(path: String): Boolean
    private external fun nativeLoadScripts(
        initScript: String,
        trackerScript: String,
        bridgeScript: String,
    ): Boolean
    private external fun nativeStart()
    private external fun nativeStop()
    private external fun nativeSetKeys(keys: Int)
    private external fun nativeReset()
    private external fun nativeSetSpeedMultiplier(multiplier: Int)
    private external fun nativePause(paused: Boolean)
    private external fun nativeSaveState(path: String): Boolean
    private external fun nativeLoadState(path: String): Boolean
    private external fun nativeGetFrame(): IntArray?
    private external fun nativeGetAudioSamples(): ShortArray?
    private external fun nativeDestroy()
}
