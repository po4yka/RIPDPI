// RDS: reuses RipDpiContentScreenScaffold / RipDpiCard / RipDpiPanelHeader /
// RipDpiTextField / RipDpiChip / RipDpiButton / WarningBanner and consumes
// RipDpiThemeTokens.spacing only — no literal Color/dp/tween. No spec card exists
// for this screen yet:
// RDS deviation: xray-provider-import — no spec card for the new Xray import
// surface; built entirely from existing RDS components and tokens (content).
package com.poyka.ripdpi.ui.screens.xray

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.subscription.XraySkipReason
import com.poyka.ripdpi.data.subscription.XraySkippedNode
import com.poyka.ripdpi.data.xray.XrayCapability
import com.poyka.ripdpi.data.xray.XrayServiceModeOption
import com.poyka.ripdpi.ui.components.LifecycleEventEffect
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
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
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

    LifecycleEventEffect(viewModel.importedEvents) { onFinished() }

    XrayProfileImportScreen(
        uiState = uiState,
        onBack = onBack,
        onSelectOption = viewModel::selectOption,
        onRawInputChange = viewModel::onRawInputChange,
        onValidate = { viewModel.validate() },
        onRetryRestore = viewModel::retryRestore,
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
    onRetryRestore: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiContentScreenScaffold(
        title = stringResource(R.string.xray_import_title),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
        navigationContentDescription = stringResource(R.string.navigation_back),
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.screen(Route.XrayImport)),
    ) {
        XrayRestoreStatusCard(
            restoreStatus = uiState.restoreStatus,
            restoreErrorMessage = uiState.restoreErrorMessage,
            onRetryRestore = onRetryRestore,
        )

        XrayServiceModeCard(
            selectedOption = uiState.selectedOption,
            enabled = uiState.canEdit,
            onSelectOption = onSelectOption,
        )

        if (uiState.requiresXrayProfile) {
            XrayProfileInputCard(
                rawInput = uiState.rawInput,
                validating = uiState.validating,
                enabled = uiState.canEdit,
                errorMessage = uiState.errorMessage,
                onRawInputChange = onRawInputChange,
                onValidate = onValidate,
            )
            XrayImportErrorBanner(message = uiState.errorMessage)
            XrayCapabilitiesCard(
                visible = uiState.acceptedConfigReady,
                capabilities = uiState.capabilities,
            )
            XraySkippedNodesCard(skipped = uiState.skipped)
        }

        RipDpiButton(
            text = stringResource(R.string.xray_import_finish_action),
            onClick = onConfirm,
            enabled = uiState.canFinish,
            loading = uiState.persisting,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun XrayRestoreStatusCard(
    restoreStatus: XrayImportRestoreStatus,
    restoreErrorMessage: String?,
    onRetryRestore: () -> Unit,
) {
    when (restoreStatus) {
        XrayImportRestoreStatus.Loading -> {
            WarningBanner(
                title = stringResource(R.string.xray_import_restore_loading_title),
                message = stringResource(R.string.xray_import_restore_loading_body),
                tone = WarningBannerTone.Warning,
                testTag = "xray_import_restore_loading",
                modifier = Modifier.fillMaxWidth(),
            )
        }

        XrayImportRestoreStatus.Failed -> {
            WarningBanner(
                title = stringResource(R.string.xray_import_restore_failed_title),
                message = restoreErrorMessage ?: stringResource(R.string.xray_import_error_restore_failed),
                tone = WarningBannerTone.Error,
                testTag = "xray_import_restore_failed",
                modifier = Modifier.fillMaxWidth(),
            )
            RipDpiButton(
                text = stringResource(R.string.xray_import_restore_retry_action),
                onClick = onRetryRestore,
                variant = RipDpiButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        XrayImportRestoreStatus.Ready -> {
        }
    }
}

@Composable
private fun XrayServiceModeCard(
    selectedOption: XrayServiceModeOption,
    enabled: Boolean,
    onSelectOption: (XrayServiceModeOption) -> Unit,
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
                checked = selectedOption == option,
                enabled = enabled,
                onCheckedChange = { selected -> if (selected) onSelectOption(option) },
                testTag = "xray_mode_${option.name}",
            )
        }
    }
}

@Composable
private fun XrayProfileInputCard(
    rawInput: String,
    validating: Boolean,
    enabled: Boolean,
    errorMessage: String?,
    onRawInputChange: (String) -> Unit,
    onValidate: () -> Unit,
) {
    RipDpiCard {
        RipDpiPanelHeader(
            title = stringResource(R.string.xray_import_profile_section_title),
            supporting = stringResource(R.string.xray_import_profile_section_body),
        )
        RipDpiTextField(
            value = rawInput,
            onValueChange = onRawInputChange,
            modifier = Modifier.fillMaxWidth(),
            decoration =
                RipDpiTextFieldDecoration(
                    label = stringResource(R.string.xray_import_input_label),
                    placeholder = stringResource(R.string.xray_import_input_placeholder),
                    errorText = errorMessage,
                    testTag = "xray_import_input",
                ),
            behavior = RipDpiTextFieldBehavior(enabled = enabled, singleLine = false),
        )
        RipDpiButton(
            text = stringResource(R.string.xray_import_validate_action),
            onClick = onValidate,
            enabled = enabled && !validating,
            loading = validating,
            variant = RipDpiButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun XrayImportErrorBanner(message: String?) {
    message?.let {
        WarningBanner(
            title = stringResource(R.string.xray_import_error_title),
            message = it,
            tone = WarningBannerTone.Error,
            testTag = "xray_import_error",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun XrayCapabilitiesCard(
    visible: Boolean,
    capabilities: List<XrayCapability>,
) {
    if (!visible) return
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard {
        RipDpiPanelHeader(
            title = stringResource(R.string.xray_import_capabilities_title),
            supporting = stringResource(R.string.xray_import_capabilities_body),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            capabilities.forEach { capability ->
                RipDpiChip(
                    text = stringResource(stringIdFor(capability.titleKey)),
                    onClick = {},
                    selected = true,
                )
            }
        }
    }
}

/**
 * Surfaces the outbounds that could not be translated to a native relay, each
 * with a localized per-node reason — matching the subscription-import skip
 * behaviour so nothing is silently dropped.
 */
@Composable
private fun XraySkippedNodesCard(skipped: List<XraySkippedNode>) {
    if (skipped.isEmpty()) return
    // Build the per-node lines in a plain loop (a composable context) so each
    // reason string can be resolved with stringResource; a joinToString lambda
    // would not be a composable scope.
    val lines = StringBuilder()
    for ((index, node) in skipped.withIndex()) {
        if (index > 0) lines.append('\n')
        lines.append(stringResource(R.string.xray_import_skipped_item_format, index + 1, skipReasonText(node.reason)))
    }
    WarningBanner(
        title = stringResource(R.string.xray_import_skipped_title),
        message = lines.toString(),
        tone = WarningBannerTone.Warning,
        testTag = "xray_import_skipped",
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun skipReasonText(reason: XraySkipReason): String =
    when (reason) {
        XraySkipReason.VMESS_REMOVED -> {
            stringResource(R.string.xray_skip_vmess_removed)
        }

        XraySkipReason.VLESS_REQUIRES_REALITY -> {
            stringResource(R.string.xray_skip_vless_requires_reality)
        }

        XraySkipReason.NON_PROXY_OUTBOUND -> {
            stringResource(R.string.xray_skip_non_proxy_safe)
        }

        XraySkipReason.UNSUPPORTED_PROTOCOL -> {
            stringResource(R.string.xray_skip_unsupported_protocol_safe)
        }

        XraySkipReason.UNSUPPORTED_TRANSPORT -> {
            stringResource(R.string.xray_skip_unsupported_transport_safe)
        }

        XraySkipReason.UNSUPPORTED_FINGERPRINT -> {
            stringResource(R.string.xray_skip_unsupported_fingerprint_safe)
        }

        XraySkipReason.MALFORMED -> {
            stringResource(R.string.xray_skip_malformed)
        }

        XraySkipReason.SINGLE_RELAY_ONLY -> {
            stringResource(R.string.xray_skip_single_relay_only)
        }
    }

/**
 * Resolves a capability / option string-resource *key name* (declared in the
 * `:core:data` models to keep them Android-free) to the `:app` `R.string` id.
 * Centralised here so the model keys and the resources cannot drift silently.
 */
internal fun stringIdFor(key: String): Int = XrayStringIds[key] ?: error("Unmapped Xray string key: $key")

private val XrayStringIds =
    mapOf(
        // Service-mode options.
        "service_mode_native_direct_title" to R.string.service_mode_native_direct_title,
        "service_mode_native_direct_desc" to R.string.service_mode_native_direct_desc,
        "service_mode_native_proxy_title" to R.string.service_mode_native_proxy_title,
        "service_mode_native_proxy_desc" to R.string.service_mode_native_proxy_desc,
        "service_mode_xray_vpn_title" to R.string.service_mode_xray_vpn_title,
        "service_mode_xray_vpn_desc" to R.string.service_mode_xray_vpn_desc,
        // Capability labels.
        "xray_capability_vpn_privacy_title" to R.string.xray_capability_vpn_privacy_title,
        "xray_capability_vpn_privacy_desc" to R.string.xray_capability_vpn_privacy_desc,
        "xray_capability_relay_title" to R.string.xray_capability_relay_title,
        "xray_capability_relay_desc" to R.string.xray_capability_relay_desc,
        "xray_capability_anti_dpi_title" to R.string.xray_capability_anti_dpi_title,
        "xray_capability_anti_dpi_desc" to R.string.xray_capability_anti_dpi_desc,
        "xray_capability_dns_protection_title" to R.string.xray_capability_dns_protection_title,
        "xray_capability_dns_protection_desc" to R.string.xray_capability_dns_protection_desc,
        "xray_capability_realtime_media_title" to R.string.xray_capability_realtime_media_title,
        "xray_capability_realtime_media_desc" to R.string.xray_capability_realtime_media_desc,
    )
