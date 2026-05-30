// RDS: reuses RipDpiContentScreenScaffold / RipDpiCard / RipDpiPanelHeader /
// RipDpiTextField / RipDpiChip / RipDpiButton / WarningBanner and consumes
// RipDpiThemeTokens.spacing only — no literal Color/dp/tween. No spec card exists
// for this screen yet:
// RDS deviation: xray-provider-import — no spec card for the new Xray import
// surface; built entirely from existing RDS components and tokens (content).
package com.poyka.ripdpi.ui.screens.xray

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.xray.XrayCapability
import com.poyka.ripdpi.data.xray.XrayServiceModeOption
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.chrome.RipDpiPanelHeader
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

/**
 * Xray provider selection + profile import destination.
 *
 * Lets the user pick a service-mode (native direct, native relay, or the
 * Xray-backed VPN provider), and — when the Xray provider is chosen — paste a
 * supported share link / config, validate it (fail-closed, redacted errors),
 * see jargon-free capability labels, and confirm.
 *
 * Reused by onboarding to validate the chosen mode before finishing.
 */
@Composable
fun XrayProfileImportRoute(
    onBack: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: XrayProfileImportViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.imported) {
        if (uiState.imported) onFinished()
    }

    XrayProfileImportScreen(
        uiState = uiState,
        onBack = onBack,
        onSelectOption = viewModel::selectOption,
        onRawInputChange = viewModel::onRawInputChange,
        onValidate = { viewModel.validate() },
        onConfirm = viewModel::confirm,
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun XrayProfileImportScreen(
    uiState: XrayImportUiState,
    onBack: () -> Unit,
    onSelectOption: (XrayServiceModeOption) -> Unit,
    onRawInputChange: (String) -> Unit,
    onValidate: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    RipDpiContentScreenScaffold(
        title = stringResource(R.string.xray_import_title),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
        navigationContentDescription = stringResource(R.string.navigation_back),
        modifier = modifier,
    ) {
        RipDpiCard {
            RipDpiPanelHeader(
                title = stringResource(R.string.xray_service_mode_section_title),
                supporting = stringResource(R.string.xray_service_mode_section_body),
            )
            XrayServiceModeOption.all.forEach { option ->
                SettingsRow(
                    title = stringResource(stringIdFor(option.titleKey)),
                    subtitle = stringResource(stringIdFor(option.descriptionKey)),
                    checked = uiState.selectedOption == option,
                    onCheckedChange = { selected -> if (selected) onSelectOption(option) },
                    testTag = "xray_mode_${option.name}",
                )
            }
        }

        if (uiState.requiresXrayProfile) {
            RipDpiCard {
                RipDpiPanelHeader(
                    title = stringResource(R.string.xray_import_profile_section_title),
                    supporting = stringResource(R.string.xray_import_profile_section_body),
                )
                RipDpiTextField(
                    value = uiState.rawInput,
                    onValueChange = onRawInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    decoration =
                        RipDpiTextFieldDecoration(
                            label = stringResource(R.string.xray_import_input_label),
                            placeholder = stringResource(R.string.xray_import_input_placeholder),
                            errorText = uiState.errorMessage,
                            testTag = "xray_import_input",
                        ),
                    behavior = RipDpiTextFieldBehavior(singleLine = false),
                )
                RipDpiButton(
                    text = stringResource(R.string.xray_import_validate_action),
                    onClick = onValidate,
                    enabled = !uiState.validating,
                    loading = uiState.validating,
                    variant = RipDpiButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            uiState.errorMessage?.let { message ->
                WarningBanner(
                    title = stringResource(R.string.xray_import_error_title),
                    message = message,
                    tone = WarningBannerTone.Error,
                    testTag = "xray_import_error",
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (uiState.acceptedConfigReady) {
                RipDpiCard {
                    RipDpiPanelHeader(
                        title = stringResource(R.string.xray_import_capabilities_title),
                        supporting = stringResource(R.string.xray_import_capabilities_body),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        uiState.capabilities.forEach { capability ->
                            RipDpiChip(
                                text = stringResource(stringIdFor(capability.titleKey)),
                                onClick = {},
                                selected = true,
                            )
                        }
                    }
                }
            }
        }

        RipDpiButton(
            text =
                if (uiState.imported) {
                    stringResource(R.string.xray_import_done_action)
                } else {
                    stringResource(R.string.xray_import_finish_action)
                },
            onClick = onConfirm,
            enabled = uiState.canFinish && !uiState.imported,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Resolves a capability / option string-resource *key name* (declared in the
 * `:core:data` models to keep them Android-free) to the `:app` `R.string` id.
 * Centralised here so the model keys and the resources cannot drift silently.
 */
@Suppress("CyclomaticComplexMethod")
internal fun stringIdFor(key: String): Int =
    when (key) {
        // Service-mode options.
        "service_mode_native_direct_title" -> R.string.service_mode_native_direct_title

        "service_mode_native_direct_desc" -> R.string.service_mode_native_direct_desc

        "service_mode_native_proxy_title" -> R.string.service_mode_native_proxy_title

        "service_mode_native_proxy_desc" -> R.string.service_mode_native_proxy_desc

        "service_mode_xray_vpn_title" -> R.string.service_mode_xray_vpn_title

        "service_mode_xray_vpn_desc" -> R.string.service_mode_xray_vpn_desc

        // Capability labels.
        "xray_capability_vpn_privacy_title" -> R.string.xray_capability_vpn_privacy_title

        "xray_capability_vpn_privacy_desc" -> R.string.xray_capability_vpn_privacy_desc

        "xray_capability_relay_title" -> R.string.xray_capability_relay_title

        "xray_capability_relay_desc" -> R.string.xray_capability_relay_desc

        "xray_capability_anti_dpi_title" -> R.string.xray_capability_anti_dpi_title

        "xray_capability_anti_dpi_desc" -> R.string.xray_capability_anti_dpi_desc

        "xray_capability_dns_protection_title" -> R.string.xray_capability_dns_protection_title

        "xray_capability_dns_protection_desc" -> R.string.xray_capability_dns_protection_desc

        "xray_capability_realtime_media_title" -> R.string.xray_capability_realtime_media_title

        "xray_capability_realtime_media_desc" -> R.string.xray_capability_realtime_media_desc

        else -> error("Unmapped Xray string key: $key")
    }
