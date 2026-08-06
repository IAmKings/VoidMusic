package com.electrodig.objectdrumstudio.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import com.electrodig.objectdrumstudio.R
import com.electrodig.objectdrumstudio.detection.color.DrumPad

/**
 * AudioTrack (SoundPool) fallback drum engine for devices where the Oboe native
 * library is unavailable (emulators, non-AAudio devices, or before NDK is set up).
 *
 * SoundPool is purpose-built for short sound effects and provides low-latency
 * concurrent playback with hardware mixing where supported.
 *
 * Latency target: < 80 ms (vs Oboe < 40 ms).
 */
class SoundPoolDrumEngine(private val context: Context) {

    @Volatile private var ready = false
    private var soundPool: SoundPool? = null
    private val soundIds = mutableMapOf<DrumPad, Int>()
    @Volatile private var masterVolume = 1f

    fun start(): Boolean {
        if (ready) return true

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(16)
            .setAudioAttributes(attrs)
            .build()

        for (pad in DrumPad.entries) {
            val resId = rawIdFor(pad)
            val soundId = soundPool?.load(context, resId, 1) ?: continue
            soundIds[pad] = soundId
        }

        if (soundIds.isEmpty()) {
            Log.e(TAG, "No samples loaded")
            return false
        }

        ready = true
        Log.i(TAG, "SoundPool fallback started with ${soundIds.size} samples")
        return true
    }

    fun trigger(pad: DrumPad, velocity: Float) {
        if (!ready) return
        val soundId = soundIds[pad] ?: return
        val vol = (0.4f + 0.6f * velocity.coerceIn(0f, 1f)) * masterVolume
        soundPool?.play(soundId, vol, vol, 1, 0, 1f)
    }

    fun setMasterVolume(volume: Float) {
        masterVolume = volume.coerceIn(0f, 1f)
    }

    fun stop() {
        if (!ready) return
        soundPool?.release()
        soundPool = null
        soundIds.clear()
        ready = false
        Log.i(TAG, "SoundPool stopped")
    }

    private fun rawIdFor(pad: DrumPad): Int = when (pad) {
        DrumPad.KICK -> R.raw.kick
        DrumPad.SNARE -> R.raw.snare
        DrumPad.CLAP -> R.raw.clap
        DrumPad.TOM -> R.raw.tom
        DrumPad.HIHAT -> R.raw.hihat
    }

    companion object {
        private const val TAG = "SoundPoolEngine"
    }
}
