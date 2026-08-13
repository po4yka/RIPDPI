package com.poyka.ripdpi.ui.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Test

class RipDpiNavigatorTest {
    @Test
    fun `session authentication only satisfies the biometric startup gate`() {
        assertEquals(true, shouldStartNavigationGate(Route.Onboarding, isSessionAuthenticated = true))
        assertEquals(false, shouldStartNavigationGate(Route.BiometricPrompt, isSessionAuthenticated = true))
        assertEquals(true, shouldStartNavigationGate(Route.BiometricPrompt, isSessionAuthenticated = false))
        assertEquals(false, shouldStartNavigationGate(Route.Home, isSessionAuthenticated = false))
    }

    @Test
    fun `navigator retains tabs resets external home and blocks gates`() {
        val state = navigationState()
        val navigator = RipDpiNavigator(state)

        navigator.navigateTopLevel(Route.Settings)
        navigator.navigate(Route.DnsSettings)
        navigator.navigateTopLevel(Route.Config)
        navigator.navigateTopLevel(Route.Settings)
        assertEquals(listOf(Route.Settings, Route.DnsSettings), state.currentStack.toList())

        navigator.resetToHome()
        navigator.navigateTopLevel(Route.Settings)
        assertEquals(listOf(Route.Settings), state.currentStack.toList())

        navigator.replaceAll(Route.BiometricPrompt)
        navigator.navigate(Route.Config)
        assertEquals(Route.BiometricPrompt, state.currentRoute)

        navigator.resetToHome()
        navigator.navigateTopLevel(Route.Settings)
        assertEquals(listOf(Route.Settings), state.currentStack.toList())
        navigator.navigateTopLevel(Route.Home)
        assertEquals(Route.Home, state.currentRoute)
    }

    @Test
    fun `onboarding can open trusted DNS and return without removing gate`() {
        val state = navigationState(startRoute = Route.Onboarding)
        val navigator = RipDpiNavigator(state)

        navigator.navigateFromGate(Route.DnsSettings)
        assertEquals(Route.DnsSettings, state.currentRoute)

        navigator.goBack()
        assertEquals(Route.Onboarding, state.currentRoute)
        assertEquals(true, state.isGateActive)
    }

    private fun navigationState(startRoute: Route = Route.Home): RipDpiNavigationState =
        RipDpiNavigationState(
            startRoute = startRoute,
            selectedTopLevelState = mutableStateOf<NavKey>(Route.Home),
            backStacks =
                Route.topLevel.associateWith { route ->
                    NavBackStack<NavKey>(route)
                },
            gateStack = NavBackStack<NavKey>(startRoute),
            initialGateActive = startRoute in gateRoutes,
        )
}
