package com.electrodig.voidmusic.detection.color

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ColorSegmenterInstrumentedTest {

    @Test
    fun syntheticRedAndBlueObjectsRemainDetectableAcrossWorkspaceReuse() {
        assertTrue(OpenCvLoader.ensureInitialised(ApplicationProvider.getApplicationContext()))
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.BLACK)
            drawRect(30f, 50f, 100f, 120f, Paint().apply { color = Color.RED })
            drawRect(190f, 90f, 260f, 160f, Paint().apply { color = Color.BLUE })
        }

        ColorSegmenter(downsample = 0.5f).use { segmenter ->
            repeat(3) {
                val zones = segmenter.segment(bitmap, DetectionConfig.DEFAULT, frameId = it.toLong())
                assertEquals(setOf("红", "蓝"), zones.map { zone -> zone.presetName }.toSet())
                assertTrue(zones.all { zone -> zone.normalizedCenter.x in 0f..1f })
                assertTrue(zones.all { zone -> zone.normalizedCenter.y in 0f..1f })
            }
        }
        bitmap.recycle()
    }
}
