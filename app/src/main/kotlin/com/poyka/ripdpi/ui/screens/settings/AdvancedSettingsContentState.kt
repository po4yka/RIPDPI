package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.runtime.Composable
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.FakeSeqModeDuplicate
import com.poyka.ripdpi.data.FakeSeqModeSequential
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.state.SettingsUiState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

internal data class AdvancedSettingsContentState(
    val visualEditorEnabled: Boolean,
    val hostPackApplyControlsEnabled: Boolean,
    val showHostFakeSection: Boolean,
    val showSeqOverlapSection: Boolean,
    val showFakeApproxSection: Boolean,
    val showFakeOrderingSection: Boolean,
    val showQuicFakeSection: Boolean,
    val showFakePayloadLibrary: Boolean,
    val showAdaptiveFakeTtlSection: Boolean,
    val showFakeTlsSection: Boolean,
    val fakeTlsBaseOptions: ImmutableList<RipDpiDropdownOption<String>>,
    val httpFakeProfileOptions: ImmutableList<RipDpiDropdownOption<String>>,
    val fakeTlsSniModeOptions: ImmutableList<RipDpiDropdownOption<String>>,
    val tlsFakeProfileOptions: ImmutableList<RipDpiDropdownOption<String>>,
    val fakeOrderOptions: ImmutableList<RipDpiDropdownOption<String>>,
    val fakeSeqModeOptions: ImmutableList<RipDpiDropdownOption<String>>,
    val ipIdModeOptions: ImmutableList<RipDpiDropdownOption<String>>,
    val hostsOptions: ImmutableList<RipDpiDropdownOption<String>>,
    val warpRouteModeOptions: ImmutableList<RipDpiDropdownOption<String>>,
    val warpEndpointSelectionOptions: ImmutableList<RipDpiDropdownOption<String>>,
    val warpAmneziaPresetOptions: ImmutableList<RipDpiDropdownOption<String>>,
    val quicModeOptions: ImmutableList<RipDpiDropdownOption<String>>,
    val tlsFingerprintOptions: ImmutableList<RipDpiDropdownOption<String>>,
    val entropyModeOptions: ImmutableList<RipDpiDropdownOption<String>>,
    val udpFakeProfileOptions: ImmutableList<RipDpiDropdownOption<String>>,
    val adaptiveSplitPresetOptions: ImmutableList<AdaptiveSplitPresetUiModel>,
    val adaptiveFakeTtlModeOptions: ImmutableList<AdaptiveFakeTtlModeUiModel>,
)

@Composable
internal fun rememberAdvancedSettingsContentState(uiState: SettingsUiState): AdvancedSettingsContentState =
    AdvancedSettingsContentState(
        visualEditorEnabled = !uiState.enableCmdSettings,
        hostPackApplyControlsEnabled = hostPackApplyEnabled(uiState),
        showHostFakeSection = uiState.showHostFakeProfile,
        showSeqOverlapSection = uiState.showSeqOverlapProfile,
        showFakeApproxSection = uiState.showFakeApproximationProfile,
        showFakeOrderingSection = uiState.showFakeOrderingProfile,
        showQuicFakeSection = uiState.showQuicFakeProfile,
        showFakePayloadLibrary = uiState.showFakePayloadLibrary,
        showAdaptiveFakeTtlSection = uiState.showAdaptiveFakeTtlProfile,
        showFakeTlsSection = shouldShowFakeTlsSection(uiState),
        fakeTlsBaseOptions = rememberFakeTlsBaseOptions(),
        httpFakeProfileOptions = rememberHttpFakeProfileOptions(),
        fakeTlsSniModeOptions = rememberFakeTlsSniModeOptions(),
        tlsFakeProfileOptions = rememberTlsFakeProfileOptions(),
        fakeOrderOptions = rememberFakeOrderOptions(),
        fakeSeqModeOptions = rememberFakeSeqModeOptions(),
        ipIdModeOptions = rememberIpIdModeOptions(),
        hostsOptions = rememberHostsOptions(),
        warpRouteModeOptions = rememberWarpRouteModeOptions(),
        warpEndpointSelectionOptions = rememberWarpEndpointSelectionOptions(),
        warpAmneziaPresetOptions = rememberWarpAmneziaPresetOptions(),
        quicModeOptions = rememberQuicModeOptions(),
        tlsFingerprintOptions = rememberTlsFingerprintOptions(),
        entropyModeOptions = rememberEntropyModeOptions(),
        udpFakeProfileOptions = rememberUdpFakeProfileOptions(),
        adaptiveSplitPresetOptions = rememberAdaptiveSplitPresetOptions(uiState),
        adaptiveFakeTtlModeOptions = rememberAdaptiveFakeTtlModeOptions(uiState),
    )

private fun shouldShowFakeTlsSection(uiState: SettingsUiState): Boolean =
    uiState.desyncHttpsEnabled ||
        uiState.isFake ||
        uiState.usesSeqOverlapFakeProfile ||
        uiState.fake.hasCustomFakeTlsProfile ||
        uiState.enableCmdSettings

@Composable
private fun rememberFakeTlsBaseOptions() =
    rememberSettingsOptions(
        labelArrayRes = R.array.fake_tls_base_modes,
        valueArrayRes = R.array.fake_tls_base_modes_entries,
    )

@Composable
private fun rememberHttpFakeProfileOptions() =
    rememberSettingsOptions(
        labelArrayRes = R.array.http_fake_profiles,
        valueArrayRes = R.array.http_fake_profiles_entries,
    )

@Composable
private fun rememberFakeTlsSniModeOptions() =
    rememberSettingsOptions(
        labelArrayRes = R.array.fake_tls_sni_modes,
        valueArrayRes = R.array.fake_tls_sni_modes_entries,
    )

@Composable
private fun rememberTlsFakeProfileOptions() =
    rememberSettingsOptions(
        labelArrayRes = R.array.tls_fake_profiles,
        valueArrayRes = R.array.tls_fake_profiles_entries,
    )

@Composable
private fun rememberFakeOrderOptions() =
    rememberSettingsOptions(
        labelArrayRes = R.array.fake_order_modes,
        valueArrayRes = R.array.fake_order_modes_entries,
    )

@Composable
private fun rememberFakeSeqModeOptions() =
    persistentListOf(
        RipDpiDropdownOption(FakeSeqModeDuplicate, "Duplicate"),
        RipDpiDropdownOption(FakeSeqModeSequential, "Sequential"),
    )

@Composable
private fun rememberIpIdModeOptions() =
    rememberSettingsOptions(
        labelArrayRes = R.array.ip_id_modes,
        valueArrayRes = R.array.ip_id_modes_entries,
    )

@Composable
private fun rememberHostsOptions() =
    rememberSettingsOptions(
        labelArrayRes = R.array.ripdpi_hosts_modes,
        valueArrayRes = R.array.ripdpi_hosts_modes_entries,
    )

@Composable
private fun rememberWarpRouteModeOptions() =
    rememberSettingsOptions(
        labelArrayRes = R.array.warp_route_modes,
        valueArrayRes = R.array.warp_route_modes_entries,
    )

@Composable
private fun rememberWarpEndpointSelectionOptions() =
    rememberSettingsOptions(
        labelArrayRes = R.array.warp_endpoint_selection_modes,
        valueArrayRes = R.array.warp_endpoint_selection_modes_entries,
    )

@Composable
private fun rememberWarpAmneziaPresetOptions() =
    rememberSettingsOptions(
        labelArrayRes = R.array.warp_amnezia_presets,
        valueArrayRes = R.array.warp_amnezia_presets_entries,
    )

@Composable
private fun rememberQuicModeOptions() =
    rememberSettingsOptions(
        labelArrayRes = R.array.quic_initial_modes,
        valueArrayRes = R.array.quic_initial_modes_entries,
    )

@Composable
private fun rememberTlsFingerprintOptions() =
    rememberSettingsOptions(
        labelArrayRes = R.array.tls_fingerprint_profiles,
        valueArrayRes = R.array.tls_fingerprint_profiles_entries,
    )

@Composable
private fun rememberEntropyModeOptions() =
    rememberSettingsOptions(
        labelArrayRes = R.array.entropy_modes,
        valueArrayRes = R.array.entropy_modes_entries,
    )

@Composable
private fun rememberUdpFakeProfileOptions() =
    rememberSettingsOptions(
        labelArrayRes = R.array.udp_fake_profiles,
        valueArrayRes = R.array.udp_fake_profiles_entries,
    )
