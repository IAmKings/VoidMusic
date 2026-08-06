package com.electrodig.objectdrumstudio.detection.hand

import androidx.compose.ui.geometry.Offset

/**
 * Indices of the 21 MediaPipe hand landmarks we care about.
 *
 * The web app primarily uses landmark **8 (index fingertip)** as the trigger
 * point and landmark 5 (index PIP) to derive direction. These constants keep
 * the detection layer free of magic numbers.
 */
object LandmarkIndex {
    const val WRIST = 0
    const val INDEX_MCP = 5
    const val INDEX_FINGERTIP = 8
}

/**
 * A single normalised landmark. Coordinates are in [0,1] relative to the source
 * image, with the MediaPipe convention: x = horizontal (left→right), y =
 * vertical (top→bottom), z = depth (towards camera = negative).
 */
data class NormalizedLandmark(val x: Float, val y: Float, val z: Float = 0f)

/**
 * One detected hand: its 21 landmarks plus a handedness label ("Left"/"Right").
 * `imageWidth`/`imageHeight` record the analysis-frame size the normalised
 * coordinates were produced from, so the UI can scale them to the viewfinder.
 */
data class Hand(
    val landmarks: List<NormalizedLandmark>,
    val handedness: String,
    val imageWidth: Int,
    val imageHeight: Int
) {
    /** Convenience accessor for the index fingertip (the trigger point). */
    val fingertip: NormalizedLandmark
        get() = landmarks.getOrNull(LandmarkIndex.INDEX_FINGERTIP)
            ?: NormalizedLandmark(0f, 0f)
}
