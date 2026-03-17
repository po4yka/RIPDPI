package com.poyka.ripdpi.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RipDpiNavHostLogicTest {
    @Test
    fun `top level routes stay in figma bottom bar order`() {
        assertEquals(
            listOf("home", "config", "diagnostics", "settings"),
            topLevelRouteOrder,
        )
        assertEquals(topLevelRouteOrder, Route.topLevel.map(Route::route))
    }

    @Test
    fun `top level route helper only matches bottom navigation destinations`() {
        assertTrue(Route.Home.route.isTopLevelRoute())
        assertTrue(Route.Settings.route.isTopLevelRoute())
        assertFalse(Route.History.route.isTopLevelRoute())
        assertFalse(null.isTopLevelRoute())
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

    @Test
    fun `history route stays off the bottom navigation`() {
        assertTrue(Route.all.contains(Route.History))
        assertFalse(Route.topLevel.contains(Route.History))
    }
}
