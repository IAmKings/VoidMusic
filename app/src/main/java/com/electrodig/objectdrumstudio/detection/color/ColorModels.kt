package com.electrodig.objectdrumstudio.detection.color

import kotlinx.serialization.Serializable

/**
 * Drum voices (PRD §8.1). Each detected object maps to one of these.
 */
@Serializable
enum class DrumPad(val displayName: String) {
    KICK("Kick"),
    SNARE("Snare"),
    CLAP("Clap"),
    TOM("Tom"),
    HIHAT("Hi-Hat");

    companion object {
        /** Cycle through pads by index, used when auto-assigning new zones. */
        fun byIndex(i: Int): DrumPad = entries[i % entries.size]
    }
}

/**
 * An HSV colour range in OpenCV's convention: H ∈ [0,180], S/V ∈ [0,255].
 * Used by [ColorSegmenter] to threshold the camera frame (PRD §9.1).
 */
@Serializable
data class HsvRange(
    val hMin: Int, val hMax: Int,
    val sMin: Int, val sMax: Int,
    val vMin: Int, val vMax: Int
) {
    init {
        require(hMin in 0..180 && hMax in 0..180) { "H must be 0..180" }
        require(sMin in 0..255 && sMax in 0..255) { "S must be 0..255" }
        require(vMin in 0..255 && vMax in 0..255) { "V must be 0..255" }
    }
}

/**
 * A named colour preset bound to a drum voice (PRD §8.1 HsvPreset).
 * Multiple presets let different object colours trigger different sounds.
 */
@Serializable
data class HsvPreset(
    val name: String,
    val range: HsvRange,
    val mappedPad: DrumPad
)

/**
 * Serializable aspect-ratio bounds (ClosedFloatingPointRange isn't serializable).
 */
@Serializable
data class AspectRange(val start: Float, val endInclusive: Float) {
    operator fun contains(value: Float): Boolean = value in start..endInclusive
}

/**
 * Tunable detection parameters (PRD §8.1 DetectionConfig). Persisted across
 * sessions (PRD F8).
 *
 * @param minAreaFraction    minimum blob area as a fraction of frame area
 * @param maxAreaFraction    maximum blob area as a fraction of frame area
 * @param aspectRatio        width/height range to accept (filters elongated noise)
 * @param morphEnabled       apply morphological open/close to denoise the mask
 */
@Serializable
data class DetectionConfig(
    val presets: List<HsvPreset>,
    val minAreaFraction: Float = 0.0005f,
    val maxAreaFraction: Float = 0.25f,
    val aspectRatio: AspectRange = AspectRange(0.3f, 3.5f),
    val morphEnabled: Boolean = true
) {
    companion object {
        /**
         * Sensible starter presets covering the primary hues. Hue values are
         * OpenCV's 0..180 scale. Users refine these via the colour controls.
         */
        val DEFAULT = DetectionConfig(
            presets = listOf(
                // Red straddles the 0/180 hue boundary: hMin=160 > hMax=8 triggers
                // the wrap path, matching H=160..180 (magenta-red) and H=0..8 (pure
                // red). S/V floor 100 rejects skin (S~50-90, rarely >100) under most
                // lighting while accepting vivid red objects/lids.
                HsvPreset("红", HsvRange(160, 8, 100, 255, 50, 255), DrumPad.KICK),
                HsvPreset("蓝", HsvRange(95, 130, 80, 255, 50, 255), DrumPad.SNARE),
                HsvPreset("绿", HsvRange(45, 85, 80, 255, 50, 255), DrumPad.CLAP),
                HsvPreset("黄", HsvRange(22, 35, 80, 255, 50, 255), DrumPad.TOM)
            ),
            // Raise the minimum blob area so skin-coloured noise (freckles,
            // knuckles, fingernails) doesn't spawn tiny spurious zones.
            minAreaFraction = 0.001f,
            maxAreaFraction = 0.15f
        )
    }
}

/**
 * A detected drum zone: the colour-segmented blob that a fingertip can hit
 * (PRD §8.1 DrumZone). Coordinates are in the analysis-frame pixel space.
 */
data class DrumZone(
    val id: Int,
    val center: Point,
    val area: Float,
    val width: Int,
    val height: Int,
    val presetName: String,
    val mappedPad: DrumPad,
    /** Normalised centre [0,1] for resolution-independent rendering. */
    val normalizedCenter: Point,
    /** Normalised bounding-box [0,1] for drawing the zone outline. */
    val normalizedBox: Rect
) {
    /** Lightweight point in image space. */
    data class Point(val x: Float, val y: Float)

    /** Axis-aligned box in normalised [0,1] coordinates. */
    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float)
}
