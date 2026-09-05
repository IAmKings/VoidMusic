package com.electrodig.voidmusic.audio

import com.electrodig.voidmusic.detection.color.DrumPad
import com.electrodig.voidmusic.detection.grid.SequenceState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Seam shared by the native drum engine and deterministic transport tests. */
fun interface AudioTrigger {
    fun trigger(pad: DrumPad, velocity: Float)
}

/** Monotonic time source; wall-clock changes must never alter musical timing. */
fun interface MonotonicClock {
    fun nowNanos(): Long
}

internal fun interface TransportSleeper {
    suspend fun sleepNanos(durationNanos: Long)
}

private object SystemMonotonicClock : MonotonicClock {
    override fun nowNanos(): Long = System.nanoTime()
}

private object CoroutineTransportSleeper : TransportSleeper {
    override suspend fun sleepNanos(durationNanos: Long) {
        if (durationNanos <= 0L) return
        delay(
            (durationNanos + Transport.NANOS_PER_MS - 1L) / Transport.NANOS_PER_MS
        )
    }
}

/**
 * Absolute-deadline 16-step transport (PRD F3 / §9.5).
 *
 * Each deadline derives from the prior musical deadline, not from completion of
 * the previous tick, so work performed by a tick does not accumulate as tempo
 * drift. If the process wakes more than one step late, expired steps are skipped
 * instead of emitted as a burst that would sound like an unintended flam.
 */
class Transport internal constructor(
    private val audioTrigger: AudioTrigger,
    private val clock: MonotonicClock,
    private val sleeper: TransportSleeper,
    private val scope: CoroutineScope
) {
    constructor(drumEngine: DrumEngine) : this(
        audioTrigger = AudioTrigger(drumEngine::trigger),
        clock = SystemMonotonicClock,
        sleeper = CoroutineTransportSleeper,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    )

    private val _state = MutableStateFlow(SequenceState())
    val state: StateFlow<SequenceState> = _state.asStateFlow()

    private val released = AtomicBoolean(false)
    private var tickerJob: Job? = null

    val isPlaying: Boolean get() = _state.value.isPlaying
    val bpm: Int get() = _state.value.bpm
    val currentStep: Int get() = _state.value.currentStep

    fun setBpm(value: Int) {
        _state.update { it.copy(bpm = value.coerceIn(MIN_BPM, MAX_BPM)) }
    }

    /** Toggle one step by publishing a deeply independent immutable snapshot. */
    fun toggleStep(row: Int, step: Int) {
        _state.update { it.withCellToggled(row, step) }
    }

    fun clear() {
        _state.update(SequenceState::cleared)
    }

    /** Restores a defensive copy of persisted data without resuming playback. */
    fun restore(bpm: Int, grid: List<List<Boolean>>) {
        val safeGrid = List(SequenceState.DEFAULT_ROWS) { row ->
            List(SequenceState.DEFAULT_STEPS) { step ->
                grid.getOrNull(row)?.getOrNull(step) ?: false
            }
        }
        _state.update {
            it.copy(
                bpm = bpm.coerceIn(MIN_BPM, MAX_BPM),
                grid = safeGrid,
                currentStep = 0,
                isPlaying = false
            )
        }
    }

    /** Start the loop. Idempotent; a released transport cannot restart. */
    @Synchronized
    fun play() {
        if (released.get() || tickerJob?.isActive == true) return
        _state.update { it.copy(isPlaying = true) }
        tickerJob = scope.launch { runLoop() }
    }

    /** Stop the loop and reset the play-head to step 0. */
    @Synchronized
    fun stop() {
        tickerJob?.cancel()
        tickerJob = null
        _state.update { it.copy(isPlaying = false, currentStep = 0) }
    }

    private suspend fun runLoop() {
        var nextDeadlineNanos = clock.nowNanos()
        while (currentCoroutineContext().isActive) {
            val beforeTick = _state.value
            val stepDurationNanos = stepDurationNanos(beforeTick.bpm)
            val nowNanos = clock.nowNanos()
            val expiredSteps = if (nowNanos > nextDeadlineNanos) {
                ((nowNanos - nextDeadlineNanos) / stepDurationNanos).toInt()
            } else {
                0
            }
            if (expiredSteps > 0) {
                _state.update { sequence ->
                    sequence.copy(
                        currentStep = (sequence.currentStep + expiredSteps) % sequence.steps
                    )
                }
                nextDeadlineNanos += expiredSteps * stepDurationNanos
            }

            val sequence = _state.value
            val step = sequence.currentStep
            SequenceState.ROW_PADS.forEachIndexed { row, pad ->
                if (sequence.isOn(row, step)) audioTrigger.trigger(pad, DEFAULT_VELOCITY)
            }
            _state.update { current ->
                current.copy(currentStep = (current.currentStep + 1) % current.steps)
            }

            nextDeadlineNanos += stepDurationNanos
            sleeper.sleepNanos(nextDeadlineNanos - clock.nowNanos())
        }
    }

    /** Release the clock coroutine (PRD §4.4 background release). */
    @Synchronized
    fun release() {
        if (!released.compareAndSet(false, true)) return
        stop()
        scope.cancel()
    }

    internal companion object {
        const val MIN_BPM = 40
        const val MAX_BPM = 220
        const val NANOS_PER_MS = 1_000_000L
        const val NANOS_PER_MINUTE = 60_000_000_000L
        const val SIXTEENTHS_PER_BEAT = 4L
        const val DEFAULT_VELOCITY = 0.9f

        fun stepDurationNanos(bpm: Int): Long =
            NANOS_PER_MINUTE / bpm.coerceIn(MIN_BPM, MAX_BPM) / SIXTEENTHS_PER_BEAT
    }
}
