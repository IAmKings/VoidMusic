package com.electrodig.voidmusic.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import com.electrodig.voidmusic.R
import com.electrodig.voidmusic.detection.color.DrumPad

/**
 * AudioTrack (SoundPool) fallback drum engine for devices where the Oboe native
 * library is unavailable (emulators, non-AAudio devices, or before NDK is set up).
 *
 * SoundPool is purpose-built for short sound effects and provides low-latency
 * concurrent playback with hardware mixing where supported.
 *
 * Latency target: < 80 ms (vs Oboe < 40 ms).
 */
class SoundPoolDrumEngine(private val context: Context, private val kit: Kit) {

    private data class PendingTrigger(val pad: DrumPad, val velocity: Float)

    @Volatile private var ready = false
    @Volatile private var soundPool: SoundPool? = null
    private val soundIds = mutableMapOf<DrumPad, Int>()
    private val pendingTriggers = ArrayDeque<PendingTrigger>()
    private var pendingLoads = 0
    private var failedLoads = 0
    @Volatile private var masterVolume = 1f

    fun start(): Boolean {
        if (soundPool != null) return true

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(16)
            .setAudioAttributes(attrs)
            .build()

        val pool = soundPool ?: return false
        pool.setOnLoadCompleteListener { completedPool, _, status ->
            val queued: List<PendingTrigger>
            synchronized(this) {
                if (soundPool !== completedPool) return@setOnLoadCompleteListener
                if (status != 0) {
                    Log.e(TAG, "SoundPool sample failed to load (status=$status)")
                    failedLoads += 1
                }
                pendingLoads -= 1
                if (pendingLoads != 0) return@setOnLoadCompleteListener

                ready = failedLoads == 0 && soundIds.size == DrumPad.entries.size
                queued = if (ready) {
                    pendingTriggers.toList().also { pendingTriggers.clear() }
                } else {
                    pendingTriggers.clear()
                    emptyList()
                }
            }
            if (queued.isNotEmpty()) queued.forEach { play(it.pad, it.velocity) }
            if (ready) {
                Log.i(TAG, "SoundPool fallback ready with ${soundIds.size} samples")
            } else {
                Log.e(TAG, "SoundPool fallback failed to prepare all samples")
            }
        }

        pendingLoads = 0
        failedLoads = 0
        for (pad in DrumPad.entries) {
            val sample = kit.samples[pad]
            if (sample == null) {
                failedLoads += 1
                continue
            }
            val resId = sample.rawResId
            val soundId = pool.load(context, resId, 1)
            if (soundId == 0) {
                failedLoads += 1
                pendingLoads -= 1
                continue
            }
            soundIds[pad] = soundId
            pendingLoads += 1
        }

        if (soundIds.isEmpty()) {
            Log.e(TAG, "No samples loaded")
            pool.release()
            soundPool = null
            return false
        }

        Log.i(TAG, "SoundPool fallback loading ${soundIds.size} samples")
        return true
    }

    fun trigger(pad: DrumPad, velocity: Float) {
        val normalizedVelocity = velocity.coerceIn(0f, 1f)
        synchronized(this) {
            if (!ready) {
                if (soundPool != null) {
                    if (pendingTriggers.size == MAX_PENDING_TRIGGERS) pendingTriggers.removeFirst()
                    pendingTriggers += PendingTrigger(pad, normalizedVelocity)
                }
                return
            }
        }
        play(pad, normalizedVelocity)
    }

    private fun play(pad: DrumPad, velocity: Float) {
        val soundId = soundIds[pad] ?: return
        val vol = (0.4f + 0.6f * velocity) * masterVolume
        soundPool?.play(soundId, vol, vol, 1, 0, 1f)
    }

    fun setMasterVolume(volume: Float) {
        masterVolume = volume.coerceIn(0f, 1f)
    }

    fun stop() {
        val pool = synchronized(this) {
            val current = soundPool ?: return
            soundPool = null
            soundIds.clear()
            pendingTriggers.clear()
            pendingLoads = 0
            failedLoads = 0
            ready = false
            current
        }
        pool.release()
        Log.i(TAG, "SoundPool stopped")
    }
    companion object {
        private const val TAG = "SoundPoolEngine"
        private const val MAX_PENDING_TRIGGERS = 16
    }
}
