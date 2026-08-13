package com.poyka.ripdpi.ui.screens.proxyimport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.ui.components.LifecycleEventEffect
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.chrome.RipDpiPanelHeader
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons

/**
 * Single-profile import-confirmation destination.
 *
 * Minimal by design — the import-confirmation surface, not a full profile editor. It
 * shows the parsed [ProxyProfile] and an "Add" action that persists it through
 * [ProfileImportConfirmViewModel]. Reached from [com.poyka.ripdpi.proxyimport.ImportHandlerActivity]
 * after a `vless://` / `ss://` / `trojan://` style share link is parsed, and from the QR
 * scanner.
 */
@Composable
fun ProfileImportConfirmRoute(
    importToken: String,
    onBack: () -> Unit,
    onImported: () -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileImportConfirmViewModel = hiltViewModel(),
) {
    val latestOnUnavailable by rememberUpdatedState(onUnavailable)
    LaunchedEffect(viewModel, importToken) {
        if (!viewModel.loadProfile(importToken)) {
            latestOnUnavailable()
        }
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleEventEffect(viewModel.importedEvents) { onImported() }

    ProfileImportConfirmScreen(
        uiState = uiState,
        onBack = onBack,
        onConfirm = viewModel::confirm,
        onCheck = viewModel::checkProfile,
        modifier = modifier,
    )
}

@Composable
internal fun ProfileImportConfirmScreen(
    uiState: ProfileImportConfirmUiState,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    onCheck: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    RipDpiContentScreenScaffold(
        title = stringResource(R.string.import_profile_confirm_title),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
        navigationContentDescription = stringResource(R.string.navigation_back),
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.screen(Route.ProfileImportConfirm())),
    ) {
        val profile = uiState.profile
        if (profile == null) {
            RipDpiCard {
                RipDpiPanelHeader(
                    title = stringResource(R.string.import_profile_confirm_empty_title),
                    supporting = stringResource(R.string.import_profile_confirm_empty_body),
                )
            }
            return@RipDpiContentScreenScaffold
        }

        RipDpiCard {
            RipDpiPanelHeader(
                title = profile.displayName,
                supporting = profileSummary(profile),
            )
        }
        uiState.errorRes?.let { errorRes ->
            RipDpiCard {
                RipDpiPanelHeader(title = stringResource(errorRes))
            }
        }
        val renderedCheckState =
            if (uiState.checkSupported && !uiState.serviceHalted && uiState.checkState == ProfileCheckState.Idle) {
                ProfileCheckState.ServiceBusy
            } else {
                uiState.checkState
            }
        checkResultText(renderedCheckState)?.let { resultText ->
            RipDpiCard {
                RipDpiPanelHeader(title = resultText)
            }
        }
        if (uiState.checkSupported) {
            RipDpiButton(
                text = stringResource(R.string.import_profile_check_action),
                onClick = onCheck,
                variant = RipDpiButtonVariant.Secondary,
                enabled = uiState.serviceHalted && !uiState.importing,
                loading = uiState.checkState == ProfileCheckState.Checking,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        RipDpiButton(
            text = stringResource(R.string.import_confirm_add_action),
            onClick = onConfirm,
            enabled = !uiState.importing && uiState.checkState != ProfileCheckState.Checking,
            loading = uiState.importing,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun checkResultText(state: ProfileCheckState): String? =
    when (state) {
        ProfileCheckState.Idle,
        ProfileCheckState.Checking,
        -> null

        ProfileCheckState.Succeeded -> stringResource(R.string.import_profile_check_success)

        ProfileCheckState.Unsupported -> stringResource(R.string.import_profile_check_unsupported)

        ProfileCheckState.ServiceBusy -> stringResource(R.string.import_profile_check_service_busy)

        ProfileCheckState.StartupFailure -> stringResource(R.string.import_profile_check_startup_failure)

        ProfileCheckState.ProbeFailure -> stringResource(R.string.import_profile_check_probe_failure)

        ProfileCheckState.TimedOut -> stringResource(R.string.import_profile_check_timeout)

        ProfileCheckState.Cancelled -> stringResource(R.string.import_profile_check_cancelled)

        ProfileCheckState.CleanupFailure -> stringResource(R.string.import_profile_check_cleanup_failure)
    }

@Composable
private fun profileSummary(profile: ProxyProfile): String =
    when (profile) {
        is ProxyProfile.Vless -> {
            stringResource(R.string.import_profile_summary_endpoint, "VLESS", profile.server, profile.serverPort)
        }

        is ProxyProfile.VlessReality -> {
            stringResource(
                R.string.import_profile_summary_endpoint,
                "VLESS (REALITY)",
                profile.server,
                profile.serverPort,
            )
        }

        is ProxyProfile.Shadowsocks -> {
            stringResource(
                R.string.import_profile_summary_endpoint,
                "Shadowsocks",
                profile.server,
                profile.serverPort,
            )
        }

        is ProxyProfile.Trojan -> {
            stringResource(R.string.import_profile_summary_endpoint, "Trojan", profile.server, profile.serverPort)
        }

        is ProxyProfile.Hysteria2 -> {
            stringResource(
                R.string.import_profile_summary_endpoint,
                "Hysteria2",
                profile.server,
                profile.serverPort,
            )
        }

        is ProxyProfile.AnyTls -> {
            stringResource(
                R.string.import_profile_summary_endpoint,
                "AnyTLS",
                profile.server,
                profile.serverPort,
            )
        }

        is ProxyProfile.Mieru -> {
            stringResource(
                R.string.import_profile_summary_endpoint,
                "Mieru",
                profile.server,
                profile.serverPort,
            )
        }

        is ProxyProfile.Ssh -> {
            stringResource(R.string.import_profile_summary_endpoint, "SSH", profile.server, profile.serverPort)
        }

        is ProxyProfile.RawConfig -> {
            stringResource(R.string.import_profile_summary_raw)
        }
    }
