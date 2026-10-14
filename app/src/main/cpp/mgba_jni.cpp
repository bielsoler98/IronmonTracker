#include <jni.h>
#include <android/log.h>

#include <mgba/core/blip_buf.h>
#include <mgba/core/config.h>
#include <mgba/core/core.h>
#include <mgba/core/log.h>
#include <mgba/core/scripting.h>
#include <mgba/core/serialize.h>
#include <mgba/gba/core.h>
#include <mgba/internal/script/lua.h>
#include <mgba/script/context.h>
#include <mgba-util/vfs.h>

#include <fcntl.h>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#define TAG "IronmonJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// GBA screen dimensions
static constexpr int GBA_W = 240;
static constexpr int GBA_H = 160;
// Stride in pixels (must be >= GBA_W; mGBA uses 256 as the next power of 2)
static constexpr int GBA_STRIDE = 256;

// mGBA's GBA audio core hardcodes its blip_buffer synthesis rate to 96 kHz
// (src/gba/audio.c: blip_set_rates(..., 96000)) -- not derived from
// core->frequency() (that's the ARM7TDMI clock, a different thing). Feeding
// AudioTrack this same rate directly avoids needing to resample ourselves.
static constexpr int GBA_AUDIO_SAMPLE_RATE = 96000;

// ─────────────────────────────────────────────────────────────────────────────
// State
// ─────────────────────────────────────────────────────────────────────────────

struct EmulatorState {
    struct mCore*           core        = nullptr;
    struct mScriptContext   scriptCtx   = {};
    struct mScriptEngineContext* luaEngine = nullptr;
    bool scriptingActive = false;

    // Framebuffer: ARGB8888, stride=GBA_STRIDE pixels
    uint32_t framebuffer[GBA_STRIDE * GBA_H] = {};

    // Shared framebuffer copy for Java reads (mutex-protected)
    std::mutex  fbMutex;
    uint32_t    fbCopy[GBA_W * GBA_H] = {};
    bool        fbDirty = false;

    // Key state (GBA bitmask)
    std::atomic<uint32_t> keys{0};

    // Set by nativeReset(), consumed on the emulator thread between frames
    std::atomic<bool>   resetRequested{false};

    // 1 = normal speed, 2/3/4 = that many times real-time (shrinks the
    // per-frame pacing sleep proportionally; each core frame is still a full
    // logical GBA frame, just displayed/advanced faster).
    std::atomic<int>    speedMultiplier{1};

    // Emulator thread control
    std::thread         emuThread;
    std::atomic<bool>   running{false};
    std::atomic<bool>   paused{false};

    // Savestate request (full core snapshot, distinct from the .sav cart
    // save) -- consumed on the emulator thread since `core` must only ever
    // be touched from there while it's running. stateFilePath is guarded by
    // stateMutex; stateOpDone/stateOpResult let the requesting (JNI) thread
    // block-wait for the emulator thread to finish processing it.
    std::atomic<bool>   saveStateRequested{false};
    std::mutex          stateMutex;
    std::string         stateFilePath;
    std::atomic<bool>   stateOpDone{false};
    std::atomic<bool>   stateOpResult{false};

    // Interleaved stereo PCM16 audio pulled from the core's blip buffers
    // after every frame, drained by nativeGetAudioSamples() into an Android
    // AudioTrack. Capped in emulatorLoop() in case the Java side falls
    // behind (e.g. briefly backgrounded).
    std::mutex           audioMutex;
    std::vector<int16_t> audioBuffer;
};

static EmulatorState* g_state = nullptr;

// ─────────────────────────────────────────────────────────────────────────────
// mGBA logging → Android logcat
// ─────────────────────────────────────────────────────────────────────────────

static void _logCallback(struct mLogger*, int, enum mLogLevel level, const char* fmt, va_list args) {
    char buf[512];
    vsnprintf(buf, sizeof(buf), fmt, args);
    if (level >= mLOG_ERROR)  LOGE("%s", buf);
    else                       LOGI("%s", buf);
}

static struct mLogger g_logger = { .log = _logCallback };

// ─────────────────────────────────────────────────────────────────────────────
// Emulator loop (runs on its own thread)
// ─────────────────────────────────────────────────────────────────────────────

static void emulatorLoop(EmulatorState* s) {
    // ~60 fps target: 16 666 µs per frame
    constexpr int FRAME_US = 16667;

    while (s->running.load()) {
        // Handled before the paused-check below so a save can still be
        // requested (e.g. app backgrounding) while paused.
        if (s->saveStateRequested.exchange(false)) {
            std::string path;
            { std::lock_guard<std::mutex> lk(s->stateMutex); path = s->stateFilePath; }
            struct VFile* vf = VFileOpen(path.c_str(), O_RDWR | O_CREAT | O_TRUNC);
            bool ok = false;
            if (vf) {
                ok = mCoreSaveStateNamed(s->core, vf, SAVESTATE_ALL);
                vf->close(vf);
            } else {
                LOGE("Cannot open state file for writing: %s", path.c_str());
            }
            s->stateOpResult.store(ok);
            s->stateOpDone.store(true);
        }

        if (s->paused.load()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(16));
            continue;
        }

        auto frameStart = std::chrono::steady_clock::now();

        // Perform any pending soft-reset before running the next frame
        if (s->resetRequested.exchange(false)) {
            s->core->reset(s->core);
            if (s->scriptingActive) {
                mScriptContextTriggerCallback(&s->scriptCtx, "reset");
            }
        }

        // Deliver key state to the core
        s->core->setKeys(s->core, s->keys.load());

        int mult = s->speedMultiplier.load();
        if (mult < 1) mult = 1;

        // Run one GBA frame
        s->core->runFrame(s->core);

        // Trigger Lua "frame" callbacks
        if (s->scriptingActive) {
            mScriptContextTriggerCallback(&s->scriptCtx, "frame");
        }

        // Pull rendered audio out of the core's blip buffers. Always drained
        // regardless of speed multiplier -- not reading it would let the
        // buffer overflow -- but only kept for playback at 1x; faster-than-
        // real-time audio fed to AudioTrack at its native rate would just be
        // noise, so it's discarded instead while fast-forwarding.
        {
            struct blip_t* left  = s->core->getAudioChannel(s->core, 0);
            struct blip_t* right = s->core->getAudioChannel(s->core, 1);
            int available = blip_samples_avail(left);
            if (available > 0) {
                std::vector<int16_t> interleaved(static_cast<size_t>(available) * 2);
                blip_read_samples(left,  interleaved.data(),     available, 1);
                blip_read_samples(right, interleaved.data() + 1, available, 1);
                if (mult == 1) {
                    std::lock_guard<std::mutex> lock(s->audioMutex);
                    s->audioBuffer.insert(s->audioBuffer.end(), interleaved.begin(), interleaved.end());
                    constexpr size_t MAX_BUFFERED_SAMPLES = GBA_AUDIO_SAMPLE_RATE; // ~0.5s stereo
                    if (s->audioBuffer.size() > MAX_BUFFERED_SAMPLES) {
                        s->audioBuffer.erase(s->audioBuffer.begin(),
                                              s->audioBuffer.end() - static_cast<long>(MAX_BUFFERED_SAMPLES));
                    }
                }
            }
        }

        // Copy and convert framebuffer: mGBA outputs 0x00BBGGRR (XBGR, alpha=0)
        // Android ARGB_8888 setPixels expects 0xFFRRGGBB — swap R/B and set alpha=0xFF
        {
            std::lock_guard<std::mutex> lock(s->fbMutex);
            for (int y = 0; y < GBA_H; y++) {
                const uint32_t* src = &s->framebuffer[y * GBA_STRIDE];
                uint32_t*       dst = &s->fbCopy[y * GBA_W];
                for (int x = 0; x < GBA_W; x++) {
                    uint32_t px = src[x];
                    dst[x] = 0xFF000000u
                           | ((px & 0x0000FFu) << 16)  // R: byte0 → byte2
                           | (px & 0x00FF00u)           // G: byte1 → byte1
                           | ((px >> 16) & 0xFFu);      // B: byte2 → byte0
                }
            }
            s->fbDirty = true;
        }

        // Pace to ~60 fps * speedMultiplier (1x = normal, 2x/3x/4x = faster).
        int64_t targetFrameUs = FRAME_US / mult;
        auto elapsed = std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now() - frameStart).count();
        if (elapsed < targetFrameUs)
            std::this_thread::sleep_for(std::chrono::microseconds(targetFrameUs - elapsed));
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// JNI functions
// ─────────────────────────────────────────────────────────────────────────────

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_biel_ironmontracker_emulator_EmulatorEngine_nativeInit(JNIEnv*, jobject) {
    if (g_state) return JNI_FALSE; // already initialised

    mLogSetDefaultLogger(&g_logger);

    g_state = new EmulatorState();
    LOGI("EmulatorEngine initialised");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_biel_ironmontracker_emulator_EmulatorEngine_nativeLoadRom(JNIEnv* env, jobject,
                                                                    jstring jpath) {
    if (!g_state) return JNI_FALSE;
    if (g_state->running.load()) return JNI_FALSE;

    const char* pathChars = env->GetStringUTFChars(jpath, nullptr);
    std::string path(pathChars);
    env->ReleaseStringUTFChars(jpath, pathChars);

    struct VFile* romVf = VFileOpen(path.c_str(), O_RDONLY);
    if (!romVf) { LOGE("Cannot open ROM"); return JNI_FALSE; }

    // Detect and create core
    struct mCore* core = mCoreFindVF(romVf);
    if (!core) { LOGE("No core for ROM"); romVf->close(romVf); return JNI_FALSE; }

    if (!core->init(core)) {
        LOGE("Core init failed");
        core->deinit(core);
        romVf->close(romVf);
        return JNI_FALSE;
    }

    // Config MUST be initialised before reset() — reset() calls mCoreConfigGetBoolValue
    mCoreConfigInit(&core->config, nullptr);

    core->setVideoBuffer(core, g_state->framebuffer, GBA_STRIDE);

    if (!core->loadROM(core, romVf)) {
        LOGE("loadROM failed");
        mCoreConfigDeinit(&core->config);
        core->deinit(core);
        return JNI_FALSE;
    }

    // Persist battery-backed save data (SRAM/flash/EEPROM) across app restarts.
    // Without this the core only ever holds save data in memory, so in-game
    // saves silently vanish once the process is killed.
    std::string savePath = path;
    auto dot = savePath.find_last_of('.');
    if (dot != std::string::npos) savePath = savePath.substr(0, dot);
    savePath += ".sav";

    struct VFile* saveVf = VFileOpen(savePath.c_str(), O_RDWR | O_CREAT);
    if (saveVf) {
        core->loadSave(core, saveVf);
    } else {
        LOGE("Cannot open save file: %s", savePath.c_str());
    }

    // Big enough to comfortably hold more than one video frame's worth of
    // audio (~1600 samples/frame at 96kHz/60fps) so the blip buffer doesn't
    // need draining more than once per runFrame().
    core->setAudioBufferSize(core, 4096);

    core->reset(core);
    g_state->core = core;
    LOGI("ROM loaded OK");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_biel_ironmontracker_emulator_EmulatorEngine_nativeLoadScripts(JNIEnv* env, jobject,
                                                                        jstring jInitScript,
                                                                        jstring jTrackerScript,
                                                                        jstring jBridgeScript) {
    if (!g_state || !g_state->core) return JNI_FALSE;

    mScriptContextInit(&g_state->scriptCtx);
    // Register Lua engine first, get its context back
    g_state->luaEngine = mScriptContextRegisterEngine(&g_state->scriptCtx, mSCRIPT_ENGINE_LUA);
    if (!g_state->luaEngine) {
        LOGE("Failed to register Lua engine");
        mScriptContextDeinit(&g_state->scriptCtx);
        return JNI_FALSE;
    }
    // Attach core AFTER registering engine — exposes `emu`, `callbacks`, etc. to Lua
    mScriptContextAttachCore(&g_state->scriptCtx, g_state->core);
    // Attach stdlib — exposes console, timing, and other standard scripting globals
    mScriptContextAttachStdlib(&g_state->scriptCtx);

    auto loadScript = [&](jstring jpath, const char* label) -> bool {
        const char* path = env->GetStringUTFChars(jpath, nullptr);
        struct VFile* vf = VFileOpen(path, O_RDONLY);
        if (!vf) {
            LOGE("Cannot open %s: %s", label, path);
            env->ReleaseStringUTFChars(jpath, path);
            return false;
        }
        bool ok = g_state->luaEngine->load(g_state->luaEngine, path, vf);
        vf->close(vf);
        if (ok) {
            ok = g_state->luaEngine->run(g_state->luaEngine);
        }
        if (!ok) {
            const char* err = g_state->luaEngine->getError(g_state->luaEngine);
            LOGE("Script error in %s: %s", label, err ? err : "unknown");
        } else {
            LOGI("Loaded %s", label);
        }
        env->ReleaseStringUTFChars(jpath, path);
        return ok;
    };

    // Load in order: init globals → Ironmon-Tracker → bridge extension
    loadScript(jInitScript,    "init");
    loadScript(jTrackerScript, "IronmonTracker");
    loadScript(jBridgeScript,  "AndroidBridge");

    // Do NOT trigger "start" here: Ironmon-Tracker.lua already calls
    // IronmonTracker.startTracker() at the top level when the script is loaded,
    // which runs Main.Run() and registers all frame callbacks.  Firing "start"
    // again would invoke Main.Run() a second time, doubling the heavy
    // initialization (FileManager.executeEachFile, table allocations, etc.).
    g_state->scriptingActive = true;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_biel_ironmontracker_emulator_EmulatorEngine_nativeStart(JNIEnv*, jobject) {
    if (!g_state || !g_state->core) return;
    if (g_state->running.load()) return;

    g_state->running = true;
    g_state->emuThread = std::thread(emulatorLoop, g_state);
    LOGI("Emulator started");
}

JNIEXPORT void JNICALL
Java_com_biel_ironmontracker_emulator_EmulatorEngine_nativeStop(JNIEnv*, jobject) {
    if (!g_state) return;
    g_state->running = false;
    if (g_state->emuThread.joinable()) g_state->emuThread.join();

    if (g_state->scriptingActive) {
        mScriptContextTriggerCallback(&g_state->scriptCtx, "stop");
        mScriptContextDeinit(&g_state->scriptCtx);
        g_state->scriptingActive = false;
    }
    if (g_state->core) {
        mCoreConfigDeinit(&g_state->core->config);
        g_state->core->deinit(g_state->core);
        g_state->core = nullptr;
    }
    LOGI("Emulator stopped");
}

JNIEXPORT void JNICALL
Java_com_biel_ironmontracker_emulator_EmulatorEngine_nativeSetKeys(JNIEnv*, jobject,
                                                                    jint keys) {
    if (g_state) g_state->keys.store(static_cast<uint32_t>(keys));
}

JNIEXPORT void JNICALL
Java_com_biel_ironmontracker_emulator_EmulatorEngine_nativeSetSpeedMultiplier(JNIEnv*, jobject,
                                                                                jint multiplier) {
    if (g_state) g_state->speedMultiplier.store(multiplier);
}

JNIEXPORT void JNICALL
Java_com_biel_ironmontracker_emulator_EmulatorEngine_nativeReset(JNIEnv*, jobject) {
    if (g_state && g_state->core) g_state->resetRequested.store(true);
}

JNIEXPORT void JNICALL
Java_com_biel_ironmontracker_emulator_EmulatorEngine_nativePause(JNIEnv*, jobject,
                                                                   jboolean paused) {
    if (g_state) g_state->paused.store(paused == JNI_TRUE);
}

// Full core snapshot (RAM/CPU/PPU/... state), distinct from the .sav cart
// save. Only valid while the emulator thread is running (started via
// nativeStart) -- the actual mCoreSaveStateNamed call happens on that thread
// (see emulatorLoop) since `core` must not be touched concurrently; this
// just posts the request and blocks briefly for the result.
JNIEXPORT jboolean JNICALL
Java_com_biel_ironmontracker_emulator_EmulatorEngine_nativeSaveState(JNIEnv* env, jobject,
                                                                       jstring jpath) {
    if (!g_state || !g_state->core || !g_state->running.load()) return JNI_FALSE;

    const char* pathChars = env->GetStringUTFChars(jpath, nullptr);
    {
        std::lock_guard<std::mutex> lk(g_state->stateMutex);
        g_state->stateFilePath = pathChars;
    }
    env->ReleaseStringUTFChars(jpath, pathChars);

    g_state->stateOpDone.store(false);
    g_state->saveStateRequested.store(true);

    // The emulator thread checks this every iteration (at most ~16ms apart,
    // even while paused) -- 2s is a generous upper bound before giving up.
    for (int i = 0; i < 400 && !g_state->stateOpDone.load(); ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    return g_state->stateOpResult.load() ? JNI_TRUE : JNI_FALSE;
}

// Restores a full core snapshot saved via nativeSaveState. Only valid right
// after nativeLoadRom succeeds and before nativeStart is called -- at that
// point the emulator thread doesn't exist yet, so `core` can be touched
// directly here with no synchronization needed (same as core->loadSave() in
// nativeLoadRom).
JNIEXPORT jboolean JNICALL
Java_com_biel_ironmontracker_emulator_EmulatorEngine_nativeLoadState(JNIEnv* env, jobject,
                                                                       jstring jpath) {
    if (!g_state || !g_state->core || g_state->running.load()) return JNI_FALSE;

    const char* pathChars = env->GetStringUTFChars(jpath, nullptr);
    struct VFile* vf = VFileOpen(pathChars, O_RDONLY);
    env->ReleaseStringUTFChars(jpath, pathChars);
    if (!vf) return JNI_FALSE;

    bool ok = mCoreLoadStateNamed(g_state->core, vf, SAVESTATE_ALL);
    vf->close(vf);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jintArray JNICALL
Java_com_biel_ironmontracker_emulator_EmulatorEngine_nativeGetFrame(JNIEnv* env, jobject) {
    if (!g_state) return nullptr;

    std::lock_guard<std::mutex> lock(g_state->fbMutex);
    if (!g_state->fbDirty) return nullptr;

    jintArray arr = env->NewIntArray(GBA_W * GBA_H);
    if (!arr) return nullptr;

    env->SetIntArrayRegion(arr, 0, GBA_W * GBA_H,
                           reinterpret_cast<const jint*>(g_state->fbCopy));
    g_state->fbDirty = false;
    return arr;
}

// Drains whatever interleaved stereo PCM16 audio has accumulated since the
// last call. Returns null when there's nothing new (matches nativeGetFrame's
// convention), not an empty array.
JNIEXPORT jshortArray JNICALL
Java_com_biel_ironmontracker_emulator_EmulatorEngine_nativeGetAudioSamples(JNIEnv* env, jobject) {
    if (!g_state) return nullptr;

    std::lock_guard<std::mutex> lock(g_state->audioMutex);
    if (g_state->audioBuffer.empty()) return nullptr;

    auto count = static_cast<jsize>(g_state->audioBuffer.size());
    jshortArray arr = env->NewShortArray(count);
    if (!arr) return nullptr;

    env->SetShortArrayRegion(arr, 0, count,
                              reinterpret_cast<const jshort*>(g_state->audioBuffer.data()));
    g_state->audioBuffer.clear();
    return arr;
}

JNIEXPORT void JNICALL
Java_com_biel_ironmontracker_emulator_EmulatorEngine_nativeDestroy(JNIEnv*, jobject) {
    if (!g_state) return;
    g_state->running = false;
    if (g_state->emuThread.joinable()) g_state->emuThread.join();

    if (g_state->scriptingActive) {
        mScriptContextDeinit(&g_state->scriptCtx);
    }
    if (g_state->core) {
        mCoreConfigDeinit(&g_state->core->config);
        g_state->core->deinit(g_state->core);
    }
    delete g_state;
    g_state = nullptr;
    LOGI("EmulatorEngine destroyed");
}

} // extern "C"
