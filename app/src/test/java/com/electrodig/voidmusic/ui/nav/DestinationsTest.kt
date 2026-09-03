package com.electrodig.voidmusic.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class DestinationsTest {
    @Test
    fun `first launch starts at onboarding`() {
        assertEquals(Destination.Onboarding.route, initialDestination(onboardingCompleted = false))
    }

    @Test
    fun `completed onboarding starts at main`() {
        assertEquals(Destination.Main.route, initialDestination(onboardingCompleted = true))
    }
}
