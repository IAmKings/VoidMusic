package com.electrodig.voidmusic

import com.electrodig.voidmusic.detection.color.DrumPad
import com.electrodig.voidmusic.detection.color.DrumZone
import com.electrodig.voidmusic.detection.hand.Hand
import com.electrodig.voidmusic.detection.hand.NormalizedLandmark

/**
 * Builders for unit-test data. Keeps tests terse and centralises the magic
 * numbers (landmark count, frame size).
 */
object TestFixtures {

    /** A hand whose only meaningful landmark is the index fingertip (8). */
    fun handAtFingertip(x: Float, y: Float, handedness: String = "Right"): Hand {
        // 21 zeroed landmarks, override the fingertip.
        val landmarks = List(21) { NormalizedLandmark(0f, 0f) }.toMutableList()
        landmarks[8] = NormalizedLandmark(x, y)
        return Hand(
            landmarks = landmarks,
            handedness = handedness,
            imageWidth = 640,
            imageHeight = 480
        )
    }

    /** A drum zone centred at [cx],[cy] with a given box half-size. */
    fun zoneAt(
        id: Int,
        cx: Float,
        cy: Float,
        halfW: Float = 0.05f,
        halfH: Float = 0.05f,
        pad: DrumPad = DrumPad.KICK
    ): DrumZone = DrumZone(
        id = id,
        center = DrumZone.Point(cx, cy),
        area = halfW * halfH * 4,
        width = (halfW * 2 * 640).toInt(),
        height = (halfH * 2 * 480).toInt(),
        presetName = "test",
        mappedPad = pad,
        normalizedCenter = DrumZone.Point(cx, cy),
        normalizedBox = DrumZone.Rect(
            left = cx - halfW,
            top = cy - halfH,
            right = cx + halfW,
            bottom = cy + halfH
        )
    )
}
