package com.poyka.ripdpi.ui.navigation

/** App-owned navigation mutations for the Navigation 3 back stack. */
internal class RipDpiNavigator(
    private val state: RipDpiNavigationState,
) {
    val currentRoute: Route
        get() = state.currentRoute

    fun navigate(
        destination: Route,
        singleTop: Boolean = false,
    ) {
        if (state.isGateActive) return
        if (destination in Route.topLevel) {
            navigateTopLevel(destination)
        } else if (!singleTop || currentRoute != destination) {
            state.currentStack += destination
        }
    }

    fun navigateTopLevel(destination: Route) {
        require(destination in Route.topLevel)
        if (!state.isGateActive) state.selectedTopLevel = destination
    }

    fun navigateConfigSubRoute(destination: Route) {
        if (state.isGateActive) return
        state.selectedTopLevel = Route.Config
        state.backStacks.getValue(Route.Config).apply {
            clear()
            add(Route.Config)
            add(destination)
        }
    }

    fun goBack() {
        if (state.isGateActive) {
            if (state.gateStack.size > 1) state.gateStack.removeLastOrNull()
            return
        }
        val currentStack = state.currentStack
        if (currentStack.size > 1) {
            currentStack.removeLastOrNull()
        } else if (state.selectedTopLevel != Route.Home) {
            state.selectedTopLevel = Route.Home
        }
    }

    fun resetToHome() {
        if (!state.isGateActive) {
            state.currentStack.apply {
                clear()
                add(state.selectedTopLevel)
            }
        }
        state.gateActive = false
        state.selectedTopLevel = Route.Home
    }

    fun navigateFromGate(destination: Route) {
        check(state.isGateActive)
        state.gateStack += destination
    }

    fun replaceAll(destination: Route) {
        require(destination in gateRoutes)
        state.backStacks.forEach { (root, stack) ->
            stack.clear()
            stack += root
        }
        state.gateStack.apply {
            clear()
            add(destination)
        }
        state.gateActive = true
    }

    fun navigateProfileImport(request: com.poyka.ripdpi.proxyimport.ProxyImportRequest.Profile) {
        val importToken =
            com.poyka.ripdpi.proxyimport.PendingProxyImportStore.process
                .stage(request.profile)
        navigate(Route.ProfileImportConfirm(importToken = importToken), singleTop = true)
    }
}

internal fun Route.selectedTopLevel(): Route? =
    when (this) {
        Route.Home -> Route.Home

        Route.Config,
        Route.LocalBypassConfig,
        Route.VpnConfig,
        -> Route.Config

        is Route.Diagnostics,
        Route.Logs,
        -> Route.Diagnostics()

        Route.Settings -> Route.Settings

        else -> null
    }
