package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.AdaptiveFakeTtlModeFixed
import com.poyka.ripdpi.data.FakeTlsSniModeFixed
import com.poyka.ripdpi.data.IpIdModeDefault
import com.poyka.ripdpi.data.isAdaptiveOffsetExpression
import com.poyka.ripdpi.data.isValidFakeOffsetExpression
import com.poyka.ripdpi.data.isValidOffsetExpression
import com.poyka.ripdpi.data.parseStrategyChainDsl
import com.poyka.ripdpi.data.validateStrategyChainUsage
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.state.SettingsUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.utility.validateIntRange
import kotlinx.collections.immutable.ImmutableList

private const val ttlMax = 255
private const val ttlMin = 1

// TLS record-layer minor-version byte (third byte of the record header).
private const val tlsMinorMax = 255
private const val tlsMinorMin = 0

internal data class DesyncSectionVisibility(
    val showHostFakeSection: Boolean,
    val showSeqOverlapSection: Boolean,
    val showFakeApproxSection: Boolean,
    val showFakeOrderingSection: Boolean,
    val showAdaptiveFakeTtlSection: Boolean,
    val showFakePayloadLibrary: Boolean,
    val showFakeTlsSection: Boolean,
)

internal data class DesyncSectionOptions(
    val adaptiveSplitPresetOptions: ImmutableList<AdaptiveSplitPresetUiModel>,
    val adaptiveFakeTtlModeOptions: ImmutableList<AdaptiveFakeTtlModeUiModel>,
    val httpFakeProfileOptions: ImmutableList<RipDpiDropdownOption<String>>,
    val fakeTlsBaseOptions: ImmutableList<RipDpiDropdownOption<String>>,
    val fakeTlsSniModeOptions: ImmutableList<RipDpiDropdownOption<String>>,
    val tlsFakeProfileOptions: ImmutableList<RipDpiDropdownOption<String>>,
    val fakeOrderOptions: ImmutableList<RipDpiDropdownOption<String>>,
    val fakeSeqModeOptions: ImmutableList<RipDpiDropdownOption<String>>,
    val ipIdModeOptions: ImmutableList<RipDpiDropdownOption<String>>,
    val udpFakeProfileOptions: ImmutableList<RipDpiDropdownOption<String>>,
)

internal data class DesyncSectionActions(
    val onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
    val onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
    val onOptionSelected: (AdvancedOptionSetting, String) -> Unit,
    val onResetAdaptiveSplit: () -> Unit,
    val onResetAdaptiveFakeTtlProfile: () -> Unit,
    val onResetFakePayloadLibrary: () -> Unit,
    val onResetFakeTlsProfile: () -> Unit,
)

internal data class DesyncSectionModel(
    val uiState: SettingsUiState,
    val visualEditorEnabled: Boolean,
    val visibility: DesyncSectionVisibility,
    val options: DesyncSectionOptions,
    val actions: DesyncSectionActions,
)

internal fun LazyListScope.desyncSection(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    visibility: DesyncSectionVisibility,
    options: DesyncSectionOptions,
    actions: DesyncSectionActions,
) {
    item(key = "advanced_desync") {
        val model =
            DesyncSectionModel(
                uiState = uiState,
                visualEditorEnabled = visualEditorEnabled,
                visibility = visibility,
                options = options,
                actions = actions,
            )

        AdvancedSettingsSection(
            title = stringResource(R.string.ripdpi_desync),
            testTag = RipDpiTestTags.advancedSection("bypass_strategy"),
        ) {
            DesyncSettingsCard(model)
        }
    }
}

@Composable
private fun DesyncSettingsCard(model: DesyncSectionModel) {
    RipDpiCard {
        DesyncCoreControls(model)
        DesyncChainProfileControls(model)
        if (model.visibility.showAdaptiveFakeTtlSection) {
            AdaptiveFakeTtlControls(model)
        }
        if (model.visibility.showFakePayloadLibrary) {
            FakePayloadLibraryControls(model)
        }
        if (model.visibility.showFakeTlsSection) {
            FakeTlsControls(model)
        }
        DesyncPacketTailControls(model)
    }
}

@Composable
private fun DesyncCoreControls(model: DesyncSectionModel) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val uiState = model.uiState
    AdvancedTextSetting(
        title = stringResource(R.string.ripdpi_default_ttl_setting),
        description = stringResource(R.string.config_default_ttl_helper),
        value = uiState.defaultTtlValue,
        placeholder = stringResource(R.string.config_placeholder_default_ttl),
        enabled = model.visualEditorEnabled,
        validator = { it.isEmpty() || validateIntRange(it, 0, ttlMax) },
        invalidMessage = stringResource(R.string.config_error_out_of_range),
        disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        setting = AdvancedTextSetting.DefaultTtl,
        onConfirm = model.actions.onTextConfirmed,
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
        onResetAdaptiveSplit = model.actions.onResetAdaptiveSplit,
        modifier = Modifier.padding(top = spacing.xs, bottom = spacing.sm),
    )
    HorizontalDivider(color = colors.divider)
    AdaptiveSplitPresetSelector(
        uiState = uiState,
        presets = model.options.adaptiveSplitPresetOptions,
        enabled = model.visualEditorEnabled && uiState.desync.adaptiveSplitVisualEditorSupported,
        onPresetSelected = { model.actions.onOptionSelected(AdvancedOptionSetting.AdaptiveSplitPreset, it) },
    )
    SplitMarkerSetting(model)
}

@Composable
private fun SplitMarkerSetting(model: DesyncSectionModel) {
    val colors = RipDpiThemeTokens.colors
    val uiState = model.uiState
    HorizontalDivider(color = colors.divider)
    if (uiState.desync.hasAdaptiveSplitPreset) return
    AdvancedTextSetting(
        title = stringResource(R.string.ripdpi_split_marker_setting),
        description = stringResource(R.string.config_split_marker_helper),
        value = uiState.desync.splitMarker,
        placeholder = stringResource(R.string.config_placeholder_split_marker),
        enabled = model.visualEditorEnabled && uiState.desync.adaptiveSplitVisualEditorSupported,
        validator = { it.isBlank() || (isValidOffsetExpression(it) && !isAdaptiveOffsetExpression(it)) },
        invalidMessage = stringResource(R.string.config_error_invalid_marker),
        disabledMessage =
            if (uiState.desync.adaptiveSplitVisualEditorSupported) {
                stringResource(R.string.advanced_settings_visual_controls_disabled)
            } else {
                stringResource(R.string.adaptive_split_hostfake_disabled)
            },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        setting = AdvancedTextSetting.SplitMarker,
        onConfirm = model.actions.onTextConfirmed,
        showDivider = true,
    )
}

@Composable
private fun DesyncChainProfileControls(model: DesyncSectionModel) {
    val uiState = model.uiState
    ChainEditorWithCollapsibleHelp(
        uiState = uiState,
        visualEditorEnabled = model.visualEditorEnabled,
        onTextConfirmed = model.actions.onTextConfirmed,
        showDivider =
            model.visibility.showHostFakeSection ||
                model.visibility.showSeqOverlapSection ||
                uiState.desync.primaryTcpFlagStep != null ||
                model.showFakeOrOobSections,
    )
    TcpFlagControls(model)
    FakeOrderingControls(model)
    HostFakeControls(model)
    SeqOverlapControls(model)
    FakeApproximationControls(model)
}

@Composable
private fun TcpFlagControls(model: DesyncSectionModel) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val uiState = model.uiState
    if (uiState.desync.primaryTcpFlagStep == null) return
    TcpFlagProfileCard(
        uiState = uiState,
        visualEditorEnabled = model.visualEditorEnabled,
        onOptionSelected = model.actions.onOptionSelected,
        modifier = Modifier.padding(top = spacing.xs, bottom = spacing.sm),
    )
    if (model.visibility.showHostFakeSection || model.visibility.showSeqOverlapSection || model.showFakeOrOobSections) {
        HorizontalDivider(color = colors.divider)
    }
}

@Composable
private fun FakeOrderingControls(model: DesyncSectionModel) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val uiState = model.uiState
    if (!model.visibility.showFakeOrderingSection || uiState.desync.primaryFakeOrderingStep == null) return
    FakeOrderingProfileCard(
        uiState = uiState,
        visualEditorEnabled = model.visualEditorEnabled,
        fakeOrderOptions = model.options.fakeOrderOptions,
        fakeSeqModeOptions = model.options.fakeSeqModeOptions,
        onOptionSelected = model.actions.onOptionSelected,
        modifier = Modifier.padding(top = spacing.xs, bottom = spacing.sm),
    )
    if (model.visibility.showHostFakeSection || model.visibility.showSeqOverlapSection || model.showFakeOrOobSections) {
        HorizontalDivider(color = colors.divider)
    }
}

@Composable
private fun HostFakeControls(model: DesyncSectionModel) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    if (!model.visibility.showHostFakeSection) return
    HostFakeProfileCard(
        uiState = model.uiState,
        modifier = Modifier.padding(top = spacing.xs, bottom = spacing.sm),
    )
    if (model.showFakeOrOobSections) {
        HorizontalDivider(color = colors.divider)
    }
}

@Composable
private fun SeqOverlapControls(model: DesyncSectionModel) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    if (!model.visibility.showSeqOverlapSection) return
    SeqOverlapProfileCard(
        uiState = model.uiState,
        modifier = Modifier.padding(top = spacing.xs, bottom = spacing.sm),
    )
    if (model.showFakeOrOobSections) {
        HorizontalDivider(color = colors.divider)
    }
}

@Composable
private fun FakeApproximationControls(model: DesyncSectionModel) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    if (!model.visibility.showFakeApproxSection) return
    FakeApproximationProfileCard(
        uiState = model.uiState,
        modifier = Modifier.padding(top = spacing.xs, bottom = spacing.sm),
    )
    if (model.visibility.showAdaptiveFakeTtlSection || model.visibility.showFakeTlsSection || model.uiState.isOob) {
        HorizontalDivider(color = colors.divider)
    }
}

@Composable
private fun AdaptiveFakeTtlControls(model: DesyncSectionModel) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val uiState = model.uiState
    AdaptiveFakeTtlProfileCard(
        uiState = uiState,
        onResetAdaptiveFakeTtlProfile = model.actions.onResetAdaptiveFakeTtlProfile,
        modifier = Modifier.padding(top = spacing.xs, bottom = spacing.sm),
    )
    HorizontalDivider(color = colors.divider)
    AdaptiveFakeTtlModeSelector(
        uiState = uiState,
        presets = model.options.adaptiveFakeTtlModeOptions,
        enabled = model.visualEditorEnabled,
        onModeSelected = { model.actions.onOptionSelected(AdvancedOptionSetting.AdaptiveFakeTtlMode, it) },
    )
    HorizontalDivider(color = colors.divider)
    AdaptiveFakeTtlValueSettings(model)
    if (uiState.isFake) {
        FakeOffsetMarkerSetting(model)
    }
}

@Composable
private fun AdaptiveFakeTtlValueSettings(model: DesyncSectionModel) {
    if (model.uiState.fake.adaptiveFakeTtlMode == AdaptiveFakeTtlModeFixed) {
        FixedFakeTtlSetting(model)
    } else {
        AdaptiveFakeTtlRangeSettings(model)
    }
}

@Composable
private fun FixedFakeTtlSetting(model: DesyncSectionModel) {
    AdvancedTextSetting(
        title = stringResource(R.string.ripdpi_fake_ttl_setting),
        description = stringResource(R.string.adaptive_fake_ttl_fixed_body),
        value =
            model.uiState.fake.fakeTtl
                .toString(),
        enabled = model.visualEditorEnabled,
        validator = { validateIntRange(it, ttlMin, ttlMax) },
        invalidMessage = stringResource(R.string.config_error_out_of_range),
        disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        setting = AdvancedTextSetting.FakeTtl,
        onConfirm = model.actions.onTextConfirmed,
        showDivider = model.uiState.isFake || model.visibility.showFakeTlsSection || model.uiState.isOob,
    )
}

@Composable
private fun AdaptiveFakeTtlRangeSettings(model: DesyncSectionModel) {
    val uiState = model.uiState
    AdvancedTextSetting(
        title = stringResource(R.string.adaptive_fake_ttl_min_title),
        description = stringResource(R.string.adaptive_fake_ttl_min_body),
        value = uiState.fake.adaptiveFakeTtlMin.toString(),
        enabled = model.visualEditorEnabled,
        validator = { validateIntRange(it, ttlMin, ttlMax) },
        invalidMessage = stringResource(R.string.config_error_out_of_range),
        disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        setting = AdvancedTextSetting.AdaptiveFakeTtlMin,
        onConfirm = model.actions.onTextConfirmed,
        showDivider = true,
    )
    AdvancedTextSetting(
        title = stringResource(R.string.adaptive_fake_ttl_max_title),
        description = stringResource(R.string.adaptive_fake_ttl_max_body),
        value = uiState.fake.adaptiveFakeTtlMax.toString(),
        enabled = model.visualEditorEnabled,
        validator = { validateIntRange(it, uiState.fake.adaptiveFakeTtlMin.coerceIn(ttlMin, ttlMax), ttlMax) },
        invalidMessage = stringResource(R.string.config_error_out_of_range),
        disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        setting = AdvancedTextSetting.AdaptiveFakeTtlMax,
        onConfirm = model.actions.onTextConfirmed,
        showDivider = true,
    )
    AdvancedTextSetting(
        title = stringResource(R.string.adaptive_fake_ttl_fallback_title),
        description = stringResource(R.string.adaptive_fake_ttl_fallback_body),
        value = uiState.fake.adaptiveFakeTtlFallback.toString(),
        enabled = model.visualEditorEnabled,
        validator = { validateIntRange(it, ttlMin, ttlMax) },
        invalidMessage = stringResource(R.string.config_error_out_of_range),
        disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        setting = AdvancedTextSetting.AdaptiveFakeTtlFallback,
        onConfirm = model.actions.onTextConfirmed,
        showDivider = uiState.isFake || model.visibility.showFakeTlsSection || uiState.isOob,
    )
}

@Composable
private fun FakeOffsetMarkerSetting(model: DesyncSectionModel) {
    AdvancedTextSetting(
        title = stringResource(R.string.ripdpi_fake_offset_setting),
        description = stringResource(R.string.config_fake_offset_marker_helper),
        value = model.uiState.fake.fakeOffsetMarker,
        placeholder = stringResource(R.string.config_placeholder_fake_offset_marker),
        enabled = model.visualEditorEnabled,
        validator = { it.isBlank() || isValidFakeOffsetExpression(it) },
        invalidMessage = stringResource(R.string.config_error_invalid_marker),
        disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        setting = AdvancedTextSetting.FakeOffsetMarker,
        onConfirm = model.actions.onTextConfirmed,
        showDivider = true,
    )
}

@Composable
private fun FakeTlsControls(model: DesyncSectionModel) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val uiState = model.uiState
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
        onResetFakeTlsProfile = model.actions.onResetFakeTlsProfile,
        modifier = Modifier.padding(top = spacing.xs, bottom = spacing.sm),
    )
    HorizontalDivider(color = colors.divider)
    FakeTlsDropdowns(model)
    FakeTlsToggles(model)
    FakeTlsSizeSetting(model)
}

@Composable
private fun FakeTlsDropdowns(model: DesyncSectionModel) {
    val uiState = model.uiState
    AdvancedDropdownSetting(
        title = stringResource(R.string.ripdpi_fake_tls_base_title),
        description = stringResource(R.string.ripdpi_fake_tls_base_body),
        value = if (uiState.fake.fakeTlsUseOriginal) "original" else "default",
        options = model.options.fakeTlsBaseOptions,
        setting = AdvancedOptionSetting.FakeTlsBase,
        onSelected = model.actions.onOptionSelected,
        enabled = model.visualEditorEnabled,
        showDivider = true,
    )
    AdvancedDropdownSetting(
        title = stringResource(R.string.ripdpi_fake_tls_sni_mode_title),
        description = stringResource(R.string.ripdpi_fake_tls_sni_mode_body),
        value = uiState.fake.fakeTlsSniMode,
        options = model.options.fakeTlsSniModeOptions,
        setting = AdvancedOptionSetting.FakeTlsSniMode,
        onSelected = model.actions.onOptionSelected,
        enabled = model.visualEditorEnabled,
        showDivider = uiState.fake.fakeTlsSniMode == FakeTlsSniModeFixed,
    )
    if (uiState.fake.fakeTlsSniMode == FakeTlsSniModeFixed) {
        AdvancedTextSetting(
            title = stringResource(R.string.sni_of_fake_packet),
            value = uiState.fake.fakeSni,
            enabled = model.visualEditorEnabled,
            disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
            setting = AdvancedTextSetting.FakeSni,
            onConfirm = model.actions.onTextConfirmed,
            showDivider = true,
        )
    }
}

@Composable
private fun FakeTlsToggles(model: DesyncSectionModel) {
    FakeTlsToggle(
        title = stringResource(R.string.ripdpi_fake_tls_randomize_title),
        subtitle = stringResource(R.string.ripdpi_fake_tls_randomize_body),
        checked = model.uiState.fake.fakeTlsRandomize,
        setting = AdvancedToggleSetting.FakeTlsRandomize,
        model = model,
    )
    FakeTlsToggle(
        title = stringResource(R.string.ripdpi_fake_tls_dup_sid_title),
        subtitle = stringResource(R.string.ripdpi_fake_tls_dup_sid_body),
        checked = model.uiState.fake.fakeTlsDupSessionId,
        setting = AdvancedToggleSetting.FakeTlsDupSessionId,
        model = model,
    )
    FakeTlsToggle(
        title = stringResource(R.string.ripdpi_fake_tls_pad_encap_title),
        subtitle = stringResource(R.string.ripdpi_fake_tls_pad_encap_body),
        checked = model.uiState.fake.fakeTlsPadEncap,
        setting = AdvancedToggleSetting.FakeTlsPadEncap,
        model = model,
    )
}

@Composable
private fun FakeTlsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    setting: AdvancedToggleSetting,
    model: DesyncSectionModel,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        checked = checked,
        onCheckedChange = { model.actions.onToggleChanged(setting, it) },
        enabled = model.visualEditorEnabled,
        showDivider = true,
        testTag = RipDpiTestTags.advancedToggle(setting),
    )
}

@Composable
private fun FakeTlsSizeSetting(model: DesyncSectionModel) {
    AdvancedTextSetting(
        title = stringResource(R.string.ripdpi_fake_tls_size_title),
        description = stringResource(R.string.config_fake_tls_size_helper),
        value =
            model.uiState.fake.fakeTlsSize
                .toString(),
        placeholder = stringResource(R.string.config_placeholder_fake_tls_size),
        enabled = model.visualEditorEnabled,
        validator = { it.isEmpty() || it.toIntOrNull() != null },
        invalidMessage = stringResource(R.string.config_error_out_of_range),
        disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        setting = AdvancedTextSetting.FakeTlsSize,
        onConfirm = model.actions.onTextConfirmed,
        showDivider = true,
    )
}

@Composable
private fun DesyncPacketTailControls(model: DesyncSectionModel) {
    if (model.uiState.isOob) {
        OobDataSetting(model)
    }
    SettingsRow(
        title = stringResource(R.string.ripdpi_drop_sack_setting),
        checked = model.uiState.fake.dropSack,
        onCheckedChange = { model.actions.onToggleChanged(AdvancedToggleSetting.DropSack, it) },
        enabled = model.visualEditorEnabled,
        showDivider = true,
        testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.DropSack),
    )
    if (model.uiState.settings.rootModeEnabled) {
        SettingsRow(
            title = stringResource(R.string.ripdpi_md5sig_setting),
            checked = model.uiState.fake.md5sig,
            onCheckedChange = { model.actions.onToggleChanged(AdvancedToggleSetting.Md5Sig, it) },
            enabled = model.visualEditorEnabled,
            showDivider = true,
            testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.Md5Sig),
        )
    }
    SettingsRow(
        title = stringResource(R.string.ripdpi_tlsminor_setting),
        checked = model.uiState.fake.tlsMinorEnabled,
        onCheckedChange = { model.actions.onToggleChanged(AdvancedToggleSetting.TlsMinor, it) },
        enabled = model.visualEditorEnabled,
        showDivider = true,
        testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.TlsMinor),
    )
    if (model.uiState.fake.tlsMinorEnabled) {
        AdvancedTextSetting(
            title = stringResource(R.string.ripdpi_tlsminor_value_title),
            description = stringResource(R.string.ripdpi_tlsminor_value_body),
            value =
                model.uiState.fake.tlsMinorValue
                    .toString(),
            enabled = model.visualEditorEnabled,
            validator = { validateIntRange(it, tlsMinorMin, tlsMinorMax) },
            invalidMessage = stringResource(R.string.config_error_out_of_range),
            disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            setting = AdvancedTextSetting.TlsMinorValue,
            onConfirm = model.actions.onTextConfirmed,
            showDivider = true,
        )
    }
    AdvancedDropdownSetting(
        title = stringResource(R.string.ripdpi_ip_id_mode_title),
        description = stringResource(R.string.ripdpi_ip_id_mode_body),
        value =
            model.uiState.fake.ipIdMode
                .ifBlank { IpIdModeDefault },
        options = model.options.ipIdModeOptions,
        setting = AdvancedOptionSetting.IpIdMode,
        onSelected = model.actions.onOptionSelected,
        enabled = model.visualEditorEnabled,
    )
}

@Composable
private fun OobDataSetting(model: DesyncSectionModel) {
    AdvancedTextSetting(
        title = stringResource(R.string.oob_data),
        value = model.uiState.fake.oobData,
        enabled = model.visualEditorEnabled,
        validator = { it.length <= 1 },
        invalidMessage = stringResource(R.string.advanced_settings_error_oob_data),
        disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        setting = AdvancedTextSetting.OobData,
        onConfirm = model.actions.onTextConfirmed,
        showDivider = true,
    )
}

@Composable
private fun ChainEditorWithCollapsibleHelp(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
    showDivider: Boolean,
) {
    AdvancedTextSetting(
        title = stringResource(R.string.config_chain_editor_label),
        descriptionContent = { ChainEditorCollapsibleDescription() },
        value = uiState.desync.chainDsl,
        placeholder = stringResource(R.string.config_placeholder_chain_dsl),
        enabled = visualEditorEnabled,
        multiline = true,
        validator = { value ->
            parseStrategyChainDsl(value)
                .map { chain ->
                    validateStrategyChainUsage(
                        tcpSteps = chain.tcpSteps,
                        udpSteps = chain.udpSteps,
                        mode = uiState.selectedMode,
                        useCommandLineSettings = uiState.enableCmdSettings,
                    )
                }.isSuccess
        },
        invalidMessage = stringResource(R.string.config_error_invalid_chain),
        disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        setting = AdvancedTextSetting.ChainDsl,
        onConfirm = onTextConfirmed,
        showDivider = showDivider,
    )
}

@Composable
private fun ChainEditorCollapsibleDescription() {
    val colors = RipDpiThemeTokens.colors
    val motion = RipDpiThemeTokens.motion
    val spacing = RipDpiThemeTokens.spacing
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column {
        Text(
            text = stringResource(R.string.config_chain_editor_helper_brief),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.mutedForeground,
        )
        AnimatedVisibility(
            visible = expanded,
            enter = motion.sectionEnterTransition(),
            exit = motion.sectionExitTransition(),
        ) {
            Text(
                text = stringResource(R.string.config_chain_editor_helper_full),
                style = RipDpiThemeTokens.type.secondaryBody,
                color = colors.mutedForeground,
                modifier = Modifier.padding(top = spacing.xs),
            )
        }
        Text(
            text =
                if (expanded) {
                    stringResource(R.string.action_show_less)
                } else {
                    stringResource(R.string.action_learn_more)
                },
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.info,
            modifier =
                Modifier
                    .ripDpiClickable(role = Role.Button, onClick = { expanded = !expanded })
                    .padding(vertical = spacing.xs),
        )
    }
}

private val DesyncSectionModel.showFakeOrOobSections: Boolean
    get() =
        visibility.showFakeOrderingSection ||
            visibility.showFakeApproxSection ||
            visibility.showAdaptiveFakeTtlSection ||
            visibility.showFakeTlsSection ||
            uiState.isOob

private val SettingsUiState.defaultTtlValue: String
    get() = if (desync.customTtl) desync.defaultTtl.toString() else ""
