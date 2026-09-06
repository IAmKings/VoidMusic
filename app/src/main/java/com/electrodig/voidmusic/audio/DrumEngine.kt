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
 * Low-latency playback owner. It accepts only fully prepared PCM and restores the
 * previous prepared kit whenever a backend cannot start the requested replacement.
 */
class DrumEngine private constructor(
    private val context: Context?,
    starter: AudioBackendStarter?,
    private val elapsedRealtimeMs: () -> Long
) {
    constructor(context: Context) : this(
        context.applicationContext,
        null,
        android.os.SystemClock::elapsedRealtime
    )

    internal constructor(starter: AudioBackendStarter) : this(null, starter, { 0L })

    private val backendStarter = starter ?: AudioBackendStarter(::startRealBackend)
    @Volatile private var activeBackend: ActiveAudioBackend? = null
    @Volatile private var ready = false
    @Volatile private var desiredRunning = false
    @Volatile var backend: AudioBackend = AudioBackend.NONE
        private set
    private var preparedKit: PreparedKit? = null
    @Volatile private var masterVolume = 0.9f
    private var nativeLoaded = false
    private var monitor: ScheduledExecutorService? = null
    private var lastNativeErrorCode = 0
    private val pendingLock = Any()
    private val pendingTriggers = ArrayDeque<PendingTrigger>()

    private val _status = MutableStateFlow(AudioRuntimeStatus())
    val status: StateFlow<AudioRuntimeStatus> = _status.asStateFlow()

    /** Open an audio backend for the already prepared kit. Idempotent. */
    @Synchronized
    fun start(): Boolean {
        desiredRunning = true
        if (ready) return true
        val kit = preparedKit
        if (kit == null) {
            publishStatus(AudioRuntimePhase.FAILED, AudioBackend.NONE)
            return false
        }
        return startBackendLocked(kit, AudioRuntimePhase.STARTING)
    }

    /** Starts or switches in one recoverable operation, including foreground resume. */
    @Synchronized
    fun start(newKit: PreparedKit): Boolean {
        if (ready) return setPreparedKit(newKit)
        val previousKit = preparedKit
        desiredRunning = true
        preparedKit = newKit
        if (startBackendLocked(newKit, AudioRuntimePhase.STARTING)) return true

        preparedKit = previousKit
        if (previousKit != null && previousKit !== newKit &&
            startBackendLocked(previousKit, AudioRuntimePhase.RECOVERING)
        ) {
            logWarning("Kit startup failed; previous kit restored")
            return false
        }
        clearPendingTriggers()
        publishStatus(AudioRuntimePhase.FAILED, AudioBackend.NONE)
        return false
    }

    /**
     * Commits a prepared kit. When startup fails, the previous kit is restarted
     * before this method returns and queued transition hits are replayed.
     */
    @Synchronized
    fun setPreparedKit(newKit: PreparedKit): Boolean {
        if (preparedKit === newKit) return true
        if (!desiredRunning) {
            preparedKit = newKit
            return true
        }

        val previousKit = preparedKit
        stopActiveBackendLocked()
        preparedKit = newKit
        if (startBackendLocked(newKit, AudioRuntimePhase.STARTING)) return true

        preparedKit = previousKit
        if (previousKit != null && startBackendLocked(previousKit, AudioRuntimePhase.RECOVERING)) {
            logWarning("Kit switch failed; previous kit restored")
            return false
        }

        clearPendingTriggers()
        publishStatus(AudioRuntimePhase.FAILED, AudioBackend.NONE)
        logError("Kit switch and previous-kit recovery both failed")
        return false
    }

    private fun startBackendLocked(kit: PreparedKit, initialPhase: AudioRuntimePhase): Boolean {
        publishStatus(initialPhase, AudioBackend.NONE)
        val started = try {
            backendStarter.start(kit)
        } catch (failure: Exception) {
            if (context != null) Log.e(TAG, "Audio backend startup failed", failure)
            null
        }
        if (started == null) {
            activeBackend = null
            backend = AudioBackend.NONE
            ready = false
            publishStatus(AudioRuntimePhase.FAILED, AudioBackend.NONE)
            return false
        }
        started.setMasterVolume(masterVolume)
        activeBackend = started
        backend = started.kind
        ready = true
        if (started.kind == AudioBackend.NATIVE_OBOE) {
            lastNativeErrorCode = 0
            ensureMonitorLocked()
        }
        publishActiveStatus(AudioRuntimePhase.RUNNING)
        drainPendingTriggers(started)
        return true
    }

    private fun startRealBackend(kit: PreparedKit): ActiveAudioBackend? {
        if (loadNative() && startNative(kit)) return NativeBackend()

        if (nativeLoaded) {
            lastNativeErrorCode = runCatching { nativeStreamError() }
                .getOrDefault(lastNativeErrorCode)
            stopNativeSafely("Failed to clean up native startup")
        }

        val appContext = requireNotNull(context)
        Log.w(TAG, "Native Oboe unavailable — falling back to SoundPool")
        return SoundPoolDrumEngine(appContext, kit).takeIf(SoundPoolDrumEngine::start)
            ?: run {
                Log.e(TAG, "SoundPool fallback also failed — audio disabled")
                null
            }
    }

    /** True when native Oboe is active (not the SoundPool fallback). */
    val isNativeAvailable: Boolean get() = backend == AudioBackend.NATIVE_OBOE

    fun droppedTriggerCount(): Long = activeBackend?.droppedTriggerCount() ?: 0L

    fun xRunCount(): Long = activeBackend?.xRunCount() ?: 0L

    /** Trigger immediately; only the short transition window uses a bounded queue. */
    fun trigger(pad: DrumPad, velocity: Float) {
        val normalizedVelocity = velocity.coerceIn(0f, 1f)
        val active = activeBackend
        if (ready && active != null) {
            active.trigger(pad, normalizedVelocity)
        } else if (desiredRunning) {
            synchronized(pendingLock) {
                if (pendingTriggers.size == MAX_PENDING_TRIGGERS) pendingTriggers.removeFirst()
                pendingTriggers += PendingTrigger(pad, normalizedVelocity)
            }
        }
    }

    fun setMasterVolume(volume: Float) {
        masterVolume = volume.coerceIn(0f, 1f)
        if (ready) activeBackend?.setMasterVolume(masterVolume)
    }

    /** Stop and release audio resources when the app leaves the foreground. */
    @Synchronized
    fun stop() {
        desiredRunning = false
        monitor?.shutdownNow()
        monitor = null
        stopActiveBackendLocked()
        clearPendingTriggers()
        lastNativeErrorCode = 0
        publishStatus(AudioRuntimePhase.STOPPED, AudioBackend.NONE)
    }

    private fun stopActiveBackendLocked() {
        ready = false
        backend = AudioBackend.NONE
        activeBackend?.stop()
        activeBackend = null
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
    internal fun pollNativeHealth() {
        val current = activeBackend ?: return
        if (!desiredRunning || !ready || current.kind != AudioBackend.NATIVE_OBOE) return
        val error = current.streamError()
        if (error == 0) {
            publishActiveStatus(AudioRuntimePhase.RUNNING)
            return
        }

        val recoveryStartedAt = elapsedRealtimeMs()
        lastNativeErrorCode = error
        stopActiveBackendLocked()
        publishStatus(AudioRuntimePhase.RECOVERING, AudioBackend.NONE)
        val kit = preparedKit
        if (desiredRunning && kit != null) {
            startBackendLocked(kit, AudioRuntimePhase.RECOVERING)
        } else {
            publishStatus(AudioRuntimePhase.FAILED, AudioBackend.NONE)
        }
        if (context != null) {
            Log.i(
                TAG,
                "Audio recovery completed in " +
                    "${elapsedRealtimeMs() - recoveryStartedAt} ms; backend=$backend"
            )
        }
    }

    private fun publishActiveStatus(phase: AudioRuntimePhase) {
        val active = activeBackend
        publishStatus(
            phase = phase,
            activeBackend = active?.kind ?: AudioBackend.NONE,
            dropped = runCatching { active?.droppedTriggerCount() ?: 0L }.getOrDefault(0L),
            xruns = runCatching { active?.xRunCount() ?: 0L }.getOrDefault(0L)
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

    private fun drainPendingTriggers(target: ActiveAudioBackend) {
        val queued = synchronized(pendingLock) {
            pendingTriggers.toList().also { pendingTriggers.clear() }
        }
        queued.forEach { target.trigger(it.pad, it.velocity) }
    }

    private fun clearPendingTriggers() {
        synchronized(pendingLock) { pendingTriggers.clear() }
    }

    private fun logWarning(message: String) {
        if (context != null) Log.w(TAG, message)
    }

    private fun logError(message: String) {
        if (context != null) Log.e(TAG, message)
    }

    private fun loadNative(): Boolean {
        if (nativeLoaded) return true
        nativeLoaded = try {
            System.loadLibrary("drumengine")
            true
        } catch (failure: UnsatisfiedLinkError) {
            Log.w(TAG, "libdrumengine.so not found", failure)
            false
        }
        return nativeLoaded
    }

    private fun startNative(kit: PreparedKit): Boolean = try {
        nativeStart(
            samples = kit.samples.mapKeys { it.key.ordinal }.mapValues { it.value.pcm },
            sampleRate = kit.sampleRate
        )
    } catch (failure: Throwable) {
        Log.e(TAG, "nativeStart failed", failure)
        false
    }

    private fun stopNativeSafely(message: String) {
        runCatching { nativeStop() }.onFailure { Log.w(TAG, message, it) }
    }

    private inner class NativeBackend : ActiveAudioBackend {
        override val kind = AudioBackend.NATIVE_OBOE

        override fun trigger(pad: DrumPad, velocity: Float) {
            runCatching { nativeTrigger(pad.ordinal, velocity) }
                .onFailure { Log.w(TAG, "nativeTrigger failed", it) }
        }

        override fun setMasterVolume(volume: Float) {
            runCatching { nativeSetVolume(volume) }
                .onFailure { Log.w(TAG, "nativeSetVolume failed", it) }
        }

        override fun droppedTriggerCount(): Long = nativeDroppedTriggerCount()
        override fun xRunCount(): Long = nativeXRunCount()
        override fun streamError(): Int = nativeStreamError()
        override fun stop() = stopNativeSafely("nativeStop failed")
    }

    private data class PendingTrigger(val pad: DrumPad, val velocity: Float)

    companion object {
        private const val TAG = "DrumEngine"
        private const val HEALTH_POLL_MS = 250L
        private const val RECOVERY_THREAD_NAME = "VM-Audio-Recovery"
        private const val MAX_PENDING_TRIGGERS = 16
    }

    // ---- JNI ----
    private external fun nativeStart(samples: Map<Int, FloatArray>, sampleRate: Int): Boolean
    private external fun nativeTrigger(padOrdinal: Int, velocity: Float)
    private external fun nativeDroppedTriggerCount(): Long
    private external fun nativeStreamError(): Int
    private external fun nativeXRunCount(): Long
    private external fun nativeSetVolume(volume: Float)
    private external fun nativeStop()
}
