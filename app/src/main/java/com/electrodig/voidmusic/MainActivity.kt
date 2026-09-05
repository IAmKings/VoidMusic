package com.electrodig.voidmusic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.electrodig.voidmusic.session.SessionViewModel
import com.electrodig.voidmusic.ui.nav.initialDestination
import com.electrodig.voidmusic.ui.nav.VoidMusicNavHost
import com.electrodig.voidmusic.ui.theme.VoidMusicTheme

/**
 * Single-Activity host for the whole app (PRD §5.3). All screens are Compose
 * destinations composed inside [VoidMusicNavHost].
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
            VoidMusicTheme {
                val viewModel: SessionViewModel = viewModel()
                val onboardingState by viewModel.onboardingState.collectAsStateWithLifecycle()

                if (onboardingState.isLoaded) {
                    VoidMusicNavHost(
                        startRoute = initialDestination(onboardingState.completed),
                        onOnboardingComplete = viewModel::completeOnboarding
                    )
                } else {
                    Surface(modifier = Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) }
                }
            }
        }
    }
}
