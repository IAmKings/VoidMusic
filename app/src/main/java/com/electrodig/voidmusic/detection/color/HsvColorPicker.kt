package com.electrodig.voidmusic.detection.color

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Builds a bounded OpenCV HSV range from a small camera-frame colour sample. */
object HsvColorPicker {
    data class Sample(val hue: Int, val saturation: Int, val value: Int)

    fun sample(bitmap: Bitmap, normalizedX: Float, normalizedY: Float, radiusPx: Int = 12): HsvRange? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        val cx = (normalizedX.coerceIn(0f, 1f) * (bitmap.width - 1)).roundToInt()
        val cy = (normalizedY.coerceIn(0f, 1f) * (bitmap.height - 1)).roundToInt()
        val samples = ArrayList<Sample>((radiusPx * 2 + 1) * (radiusPx * 2 + 1))
        val hsv = FloatArray(3)
        for (y in (cy - radiusPx).coerceAtLeast(0)..(cy + radiusPx).coerceAtMost(bitmap.height - 1)) {
            for (x in (cx - radiusPx).coerceAtLeast(0)..(cx + radiusPx).coerceAtMost(bitmap.width - 1)) {
                Color.colorToHSV(bitmap.getPixel(x, y), hsv)
                samples += Sample(
                    hue = (hsv[0] / 2f).roundToInt().coerceIn(0, 180),
                    saturation = hsv[1].times(255).roundToInt().coerceIn(0, 255),
                    value = hsv[2].times(255).roundToInt().coerceIn(0, 255)
                )
            }
        }
        return rangeFor(samples)
    }

    /** Pure core used by tests; hue is in OpenCV's 0..180 convention. */
    fun rangeFor(
        samples: List<Sample>,
        hueBuffer: Int = 10,
        saturationBuffer: Int = 35,
        valueBuffer: Int = 35
    ): HsvRange? {
        if (samples.isEmpty()) return null
        val radians = samples.map { it.hue * 2.0 * PI / 180.0 }
        val meanHue = ((atan2(radians.sumOf(::sin), radians.sumOf(::cos)) * 180.0 / PI / 2.0) + 180.0) % 180.0
        val hMin = (meanHue.roundToInt() - hueBuffer).floorMod(181)
        val hMax = (meanHue.roundToInt() + hueBuffer).floorMod(181)
        return HsvRange(
            hMin = hMin,
            hMax = hMax,
            sMin = (samples.minOf { it.saturation } - saturationBuffer).coerceAtLeast(0),
            sMax = (samples.maxOf { it.saturation } + saturationBuffer).coerceAtMost(255),
            vMin = (samples.minOf { it.value } - valueBuffer).coerceAtLeast(0),
            vMax = (samples.maxOf { it.value } + valueBuffer).coerceAtMost(255)
        )
    }

    private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus
}
