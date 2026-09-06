package com.electrodig.voidmusic.audio

import com.electrodig.voidmusic.R
import com.electrodig.voidmusic.detection.color.DrumPad
/** Stable, storage-agnostic origin of one drum sample. */
sealed interface AudioSampleSource {
    data class BuiltIn(val rawResId: Int) : AudioSampleSource
    data class Imported(val storageKey: String) : AudioSampleSource
}

/**
 * A named drum kit: one sample per [DrumPad] (PRD §8.1 Kit / F6.6 / F7).
 */
data class PlayableKit(
    val id: String,
    val name: String,
    val samples: Map<DrumPad, AudioSampleSource>
)

/**
 * Catalogue of built-in kits (PRD F7.1). The second kit deliberately remaps the
 * bundled samples, so selecting it changes the sound for every pad even before
 * a future release adds another sample pack.
 */
object BuiltInKits {

    val DEFAULT = PlayableKit(
        id = "default",
        name = "默认套鼓",
        samples = DrumPad.entries.associateWith { pad ->
            AudioSampleSource.BuiltIn(rawResId = rawIdFor(pad))
        }
    )

    val ELECTRO = PlayableKit(
        id = "electro",
        name = "电子打击",
        samples = mapOf(
            DrumPad.KICK to AudioSampleSource.BuiltIn(R.raw.tom),
            DrumPad.SNARE to AudioSampleSource.BuiltIn(R.raw.clap),
            DrumPad.CLAP to AudioSampleSource.BuiltIn(R.raw.hihat),
            DrumPad.TOM to AudioSampleSource.BuiltIn(R.raw.snare),
            DrumPad.HIHAT to AudioSampleSource.BuiltIn(R.raw.kick)
        )
    )

    /** All selectable built-in kits, in display order. */
    val all: List<PlayableKit> = listOf(DEFAULT, ELECTRO)

    fun byIndex(i: Int): PlayableKit = all.getOrElse(i) { DEFAULT }

    fun byId(id: String?): PlayableKit = all.firstOrNull { it.id == id } ?: DEFAULT

    private fun rawIdFor(pad: DrumPad): Int = when (pad) {
        DrumPad.KICK  -> R.raw.kick
        DrumPad.SNARE -> R.raw.snare
        DrumPad.CLAP  -> R.raw.clap
        DrumPad.TOM   -> R.raw.tom
        DrumPad.HIHAT -> R.raw.hihat
    }
}
