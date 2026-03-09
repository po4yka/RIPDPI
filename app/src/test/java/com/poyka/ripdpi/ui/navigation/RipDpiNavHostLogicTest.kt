package com.poyka.ripdpi.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RipDpiNavHostLogicTest {
    @Test
    fun `top level routes stay in figma bottom bar order`() {
        assertEquals(
            listOf("home", "config", "logs", "settings"),
            topLevelRouteOrder,
        )
    }

    @Test
    fun `launch home request navigates away from top level routes`() {
        assertTrue(
            shouldNavigateToHomeFromLaunchRequest(
                launchHomeRequested = true,
                currentRoute = Route.Settings.route,
            ),
        )
    }

    @Test
    fun `launch home request is blocked during biometric gate`() {
        assertFalse(
            shouldNavigateToHomeFromLaunchRequest(
                launchHomeRequested = true,
                currentRoute = Route.BiometricPrompt.route,
            ),
        )
    }

    @Test
    fun `launch home request is ignored when already on home`() {
        assertFalse(
            shouldNavigateToHomeFromLaunchRequest(
                launchHomeRequested = true,
                currentRoute = Route.Home.route,
            ),
        )
    }
}
