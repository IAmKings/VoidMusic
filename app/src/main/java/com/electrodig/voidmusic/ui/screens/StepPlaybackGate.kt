package com.electrodig.voidmusic.ui.screens

internal enum class StepPlaybackAction {
    STOP,
    PLAY,
    CALIBRATE
}

internal fun stepPlaybackAction(
    isPlaying: Boolean,
    isCalibrated: Boolean
): StepPlaybackAction = when {
    isPlaying -> StepPlaybackAction.STOP
    isCalibrated -> StepPlaybackAction.PLAY
    else -> StepPlaybackAction.CALIBRATE
}
