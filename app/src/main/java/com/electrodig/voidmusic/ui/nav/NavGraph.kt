package com.electrodig.voidmusic.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.electrodig.voidmusic.ui.screens.MainScreen
import com.electrodig.voidmusic.ui.screens.OnboardingScreen
import com.electrodig.voidmusic.ui.screens.SettingsScreen

/**
 * Linear navigation graph (PRD §7.2 Flow A):
 * onboarding → main (which itself handles the permission gate) → settings.
 */
@Composable
fun VoidMusicNavHost(startRoute: String = Destination.Onboarding.route) {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = startRoute) {
        composable(Destination.Onboarding.route) {
            OnboardingScreen(onComplete = {
                nav.navigate(Destination.Main.route) {
                    popUpTo(Destination.Onboarding.route) { inclusive = true }
                }
            })
        }
        composable(Destination.Main.route) {
            MainScreen(onOpenSettings = { nav.navigate(Destination.Settings.route) })
        }
        composable(Destination.Settings.route) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
