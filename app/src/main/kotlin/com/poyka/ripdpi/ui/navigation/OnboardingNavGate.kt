package com.poyka.ripdpi.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController

/** True while onboarding is anywhere on the back stack, including screens opened from it. */
@Composable
internal fun NavHostController.hasOnboardingEntry(entry: NavBackStackEntry?): Boolean =
    remember(entry) { runCatching { getBackStackEntry<Route.Onboarding>() }.isSuccess }

/** Screens opened from onboarding stay behind its launch-request gate until onboarding completes. */
@Composable
internal fun NavHostController.launchGateRoute(
    entry: NavBackStackEntry?,
    currentStableRoute: String?,
): String? = if (hasOnboardingEntry(entry)) Route.Onboarding.stableRoute else currentStableRoute
