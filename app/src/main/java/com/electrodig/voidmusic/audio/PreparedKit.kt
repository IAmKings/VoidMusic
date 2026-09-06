package com.electrodig.voidmusic.audio

import android.content.Context
import android.media.SoundPool
import com.electrodig.voidmusic.detection.color.DrumPad
import java.io.File

/** Fully decoded kit that can enter the real-time audio layer without file I/O. */
data class PreparedKit(
    val id: String,
    val name: String,
    val sampleRate: Int,
    val samples: Map<DrumPad, PreparedSample>
) {
    init {
        require(sampleRate > 0)
        require(samples.keys == DrumPad.entries.toSet()) { "Prepared kit must contain every drum pad" }
        require(samples.values.all { it.pcm.isNotEmpty() }) { "Prepared samples must not be empty" }
    }
}

/** Public playback data keeps the stable source identity but hides managed absolute paths. */
class PreparedSample internal constructor(
    val source: AudioSampleSource,
    val pcm: FloatArray,
    internal val soundPoolSource: SoundPoolSampleSource
)

/** Capability object used only by the SoundPool adapter; paths never cross into UI state. */
internal sealed interface SoundPoolSampleSource {
    fun load(context: Context, pool: SoundPool): Int

    data class BuiltIn(private val rawResId: Int) : SoundPoolSampleSource {
        override fun load(context: Context, pool: SoundPool): Int = pool.load(context, rawResId, 1)
    }

    class ManagedFile(private val file: File) : SoundPoolSampleSource {
        override fun load(context: Context, pool: SoundPool): Int = pool.load(file.absolutePath, 1)
    }
}
