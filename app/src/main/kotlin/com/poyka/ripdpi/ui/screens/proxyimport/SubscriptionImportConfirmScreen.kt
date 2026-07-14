package com.poyka.ripdpi.ui.screens.proxyimport

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.LifecycleEventEffect
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.chrome.RipDpiPanelHeader
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons

/**
 * Subscription-add import-confirmation destination.
 *
 * Minimal by design — the import-confirmation surface, not a full subscription editor.
 * It shows the deep link's pre-filled URL and name (plus a bootstrap badge when the
 * URL path was `/bootstrap/...`) and an "Add" action that persists the subscription
 * through [SubscriptionImportConfirmViewModel]. The user confirms here before any
 * network request is made. Reached from
 * [com.poyka.ripdpi.proxyimport.ImportHandlerActivity] after a
 * `singbox://import-remote-profile?url=…` deep link is parsed.
 */
@Composable
fun SubscriptionImportConfirmRoute(
    importToken: String,
    onBack: () -> Unit,
    onImported: () -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SubscriptionImportConfirmViewModel = hiltViewModel(),
) {
    val latestOnUnavailable by rememberUpdatedState(onUnavailable)
    LaunchedEffect(viewModel, importToken) {
        if (!viewModel.claimRequest(importToken)) latestOnUnavailable()
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleEventEffect(viewModel.importedEvents) { onImported() }

    SubscriptionImportConfirmScreen(
        uiState = uiState,
        onBack = onBack,
        onConfirm = viewModel::confirm,
        modifier = modifier,
    )
}

@Composable
internal fun SubscriptionImportConfirmScreen(
    uiState: SubscriptionImportConfirmUiState,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiContentScreenScaffold(
        title = stringResource(R.string.import_subscription_confirm_title),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
        navigationContentDescription = stringResource(R.string.navigation_back),
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.screen(Route.SubscriptionImportConfirm())),
    ) {
        if (uiState.bootstrap) {
            WarningBanner(
                title = stringResource(R.string.import_subscription_bootstrap_title),
                message = stringResource(R.string.import_subscription_bootstrap_body),
                tone = WarningBannerTone.Info,
            )
        }
        if (uiState.importFailed) {
            WarningBanner(
                title = stringResource(R.string.strategy_config_import_failed_title),
                message = stringResource(R.string.import_profile_confirm_error),
                tone = WarningBannerTone.Error,
            )
        }
        RipDpiCard {
            RipDpiPanelHeader(
                title =
                    uiState.name.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.import_subscription_unnamed),
                supporting = uiState.url,
            )
        }
        RipDpiButton(
            text = stringResource(R.string.import_confirm_add_action),
            onClick = onConfirm,
            enabled = !uiState.importing && uiState.url.isNotBlank(),
            loading = uiState.importing,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
