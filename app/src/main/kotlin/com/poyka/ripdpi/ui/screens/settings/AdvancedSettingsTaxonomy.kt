package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.data.HostPackApplyModeMerge
import com.poyka.ripdpi.data.HostPackPreset
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone

internal const val TlsPreludeModeDisabled = "disabled"
internal const val HostPackApplyDialogDefaultMode = HostPackApplyModeMerge

internal data class AdaptiveSplitPresetUiModel(
    val value: String,
    val title: String,
    val body: String,
    val isRecommended: Boolean = false,
)

internal data class AdaptiveFakeTtlModeUiModel(
    val value: String,
    val title: String,
    val body: String,
    val badgeLabel: String? = null,
    val badgeTone: StatusIndicatorTone = StatusIndicatorTone.Active,
)

internal data class AdvancedNotice(
    val title: String,
    val message: String,
    val tone: WarningBannerTone,
)

internal data class AdvancedSettingsActions(
    val onBack: () -> Unit,
    val onOpenStrategyConfig: () -> Unit,
    val onOpenBlockcheck: () -> Unit,
    val onOpenAssetProvider: () -> Unit,
    val onOpenRememberedNetworks: () -> Unit,
    val onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
    val onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
    val onOptionSelected: (AdvancedOptionSetting, String) -> Unit,
    val onApplyHostPackPreset: (HostPackPreset, String, String) -> Unit,
    val onRefreshHostPackCatalog: () -> Unit,
    val onRefreshStrategyPackCatalog: () -> Unit,
    val onForgetLearnedHosts: () -> Unit,
    val onClearRememberedNetworks: () -> Unit,
    val onWsTunnelModeChanged: (String) -> Unit,
    val onSaveWsTunnelWorkerTransport: (String, String, String) -> Unit,
    val onClearWsTunnelWorkerTransport: () -> Unit,
    val onRotateSalt: () -> Unit,
    val onSaveActivationRange: (ActivationWindowDimension, Long?, Long?) -> Unit,
    val onResetAdaptiveSplit: () -> Unit,
    val onResetAdaptiveFakeTtlProfile: () -> Unit,
    val onResetActivationWindow: () -> Unit,
    val onResetHttpParserEvasions: () -> Unit,
    val onResetFakePayloadLibrary: () -> Unit,
    val onResetFakeTlsProfile: () -> Unit,
    val onRoutingPolicyModeSelected: (String) -> Unit,
    val onDhtMitigationModeSelected: (String) -> Unit,
    val onAntiCorrelationEnabledChanged: (Boolean) -> Unit,
    val onAppRoutingPresetEnabledChanged: (String, Boolean) -> Unit,
)
