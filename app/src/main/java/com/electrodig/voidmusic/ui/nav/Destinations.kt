package com.electrodig.voidmusic.ui.nav

/**
 * Single source of truth for navigation destinations. Kept as a simple enum
 * rather than a full nav graph because the flow is linear (PRD §7.2 Flow A).
 */
sealed class Destination(val route: String) {
    data object Onboarding : Destination("onboarding")
    data object Permission : Destination("permission")
    data object Main : Destination("main")
    data object Settings : Destination("settings")
}
