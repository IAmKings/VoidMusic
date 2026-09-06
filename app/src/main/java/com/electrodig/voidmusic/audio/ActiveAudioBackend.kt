package com.electrodig.voidmusic.audio

import com.electrodig.voidmusic.detection.color.DrumPad

/** One fully started backend session. Construction failure must return no session. */
internal interface ActiveAudioBackend {
    val kind: AudioBackend

    fun trigger(pad: DrumPad, velocity: Float)
    fun setMasterVolume(volume: Float)
    fun droppedTriggerCount(): Long = 0L
    fun xRunCount(): Long = 0L
    fun streamError(): Int = 0
    fun stop()
}

internal fun interface AudioBackendStarter {
    fun start(kit: PreparedKit): ActiveAudioBackend?
}
