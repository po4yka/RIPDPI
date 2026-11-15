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
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.ui.components.LifecycleEventEffect
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.chrome.RipDpiPanelHeader
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.ssh.SshProfileRoute
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons

/**
 * Single-profile import-confirmation destination.
 *
 * Shows the parsed [ProxyProfile], its trust policy, and whether Add can save it.
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

    val ssh = uiState.profile as? ProxyProfile.Ssh
    if (ssh != null && ssh.hostKeyFingerprint.isNullOrBlank()) {
        SshProfileRoute(onBack = onBack, importedProfile = ssh, onSaved = onImported, modifier = modifier)
        return
    }

    ProfileImportConfirmScreen(
        uiState = uiState,
        onBack = onBack,
        onConfirm = viewModel::confirm,
        modifier = modifier,
    )
}

@Composable
internal fun ProfileImportConfirmScreen(
    uiState: ProfileImportConfirmUiState,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
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
        profileTrustSummary(profile)?.let { summary ->
            RipDpiCard {
                RipDpiPanelHeader(title = summary)
            }
        }
        (uiState.admissionErrorRes ?: uiState.errorRes)?.let { errorRes ->
            RipDpiCard {
                RipDpiPanelHeader(title = stringResource(errorRes))
            }
        }
        if (uiState.admissionErrorRes == null) {
            RipDpiButton(
                text = stringResource(R.string.import_confirm_add_action),
                onClick = onConfirm,
                enabled = !uiState.importing,
                loading = uiState.importing,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
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

        is ProxyProfile.Tuic -> {
            stringResource(R.string.import_profile_summary_endpoint, "TUIC", profile.server, profile.serverPort)
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

        is ProxyProfile.Naive -> {
            stringResource(
                R.string.import_profile_summary_endpoint,
                "NaiveProxy",
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

@Composable
private fun profileTrustSummary(profile: ProxyProfile): String? =
    when (profile) {
        is ProxyProfile.Hysteria2 -> {
            val pin = profile.pinSha256
            when {
                !pin.isNullOrBlank() -> stringResource(R.string.import_profile_confirm_tls_pin, pin)
                profile.insecure == true -> stringResource(R.string.import_profile_confirm_tls_unverified)
                else -> null
            }
        }

        is ProxyProfile.AnyTls -> {
            profile.pinSha256?.let { stringResource(R.string.import_profile_confirm_tls_pin, it) }
        }

        is ProxyProfile.Naive -> {
            profile.serverPinSha256.takeIf { it.isNotEmpty() }?.let {
                stringResource(R.string.import_profile_confirm_tls_pin, it)
            }
        }

        is ProxyProfile.Ssh -> {
            profile.hostKeyFingerprint?.let {
                "${stringResource(R.string.ssh_field_host_key_fingerprint)}\n$it"
            }
        }

        else -> {
            null
        }
    }
