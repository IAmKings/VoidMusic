package com.electrodig.voidmusic.audio

import android.content.Context
import android.util.Log
import com.electrodig.voidmusic.detection.color.DrumPad
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The playback backend currently serving trigger events. */
enum class AudioBackend { NONE, NATIVE_OBOE, SOUND_POOL }

/** User-visible lifecycle of the low-latency audio path. */
enum class AudioRuntimePhase { STOPPED, STARTING, RUNNING, RECOVERING, FAILED }

/** Snapshot published to the diagnostics HUD without polling JNI from Compose. */
data class AudioRuntimeStatus(
    val phase: AudioRuntimePhase = AudioRuntimePhase.STOPPED,
    val backend: AudioBackend = AudioBackend.NONE,
    val droppedTriggerCount: Long = 0L,
    val xRunCount: Long = 0L,
    val lastNativeErrorCode: Int = 0
)

/**
 * Low-latency drum playback engine backed by Oboe (AAudio) via JNI (PRD F6 / §9.5).
 *
 * Oboe is the primary path and SoundPool is the emergency fallback. A lightweight
 * control-thread monitor detects native stream disconnects, rebuilds Oboe outside
 * the real-time callback and falls back to SoundPool if reopening fails. Native
 * audio callbacks remain lock-free and allocation-free.
 */
class DrumEngine(context: Context) {

    private val context = context.applicationContext

    @Volatile private var ready = false
    @Volatile private var nativeLoaded = false
    @Volatile private var desiredRunning = false
    @Volatile var backend: AudioBackend = AudioBackend.NONE
        private set
    private var fallback: SoundPoolDrumEngine? = null
    private var kit: Kit = BuiltInKits.DEFAULT
    @Volatile private var masterVolume = 0.9f
    private var monitor: ScheduledExecutorService? = null
    private var decodedKitId: String? = null
    private var decodedSamples: Map<Int, FloatArray>? = null
    private var lastNativeErrorCode = 0

    private val _status = MutableStateFlow(AudioRuntimeStatus())
    val status: StateFlow<AudioRuntimeStatus> = _status.asStateFlow()

    /** Load the active kit and open an audio backend. Idempotent. */
    @Synchronized
    fun start(): Boolean {
        desiredRunning = true
        if (ready) return true
        val started = startBackendsLocked(AudioRuntimePhase.STARTING)
        if (started && backend == AudioBackend.NATIVE_OBOE) ensureMonitorLocked()
        return started
    }

    private fun startBackendsLocked(initialPhase: AudioRuntimePhase): Boolean {
        publishStatus(initialPhase, AudioBackend.NONE)
        if (loadNative() && startNative()) {
            backend = AudioBackend.NATIVE_OBOE
            ready = true
            nativeSetVolumeSafely(masterVolume)
            lastNativeErrorCode = 0
            publishNativeStatus(AudioRuntimePhase.RUNNING)
            return true
        }

        // nativeStart can leave partial state after an open/start error.
        if (nativeLoaded) {
            lastNativeErrorCode = runCatching { nativeStreamError() }
                .getOrDefault(lastNativeErrorCode)
            stopNativeSafely("Failed to clean up native startup")
        }

        Log.w(TAG, "Native Oboe unavailable — falling back to SoundPool")
        val fb = SoundPoolDrumEngine(context, kit)
        if (fb.start()) {
            fb.setMasterVolume(masterVolume)
            fallback = fb
            backend = AudioBackend.SOUND_POOL
            ready = true
            publishStatus(AudioRuntimePhase.RUNNING, backend)
            return true
        }

        backend = AudioBackend.NONE
        ready = false
        publishStatus(AudioRuntimePhase.FAILED, AudioBackend.NONE)
        Log.e(TAG, "SoundPool fallback also failed — audio disabled")
        return false
    }

    private fun startNative(): Boolean {
        val samples = decodedSamplesForKit() ?: return false
        return try {
            nativeStart(context.assets, samples)
        } catch (t: Throwable) {
            Log.e(TAG, "nativeStart failed", t)
            false
        }
    }

    private fun decodedSamplesForKit(): Map<Int, FloatArray>? {
        if (decodedKitId == kit.id) decodedSamples?.let { return it }
        val loaded = DrumPad.entries.associate { pad ->
            pad.ordinal to kit.samples[pad]?.let(::loadWav)
        }
        if (loaded.any { it.value == null }) {
            Log.e(TAG, "Active kit is incomplete; native engine will not start")
            return null
        }
        return loaded.mapValues { requireNotNull(it.value) }.also {
            decodedKitId = kit.id
            decodedSamples = it
        }
    }

    /** True when native Oboe is active (not the SoundPool fallback). */
    val isNativeAvailable: Boolean get() = backend == AudioBackend.NATIVE_OBOE

    /** Native queue overflows since the active stream was started; 0 for fallback. */
    fun droppedTriggerCount(): Long = if (backend == AudioBackend.NATIVE_OBOE) {
        runCatching { nativeDroppedTriggerCount() }.getOrDefault(0L)
    } else 0L

    /** Native buffer underruns since the active stream was started; 0 for fallback. */
    fun xRunCount(): Long = if (backend == AudioBackend.NATIVE_OBOE) {
        runCatching { nativeXRunCount() }.getOrDefault(0L)
    } else 0L

    /** Trigger [pad] immediately at [velocity] (0..1). No-op while rebuilding. */
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
            AudioBackend.NATIVE_OBOE -> nativeSetVolumeSafely(masterVolume)
            AudioBackend.NONE -> Unit
        }
    }

    /** Applies a built-in kit to the active backend. Returns false if reloading fails. */
    @Synchronized
    fun setKit(newKit: Kit): Boolean {
        if (kit.id == newKit.id) return true
        val restart = desiredRunning
        stopBackendsLocked()
        kit = newKit
        decodedKitId = null
        decodedSamples = null
        if (!restart) return true
        val started = startBackendsLocked(AudioRuntimePhase.STARTING)
        if (started && backend == AudioBackend.NATIVE_OBOE) ensureMonitorLocked()
        return started
    }

    /** Stop and release audio resources (PRD §4.4 background release). */
    @Synchronized
    fun stop() {
        desiredRunning = false
        monitor?.shutdownNow()
        monitor = null
        stopBackendsLocked()
        lastNativeErrorCode = 0
        publishStatus(AudioRuntimePhase.STOPPED, AudioBackend.NONE)
    }

    private fun stopBackendsLocked() {
        val activeBackend = backend
        ready = false
        backend = AudioBackend.NONE
        if (activeBackend == AudioBackend.SOUND_POOL) fallback?.stop()
        fallback = null
        if (activeBackend == AudioBackend.NATIVE_OBOE) stopNativeSafely("nativeStop failed")
    }

    private fun ensureMonitorLocked() {
        if (monitor?.isShutdown == false) return
        monitor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, RECOVERY_THREAD_NAME)
        }.also { executor ->
            executor.scheduleWithFixedDelay(
                {
                    runCatching { pollNativeHealth() }
                        .onFailure { Log.w(TAG, "Audio health check failed", it) }
                },
                HEALTH_POLL_MS,
                HEALTH_POLL_MS,
                TimeUnit.MILLISECONDS
            )
        }
    }

    @Synchronized
    private fun pollNativeHealth() {
        if (!desiredRunning || !ready || backend != AudioBackend.NATIVE_OBOE) return
        val error = nativeStreamError()
        if (error == 0) {
            publishNativeStatus(AudioRuntimePhase.RUNNING)
            return
        }

        val recoveryStartedAt = android.os.SystemClock.elapsedRealtime()
        lastNativeErrorCode = error
        ready = false
        backend = AudioBackend.NONE
        publishStatus(AudioRuntimePhase.RECOVERING, AudioBackend.NONE)
        stopNativeSafely("Failed to close disconnected Oboe stream")

        val recovered = desiredRunning && startNative()
        if (recovered) {
            backend = AudioBackend.NATIVE_OBOE
            ready = true
            nativeSetVolumeSafely(masterVolume)
            publishNativeStatus(AudioRuntimePhase.RUNNING)
        } else if (desiredRunning) {
            stopNativeSafely("Failed to clean up Oboe recovery")
            val fb = SoundPoolDrumEngine(context, kit)
            if (fb.start()) {
                fb.setMasterVolume(masterVolume)
                fallback = fb
                backend = AudioBackend.SOUND_POOL
                ready = true
                publishStatus(AudioRuntimePhase.RUNNING, backend)
            } else {
                backend = AudioBackend.NONE
                ready = false
                publishStatus(AudioRuntimePhase.FAILED, AudioBackend.NONE)
            }
        }

        Log.i(
            TAG,
            "Audio recovery completed in " +
                "${android.os.SystemClock.elapsedRealtime() - recoveryStartedAt} ms; backend=$backend"
        )
    }

    private fun publishNativeStatus(phase: AudioRuntimePhase) {
        publishStatus(
            phase = phase,
            activeBackend = AudioBackend.NATIVE_OBOE,
            dropped = runCatching { nativeDroppedTriggerCount() }.getOrDefault(0L),
            xruns = runCatching { nativeXRunCount() }.getOrDefault(0L)
        )
    }

    private fun publishStatus(
        phase: AudioRuntimePhase,
        activeBackend: AudioBackend,
        dropped: Long = 0L,
        xruns: Long = 0L
    ) {
        _status.value = AudioRuntimeStatus(
            phase = phase,
            backend = activeBackend,
            droppedTriggerCount = dropped,
            xRunCount = xruns,
            lastNativeErrorCode = lastNativeErrorCode
        )
    }

    private fun stopNativeSafely(message: String) {
        runCatching { nativeStop() }.onFailure { Log.w(TAG, message, it) }
    }

    private fun nativeSetVolumeSafely(volume: Float) {
        runCatching { nativeSetVolume(volume) }
            .onFailure { Log.w(TAG, "nativeSetVolume failed", it) }
    }

    private fun loadNative(): Boolean {
        if (nativeLoaded) return true
        nativeLoaded = try {
            System.loadLibrary("drumengine")
            true
        } catch (t: UnsatisfiedLinkError) {
            Log.w(TAG, "libdrumengine.so not found", t)
            false
        }
        return nativeLoaded
    }

    /** Decode a res/raw WAV into a mono float [-1,1] array. */
    private fun loadWav(sample: SampleRef): FloatArray? = runCatching {
        context.resources.openRawResource(sample.rawResId).use { input ->
            WavDecoder.toMonoFloats(input.readBytes())
        }
    }.getOrElse {
        Log.e(TAG, "decode kit sample failed", it)
        null
    }

    companion object {
        private const val TAG = "DrumEngine"
        private const val HEALTH_POLL_MS = 250L
        private const val RECOVERY_THREAD_NAME = "VM-Audio-Recovery"
    }

    // ---- JNI ----
    private external fun nativeStart(
        assetMgr: android.content.res.AssetManager,
        samples: Map<Int, FloatArray>
    ): Boolean

    private external fun nativeTrigger(padOrdinal: Int, velocity: Float)
    private external fun nativeDroppedTriggerCount(): Long
    private external fun nativeStreamError(): Int
    private external fun nativeXRunCount(): Long
    private external fun nativeSetVolume(volume: Float)
    private external fun nativeStop()
}
