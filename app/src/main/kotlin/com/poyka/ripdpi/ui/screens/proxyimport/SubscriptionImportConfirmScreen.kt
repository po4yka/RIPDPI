package com.poyka.ripdpi.ui.screens.proxyimport

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.proxyimport.subscriptionHost
import com.poyka.ripdpi.ui.components.LifecycleEventEffect
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.chrome.RipDpiPanelHeader
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.security.SecureWindowEffect
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
    onImported: (awgKeyProfileId: String) -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SubscriptionImportConfirmViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val latestOnUnavailable by rememberUpdatedState(onUnavailable)
    LaunchedEffect(viewModel, importToken) {
        if (!viewModel.claimRequest(importToken)) {
            context.toastImportUnavailable()
            latestOnUnavailable()
        }
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val addedName = subscriptionDisplayName(uiState)
    LifecycleEventEffect(viewModel.importedEvents) {
        context.toastImported(addedName)
        onImported(it)
    }

    SubscriptionImportConfirmScreen(
        uiState = uiState,
        // Once the import is saved, Back closes it the same way as Done.
        onBack = { if (uiState.skipped.isEmpty()) onBack() else onImported(uiState.awgKeyProfileId) },
        onConfirm = viewModel::confirm,
        onCheck = viewModel::check,
        onDone = { onImported(uiState.awgKeyProfileId) },
        modifier = modifier,
    )
}

@Composable
internal fun SubscriptionImportConfirmScreen(
    uiState: SubscriptionImportConfirmUiState,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    onCheck: () -> Unit = {},
    onDone: () -> Unit = {},
) {
    SecureWindowEffect()
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
                message = subscriptionFailureText(uiState.rejection, uiState.refreshFailure, uiState.httpStatus),
                tone = WarningBannerTone.Error,
            )
        }
        if (uiState.skipped.isNotEmpty()) {
            WarningBanner(
                title =
                    pluralStringResource(
                        R.plurals.import_entries_skipped,
                        uiState.skipped.size,
                        uiState.skipped.size,
                    ),
                message = skippedEntriesText(uiState.skipped),
                tone = WarningBannerTone.Warning,
            )
        }
        // Same empty state as profile import: nothing to add, so no card and no disabled Add.
        if (uiState.url.isBlank() && !uiState.localBundle) {
            RipDpiCard {
                RipDpiPanelHeader(
                    title = stringResource(R.string.import_profile_confirm_empty_title),
                    supporting = stringResource(R.string.import_subscription_confirm_empty_body),
                )
            }
            return@RipDpiContentScreenScaffold
        }
        RipDpiCard {
            RipDpiPanelHeader(
                title = subscriptionDisplayName(uiState),
                supporting =
                    listOfNotNull(
                        uiState.serverCount?.let {
                            pluralStringResource(R.plurals.import_subscription_servers, it, it)
                        },
                        uiState.url.takeIf { it.isNotBlank() },
                    ).joinToString("\n"),
            )
        }
        // The import is saved once entries were skipped; the screen only waits for the user to read them.
        val saved = uiState.skipped.isNotEmpty()
        RipDpiButton(
            text =
                stringResource(
                    when {
                        saved -> R.string.xray_import_done_action
                        uiState.needsCheck -> R.string.import_subscription_check_action
                        else -> R.string.import_confirm_add_action
                    },
                ),
            onClick =
                when {
                    saved -> onDone
                    uiState.needsCheck -> onCheck
                    else -> onConfirm
                },
            enabled = !uiState.importing,
            loading = uiState.importing,
            modifier =
                Modifier.fillMaxWidth().then(
                    if (uiState.needsCheck) {
                        Modifier.ripDpiTestTag(
                            RipDpiTestTags.SubscriptionImportCheck,
                        )
                    } else {
                        Modifier
                    },
                ),
        )
    }
}

private fun subscriptionDisplayName(uiState: SubscriptionImportConfirmUiState): String =
    uiState.name.ifBlank { uiState.fetchedTitle }.ifBlank { subscriptionHost(uiState.url).ifEmpty { uiState.url } }
