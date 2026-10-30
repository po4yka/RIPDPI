package com.poyka.ripdpi.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.ui.components.navigation.RipDpiNavigationItem
import com.poyka.ripdpi.ui.components.navigation.RipDpiNavigationSuiteScaffold
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import kotlinx.collections.immutable.toImmutableList

/** Shows [Route.topLevel] in the adaptive navigation suite; no navigation shows when [selectedRoute] is null. */
@Composable
internal fun TopLevelNavigationScaffold(
    selectedRoute: Route?,
    onNavigate: (Route) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val items =
        Route.topLevel
            .map { destination ->
                RipDpiNavigationItem(
                    label = stringResource(destination.titleRes),
                    icon = requireNotNull(destination.icon),
                    selected = destination == selectedRoute,
                    testTag = RipDpiTestTags.bottomNav(destination),
                    onClick = { onNavigate(destination) },
                )
            }.toImmutableList()
    RipDpiNavigationSuiteScaffold(
        items = items,
        modifier = modifier,
        showNavigation = selectedRoute != null,
        content = content,
    )
}
