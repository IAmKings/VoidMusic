package com.electrodig.objectdrumstudio.audio

import android.content.Context
import android.util.Log
import com.electrodig.objectdrumstudio.R
import com.electrodig.objectdrumstudio.detection.color.DrumPad

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
    private var fallback: SoundPoolDrumEngine? = null

    /**
     * Load the built-in kit and open the audio stream. Idempotent.
     * Tries Oboe native first; falls back to SoundPool if native lib unavailable.
     * @return true on success.
     */
    fun start(): Boolean {
        if (ready) return true
        if (!loadNative()) {
            Log.w(TAG, "Native lib not available — falling back to SoundPool")
            val fb = SoundPoolDrumEngine(context)
            if (fb.start()) {
                fallback = fb
                ready = true
                return true
            }
            Log.e(TAG, "SoundPool fallback also failed — audio disabled")
            return false
        }
        val loaded = DrumPad.entries.associateWith { loadWav(it) }
        if (loaded.any { it.value == null }) {
            Log.e(TAG, "Some samples failed to load")
        }
        val ok = try {
            nativeStart(
                context.assets,
                loaded.mapNotNull { (pad, data) -> data?.let { pad.ordinal to it } }
                    .toMap()
            )
        } catch (t: Throwable) {
            Log.e(TAG, "nativeStart failed", t); false
        }
        ready = ok
        return ok
    }

    /** True when native Oboe is active (not the SoundPool fallback). */
    val isNativeAvailable: Boolean get() = nativeLoaded && ready

    /** Trigger [pad] immediately at [velocity] (0..1). No-op if not ready. */
    fun trigger(pad: DrumPad, velocity: Float) {
        if (!ready) return
        fallback?.let {
            it.trigger(pad, velocity)
            return
        }
        try {
            nativeTrigger(pad.ordinal, velocity.coerceIn(0f, 1f))
        } catch (t: Throwable) {
            Log.w(TAG, "nativeTrigger failed", t)
        }
    }

    /** Set master gain 0..1. */
    fun setMasterVolume(volume: Float) {
        if (!ready) return
        fallback?.let {
            it.setMasterVolume(volume)
            return
        }
        try { nativeSetVolume(volume.coerceIn(0f, 1f)) } catch (t: Throwable) { }
    }

    /** Stop and release the audio resources (PRD §4.4 background release). */
    fun stop() {
        if (!ready) return
        fallback?.stop()
        fallback = null
        try { nativeStop() } catch (t: Throwable) { Log.w(TAG, "nativeStop failed", t) }
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
    private fun loadWav(pad: DrumPad): FloatArray? {
        val resId = when (pad) {
            DrumPad.KICK -> R.raw.kick
            DrumPad.SNARE -> R.raw.snare
            DrumPad.CLAP -> R.raw.clap
            DrumPad.TOM -> R.raw.tom
            DrumPad.HIHAT -> R.raw.hihat
        }
        return runCatching { decodeWav(resId) }.getOrElse {
            Log.e(TAG, "decode ${pad.name} failed", it); null
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
    private external fun nativeSetVolume(volume: Float)
    private external fun nativeStop()
}
