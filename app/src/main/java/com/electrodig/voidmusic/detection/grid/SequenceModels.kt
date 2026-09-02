package com.electrodig.voidmusic.detection.grid

import com.electrodig.voidmusic.detection.color.DrumPad

/**
 * The 4×16 step-sequencer state (PRD §8.1 SequenceState / F3).
 *
 * - 4 rows, one per drum voice (mapped by [rowToPad]).
 * - 16 columns = sixteenth-note steps in one bar.
 * - [grid][row][step] == true means that step triggers its row's pad when the
 *   play-head sweeps past it.
 *
 * [currentStep] advances under the [com.electrodig.voidmusic.audio.Transport]
 * clock; the overlay reads it to render the sweeping highlight.
 */
data class SequenceState(
    val rows: Int = DEFAULT_ROWS,
    val steps: Int = DEFAULT_STEPS,
    val grid: List<MutableList<Boolean>> = List(rows) { MutableList(steps) { false } },
    val bpm: Int = DEFAULT_BPM,
    val currentStep: Int = 0,
    val isPlaying: Boolean = false
) {
    /** Safe accessor that clamps indices. */
    fun isOn(row: Int, step: Int): Boolean =
        row in 0 until rows && step in 0 until steps && grid[row][step]

    fun copyWithGrid(newGrid: List<List<Boolean>>): SequenceState =
        copy(grid = newGrid.map { it.toMutableList() })

    companion object {
        const val DEFAULT_ROWS = 4
        const val DEFAULT_STEPS = 16
        const val DEFAULT_BPM = 110

        /** Row index → drum voice (PRD F3.1: 4 rows = 4 voices). */
        val ROW_PADS: List<DrumPad> = listOf(
            DrumPad.KICK, DrumPad.SNARE, DrumPad.CLAP, DrumPad.HIHAT
        )
    }
}
