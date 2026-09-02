package com.electrodig.objectdrumstudio.audio

import com.electrodig.objectdrumstudio.detection.color.DrumPad
import com.electrodig.objectdrumstudio.detection.grid.SequenceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Step-sequencer transport / clock (PRD F3 / §9.5 Transport).
 *
 * Drives a 16-step loop: every sixteenth-note interval the play-head advances
 * one step, and any row whose cell is lit at that step triggers its drum voice
 * on the [DrumEngine]. The current step is published as a [StateFlow] so the
 * overlay can render the sweeping highlight.
 *
 * Timing uses a fixed-delay coroutine loop calibrated to the BPM. For an AR
 * sketch tool this is plenty tight; if sample-accurate timing is ever needed
 * the Oboe callback itself can drive the clock (a future enhancement).
 *
 * @param drumEngine target for trigger events (no-op when native audio isn't built).
 */
class Transport(
    private val drumEngine: DrumEngine
) {
    private val _state = MutableStateFlow(SequenceState())
    val state: StateFlow<SequenceState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickerJob: Job? = null

    val isPlaying: Boolean get() = _state.value.isPlaying
    val bpm: Int get() = _state.value.bpm
    val currentStep: Int get() = _state.value.currentStep

    fun setBpm(value: Int) {
        _state.update { it.copy(bpm = value.coerceIn(40, 220)) }
    }

    /** Toggle the lit state of [row]/[step] (PRD F3.3). */
    fun toggleStep(row: Int, step: Int) {
        _state.update { seq ->
            if (row !in 0 until seq.rows || step !in 0 until seq.steps) return@update seq
            seq.grid[row][step] = !seq.grid[row][step]
            seq.copy() // emit a new instance so collectors recompose
        }
    }

    fun clear() {
        _state.update { seq ->
            seq.grid.forEach { row -> row.indices.forEach { row[it] = false } }
            seq.copy(currentStep = 0)
        }
    }

    /** Restores the persisted grid without resuming playback. */
    fun restore(bpm: Int, grid: List<List<Boolean>>) {
        val safeGrid = List(SequenceState.DEFAULT_ROWS) { row ->
            MutableList(SequenceState.DEFAULT_STEPS) { step ->
                grid.getOrNull(row)?.getOrNull(step) ?: false
            }
        }
        _state.update {
            it.copy(
                bpm = bpm.coerceIn(40, 220),
                grid = safeGrid,
                currentStep = 0,
                isPlaying = false
            )
        }
    }

    /** Start the loop (PRD F3.4). Idempotent. */
    fun play() {
        if (tickerJob?.isActive == true) return
        _state.update { it.copy(isPlaying = true) }
        tickerJob = scope.launch { runLoop() }
    }

    /** Stop the loop and reset the play-head to step 0. */
    fun stop() {
        tickerJob?.cancel()
        tickerJob = null
        _state.update { it.copy(isPlaying = false, currentStep = 0) }
    }

    private suspend fun runLoop() {
        while (scope.isActive) {
            val seq = _state.value
            val step = seq.currentStep
            // Fire every lit row at this step.
            SequenceState.ROW_PADS.forEachIndexed { row, pad ->
                if (seq.isOn(row, step)) {
                    drumEngine.trigger(pad, velocity = 0.9f)
                }
            }
            // Advance, wrapping at the bar end.
            val next = (step + 1) % seq.steps
            _state.update { it.copy(currentStep = next) }
            // Sixteenth-note duration from BPM (4 sixteenths per quarter note).
            val sixteenthMs = 60_000f / seq.bpm / 4f
            delay(sixteenthMs.toLong())
        }
    }

    /** Release the clock coroutine (PRD §4.4 background release). */
    fun release() {
        stop()
        scope.cancel()
    }
}
