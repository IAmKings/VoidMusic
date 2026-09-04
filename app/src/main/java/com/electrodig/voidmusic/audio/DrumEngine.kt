package com.electrodig.voidmusic.audio

import android.content.Context
import android.util.Log
import com.electrodig.voidmusic.R
import com.electrodig.voidmusic.detection.color.DrumPad

/** The playback backend currently serving trigger events. */
enum class AudioBackend { NONE, NATIVE_OBOE, SOUND_POOL }

/**
 * Low-latency drum playback engine backed by Oboe (AAudio) via JNI (PRD F6 / §9.5).
 *
 * Primary path: native Oboe via libdrumengine.so (exclusive low-latency, < 40 ms).
 * Fallback path: SoundPool when native lib is unavailable (emulators, non-AAudio
 * devices, or before NDK is installed; < 80 ms target).
 *
 * The native side owns a single output AudioStream and a set of preloaded PCM
 * samples (one per [DrumPad]). [trigger] enqueues a short-lived "voice" that
 * the native audio callback mixes into the output buffer — this keeps the
 * trigger→sound path on the audio thread with no GC/allocation stalls.
 *
 * Samples are loaded from `res/raw` WAVs by the Kotlin side and handed to native
 * as mono float arrays, so the C++ layer stays format-agnostic.
 */
class DrumEngine(private val context: Context) {

    /** Whether the engine is running and samples are loaded. */
    @Volatile private var ready = false
    @Volatile private var nativeLoaded = false
    @Volatile var backend: AudioBackend = AudioBackend.NONE
        private set
    private var fallback: SoundPoolDrumEngine? = null
    private var kit: Kit = BuiltInKits.DEFAULT
    @Volatile private var masterVolume = 0.9f

    /**
     * Load the built-in kit and open the audio stream. Idempotent.
     * Tries Oboe native first; falls back to SoundPool if native lib unavailable.
     * @return true on success.
     */
    fun start(): Boolean {
        if (ready) return true
        if (loadNative() && startNative()) {
            backend = AudioBackend.NATIVE_OBOE
            ready = true
            return true
        }
        // nativeStart may have opened a stream before an error is reported.
        // Release that partial state before handing playback to SoundPool.
        if (nativeLoaded) {
            runCatching { nativeStop() }
                .onFailure { Log.w(TAG, "Failed to clean up native startup", it) }
        }

        Log.w(TAG, "Native Oboe unavailable — falling back to SoundPool")
        val fb = SoundPoolDrumEngine(context, kit)
        if (fb.start()) {
            fallback = fb
            backend = AudioBackend.SOUND_POOL
            ready = true
            return true
        }
        backend = AudioBackend.NONE
        Log.e(TAG, "SoundPool fallback also failed — audio disabled")
        return false
    }

    private fun startNative(): Boolean {
        val loaded = DrumPad.entries.associateWith { pad ->
            kit.samples[pad]?.let(::loadWav)
        }
        if (loaded.any { it.value == null }) {
            Log.e(TAG, "Built-in kit is incomplete; native engine will not start")
            return false
        }
        return try {
            nativeStart(
                context.assets,
                loaded.map { (pad, data) -> pad.ordinal to requireNotNull(data) }
                    .toMap()
            )
        } catch (t: Throwable) {
            Log.e(TAG, "nativeStart failed", t); false
        }
    }

    /** True when native Oboe is active (not the SoundPool fallback). */
    val isNativeAvailable: Boolean get() = backend == AudioBackend.NATIVE_OBOE

    /** Native queue overflows since the active stream was started; 0 for fallback. */
    fun droppedTriggerCount(): Long = if (backend == AudioBackend.NATIVE_OBOE) {
        runCatching { nativeDroppedTriggerCount() }.getOrDefault(0L)
    } else 0L

    /** Trigger [pad] immediately at [velocity] (0..1). No-op if not ready. */
    fun trigger(pad: DrumPad, velocity: Float) {
        if (!ready) return
        when (backend) {
            AudioBackend.SOUND_POOL -> fallback?.trigger(pad, velocity)
            AudioBackend.NATIVE_OBOE -> try {
                nativeTrigger(pad.ordinal, velocity.coerceIn(0f, 1f))
            } catch (t: Throwable) {
                Log.w(TAG, "nativeTrigger failed", t)
            }
            AudioBackend.NONE -> Unit
        }
    }

    /** Set master gain 0..1. */
    fun setMasterVolume(volume: Float) {
        masterVolume = volume.coerceIn(0f, 1f)
        if (!ready) return
        when (backend) {
            AudioBackend.SOUND_POOL -> fallback?.setMasterVolume(masterVolume)
            AudioBackend.NATIVE_OBOE -> try {
                nativeSetVolume(masterVolume)
            } catch (_: Throwable) {
                // A failing volume update must not take down the interaction path.
            }
            AudioBackend.NONE -> Unit
        }
    }

    /** Applies a built-in kit to the active backend. Returns false if reloading fails. */
    fun setKit(newKit: Kit): Boolean {
        if (kit.id == newKit.id) return true
        kit = newKit
        if (!ready) return true
        stop()
        val restarted = start()
        if (restarted) setMasterVolume(masterVolume)
        return restarted
    }

    /** Stop and release the audio resources (PRD §4.4 background release). */
    fun stop() {
        if (!ready) return
        if (backend == AudioBackend.SOUND_POOL) fallback?.stop()
        fallback = null
        if (backend == AudioBackend.NATIVE_OBOE) {
            try {
                nativeStop()
            } catch (t: Throwable) {
                Log.w(TAG, "nativeStop failed", t)
            }
        }
        backend = AudioBackend.NONE
        ready = false
    }

    private fun loadNative(): Boolean {
        if (nativeLoaded) return true
        nativeLoaded = try {
            System.loadLibrary("drumengine")
            true
        } catch (t: UnsatisfiedLinkError) {
            Log.w(TAG, "libdrumengine.so not found", t); false
        }
        return nativeLoaded
    }

    /** Decode a res/raw WAV into a mono float [-1,1] array. Returns null on failure. */
    private fun loadWav(sample: SampleRef): FloatArray? {
        val resId = sample.rawResId
        return runCatching { decodeWav(resId) }.getOrElse {
            Log.e(TAG, "decode kit sample failed", it); null
        }
    }

    private fun decodeWav(resId: Int): FloatArray {
        context.resources.openRawResource(resId).use { input ->
            val bytes = input.readBytes()
            return WavDecoder.toMonoFloats(bytes)
        }
    }

    companion object {
        private const val TAG = "DrumEngine"
    }

    // ---- JNI ----
    private external fun nativeStart(
        assetMgr: android.content.res.AssetManager,
        samples: Map<Int, FloatArray>
    ): Boolean

    private external fun nativeTrigger(padOrdinal: Int, velocity: Float)
    private external fun nativeDroppedTriggerCount(): Long
    private external fun nativeSetVolume(volume: Float)
    private external fun nativeStop()
}
