package com.electrodig.voidmusic.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.electrodig.voidmusic.session.SessionViewModel
import com.electrodig.voidmusic.ui.screens.MainScreen
import com.electrodig.voidmusic.ui.screens.OnboardingScreen
import com.electrodig.voidmusic.ui.screens.SettingsScreen

/**
 * Linear navigation graph (PRD §7.2 Flow A):
 * onboarding → main (which itself handles the permission gate) → settings.
 */
@Composable
fun VoidMusicNavHost(
    startRoute: String,
    viewModel: SessionViewModel,
    onOnboardingComplete: () -> Unit
) {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = startRoute) {
        composable(Destination.Onboarding.route) {
            OnboardingScreen(onComplete = {
                onOnboardingComplete()
                if (!nav.popBackStack(Destination.Main.route, inclusive = false)) {
                    nav.navigate(Destination.Main.route) {
                        popUpTo(Destination.Onboarding.route) { inclusive = true }
                    }
                }
            })
        }
        composable(Destination.Main.route) {
            MainScreen(
                viewModel = viewModel,
                onOpenSettings = { nav.navigate(Destination.Settings.route) }
            )
        }
        composable(Destination.Settings.route) {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { nav.popBackStack() },
                onOpenGuide = {
                    nav.navigate(Destination.Onboarding.route) { launchSingleTop = true }
                }
            )
        }
    }
}
