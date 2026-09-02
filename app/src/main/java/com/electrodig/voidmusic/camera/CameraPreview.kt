package com.electrodig.voidmusic.camera

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Full-bleed camera viewfinder. The [PreviewView] is created once and reused;
 * [onPreviewViewReady] hands it to the caller so the [CameraModule] can bind a
 * Preview use case to it once permission is granted.
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onPreviewViewReady: (PreviewView) -> Unit,
    onViewportSizeChanged: (width: Int, height: Int) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current

    val previewView = remember {
        PreviewView(context).apply {
            // Fill the surface so the viewfinder is edge-to-edge.
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = {
                previewView.also { onPreviewViewReady(it) }
            },
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { onViewportSizeChanged(it.width, it.height) }
        )
    }
}
