package com.electrodig.voidmusic.detection.color

import org.junit.Test

class ColorSegmenterTest {

    @Test
    fun `construction does not require the OpenCV native library`() {
        // MainScreen constructs the segmenter during composition, before its
        // LaunchedEffect invokes OpenCvLoader. This must remain safe.
        ColorSegmenter().close()
    }
}
