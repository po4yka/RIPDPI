package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.AdaptiveFakeTtlModeAdaptive
import com.poyka.ripdpi.activities.AdaptiveFakeTtlModeCustom
import com.poyka.ripdpi.activities.AdaptiveFakeTtlModeFixed
import com.poyka.ripdpi.activities.AdaptiveSplitPresetCustom
import com.poyka.ripdpi.activities.AdaptiveSplitPresetManual
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.data.AdaptiveMarkerBalanced
import com.poyka.ripdpi.data.AdaptiveMarkerEndHost
import com.poyka.ripdpi.data.AdaptiveMarkerHost
import com.poyka.ripdpi.data.AdaptiveMarkerSniExt
import com.poyka.ripdpi.data.DefaultFakeSni
import com.poyka.ripdpi.data.FakeTlsSniModeFixed
import com.poyka.ripdpi.data.formatOffsetExpressionLabel
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.isAdaptiveOffsetExpression
import com.poyka.ripdpi.data.isValidOffsetExpression
import com.poyka.ripdpi.data.parseStrategyChainDsl
import com.poyka.ripdpi.data.primaryTcpChainStep
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.utility.validateIntRange

internal fun LazyListScope.desyncSection(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    showHostFakeSection: Boolean,
    showFakeApproxSection: Boolean,
    showAdaptiveFakeTtlSection: Boolean,
    showFakePayloadLibrary: Boolean,
    showFakeTlsSection: Boolean,
    adaptiveSplitPresetOptions: List<AdaptiveSplitPresetUiModel>,
    adaptiveFakeTtlModeOptions: List<AdaptiveFakeTtlModeUiModel>,
    httpFakeProfileOptions: List<RipDpiDropdownOption<String>>,
    fakeTlsBaseOptions: List<RipDpiDropdownOption<String>>,
    fakeTlsSniModeOptions: List<RipDpiDropdownOption<String>>,
    tlsFakeProfileOptions: List<RipDpiDropdownOption<String>>,
    udpFakeProfileOptions: List<RipDpiDropdownOption<String>>,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
    onOptionSelected: (AdvancedOptionSetting, String) -> Unit,
    onResetAdaptiveSplit: () -> Unit,
    onResetAdaptiveFakeTtlProfile: () -> Unit,
    onResetFakePayloadLibrary: () -> Unit,
    onResetFakeTlsProfile: () -> Unit,
) {
    item(key = "advanced_desync") {
        val colors = RipDpiThemeTokens.colors
        val spacing = RipDpiThemeTokens.spacing

        AdvancedSettingsSection(title = stringResource(R.string.ripdpi_desync)) {
            RipDpiCard {
                AdvancedTextSetting(
                    title = stringResource(R.string.ripdpi_default_ttl_setting),
                    description = stringResource(R.string.config_default_ttl_helper),
                    value = uiState.defaultTtlValue,
                    placeholder = stringResource(R.string.config_placeholder_default_ttl),
                    enabled = visualEditorEnabled,
                    validator = { it.isEmpty() || validateIntRange(it, 0, 255) },
                    invalidMessage = stringResource(R.string.config_error_out_of_range),
                    disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                    setting = AdvancedTextSetting.DefaultTtl,
                    onConfirm = onTextConfirmed,
                    showDivider = true,
                )
                Text(
                    text = stringResource(R.string.config_chain_summary_label, uiState.chainSummary),
                    style = RipDpiThemeTokens.type.caption,
                    color = colors.mutedForeground,
                )
                HorizontalDivider(color = colors.divider)
                AdaptiveSplitProfileCard(
                    uiState = uiState,
                    onResetAdaptiveSplit = onResetAdaptiveSplit,
                    modifier = Modifier.padding(top = spacing.xs, bottom = spacing.sm),
                )
                HorizontalDivider(color = colors.divider)
                AdaptiveSplitPresetSelector(
                    uiState = uiState,
                    presets = adaptiveSplitPresetOptions,
                    enabled = visualEditorEnabled && uiState.adaptiveSplitVisualEditorSupported,
                    onPresetSelected = { onOptionSelected(AdvancedOptionSetting.AdaptiveSplitPreset, it) },
                )
                if (!uiState.hasAdaptiveSplitPreset) {
                    HorizontalDivider(color = colors.divider)
                    AdvancedTextSetting(
                        title = stringResource(R.string.ripdpi_split_marker_setting),
                        description = stringResource(R.string.config_split_marker_helper),
                        value = uiState.splitMarker,
                        placeholder = stringResource(R.string.config_placeholder_split_marker),
                        enabled = visualEditorEnabled && uiState.adaptiveSplitVisualEditorSupported,
                        validator = { it.isBlank() || (isValidOffsetExpression(it) && !isAdaptiveOffsetExpression(it)) },
                        invalidMessage = stringResource(R.string.config_error_invalid_marker),
                        disabledMessage =
                            if (uiState.adaptiveSplitVisualEditorSupported) {
                                stringResource(R.string.advanced_settings_visual_controls_disabled)
                            } else {
                                stringResource(R.string.adaptive_split_hostfake_disabled)
                            },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                        setting = AdvancedTextSetting.SplitMarker,
                        onConfirm = onTextConfirmed,
                        showDivider = true,
                    )
                } else {
                    HorizontalDivider(color = colors.divider)
                }
                AdvancedTextSetting(
                    title = stringResource(R.string.config_chain_editor_label),
                    description = stringResource(R.string.config_chain_editor_helper),
                    value = uiState.chainDsl,
                    placeholder = stringResource(R.string.config_placeholder_chain_dsl),
                    enabled = visualEditorEnabled,
                    multiline = true,
                    validator = { parseStrategyChainDsl(it).isSuccess },
                    invalidMessage = stringResource(R.string.config_error_invalid_chain),
                    disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    setting = AdvancedTextSetting.ChainDsl,
                    onConfirm = onTextConfirmed,
                    showDivider =
                        showHostFakeSection ||
                            showFakeApproxSection ||
                            showAdaptiveFakeTtlSection ||
                            showFakeTlsSection ||
                            uiState.isOob,
                )
                if (showHostFakeSection) {
                    HostFakeProfileCard(
                        uiState = uiState,
                        modifier = Modifier.padding(top = spacing.xs, bottom = spacing.sm),
                    )
                    if (showFakeApproxSection || showAdaptiveFakeTtlSection || showFakeTlsSection || uiState.isOob) {
                        HorizontalDivider(color = colors.divider)
                    }
                }
                if (showFakeApproxSection) {
                    FakeApproximationProfileCard(
                        uiState = uiState,
                        modifier = Modifier.padding(top = spacing.xs, bottom = spacing.sm),
                    )
                    if (showAdaptiveFakeTtlSection || showFakeTlsSection || uiState.isOob) {
                        HorizontalDivider(color = colors.divider)
                    }
                }
                if (showAdaptiveFakeTtlSection) {
                    AdaptiveFakeTtlProfileCard(
                        uiState = uiState,
                        onResetAdaptiveFakeTtlProfile = onResetAdaptiveFakeTtlProfile,
                        modifier = Modifier.padding(top = spacing.xs, bottom = spacing.sm),
                    )
                    HorizontalDivider(color = colors.divider)
                    AdaptiveFakeTtlModeSelector(
                        uiState = uiState,
                        presets = adaptiveFakeTtlModeOptions,
                        enabled = visualEditorEnabled,
                        onModeSelected = { onOptionSelected(AdvancedOptionSetting.AdaptiveFakeTtlMode, it) },
                    )
                    HorizontalDivider(color = colors.divider)
                    if (uiState.adaptiveFakeTtlMode == AdaptiveFakeTtlModeFixed) {
                        AdvancedTextSetting(
                            title = stringResource(R.string.ripdpi_fake_ttl_setting),
                            description = stringResource(R.string.adaptive_fake_ttl_fixed_body),
                            value = uiState.fakeTtl.toString(),
                            enabled = visualEditorEnabled,
                            validator = { validateIntRange(it, 1, 255) },
                            invalidMessage = stringResource(R.string.config_error_out_of_range),
                            disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                            keyboardOptions =
                                KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done,
                                ),
                            setting = AdvancedTextSetting.FakeTtl,
                            onConfirm = onTextConfirmed,
                            showDivider = uiState.isFake || showFakeTlsSection || uiState.isOob,
                        )
                    } else {
                        AdvancedTextSetting(
                            title = stringResource(R.string.adaptive_fake_ttl_min_title),
                            description = stringResource(R.string.adaptive_fake_ttl_min_body),
                            value = uiState.adaptiveFakeTtlMin.toString(),
                            enabled = visualEditorEnabled,
                            validator = { validateIntRange(it, 1, 255) },
                            invalidMessage = stringResource(R.string.config_error_out_of_range),
                            disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                            keyboardOptions =
                                KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done,
                                ),
                            setting = AdvancedTextSetting.AdaptiveFakeTtlMin,
                            onConfirm = onTextConfirmed,
                            showDivider = true,
                        )
                        AdvancedTextSetting(
                            title = stringResource(R.string.adaptive_fake_ttl_max_title),
                            description = stringResource(R.string.adaptive_fake_ttl_max_body),
                            value = uiState.adaptiveFakeTtlMax.toString(),
                            enabled = visualEditorEnabled,
                            validator = { validateIntRange(it, uiState.adaptiveFakeTtlMin.coerceIn(1, 255), 255) },
                            invalidMessage = stringResource(R.string.config_error_out_of_range),
                            disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                            keyboardOptions =
                                KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done,
                                ),
                            setting = AdvancedTextSetting.AdaptiveFakeTtlMax,
                            onConfirm = onTextConfirmed,
                            showDivider = true,
                        )
                        AdvancedTextSetting(
                            title = stringResource(R.string.adaptive_fake_ttl_fallback_title),
                            description = stringResource(R.string.adaptive_fake_ttl_fallback_body),
                            value = uiState.adaptiveFakeTtlFallback.toString(),
                            enabled = visualEditorEnabled,
                            validator = { validateIntRange(it, 1, 255) },
                            invalidMessage = stringResource(R.string.config_error_out_of_range),
                            disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                            keyboardOptions =
                                KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done,
                                ),
                            setting = AdvancedTextSetting.AdaptiveFakeTtlFallback,
                            onConfirm = onTextConfirmed,
                            showDivider = uiState.isFake || showFakeTlsSection || uiState.isOob,
                        )
                    }
                    if (uiState.isFake) {
                        AdvancedTextSetting(
                            title = stringResource(R.string.ripdpi_fake_offset_setting),
                            description = stringResource(R.string.config_fake_offset_marker_helper),
                            value = uiState.fakeOffsetMarker,
                            placeholder = stringResource(R.string.config_placeholder_fake_offset_marker),
                            enabled = visualEditorEnabled,
                            validator = {
                                it.isBlank() || (isValidOffsetExpression(it) && !isAdaptiveOffsetExpression(it))
                            },
                            invalidMessage = stringResource(R.string.config_error_invalid_marker),
                            disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                            keyboardOptions =
                                KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                            setting = AdvancedTextSetting.FakeOffsetMarker,
                            onConfirm = onTextConfirmed,
                            showDivider = true,
                        )
                    }
                }
                if (showFakePayloadLibrary) {
                    Text(
                        text = stringResource(R.string.fake_payload_library_section_title),
                        style = RipDpiThemeTokens.type.bodyEmphasis,
                        color = colors.foreground,
                    )
                    Text(
                        text =
                            if (uiState.fakePayloadLibraryControlsRelevant) {
                                stringResource(R.string.fake_payload_library_section_body)
                            } else {
                                stringResource(R.string.fake_payload_library_inactive)
                            },
                        style = RipDpiThemeTokens.type.secondaryBody,
                        color = colors.mutedForeground,
                    )
                    FakePayloadLibraryCard(
                        uiState = uiState,
                        onResetFakePayloadLibrary = onResetFakePayloadLibrary,
                        modifier = Modifier.padding(top = spacing.xs, bottom = spacing.sm),
                    )
                    FakePayloadProfileCard(
                        title = stringResource(R.string.http_fake_profile_title),
                        description = stringResource(R.string.http_fake_profile_body),
                        profileLabel = formatHttpFakeProfileLabel(uiState.httpFakeProfile),
                        statusLabel =
                            when {
                                uiState.enableCmdSettings -> stringResource(R.string.fake_payload_library_cli_title)
                                !uiState.desyncHttpEnabled -> stringResource(R.string.fake_payload_profile_status_off)
                                uiState.httpFakeProfileActiveInStrategy ->
                                    stringResource(R.string.fake_payload_profile_status_live)
                                uiState.hasHostFake -> stringResource(R.string.fake_payload_profile_status_separate)
                                else -> stringResource(R.string.fake_payload_profile_status_ready)
                            },
                        statusTone =
                            when {
                                uiState.enableCmdSettings -> StatusIndicatorTone.Warning
                                !uiState.desyncHttpEnabled -> StatusIndicatorTone.Idle
                                uiState.httpFakeProfileActiveInStrategy -> StatusIndicatorTone.Active
                                uiState.hasHostFake -> StatusIndicatorTone.Idle
                                else -> StatusIndicatorTone.Idle
                            },
                        badges =
                            buildList {
                                add(
                                    (
                                        if (uiState.desyncHttpEnabled) {
                                            stringResource(R.string.fake_payload_badge_http_on)
                                        } else {
                                            stringResource(R.string.fake_payload_badge_http_off)
                                        }
                                    ) to SummaryCapsuleTone.Neutral,
                                )
                                add(
                                    (
                                        if (uiState.httpFakeProfileActiveInStrategy) {
                                            stringResource(R.string.fake_payload_badge_fake_step_live)
                                        } else if (uiState.hasHostFake) {
                                            stringResource(R.string.fake_payload_badge_hostfake_separate)
                                        } else {
                                            stringResource(R.string.fake_payload_badge_fake_step_needed)
                                        }
                                    ) to
                                        when {
                                            uiState.httpFakeProfileActiveInStrategy -> SummaryCapsuleTone.Active
                                            uiState.hasHostFake -> SummaryCapsuleTone.Info
                                            else -> SummaryCapsuleTone.Warning
                                        },
                                )
                            },
                        appliesSummary =
                            when {
                                !uiState.desyncHttpEnabled ->
                                    stringResource(R.string.http_fake_profile_scope_off)
                                uiState.httpFakeProfileActiveInStrategy ->
                                    stringResource(R.string.http_fake_profile_scope_live)
                                uiState.hasHostFake ->
                                    stringResource(R.string.http_fake_profile_scope_hostfake)
                                else -> stringResource(R.string.http_fake_profile_scope_ready)
                            },
                        interactionSummary = stringResource(R.string.http_fake_profile_interaction),
                        value = uiState.httpFakeProfile,
                        options = httpFakeProfileOptions,
                        setting = AdvancedOptionSetting.HttpFakeProfile,
                        onSelected = onOptionSelected,
                        enabled = visualEditorEnabled,
                        modifier = Modifier.padding(bottom = spacing.sm),
                    )
                    FakePayloadProfileCard(
                        title = stringResource(R.string.tls_fake_profile_title),
                        description = stringResource(R.string.tls_fake_profile_body),
                        profileLabel = formatTlsFakeProfileLabel(uiState.tlsFakeProfile),
                        statusLabel =
                            when {
                                uiState.enableCmdSettings -> stringResource(R.string.fake_payload_library_cli_title)
                                !uiState.desyncHttpsEnabled -> stringResource(R.string.fake_payload_profile_status_off)
                                uiState.tlsFakeProfileActiveInStrategy ->
                                    stringResource(R.string.fake_payload_profile_status_live)
                                uiState.hasHostFake -> stringResource(R.string.fake_payload_profile_status_separate)
                                else -> stringResource(R.string.fake_payload_profile_status_ready)
                            },
                        statusTone =
                            when {
                                uiState.enableCmdSettings -> StatusIndicatorTone.Warning
                                !uiState.desyncHttpsEnabled -> StatusIndicatorTone.Idle
                                uiState.tlsFakeProfileActiveInStrategy -> StatusIndicatorTone.Active
                                uiState.hasHostFake -> StatusIndicatorTone.Idle
                                else -> StatusIndicatorTone.Idle
                            },
                        badges =
                            buildList {
                                add(
                                    (
                                        if (uiState.desyncHttpsEnabled) {
                                            stringResource(R.string.fake_payload_badge_https_on)
                                        } else {
                                            stringResource(R.string.fake_payload_badge_https_off)
                                        }
                                    ) to SummaryCapsuleTone.Neutral,
                                )
                                add(
                                    (
                                        if (uiState.tlsFakeProfileActiveInStrategy) {
                                            stringResource(R.string.fake_payload_badge_fake_step_live)
                                        } else if (uiState.hasHostFake) {
                                            stringResource(R.string.fake_payload_badge_hostfake_separate)
                                        } else {
                                            stringResource(R.string.fake_payload_badge_fake_step_needed)
                                        }
                                    ) to
                                        when {
                                            uiState.tlsFakeProfileActiveInStrategy -> SummaryCapsuleTone.Active
                                            uiState.hasHostFake -> SummaryCapsuleTone.Info
                                            else -> SummaryCapsuleTone.Warning
                                        },
                                )
                                add(
                                    stringResource(R.string.fake_payload_badge_fake_tls_layers) to SummaryCapsuleTone.Info,
                                )
                            },
                        appliesSummary =
                            when {
                                !uiState.desyncHttpsEnabled ->
                                    stringResource(R.string.tls_fake_profile_scope_off)
                                uiState.tlsFakeProfileActiveInStrategy ->
                                    stringResource(R.string.tls_fake_profile_scope_live)
                                uiState.hasHostFake ->
                                    stringResource(R.string.tls_fake_profile_scope_hostfake)
                                else -> stringResource(R.string.tls_fake_profile_scope_ready)
                            },
                        interactionSummary = stringResource(R.string.tls_fake_profile_interaction),
                        value = uiState.tlsFakeProfile,
                        options = tlsFakeProfileOptions,
                        setting = AdvancedOptionSetting.TlsFakeProfile,
                        onSelected = onOptionSelected,
                        enabled = visualEditorEnabled,
                        modifier = Modifier.padding(bottom = spacing.sm),
                    )
                    FakePayloadProfileCard(
                        title = stringResource(R.string.udp_fake_profile_title),
                        description = stringResource(R.string.udp_fake_profile_body),
                        profileLabel = formatUdpFakeProfileLabel(uiState.udpFakeProfile),
                        statusLabel =
                            when {
                                uiState.enableCmdSettings -> stringResource(R.string.fake_payload_library_cli_title)
                                !uiState.desyncUdpEnabled -> stringResource(R.string.fake_payload_profile_status_off)
                                uiState.udpFakeProfileActiveInStrategy ->
                                    stringResource(R.string.fake_payload_profile_status_live)
                                else -> stringResource(R.string.fake_payload_profile_status_ready)
                            },
                        statusTone =
                            when {
                                uiState.enableCmdSettings -> StatusIndicatorTone.Warning
                                !uiState.desyncUdpEnabled -> StatusIndicatorTone.Idle
                                uiState.udpFakeProfileActiveInStrategy -> StatusIndicatorTone.Active
                                else -> StatusIndicatorTone.Idle
                            },
                        badges =
                            buildList {
                                add(
                                    (
                                        if (uiState.desyncUdpEnabled) {
                                            stringResource(R.string.fake_payload_badge_udp_on)
                                        } else {
                                            stringResource(R.string.fake_payload_badge_udp_off)
                                        }
                                    ) to SummaryCapsuleTone.Neutral,
                                )
                                add(
                                    (
                                        if (uiState.udpFakeProfileActiveInStrategy) {
                                            stringResource(
                                                R.string.fake_payload_badge_burst_ready,
                                                uiState.udpFakeCount,
                                            )
                                        } else {
                                            stringResource(R.string.fake_payload_badge_burst_needed)
                                        }
                                    ) to
                                        if (uiState.udpFakeProfileActiveInStrategy) {
                                            SummaryCapsuleTone.Active
                                        } else {
                                            SummaryCapsuleTone.Warning
                                        },
                                )
                                if (uiState.quicFakeProfileActive) {
                                    add(
                                        stringResource(R.string.fake_payload_badge_quic_separate) to SummaryCapsuleTone.Info,
                                    )
                                }
                            },
                        appliesSummary =
                            when {
                                !uiState.desyncUdpEnabled ->
                                    stringResource(R.string.udp_fake_profile_scope_off)
                                uiState.udpFakeProfileActiveInStrategy ->
                                    stringResource(
                                        R.string.udp_fake_profile_scope_live,
                                        uiState.udpFakeCount,
                                    )
                                else -> stringResource(R.string.udp_fake_profile_scope_ready)
                            },
                        interactionSummary =
                            if (uiState.quicFakeProfileActive) {
                                stringResource(R.string.udp_fake_profile_interaction_quic_override)
                            } else {
                                stringResource(R.string.udp_fake_profile_interaction)
                            },
                        value = uiState.udpFakeProfile,
                        options = udpFakeProfileOptions,
                        setting = AdvancedOptionSetting.UdpFakeProfile,
                        onSelected = onOptionSelected,
                        enabled = visualEditorEnabled,
                        modifier = Modifier.padding(bottom = spacing.sm),
                    )
                }
                if (showFakeTlsSection) {
                    Text(
                        text = stringResource(R.string.ripdpi_fake_tls_section_title),
                        style = RipDpiThemeTokens.type.bodyEmphasis,
                        color = colors.foreground,
                    )
                    Text(
                        text =
                            if (uiState.fakeTlsControlsRelevant) {
                                stringResource(R.string.ripdpi_fake_tls_section_body)
                            } else {
                                stringResource(R.string.ripdpi_fake_tls_inactive)
                            },
                        style = RipDpiThemeTokens.type.secondaryBody,
                        color = colors.mutedForeground,
                    )
                    FakeTlsProfileCard(
                        uiState = uiState,
                        onResetFakeTlsProfile = onResetFakeTlsProfile,
                        modifier = Modifier.padding(top = spacing.xs, bottom = spacing.sm),
                    )
                    HorizontalDivider(color = colors.divider)
                    AdvancedDropdownSetting(
                        title = stringResource(R.string.ripdpi_fake_tls_base_title),
                        description = stringResource(R.string.ripdpi_fake_tls_base_body),
                        value = if (uiState.fakeTlsUseOriginal) "original" else "default",
                        options = fakeTlsBaseOptions,
                        setting = AdvancedOptionSetting.FakeTlsBase,
                        onSelected = onOptionSelected,
                        enabled = visualEditorEnabled,
                        showDivider = true,
                    )
                    AdvancedDropdownSetting(
                        title = stringResource(R.string.ripdpi_fake_tls_sni_mode_title),
                        description = stringResource(R.string.ripdpi_fake_tls_sni_mode_body),
                        value = uiState.fakeTlsSniMode,
                        options = fakeTlsSniModeOptions,
                        setting = AdvancedOptionSetting.FakeTlsSniMode,
                        onSelected = onOptionSelected,
                        enabled = visualEditorEnabled,
                        showDivider = uiState.fakeTlsSniMode == FakeTlsSniModeFixed,
                    )
                    if (uiState.fakeTlsSniMode == FakeTlsSniModeFixed) {
                        AdvancedTextSetting(
                            title = stringResource(R.string.sni_of_fake_packet),
                            value = uiState.fakeSni,
                            enabled = visualEditorEnabled,
                            disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                            setting = AdvancedTextSetting.FakeSni,
                            onConfirm = onTextConfirmed,
                            showDivider = true,
                        )
                    }
                    SettingsRow(
                        title = stringResource(R.string.ripdpi_fake_tls_randomize_title),
                        subtitle = stringResource(R.string.ripdpi_fake_tls_randomize_body),
                        checked = uiState.fakeTlsRandomize,
                        onCheckedChange = { onToggleChanged(AdvancedToggleSetting.FakeTlsRandomize, it) },
                        enabled = visualEditorEnabled,
                        showDivider = true,
                    )
                    SettingsRow(
                        title = stringResource(R.string.ripdpi_fake_tls_dup_sid_title),
                        subtitle = stringResource(R.string.ripdpi_fake_tls_dup_sid_body),
                        checked = uiState.fakeTlsDupSessionId,
                        onCheckedChange = { onToggleChanged(AdvancedToggleSetting.FakeTlsDupSessionId, it) },
                        enabled = visualEditorEnabled,
                        showDivider = true,
                    )
                    SettingsRow(
                        title = stringResource(R.string.ripdpi_fake_tls_pad_encap_title),
                        subtitle = stringResource(R.string.ripdpi_fake_tls_pad_encap_body),
                        checked = uiState.fakeTlsPadEncap,
                        onCheckedChange = { onToggleChanged(AdvancedToggleSetting.FakeTlsPadEncap, it) },
                        enabled = visualEditorEnabled,
                        showDivider = true,
                    )
                    AdvancedTextSetting(
                        title = stringResource(R.string.ripdpi_fake_tls_size_title),
                        description = stringResource(R.string.config_fake_tls_size_helper),
                        value = uiState.fakeTlsSize.toString(),
                        placeholder = stringResource(R.string.config_placeholder_fake_tls_size),
                        enabled = visualEditorEnabled,
                        validator = { it.isEmpty() || it.toIntOrNull() != null },
                        invalidMessage = stringResource(R.string.config_error_out_of_range),
                        disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                        setting = AdvancedTextSetting.FakeTlsSize,
                        onConfirm = onTextConfirmed,
                        showDivider = true,
                    )
                }
                if (uiState.isOob) {
                    AdvancedTextSetting(
                        title = stringResource(R.string.oob_data),
                        value = uiState.oobData,
                        enabled = visualEditorEnabled,
                        validator = { it.length <= 1 },
                        invalidMessage = stringResource(R.string.advanced_settings_error_oob_data),
                        disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done,
                            ),
                        setting = AdvancedTextSetting.OobData,
                        onConfirm = onTextConfirmed,
                        showDivider = true,
                    )
                }
                SettingsRow(
                    title = stringResource(R.string.ripdpi_drop_sack_setting),
                    checked = uiState.dropSack,
                    onCheckedChange = { onToggleChanged(AdvancedToggleSetting.DropSack, it) },
                    enabled = visualEditorEnabled,
                )
            }
        }
    }
}

private val SettingsUiState.defaultTtlValue: String
    get() = if (customTtl) defaultTtl.toString() else ""

private data class AdaptiveSplitStatusContent(
    val label: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

private data class AdaptiveFakeTtlStatusContent(
    val label: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

private data class HostFakeStatusContent(
    val label: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

private data class FakeApproximationStatusContent(
    val label: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

private data class FakePayloadLibraryStatusContent(
    val label: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

private data class FakeTlsStatusContent(
    val label: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

@Composable
internal fun rememberAdaptiveSplitPresetOptions(
    uiState: SettingsUiState,
    includeCustom: Boolean = uiState.hasCustomAdaptiveSplitPreset,
): List<AdaptiveSplitPresetUiModel> =
    buildList {
        add(
            AdaptiveSplitPresetUiModel(
                value = AdaptiveSplitPresetManual,
                title = stringResource(R.string.adaptive_split_preset_manual),
                body = stringResource(R.string.adaptive_split_preset_manual_body),
            ),
        )
        add(
            AdaptiveSplitPresetUiModel(
                value = AdaptiveMarkerBalanced,
                title = stringResource(R.string.adaptive_split_preset_balanced),
                body = stringResource(R.string.adaptive_split_preset_balanced_body),
                isRecommended = true,
            ),
        )
        add(
            AdaptiveSplitPresetUiModel(
                value = AdaptiveMarkerHost,
                title = stringResource(R.string.adaptive_split_preset_host),
                body = stringResource(R.string.adaptive_split_preset_host_body),
            ),
        )
        add(
            AdaptiveSplitPresetUiModel(
                value = AdaptiveMarkerEndHost,
                title = stringResource(R.string.adaptive_split_preset_endhost),
                body = stringResource(R.string.adaptive_split_preset_endhost_body),
            ),
        )
        add(
            AdaptiveSplitPresetUiModel(
                value = AdaptiveMarkerSniExt,
                title = stringResource(R.string.adaptive_split_preset_sniext),
                body = stringResource(R.string.adaptive_split_preset_sniext_body),
            ),
        )
        if (includeCustom) {
            add(
                1,
                AdaptiveSplitPresetUiModel(
                    value = AdaptiveSplitPresetCustom,
                    title = stringResource(R.string.adaptive_split_preset_custom),
                    body =
                        stringResource(
                            R.string.adaptive_split_preset_custom_body,
                            formatOffsetExpressionLabel(uiState.splitMarker),
                        ),
                ),
            )
        }
    }

@Composable
internal fun rememberAdaptiveFakeTtlModeOptions(
    uiState: SettingsUiState,
    includeCustom: Boolean = uiState.hasCustomAdaptiveFakeTtl,
): List<AdaptiveFakeTtlModeUiModel> =
    buildList {
        add(
            AdaptiveFakeTtlModeUiModel(
                value = AdaptiveFakeTtlModeFixed,
                title = stringResource(R.string.adaptive_fake_ttl_mode_fixed_title),
                body = stringResource(R.string.adaptive_fake_ttl_mode_fixed_body),
            ),
        )
        add(
            AdaptiveFakeTtlModeUiModel(
                value = AdaptiveFakeTtlModeAdaptive,
                title = stringResource(R.string.adaptive_fake_ttl_mode_adaptive_title),
                body = stringResource(R.string.adaptive_fake_ttl_mode_adaptive_body),
                badgeLabel = stringResource(R.string.adaptive_fake_ttl_mode_recommended),
            ),
        )
        if (includeCustom) {
            add(
                1,
                AdaptiveFakeTtlModeUiModel(
                    value = AdaptiveFakeTtlModeCustom,
                    title = stringResource(R.string.adaptive_fake_ttl_mode_custom_title),
                    body =
                        stringResource(
                            R.string.adaptive_fake_ttl_mode_custom_body,
                            uiState.adaptiveFakeTtlDelta,
                        ),
                    badgeLabel = stringResource(R.string.adaptive_fake_ttl_mode_custom_badge),
                    badgeTone = StatusIndicatorTone.Warning,
                ),
            )
        }
    }

@Composable
private fun AdaptiveSplitProfileCard(
    uiState: SettingsUiState,
    onResetAdaptiveSplit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val status = rememberAdaptiveSplitStatus(uiState)
    val profileSummary =
        when (uiState.adaptiveSplitPreset) {
            AdaptiveSplitPresetManual -> stringResource(R.string.adaptive_split_profile_manual)
            AdaptiveSplitPresetCustom ->
                stringResource(
                    R.string.adaptive_split_profile_custom,
                    formatOffsetExpressionLabel(uiState.splitMarker),
                )
            else -> formatOffsetExpressionLabel(uiState.splitMarker)
        }
    val targetSummary =
        if (uiState.settings.tcpChainStepsCount > 0 && primaryTcpChainStep(uiState.tcpChainSteps) != null) {
            stringResource(R.string.adaptive_split_target_chain_step)
        } else {
            stringResource(R.string.adaptive_split_target_legacy)
        }
    val focusSummary =
        when (uiState.adaptiveSplitPreset) {
            AdaptiveSplitPresetManual -> stringResource(R.string.adaptive_split_focus_manual)
            AdaptiveSplitPresetCustom -> stringResource(R.string.adaptive_split_focus_custom)
            AdaptiveMarkerBalanced -> stringResource(R.string.adaptive_split_focus_balanced)
            AdaptiveMarkerHost -> stringResource(R.string.adaptive_split_focus_host)
            AdaptiveMarkerEndHost -> stringResource(R.string.adaptive_split_focus_endhost)
            AdaptiveMarkerSniExt -> stringResource(R.string.adaptive_split_focus_sniext)
            else -> stringResource(R.string.adaptive_split_focus_custom)
        }
    val scopeSummary =
        when {
            uiState.enableCmdSettings -> stringResource(R.string.adaptive_split_scope_cli)
            !uiState.desyncEnabled -> stringResource(R.string.adaptive_split_scope_disabled)
            !uiState.adaptiveSplitVisualEditorSupported -> stringResource(R.string.adaptive_split_scope_hostfake)
            uiState.hasAdaptiveSplitPreset -> stringResource(R.string.adaptive_split_scope_active)
            else -> stringResource(R.string.adaptive_split_scope_manual)
        }
    val protocolSummary =
        when {
            uiState.desyncHttpEnabled && uiState.desyncHttpsEnabled ->
                stringResource(R.string.adaptive_split_protocol_http_https)
            uiState.desyncHttpEnabled -> stringResource(R.string.adaptive_split_protocol_http)
            uiState.desyncHttpsEnabled -> stringResource(R.string.adaptive_split_protocol_https)
            else -> stringResource(R.string.adaptive_split_protocol_none)
        }
    val dslSummary = stringResource(R.string.adaptive_split_dsl_only_summary)
    val badges =
        buildList {
            add(
                (
                    if (uiState.hasAdaptiveSplitPreset) {
                        stringResource(R.string.adaptive_split_badge_adaptive)
                    } else {
                        stringResource(R.string.adaptive_split_badge_manual)
                    }
                ) to
                    if (uiState.hasAdaptiveSplitPreset) {
                        SummaryCapsuleTone.Active
                    } else {
                        SummaryCapsuleTone.Neutral
                    },
            )
            if (uiState.hasCustomAdaptiveSplitPreset) {
                add(stringResource(R.string.adaptive_split_badge_custom) to SummaryCapsuleTone.Info)
            }
            if (uiState.desyncHttpsEnabled) {
                add(stringResource(R.string.adaptive_split_badge_https) to SummaryCapsuleTone.Info)
            }
            if (uiState.desyncHttpEnabled) {
                add(stringResource(R.string.adaptive_split_badge_http) to SummaryCapsuleTone.Info)
            }
        }

    RipDpiCard(
        modifier = modifier,
        variant = RipDpiCardVariant.Elevated,
    ) {
        StatusIndicator(
            label = status.label,
            tone = status.tone,
        )
        Text(
            text = status.body,
            style = type.secondaryBody,
            color = colors.foreground,
        )
        SummaryCapsuleFlow(items = badges)
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_split_summary_label_profile),
                value = profileSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_split_summary_label_target),
                value = targetSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_split_summary_label_focus),
                value = focusSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_split_summary_label_protocols),
                value = protocolSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_split_summary_label_scope),
                value = scopeSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_split_summary_label_dsl),
                value = dslSummary,
            )
        }
        Text(
            text = stringResource(R.string.adaptive_split_editor_note),
            style = type.caption,
            color = colors.mutedForeground,
        )
        if (uiState.canResetAdaptiveSplitPreset) {
            RipDpiButton(
                text = stringResource(R.string.adaptive_split_reset_action),
                onClick = onResetAdaptiveSplit,
                variant = RipDpiButtonVariant.Outline,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun rememberAdaptiveSplitStatus(uiState: SettingsUiState): AdaptiveSplitStatusContent =
    when {
        uiState.enableCmdSettings ->
            AdaptiveSplitStatusContent(
                label = stringResource(R.string.adaptive_split_cli_title),
                body = stringResource(R.string.adaptive_split_cli_body),
                tone = StatusIndicatorTone.Warning,
            )

        !uiState.adaptiveSplitVisualEditorSupported ->
            AdaptiveSplitStatusContent(
                label = stringResource(R.string.adaptive_split_hostfake_title),
                body = stringResource(R.string.adaptive_split_hostfake_body),
                tone = StatusIndicatorTone.Idle,
            )

        !uiState.desyncEnabled && uiState.hasAdaptiveSplitPreset ->
            AdaptiveSplitStatusContent(
                label = stringResource(R.string.adaptive_split_saved_title),
                body = stringResource(R.string.adaptive_split_saved_body),
                tone = StatusIndicatorTone.Idle,
            )

        !uiState.desyncEnabled ->
            AdaptiveSplitStatusContent(
                label = stringResource(R.string.adaptive_split_off_title),
                body = stringResource(R.string.adaptive_split_off_body),
                tone = StatusIndicatorTone.Idle,
            )

        uiState.isServiceRunning && uiState.hasAdaptiveSplitPreset ->
            AdaptiveSplitStatusContent(
                label = stringResource(R.string.adaptive_split_restart_title),
                body = stringResource(R.string.adaptive_split_restart_body),
                tone = StatusIndicatorTone.Warning,
            )

        uiState.hasAdaptiveSplitPreset ->
            AdaptiveSplitStatusContent(
                label = stringResource(R.string.adaptive_split_ready_title),
                body = stringResource(R.string.adaptive_split_ready_body),
                tone = StatusIndicatorTone.Active,
            )

        else ->
            AdaptiveSplitStatusContent(
                label = stringResource(R.string.adaptive_split_manual_title),
                body = stringResource(R.string.adaptive_split_manual_body),
                tone = StatusIndicatorTone.Idle,
            )
    }

@Composable
private fun AdaptiveSplitPresetSelector(
    uiState: SettingsUiState,
    presets: List<AdaptiveSplitPresetUiModel>,
    enabled: Boolean,
    onPresetSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.adaptive_split_selector_title),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text = stringResource(R.string.adaptive_split_selector_body),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.mutedForeground,
        )
        presets.forEach { preset ->
            AdaptiveSplitPresetCard(
                preset = preset,
                selected = uiState.adaptiveSplitPreset == preset.value,
                enabled = enabled && preset.value != AdaptiveSplitPresetCustom,
                onClick = { onPresetSelected(preset.value) },
            )
        }
    }
}

@Composable
private fun AdaptiveSplitPresetCard(
    preset: AdaptiveSplitPresetUiModel,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val badgeLabel =
        when {
            selected -> stringResource(R.string.adaptive_split_preset_selected)
            preset.isRecommended -> stringResource(R.string.adaptive_split_preset_recommended)
            preset.value == AdaptiveSplitPresetCustom -> stringResource(R.string.adaptive_split_preset_dsl_only)
            else -> null
        }
    val badgeTone =
        when {
            selected -> StatusIndicatorTone.Active
            preset.isRecommended -> StatusIndicatorTone.Idle
            else -> StatusIndicatorTone.Idle
        }

    RipDpiCard(
        modifier = modifier,
        variant = if (selected) RipDpiCardVariant.Tonal else RipDpiCardVariant.Outlined,
        enabled = enabled,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
            ) {
                Text(
                    text = preset.title,
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = RipDpiThemeTokens.colors.foreground,
                )
                Text(
                    text = preset.body,
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
            }
            badgeLabel?.let {
                StatusIndicator(
                    label = it,
                    tone = badgeTone,
                )
            }
        }
    }
}

@Composable
private fun AdaptiveFakeTtlProfileCard(
    uiState: SettingsUiState,
    onResetAdaptiveFakeTtlProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val status = rememberAdaptiveFakeTtlStatus(uiState)
    val modeSummary =
        when (uiState.adaptiveFakeTtlMode) {
            AdaptiveFakeTtlModeAdaptive -> stringResource(R.string.adaptive_fake_ttl_summary_mode_adaptive)
            AdaptiveFakeTtlModeCustom -> stringResource(R.string.adaptive_fake_ttl_summary_mode_custom, uiState.adaptiveFakeTtlDelta)
            else -> stringResource(R.string.adaptive_fake_ttl_summary_mode_fixed)
        }
    val windowSummary =
        if (uiState.hasAdaptiveFakeTtl) {
            stringResource(
                R.string.adaptive_fake_ttl_summary_window_value,
                uiState.adaptiveFakeTtlMin,
                uiState.adaptiveFakeTtlMax,
            )
        } else {
            stringResource(R.string.adaptive_fake_ttl_summary_window_fixed)
        }
    val fallbackSummary =
        if (uiState.hasAdaptiveFakeTtl) {
            stringResource(R.string.adaptive_fake_ttl_summary_fallback_value, uiState.adaptiveFakeTtlFallback)
        } else {
            stringResource(R.string.adaptive_fake_ttl_summary_fallback_fixed, uiState.fakeTtl)
        }
    val scopeSummary =
        when {
            uiState.enableCmdSettings -> stringResource(R.string.adaptive_fake_ttl_scope_cli)
            !uiState.fakeTtlControlsRelevant -> stringResource(R.string.adaptive_fake_ttl_scope_idle)
            uiState.hasAdaptiveFakeTtl -> stringResource(R.string.adaptive_fake_ttl_scope_adaptive)
            else -> stringResource(R.string.adaptive_fake_ttl_scope_fixed)
        }
    val targetLabels =
        buildList {
            if (uiState.isFake) add(stringResource(R.string.adaptive_fake_ttl_targets_fake))
            if (uiState.hasHostFake) add(stringResource(R.string.adaptive_fake_ttl_targets_hostfake))
            if (uiState.hasDisoob) add(stringResource(R.string.adaptive_fake_ttl_targets_disoob))
        }
    val targetSummary =
        if (targetLabels.isEmpty()) {
            stringResource(R.string.adaptive_fake_ttl_targets_none)
        } else {
            targetLabels.joinToString()
        }
    val learningSummary =
        if (uiState.hasAdaptiveFakeTtl) {
            stringResource(R.string.adaptive_fake_ttl_learning_runtime)
        } else {
            stringResource(R.string.adaptive_fake_ttl_learning_fixed)
        }
    val badges =
        buildList {
            add(
                (
                    when (uiState.adaptiveFakeTtlMode) {
                        AdaptiveFakeTtlModeAdaptive -> stringResource(R.string.adaptive_fake_ttl_badge_adaptive)
                        AdaptiveFakeTtlModeCustom -> stringResource(R.string.adaptive_fake_ttl_badge_custom)
                        else -> stringResource(R.string.adaptive_fake_ttl_badge_fixed)
                    }
                ) to
                    when (uiState.adaptiveFakeTtlMode) {
                        AdaptiveFakeTtlModeAdaptive -> SummaryCapsuleTone.Active
                        AdaptiveFakeTtlModeCustom -> SummaryCapsuleTone.Info
                        else -> SummaryCapsuleTone.Neutral
                    },
            )
            add(stringResource(R.string.adaptive_fake_ttl_badge_tcp_only) to SummaryCapsuleTone.Info)
            if (uiState.hasAdaptiveFakeTtl) {
                add(stringResource(R.string.adaptive_fake_ttl_badge_runtime_learned) to SummaryCapsuleTone.Active)
            }
            if (uiState.isFake) {
                add(stringResource(R.string.adaptive_fake_ttl_badge_fake) to SummaryCapsuleTone.Active)
            }
            if (uiState.hasHostFake) {
                add(stringResource(R.string.adaptive_fake_ttl_badge_hostfake) to SummaryCapsuleTone.Info)
            }
            if (uiState.hasDisoob) {
                add(stringResource(R.string.adaptive_fake_ttl_badge_disoob) to SummaryCapsuleTone.Warning)
            }
        }

    RipDpiCard(
        modifier = modifier,
        variant = RipDpiCardVariant.Elevated,
    ) {
        StatusIndicator(
            label = status.label,
            tone = status.tone,
        )
        Text(
            text = status.body,
            style = type.secondaryBody,
            color = colors.foreground,
        )
        SummaryCapsuleFlow(items = badges)
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_fake_ttl_summary_label_mode),
                value = modeSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_fake_ttl_summary_label_window),
                value = windowSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_fake_ttl_summary_label_fallback),
                value = fallbackSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_fake_ttl_summary_label_scope),
                value = scopeSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_fake_ttl_summary_label_targets),
                value = targetSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_fake_ttl_summary_label_learning),
                value = learningSummary,
            )
        }
        if (uiState.canResetAdaptiveFakeTtlProfile) {
            RipDpiButton(
                text = stringResource(R.string.adaptive_fake_ttl_reset_action),
                onClick = onResetAdaptiveFakeTtlProfile,
                variant = RipDpiButtonVariant.Outline,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun rememberAdaptiveFakeTtlStatus(uiState: SettingsUiState): AdaptiveFakeTtlStatusContent =
    when {
        uiState.enableCmdSettings ->
            AdaptiveFakeTtlStatusContent(
                label = stringResource(R.string.adaptive_fake_ttl_cli_title),
                body = stringResource(R.string.adaptive_fake_ttl_cli_body),
                tone = StatusIndicatorTone.Warning,
            )

        !uiState.fakeTtlControlsRelevant && uiState.hasAdaptiveFakeTtl ->
            AdaptiveFakeTtlStatusContent(
                label = stringResource(R.string.adaptive_fake_ttl_saved_title),
                body = stringResource(R.string.adaptive_fake_ttl_saved_body),
                tone = StatusIndicatorTone.Idle,
            )

        !uiState.fakeTtlControlsRelevant ->
            AdaptiveFakeTtlStatusContent(
                label = stringResource(R.string.adaptive_fake_ttl_fixed_title),
                body = stringResource(R.string.adaptive_fake_ttl_fixed_body),
                tone = StatusIndicatorTone.Idle,
            )

        uiState.isServiceRunning && uiState.hasAdaptiveFakeTtl ->
            AdaptiveFakeTtlStatusContent(
                label = stringResource(R.string.adaptive_fake_ttl_restart_title),
                body = stringResource(R.string.adaptive_fake_ttl_restart_body),
                tone = StatusIndicatorTone.Warning,
            )

        uiState.hasCustomAdaptiveFakeTtl ->
            AdaptiveFakeTtlStatusContent(
                label = stringResource(R.string.adaptive_fake_ttl_custom_title),
                body = stringResource(R.string.adaptive_fake_ttl_custom_body),
                tone = StatusIndicatorTone.Active,
            )

        uiState.hasAdaptiveFakeTtl ->
            AdaptiveFakeTtlStatusContent(
                label = stringResource(R.string.adaptive_fake_ttl_adaptive_title),
                body = stringResource(R.string.adaptive_fake_ttl_adaptive_body),
                tone = StatusIndicatorTone.Active,
            )

        else ->
            AdaptiveFakeTtlStatusContent(
                label = stringResource(R.string.adaptive_fake_ttl_fixed_title),
                body = stringResource(R.string.adaptive_fake_ttl_fixed_body),
                tone = StatusIndicatorTone.Idle,
            )
    }

@Composable
private fun AdaptiveFakeTtlModeSelector(
    uiState: SettingsUiState,
    presets: List<AdaptiveFakeTtlModeUiModel>,
    enabled: Boolean,
    onModeSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.adaptive_fake_ttl_selector_title),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Text(
            text = stringResource(R.string.adaptive_fake_ttl_selector_body),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        presets.forEach { preset ->
            AdaptiveFakeTtlModeCard(
                preset = preset,
                selected = uiState.adaptiveFakeTtlMode == preset.value,
                enabled = enabled && preset.value != AdaptiveFakeTtlModeCustom,
                onClick = { onModeSelected(preset.value) },
            )
        }
    }
}

@Composable
private fun AdaptiveFakeTtlModeCard(
    preset: AdaptiveFakeTtlModeUiModel,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    RipDpiCard(
        modifier = modifier,
        variant = if (selected) RipDpiCardVariant.Tonal else RipDpiCardVariant.Outlined,
        enabled = enabled,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = preset.title,
                    style = type.bodyEmphasis,
                    color = colors.foreground,
                )
                Text(
                    text = preset.body,
                    style = type.secondaryBody,
                    color = colors.mutedForeground,
                )
            }
            val badgeLabel =
                when {
                    selected -> stringResource(R.string.adaptive_fake_ttl_mode_selected)
                    preset.badgeLabel != null -> preset.badgeLabel
                    else -> null
                }
            badgeLabel?.let {
                StatusIndicator(
                    label = it,
                    tone = if (selected) StatusIndicatorTone.Active else preset.badgeTone,
                )
            }
        }
    }
}

@Composable
private fun HostFakeProfileCard(
    uiState: SettingsUiState,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val status = rememberHostFakeStatus(uiState)
    val primaryStep = uiState.primaryHostFakeStep
    val profileSummary =
        when (uiState.hostFakeStepCount) {
            0 -> stringResource(R.string.ripdpi_hostfake_summary_profile_none)
            1 -> stringResource(R.string.ripdpi_hostfake_summary_profile_single)
            else -> stringResource(R.string.ripdpi_hostfake_summary_profile_multiple, uiState.hostFakeStepCount)
        }
    val scopeSummary =
        when {
            uiState.desyncHttpEnabled && uiState.desyncHttpsEnabled ->
                stringResource(R.string.ripdpi_hostfake_summary_scope_http_https)
            uiState.desyncHttpEnabled -> stringResource(R.string.ripdpi_hostfake_summary_scope_http)
            uiState.desyncHttpsEnabled -> stringResource(R.string.ripdpi_hostfake_summary_scope_https)
            else -> stringResource(R.string.ripdpi_hostfake_summary_scope_none)
        }
    val templateSummary =
        primaryStep
            ?.fakeHostTemplate
            ?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.ripdpi_hostfake_summary_template_random)
    val midhostSummary =
        primaryStep
            ?.midhostMarker
            ?.takeIf { it.isNotBlank() }
            ?.let { stringResource(R.string.ripdpi_hostfake_summary_midhost_marker, it) }
            ?: stringResource(R.string.ripdpi_hostfake_summary_midhost_whole)
    val endMarkerSummary =
        primaryStep
            ?.marker
            ?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.ripdpi_hostfake_summary_end_marker_none)

    RipDpiCard(
        modifier = modifier,
        variant = RipDpiCardVariant.Tonal,
    ) {
        StatusIndicator(
            label = status.label,
            tone = status.tone,
        )
        Text(
            text = status.body,
            style = type.secondaryBody,
            color = colors.foreground,
        )
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_hostfake_summary_label_profile),
                value = profileSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_hostfake_summary_label_scope),
                value = scopeSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_hostfake_summary_label_template),
                value = templateSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_hostfake_summary_label_midhost),
                value = midhostSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_hostfake_summary_label_end_marker),
                value = endMarkerSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_hostfake_summary_label_transport),
                value = stringResource(R.string.ripdpi_hostfake_summary_transport, uiState.fakeTtl),
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_hostfake_summary_label_http_example),
                value = stringResource(R.string.ripdpi_hostfake_example_http),
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_hostfake_summary_label_tls_example),
                value = stringResource(R.string.ripdpi_hostfake_example_tls),
            )
        }
        Text(
            text = stringResource(R.string.ripdpi_hostfake_scope_note),
            style = type.caption,
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun rememberHostFakeStatus(uiState: SettingsUiState): HostFakeStatusContent =
    when {
        uiState.enableCmdSettings ->
            HostFakeStatusContent(
                label = stringResource(R.string.ripdpi_hostfake_cli_title),
                body = stringResource(R.string.ripdpi_hostfake_cli_body),
                tone = StatusIndicatorTone.Warning,
            )

        !uiState.hostFakeControlsRelevant ->
            HostFakeStatusContent(
                label = stringResource(R.string.ripdpi_hostfake_protocols_off_title),
                body = stringResource(R.string.ripdpi_hostfake_protocols_off_body),
                tone = StatusIndicatorTone.Idle,
            )

        uiState.hasHostFake && uiState.isServiceRunning ->
            HostFakeStatusContent(
                label = stringResource(R.string.ripdpi_hostfake_restart_title),
                body = stringResource(R.string.ripdpi_hostfake_restart_body),
                tone = StatusIndicatorTone.Warning,
            )

        uiState.hasHostFake ->
            HostFakeStatusContent(
                label = stringResource(R.string.ripdpi_hostfake_ready_title),
                body = stringResource(R.string.ripdpi_hostfake_ready_body),
                tone = StatusIndicatorTone.Active,
            )

        else ->
            HostFakeStatusContent(
                label = stringResource(R.string.ripdpi_hostfake_available_title),
                body = stringResource(R.string.ripdpi_hostfake_available_body),
                tone = StatusIndicatorTone.Idle,
            )
    }

@Composable
private fun FakeApproximationProfileCard(
    uiState: SettingsUiState,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val status = rememberFakeApproximationStatus(uiState)
    val primaryStep = uiState.primaryFakeApproximationStep
    val profileSummary =
        when (uiState.fakeApproximationStepCount) {
            0 -> stringResource(R.string.ripdpi_fake_approx_summary_profile_none)
            1 -> stringResource(R.string.ripdpi_fake_approx_summary_profile_single)
            else -> stringResource(R.string.ripdpi_fake_approx_summary_profile_multiple, uiState.fakeApproximationStepCount)
        }
    val scopeSummary =
        when {
            uiState.desyncHttpEnabled && uiState.desyncHttpsEnabled ->
                stringResource(R.string.ripdpi_fake_approx_summary_scope_http_https)
            uiState.desyncHttpEnabled -> stringResource(R.string.ripdpi_fake_approx_summary_scope_http)
            uiState.desyncHttpsEnabled -> stringResource(R.string.ripdpi_fake_approx_summary_scope_https)
            else -> stringResource(R.string.ripdpi_fake_approx_summary_scope_none)
        }
    val modeSummary =
        when (primaryStep?.kind) {
            TcpChainStepKind.FakeSplit -> stringResource(R.string.ripdpi_fake_approx_summary_mode_fakedsplit)
            TcpChainStepKind.FakeDisorder -> stringResource(R.string.ripdpi_fake_approx_summary_mode_fakeddisorder)
            else -> stringResource(R.string.ripdpi_fake_approx_summary_mode_none)
        }
    val markerSummary =
        primaryStep
            ?.marker
            ?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.ripdpi_fake_approx_summary_marker_none)
    val transportSummary =
        when (primaryStep?.kind) {
            TcpChainStepKind.FakeSplit -> stringResource(R.string.ripdpi_fake_approx_summary_transport_fakedsplit)
            TcpChainStepKind.FakeDisorder -> stringResource(R.string.ripdpi_fake_approx_summary_transport_fakeddisorder)
            else -> stringResource(R.string.ripdpi_fake_approx_summary_transport_none)
        }
    val badges =
        buildList {
            add(
                (
                    if (uiState.hasFakeApproximation) {
                        stringResource(R.string.ripdpi_fake_approx_badge_configured)
                    } else {
                        stringResource(R.string.ripdpi_fake_approx_badge_available)
                    }
                ) to
                    if (uiState.hasFakeApproximation) {
                        SummaryCapsuleTone.Active
                    } else {
                        SummaryCapsuleTone.Neutral
                    },
            )
            add(stringResource(R.string.ripdpi_fake_approx_badge_linux_android) to SummaryCapsuleTone.Info)
            if (uiState.desyncHttpEnabled) {
                add(stringResource(R.string.ripdpi_fake_approx_badge_http) to SummaryCapsuleTone.Info)
            }
            if (uiState.desyncHttpsEnabled) {
                add(stringResource(R.string.ripdpi_fake_approx_badge_https) to SummaryCapsuleTone.Info)
            }
            if (uiState.hasFakeSplitApproximation) {
                add(stringResource(R.string.ripdpi_fake_approx_badge_fakedsplit) to SummaryCapsuleTone.Active)
            }
            if (uiState.hasFakeDisorderApproximation) {
                add(stringResource(R.string.ripdpi_fake_approx_badge_fakeddisorder) to SummaryCapsuleTone.Warning)
            }
        }

    RipDpiCard(
        modifier = modifier,
        variant = RipDpiCardVariant.Tonal,
    ) {
        StatusIndicator(
            label = status.label,
            tone = status.tone,
        )
        Text(
            text = status.body,
            style = type.secondaryBody,
            color = colors.foreground,
        )
        SummaryCapsuleFlow(items = badges)
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_approx_summary_label_profile),
                value = profileSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_approx_summary_label_scope),
                value = scopeSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_approx_summary_label_mode),
                value = modeSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_approx_summary_label_marker),
                value = markerSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_approx_summary_label_shared),
                value = stringResource(R.string.ripdpi_fake_approx_summary_shared),
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_approx_summary_label_transport),
                value = transportSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_approx_summary_label_http_example),
                value = stringResource(R.string.ripdpi_fake_approx_example_http),
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_approx_summary_label_tls_example),
                value = stringResource(R.string.ripdpi_fake_approx_example_tls),
            )
        }
        Text(
            text = stringResource(R.string.ripdpi_fake_approx_scope_note),
            style = type.caption,
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun rememberFakeApproximationStatus(uiState: SettingsUiState): FakeApproximationStatusContent =
    when {
        uiState.enableCmdSettings ->
            FakeApproximationStatusContent(
                label = stringResource(R.string.ripdpi_fake_approx_cli_title),
                body = stringResource(R.string.ripdpi_fake_approx_cli_body),
                tone = StatusIndicatorTone.Warning,
            )

        !uiState.fakeApproximationControlsRelevant ->
            FakeApproximationStatusContent(
                label = stringResource(R.string.ripdpi_fake_approx_protocols_off_title),
                body = stringResource(R.string.ripdpi_fake_approx_protocols_off_body),
                tone = StatusIndicatorTone.Idle,
            )

        uiState.hasFakeApproximation && uiState.isServiceRunning ->
            FakeApproximationStatusContent(
                label = stringResource(R.string.ripdpi_fake_approx_restart_title),
                body = stringResource(R.string.ripdpi_fake_approx_restart_body),
                tone = StatusIndicatorTone.Warning,
            )

        uiState.hasFakeApproximation ->
            FakeApproximationStatusContent(
                label = stringResource(R.string.ripdpi_fake_approx_ready_title),
                body = stringResource(R.string.ripdpi_fake_approx_ready_body),
                tone = StatusIndicatorTone.Active,
            )

        else ->
            FakeApproximationStatusContent(
                label = stringResource(R.string.ripdpi_fake_approx_available_title),
                body = stringResource(R.string.ripdpi_fake_approx_available_body),
                tone = StatusIndicatorTone.Idle,
            )
    }

@Composable
private fun FakePayloadLibraryCard(
    uiState: SettingsUiState,
    onResetFakePayloadLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val status = rememberFakePayloadLibraryStatus(uiState)
    val scopeSummary =
        when {
            uiState.enableCmdSettings -> stringResource(R.string.fake_payload_library_scope_cli)
            !uiState.fakePayloadLibraryControlsRelevant -> stringResource(R.string.fake_payload_library_scope_protocols_disabled)
            uiState.httpFakeProfileActiveInStrategy && uiState.udpFakeProfileActiveInStrategy ->
                stringResource(R.string.fake_payload_library_scope_tcp_and_udp_live)
            uiState.httpFakeProfileActiveInStrategy || uiState.tlsFakeProfileActiveInStrategy ->
                stringResource(R.string.fake_payload_library_scope_tcp_live)
            uiState.udpFakeProfileActiveInStrategy -> stringResource(R.string.fake_payload_library_scope_udp_live)
            uiState.hasHostFake -> stringResource(R.string.fake_payload_library_scope_hostfake_only)
            else -> stringResource(R.string.fake_payload_library_scope_active)
        }
    val badges =
        buildList {
            if (uiState.hasCustomFakePayloadProfiles) {
                add(stringResource(R.string.fake_payload_library_badge_custom) to SummaryCapsuleTone.Active)
            } else {
                add(stringResource(R.string.fake_payload_library_badge_default) to SummaryCapsuleTone.Neutral)
            }
            if (uiState.isFake) {
                add(stringResource(R.string.fake_payload_library_badge_tcp_fake_live) to SummaryCapsuleTone.Active)
            } else if (uiState.hasHostFake) {
                add(stringResource(R.string.fake_payload_library_badge_hostfake_only) to SummaryCapsuleTone.Info)
            }
            if (uiState.hasUdpFakeBurst) {
                add(
                    stringResource(R.string.fake_payload_library_badge_udp_burst, uiState.udpFakeCount) to
                        SummaryCapsuleTone.Active,
                )
            }
            if (uiState.quicFakeProfileActive) {
                add(stringResource(R.string.fake_payload_library_badge_quic_separate) to SummaryCapsuleTone.Info)
            }
        }

    RipDpiCard(
        modifier = modifier,
        variant = RipDpiCardVariant.Elevated,
    ) {
        StatusIndicator(
            label = status.label,
            tone = status.tone,
        )
        Text(
            text = status.body,
            style = type.secondaryBody,
            color = colors.foreground,
        )
        SummaryCapsuleFlow(items = badges)
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            ProfileSummaryLine(
                label = stringResource(R.string.fake_payload_library_summary_label_http),
                value = formatHttpFakeProfileLabel(uiState.httpFakeProfile),
            )
            ProfileSummaryLine(
                label = stringResource(R.string.fake_payload_library_summary_label_tls),
                value = formatTlsFakeProfileLabel(uiState.tlsFakeProfile),
            )
            ProfileSummaryLine(
                label = stringResource(R.string.fake_payload_library_summary_label_udp),
                value = formatUdpFakeProfileLabel(uiState.udpFakeProfile),
            )
            ProfileSummaryLine(
                label = stringResource(R.string.fake_payload_library_summary_label_scope),
                value = scopeSummary,
            )
        }
        if (uiState.canResetFakePayloadLibrary) {
            RipDpiButton(
                text = stringResource(R.string.fake_payload_library_reset_action),
                onClick = onResetFakePayloadLibrary,
                variant = RipDpiButtonVariant.Outline,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun FakePayloadProfileCard(
    title: String,
    description: String,
    profileLabel: String,
    statusLabel: String,
    statusTone: StatusIndicatorTone,
    badges: List<Pair<String, SummaryCapsuleTone>>,
    appliesSummary: String,
    interactionSummary: String,
    value: String,
    options: List<RipDpiDropdownOption<String>>,
    setting: AdvancedOptionSetting,
    onSelected: (AdvancedOptionSetting, String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    RipDpiCard(
        modifier = modifier,
        variant = RipDpiCardVariant.Outlined,
    ) {
        StatusIndicator(
            label = statusLabel,
            tone = statusTone,
        )
        Text(
            text = title,
            style = type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text = description,
            style = type.secondaryBody,
            color = colors.mutedForeground,
        )
        SummaryCapsuleFlow(items = badges)
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            ProfileSummaryLine(
                label = stringResource(R.string.fake_payload_profile_summary_label_current),
                value = profileLabel,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.fake_payload_profile_summary_label_when_used),
                value = appliesSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.fake_payload_profile_summary_label_interaction),
                value = interactionSummary,
            )
        }
        RipDpiDropdown(
            options = options,
            selectedValue = value,
            onValueSelected = { selectedValue -> onSelected(setting, selectedValue) },
            enabled = enabled,
        )
    }
}

@Composable
private fun rememberFakePayloadLibraryStatus(uiState: SettingsUiState): FakePayloadLibraryStatusContent =
    when {
        uiState.enableCmdSettings ->
            FakePayloadLibraryStatusContent(
                label = stringResource(R.string.fake_payload_library_cli_title),
                body = stringResource(R.string.fake_payload_library_cli_body),
                tone = StatusIndicatorTone.Warning,
            )

        !uiState.fakePayloadLibraryControlsRelevant ->
            FakePayloadLibraryStatusContent(
                label = stringResource(R.string.fake_payload_library_protocols_disabled_title),
                body = stringResource(R.string.fake_payload_library_protocols_disabled_body),
                tone = StatusIndicatorTone.Idle,
            )

        uiState.isServiceRunning && uiState.hasCustomFakePayloadProfiles ->
            FakePayloadLibraryStatusContent(
                label = stringResource(R.string.fake_payload_library_restart_title),
                body = stringResource(R.string.fake_payload_library_restart_body),
                tone = StatusIndicatorTone.Warning,
            )

        uiState.hasCustomFakePayloadProfiles ->
            FakePayloadLibraryStatusContent(
                label = stringResource(R.string.fake_payload_library_custom_title),
                body = stringResource(R.string.fake_payload_library_custom_body),
                tone = StatusIndicatorTone.Active,
            )

        else ->
            FakePayloadLibraryStatusContent(
                label = stringResource(R.string.fake_payload_library_default_title),
                body = stringResource(R.string.fake_payload_library_default_body),
                tone = StatusIndicatorTone.Idle,
            )
    }

@Composable
private fun FakeTlsProfileCard(
    uiState: SettingsUiState,
    onResetFakeTlsProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val status = rememberFakeTlsStatus(uiState)
    val baseSummary =
        stringResource(
            if (uiState.fakeTlsUseOriginal) {
                R.string.ripdpi_fake_tls_summary_base_original
            } else {
                R.string.ripdpi_fake_tls_summary_base_default
            },
        )
    val sniSummary =
        if (uiState.fakeTlsSniMode == FakeTlsSniModeFixed) {
            stringResource(
                R.string.ripdpi_fake_tls_summary_sni_fixed,
                uiState.fakeSni.ifBlank { DefaultFakeSni },
            )
        } else {
            stringResource(R.string.ripdpi_fake_tls_summary_sni_randomized)
        }
    val mutationSummary =
        buildList {
            if (uiState.fakeTlsRandomize) add(stringResource(R.string.ripdpi_fake_tls_summary_mutation_randomize))
            if (uiState.fakeTlsDupSessionId) add(stringResource(R.string.ripdpi_fake_tls_summary_mutation_dup_sid))
            if (uiState.fakeTlsPadEncap) add(stringResource(R.string.ripdpi_fake_tls_summary_mutation_pad_encap))
        }.ifEmpty {
            listOf(stringResource(R.string.ripdpi_fake_tls_summary_mutation_none))
        }.joinToString(", ")
    val sizeSummary =
        when {
            uiState.fakeTlsSize > 0 -> stringResource(R.string.ripdpi_fake_tls_summary_size_exact, uiState.fakeTlsSize)
            uiState.fakeTlsSize < 0 -> stringResource(R.string.ripdpi_fake_tls_summary_size_minus, -uiState.fakeTlsSize)
            else -> stringResource(R.string.ripdpi_fake_tls_summary_size_input)
        }
    val scopeSummary =
        when {
            uiState.enableCmdSettings -> stringResource(R.string.ripdpi_fake_tls_scope_cli)
            !uiState.desyncHttpsEnabled -> stringResource(R.string.ripdpi_fake_tls_scope_https_disabled)
            !uiState.isFake -> stringResource(R.string.ripdpi_fake_tls_scope_needs_fake)
            uiState.isServiceRunning -> stringResource(R.string.ripdpi_fake_tls_scope_restart)
            else -> stringResource(R.string.ripdpi_fake_tls_scope_applies)
        }

    RipDpiCard(
        modifier = modifier,
        variant = RipDpiCardVariant.Tonal,
    ) {
        StatusIndicator(
            label = status.label,
            tone = status.tone,
        )
        Text(
            text = status.body,
            style = type.secondaryBody,
            color = colors.foreground,
        )
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_tls_summary_label_base),
                value = baseSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_tls_summary_label_sni),
                value = sniSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_tls_summary_label_mutations),
                value = mutationSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_tls_summary_label_size),
                value = sizeSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_fake_tls_summary_label_scope),
                value = scopeSummary,
            )
        }
        if (uiState.canResetFakeTlsProfile) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                RipDpiButton(
                    text = stringResource(R.string.ripdpi_fake_tls_reset_action),
                    onClick = onResetFakeTlsProfile,
                    variant = RipDpiButtonVariant.Outline,
                    trailingIcon = RipDpiIcons.Close,
                )
            }
            Text(
                text = stringResource(R.string.ripdpi_fake_tls_reset_hint),
                style = type.caption,
                color = colors.mutedForeground,
            )
        }
    }
}

@Composable
private fun rememberFakeTlsStatus(uiState: SettingsUiState): FakeTlsStatusContent =
    when {
        uiState.enableCmdSettings ->
            FakeTlsStatusContent(
                label = stringResource(R.string.ripdpi_fake_tls_cli_status_title),
                body = stringResource(R.string.ripdpi_fake_tls_cli_status_body),
                tone = StatusIndicatorTone.Warning,
            )

        !uiState.desyncHttpsEnabled ->
            FakeTlsStatusContent(
                label = stringResource(R.string.ripdpi_fake_tls_https_disabled_title),
                body = stringResource(R.string.ripdpi_fake_tls_https_disabled_body),
                tone = StatusIndicatorTone.Idle,
            )

        !uiState.isFake && uiState.hasCustomFakeTlsProfile ->
            FakeTlsStatusContent(
                label = stringResource(R.string.ripdpi_fake_tls_saved_title),
                body = stringResource(R.string.ripdpi_fake_tls_saved_body),
                tone = StatusIndicatorTone.Warning,
            )

        !uiState.isFake ->
            FakeTlsStatusContent(
                label = stringResource(R.string.ripdpi_fake_tls_waiting_title),
                body = stringResource(R.string.ripdpi_fake_tls_waiting_body),
                tone = StatusIndicatorTone.Idle,
            )

        uiState.hasCustomFakeTlsProfile ->
            FakeTlsStatusContent(
                label = stringResource(R.string.ripdpi_fake_tls_custom_title),
                body = stringResource(R.string.ripdpi_fake_tls_custom_body),
                tone = StatusIndicatorTone.Active,
            )

        else ->
            FakeTlsStatusContent(
                label = stringResource(R.string.ripdpi_fake_tls_default_title),
                body = stringResource(R.string.ripdpi_fake_tls_default_body),
                tone = StatusIndicatorTone.Active,
            )
    }
