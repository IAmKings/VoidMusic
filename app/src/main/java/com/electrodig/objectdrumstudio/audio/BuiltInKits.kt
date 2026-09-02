package com.electrodig.objectdrumstudio.audio

import com.electrodig.objectdrumstudio.R
import com.electrodig.objectdrumstudio.detection.color.DrumPad
import kotlinx.serialization.Serializable

/**
 * Reference to a single sample (PRD §8.1 SampleRef). Built-in samples live in
 * `res/raw`; user-imported samples (P2, future) use a file URI.
 */
@Serializable
data class SampleRef(
    /** res/raw resource id for built-in samples, or null if external. */
    val rawResId: Int,
    val isBuiltIn: Boolean = true
)

/**
 * A named drum kit: one sample per [DrumPad] (PRD §8.1 Kit / F6.6 / F7).
 */
@Serializable
data class Kit(
    val id: String,
    val name: String,
    val samples: Map<DrumPad, SampleRef>
)

/**
 * Catalogue of built-in kits (PRD F7.1). The second kit deliberately remaps the
 * bundled samples, so selecting it changes the sound for every pad even before
 * a future release adds another sample pack.
 */
object BuiltInKits {

    val DEFAULT = Kit(
        id = "default",
        name = "默认套鼓",
        samples = DrumPad.entries.associateWith { pad ->
            SampleRef(rawResId = rawIdFor(pad))
        }
    )

    val ELECTRO = Kit(
        id = "electro",
        name = "电子打击",
        samples = mapOf(
            DrumPad.KICK to SampleRef(R.raw.tom),
            DrumPad.SNARE to SampleRef(R.raw.clap),
            DrumPad.CLAP to SampleRef(R.raw.hihat),
            DrumPad.TOM to SampleRef(R.raw.snare),
            DrumPad.HIHAT to SampleRef(R.raw.kick)
        )
    )

    /** All selectable built-in kits, in display order. */
    val all: List<Kit> = listOf(DEFAULT, ELECTRO)

    fun byIndex(i: Int): Kit = all.getOrElse(i) { DEFAULT }

    private fun rawIdFor(pad: DrumPad): Int = when (pad) {
        DrumPad.KICK  -> R.raw.kick
        DrumPad.SNARE -> R.raw.snare
        DrumPad.CLAP  -> R.raw.clap
        DrumPad.TOM   -> R.raw.tom
        DrumPad.HIHAT -> R.raw.hihat
    }
}
