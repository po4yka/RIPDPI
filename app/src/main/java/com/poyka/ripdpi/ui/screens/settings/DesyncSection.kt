package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.AdaptiveFakeTtlModeFixed
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.data.FakeTlsSniModeFixed
import com.poyka.ripdpi.data.isAdaptiveOffsetExpression
import com.poyka.ripdpi.data.isValidOffsetExpression
import com.poyka.ripdpi.data.parseStrategyChainDsl
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
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
                    text = stringResource(R.string.config_chain_summary_label, uiState.desync.chainSummary),
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
                    enabled = visualEditorEnabled && uiState.desync.adaptiveSplitVisualEditorSupported,
                    onPresetSelected = { onOptionSelected(AdvancedOptionSetting.AdaptiveSplitPreset, it) },
                )
                if (!uiState.desync.hasAdaptiveSplitPreset) {
                    HorizontalDivider(color = colors.divider)
                    AdvancedTextSetting(
                        title = stringResource(R.string.ripdpi_split_marker_setting),
                        description = stringResource(R.string.config_split_marker_helper),
                        value = uiState.desync.splitMarker,
                        placeholder = stringResource(R.string.config_placeholder_split_marker),
                        enabled = visualEditorEnabled && uiState.desync.adaptiveSplitVisualEditorSupported,
                        validator = {
                            it.isBlank() || (
                                isValidOffsetExpression(
                                    it,
                                ) && !isAdaptiveOffsetExpression(it)
                            )
                        },
                        invalidMessage = stringResource(R.string.config_error_invalid_marker),
                        disabledMessage =
                            if (uiState.desync.adaptiveSplitVisualEditorSupported) {
                                stringResource(R.string.advanced_settings_visual_controls_disabled)
                            } else {
                                stringResource(R.string.adaptive_split_hostfake_disabled)
                            },
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done,
                            ),
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
                    value = uiState.desync.chainDsl,
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
                    if (uiState.fake.adaptiveFakeTtlMode == AdaptiveFakeTtlModeFixed) {
                        AdvancedTextSetting(
                            title = stringResource(R.string.ripdpi_fake_ttl_setting),
                            description = stringResource(R.string.adaptive_fake_ttl_fixed_body),
                            value = uiState.fake.fakeTtl.toString(),
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
                            value = uiState.fake.adaptiveFakeTtlMin.toString(),
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
                            value = uiState.fake.adaptiveFakeTtlMax.toString(),
                            enabled = visualEditorEnabled,
                            validator = { validateIntRange(it, uiState.fake.adaptiveFakeTtlMin.coerceIn(1, 255), 255) },
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
                            value = uiState.fake.adaptiveFakeTtlFallback.toString(),
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
                            value = uiState.fake.fakeOffsetMarker,
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
                        profileLabel = formatHttpFakeProfileLabel(uiState.fake.httpFakeProfile),
                        statusLabel =
                            when {
                                uiState.enableCmdSettings -> {
                                    stringResource(R.string.fake_payload_library_cli_title)
                                }

                                !uiState.desyncHttpEnabled -> {
                                    stringResource(R.string.fake_payload_profile_status_off)
                                }

                                uiState.httpFakeProfileActiveInStrategy -> {
                                    stringResource(R.string.fake_payload_profile_status_live)
                                }

                                uiState.hasHostFake -> {
                                    stringResource(R.string.fake_payload_profile_status_separate)
                                }

                                else -> {
                                    stringResource(R.string.fake_payload_profile_status_ready)
                                }
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
                                !uiState.desyncHttpEnabled -> {
                                    stringResource(R.string.http_fake_profile_scope_off)
                                }

                                uiState.httpFakeProfileActiveInStrategy -> {
                                    stringResource(R.string.http_fake_profile_scope_live)
                                }

                                uiState.hasHostFake -> {
                                    stringResource(R.string.http_fake_profile_scope_hostfake)
                                }

                                else -> {
                                    stringResource(R.string.http_fake_profile_scope_ready)
                                }
                            },
                        interactionSummary = stringResource(R.string.http_fake_profile_interaction),
                        value = uiState.fake.httpFakeProfile,
                        options = httpFakeProfileOptions,
                        setting = AdvancedOptionSetting.HttpFakeProfile,
                        onSelected = onOptionSelected,
                        enabled = visualEditorEnabled,
                        modifier = Modifier.padding(bottom = spacing.sm),
                    )
                    FakePayloadProfileCard(
                        title = stringResource(R.string.tls_fake_profile_title),
                        description = stringResource(R.string.tls_fake_profile_body),
                        profileLabel = formatTlsFakeProfileLabel(uiState.fake.tlsFakeProfile),
                        statusLabel =
                            when {
                                uiState.enableCmdSettings -> {
                                    stringResource(R.string.fake_payload_library_cli_title)
                                }

                                !uiState.desyncHttpsEnabled -> {
                                    stringResource(R.string.fake_payload_profile_status_off)
                                }

                                uiState.tlsFakeProfileActiveInStrategy -> {
                                    stringResource(R.string.fake_payload_profile_status_live)
                                }

                                uiState.hasHostFake -> {
                                    stringResource(R.string.fake_payload_profile_status_separate)
                                }

                                else -> {
                                    stringResource(R.string.fake_payload_profile_status_ready)
                                }
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
                                    stringResource(R.string.fake_payload_badge_fake_tls_layers) to
                                        SummaryCapsuleTone.Info,
                                )
                            },
                        appliesSummary =
                            when {
                                !uiState.desyncHttpsEnabled -> {
                                    stringResource(R.string.tls_fake_profile_scope_off)
                                }

                                uiState.tlsFakeProfileActiveInStrategy -> {
                                    stringResource(R.string.tls_fake_profile_scope_live)
                                }

                                uiState.hasHostFake -> {
                                    stringResource(R.string.tls_fake_profile_scope_hostfake)
                                }

                                else -> {
                                    stringResource(R.string.tls_fake_profile_scope_ready)
                                }
                            },
                        interactionSummary = stringResource(R.string.tls_fake_profile_interaction),
                        value = uiState.fake.tlsFakeProfile,
                        options = tlsFakeProfileOptions,
                        setting = AdvancedOptionSetting.TlsFakeProfile,
                        onSelected = onOptionSelected,
                        enabled = visualEditorEnabled,
                        modifier = Modifier.padding(bottom = spacing.sm),
                    )
                    FakePayloadProfileCard(
                        title = stringResource(R.string.udp_fake_profile_title),
                        description = stringResource(R.string.udp_fake_profile_body),
                        profileLabel = formatUdpFakeProfileLabel(uiState.fake.udpFakeProfile),
                        statusLabel =
                            when {
                                uiState.enableCmdSettings -> {
                                    stringResource(R.string.fake_payload_library_cli_title)
                                }

                                !uiState.desyncUdpEnabled -> {
                                    stringResource(R.string.fake_payload_profile_status_off)
                                }

                                uiState.udpFakeProfileActiveInStrategy -> {
                                    stringResource(R.string.fake_payload_profile_status_live)
                                }

                                else -> {
                                    stringResource(R.string.fake_payload_profile_status_ready)
                                }
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
                                                uiState.desync.udpFakeCount,
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
                                if (uiState.quic.quicFakeProfileActive) {
                                    add(
                                        stringResource(R.string.fake_payload_badge_quic_separate) to
                                            SummaryCapsuleTone.Info,
                                    )
                                }
                            },
                        appliesSummary =
                            when {
                                !uiState.desyncUdpEnabled -> {
                                    stringResource(R.string.udp_fake_profile_scope_off)
                                }

                                uiState.udpFakeProfileActiveInStrategy -> {
                                    stringResource(
                                        R.string.udp_fake_profile_scope_live,
                                        uiState.desync.udpFakeCount,
                                    )
                                }

                                else -> {
                                    stringResource(R.string.udp_fake_profile_scope_ready)
                                }
                            },
                        interactionSummary =
                            if (uiState.quic.quicFakeProfileActive) {
                                stringResource(R.string.udp_fake_profile_interaction_quic_override)
                            } else {
                                stringResource(R.string.udp_fake_profile_interaction)
                            },
                        value = uiState.fake.udpFakeProfile,
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
                        value = if (uiState.fake.fakeTlsUseOriginal) "original" else "default",
                        options = fakeTlsBaseOptions,
                        setting = AdvancedOptionSetting.FakeTlsBase,
                        onSelected = onOptionSelected,
                        enabled = visualEditorEnabled,
                        showDivider = true,
                    )
                    AdvancedDropdownSetting(
                        title = stringResource(R.string.ripdpi_fake_tls_sni_mode_title),
                        description = stringResource(R.string.ripdpi_fake_tls_sni_mode_body),
                        value = uiState.fake.fakeTlsSniMode,
                        options = fakeTlsSniModeOptions,
                        setting = AdvancedOptionSetting.FakeTlsSniMode,
                        onSelected = onOptionSelected,
                        enabled = visualEditorEnabled,
                        showDivider = uiState.fake.fakeTlsSniMode == FakeTlsSniModeFixed,
                    )
                    if (uiState.fake.fakeTlsSniMode == FakeTlsSniModeFixed) {
                        AdvancedTextSetting(
                            title = stringResource(R.string.sni_of_fake_packet),
                            value = uiState.fake.fakeSni,
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
                        checked = uiState.fake.fakeTlsRandomize,
                        onCheckedChange = { onToggleChanged(AdvancedToggleSetting.FakeTlsRandomize, it) },
                        enabled = visualEditorEnabled,
                        showDivider = true,
                    )
                    SettingsRow(
                        title = stringResource(R.string.ripdpi_fake_tls_dup_sid_title),
                        subtitle = stringResource(R.string.ripdpi_fake_tls_dup_sid_body),
                        checked = uiState.fake.fakeTlsDupSessionId,
                        onCheckedChange = { onToggleChanged(AdvancedToggleSetting.FakeTlsDupSessionId, it) },
                        enabled = visualEditorEnabled,
                        showDivider = true,
                    )
                    SettingsRow(
                        title = stringResource(R.string.ripdpi_fake_tls_pad_encap_title),
                        subtitle = stringResource(R.string.ripdpi_fake_tls_pad_encap_body),
                        checked = uiState.fake.fakeTlsPadEncap,
                        onCheckedChange = { onToggleChanged(AdvancedToggleSetting.FakeTlsPadEncap, it) },
                        enabled = visualEditorEnabled,
                        showDivider = true,
                    )
                    AdvancedTextSetting(
                        title = stringResource(R.string.ripdpi_fake_tls_size_title),
                        description = stringResource(R.string.config_fake_tls_size_helper),
                        value = uiState.fake.fakeTlsSize.toString(),
                        placeholder = stringResource(R.string.config_placeholder_fake_tls_size),
                        enabled = visualEditorEnabled,
                        validator = { it.isEmpty() || it.toIntOrNull() != null },
                        invalidMessage = stringResource(R.string.config_error_out_of_range),
                        disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done,
                            ),
                        setting = AdvancedTextSetting.FakeTlsSize,
                        onConfirm = onTextConfirmed,
                        showDivider = true,
                    )
                }
                if (uiState.isOob) {
                    AdvancedTextSetting(
                        title = stringResource(R.string.oob_data),
                        value = uiState.fake.oobData,
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
                    checked = uiState.fake.dropSack,
                    onCheckedChange = { onToggleChanged(AdvancedToggleSetting.DropSack, it) },
                    enabled = visualEditorEnabled,
                )
            }
        }
    }
}

private val SettingsUiState.defaultTtlValue: String
    get() = if (desync.customTtl) desync.defaultTtl.toString() else ""
