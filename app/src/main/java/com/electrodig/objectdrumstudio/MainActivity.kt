package com.electrodig.objectdrumstudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.electrodig.objectdrumstudio.ui.nav.ObjectDrumNavHost
import com.electrodig.objectdrumstudio.ui.theme.ObjectDrumStudioTheme

/**
 * Single-Activity host for the whole app (PRD §5.3). All screens are Compose
 * destinations composed inside [ObjectDrumNavHost].
 *
 * Edge-to-edge is enabled so the camera viewfinder renders full-bleed behind
 * the system bars; the HUD applies its own status-bar padding.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent {
            ObjectDrumStudioTheme {
                ObjectDrumNavHost()
            }
        }
    }
}
