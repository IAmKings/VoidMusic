package com.electrodig.voidmusic.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class StepPlaybackGateTest {
    @Test
    fun `stopped sequence without calibration opens calibration`() {
        assertEquals(
            StepPlaybackAction.CALIBRATE,
            stepPlaybackAction(isPlaying = false, isCalibrated = false)
        )
    }

    @Test
    fun `stopped calibrated sequence starts playback`() {
        assertEquals(
            StepPlaybackAction.PLAY,
            stepPlaybackAction(isPlaying = false, isCalibrated = true)
        )
    }

    @Test
    fun `playing sequence can always stop`() {
        assertEquals(
            StepPlaybackAction.STOP,
            stepPlaybackAction(isPlaying = true, isCalibrated = false)
        )
    }
}
