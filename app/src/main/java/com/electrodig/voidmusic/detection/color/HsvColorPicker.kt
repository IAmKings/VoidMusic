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
        val coreRadius = (radiusPx * CORE_RADIUS_RATIO).roundToInt().coerceAtLeast(1)
        val samples = ArrayList<Sample>((coreRadius * 2 + 1) * (coreRadius * 2 + 1))
        val hsv = FloatArray(3)
        for (y in (cy - coreRadius).coerceAtLeast(0)..(cy + coreRadius).coerceAtMost(bitmap.height - 1)) {
            for (x in (cx - coreRadius).coerceAtLeast(0)..(cx + coreRadius).coerceAtMost(bitmap.width - 1)) {
                val dx = x - cx
                val dy = y - cy
                if (dx * dx + dy * dy > coreRadius * coreRadius) continue
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
        valueBuffer: Int = 35,
        minimumSaturation: Int = MINIMUM_SATURATION
    ): HsvRange? {
        val colored = samples.filter { it.saturation >= minimumSaturation }
        if (colored.isEmpty()) return null
        val radians = colored.map { it.hue * 2.0 * PI / 180.0 }
        val meanHue = ((atan2(radians.sumOf(::sin), radians.sumOf(::cos)) * 180.0 / PI / 2.0) + 180.0) % 180.0
        val hMin = (meanHue.roundToInt() - hueBuffer).floorMod(181)
        val hMax = (meanHue.roundToInt() + hueBuffer).floorMod(181)
        return HsvRange(
            hMin = hMin,
            hMax = hMax,
            sMin = (percentile(colored.map(Sample::saturation), 0.10f) - saturationBuffer).coerceAtLeast(0),
            sMax = (percentile(colored.map(Sample::saturation), 0.90f) + saturationBuffer).coerceAtMost(255),
            vMin = (percentile(colored.map(Sample::value), 0.10f) - valueBuffer).coerceAtLeast(0),
            vMax = (percentile(colored.map(Sample::value), 0.90f) + valueBuffer).coerceAtMost(255)
        )
    }

    private fun percentile(values: List<Int>, fraction: Float): Int {
        val sorted = values.sorted()
        val index = (fraction.coerceIn(0f, 1f) * sorted.lastIndex).roundToInt()
        return sorted[index]
    }

    private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus

    private const val CORE_RADIUS_RATIO = 0.6f
    private const val MINIMUM_SATURATION = 24
}
