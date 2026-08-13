package com.poyka.ripdpi.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.serialization.NavKeySerializer
import androidx.savedstate.compose.serialization.serializers.MutableStateSerializer

@Composable
internal fun rememberRipDpiNavigationState(
    startRoute: Route,
    initialGateActive: Boolean,
): RipDpiNavigationState {
    val selectedTopLevel =
        rememberSerializable(
            startRoute,
            serializer = MutableStateSerializer(NavKeySerializer()),
        ) {
            mutableStateOf<NavKey>(startRoute.takeIf { it in Route.topLevel } ?: Route.Home)
        }
    val backStacks =
        Route.topLevel.associateWith { route ->
            rememberNavBackStack(route)
        }
    val gateStack = rememberNavBackStack(startRoute)
    return remember(startRoute, backStacks) {
        RipDpiNavigationState(
            startRoute = startRoute,
            selectedTopLevelState = selectedTopLevel,
            backStacks = backStacks,
            gateStack = gateStack,
            initialGateActive = initialGateActive,
        )
    }
}

internal class RipDpiNavigationState(
    val startRoute: Route,
    selectedTopLevelState: MutableState<NavKey>,
    val backStacks: Map<Route, NavBackStack<NavKey>>,
    val gateStack: NavBackStack<NavKey>,
    initialGateActive: Boolean,
) {
    private var selectedTopLevelKey: NavKey by selectedTopLevelState
    var selectedTopLevel: Route
        get() = selectedTopLevelKey as Route
        set(value) {
            selectedTopLevelKey = value
        }

    var gateActive: Boolean by mutableStateOf(initialGateActive)

    val isGateActive: Boolean
        get() = gateActive

    val currentStack: NavBackStack<NavKey>
        get() = if (isGateActive) gateStack else backStacks.getValue(selectedTopLevel)

    val currentRoute: Route
        get() = currentStack.last() as Route

    @Composable
    fun toEntries(entryProvider: (NavKey) -> NavEntry<NavKey>): List<NavEntry<NavKey>> {
        if (isGateActive) {
            return rememberDecoratedNavEntries(
                backStack = gateStack,
                entryDecorators =
                    listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberRipDpiSharedViewModelStoreNavEntryDecorator(),
                    ),
                entryProvider = entryProvider,
            )
        }
        val decorated =
            backStacks.mapValues { (_, stack) ->
                rememberDecoratedNavEntries(
                    backStack = stack,
                    entryDecorators =
                        listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberRipDpiSharedViewModelStoreNavEntryDecorator(),
                        ),
                    entryProvider = entryProvider,
                )
            }
        return if (selectedTopLevel == Route.Home) {
            decorated.getValue(Route.Home)
        } else {
            decorated.getValue(Route.Home) + decorated.getValue(selectedTopLevel)
        }
    }
}

internal val gateRoutes = setOf(Route.Onboarding, Route.BiometricPrompt)

internal fun shouldStartNavigationGate(
    startRoute: Route,
    isSessionAuthenticated: Boolean,
): Boolean =
    startRoute == Route.Onboarding ||
        (startRoute == Route.BiometricPrompt && !isSessionAuthenticated)
