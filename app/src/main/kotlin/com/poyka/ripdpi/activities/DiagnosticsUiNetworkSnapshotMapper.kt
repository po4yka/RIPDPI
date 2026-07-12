package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.CellularNetworkDetails
import com.poyka.ripdpi.diagnostics.DiagnosticNetworkSnapshot
import com.poyka.ripdpi.diagnostics.NetworkSnapshotModel
import com.poyka.ripdpi.diagnostics.WifiNetworkDetails
import kotlinx.collections.immutable.toImmutableList

internal fun DiagnosticsUiFactorySupport.toNetworkSnapshotUiModel(
    entity: DiagnosticNetworkSnapshot,
    showSensitiveDetails: Boolean,
): DiagnosticsNetworkSnapshotUiModel? {
    val snapshot = entity.snapshot ?: return null
    return DiagnosticsNetworkSnapshotUiModel(
        title = entity.snapshotKind.replace('_', ' ').replaceFirstChar { it.uppercase() },
        subtitle = "${snapshot.transport} · ${formatTimestamp(snapshot.capturedAt)}",
        fieldGroups = networkSnapshotGroups(snapshot, showSensitiveDetails).toImmutableList(),
    )
}

private fun DiagnosticsUiFactorySupport.networkSnapshotGroups(
    snapshot: NetworkSnapshotModel,
    showSensitiveDetails: Boolean,
): List<DiagnosticsFieldGroupUiModel> =
    buildList {
        add(
            DiagnosticsFieldGroupUiModel(
                header = context.getString(R.string.diagnostics_section_network),
                fields = networkFields(snapshot, showSensitiveDetails).toImmutableList(),
            ),
        )
        val transportFields = transportSpecificFields(snapshot, showSensitiveDetails)
        if (transportFields.isNotEmpty()) {
            add(
                DiagnosticsFieldGroupUiModel(
                    header = transportSectionHeader(snapshot),
                    fields = transportFields.toImmutableList(),
                ),
            )
        }
    }

private fun DiagnosticsUiFactorySupport.networkFields(
    snapshot: NetworkSnapshotModel,
    showSensitiveDetails: Boolean,
): List<DiagnosticsFieldUiModel> =
    listOf(
        field(
            R.string.diagnostics_field_capabilities,
            snapshot.capabilities.joinToString().ifBlank { unknownFieldLabel() },
        ),
        field(
            R.string.diagnostics_field_dns,
            if (showSensitiveDetails) {
                snapshot.dnsServers.joinToString().ifBlank { unknownFieldLabel() }
            } else {
                redactCollection(snapshot.dnsServers)
            },
        ),
        field(R.string.diagnostics_field_private_dns, snapshot.privateDnsMode),
        field(R.string.diagnostics_field_mtu, snapshot.mtu?.toString() ?: unknownFieldLabel()),
        field(
            R.string.diagnostics_field_local,
            if (showSensitiveDetails) {
                snapshot.localAddresses.joinToString().ifBlank { unknownFieldLabel() }
            } else {
                redactCollection(snapshot.localAddresses)
            },
        ),
        field(
            R.string.diagnostics_field_public_ip,
            if (showSensitiveDetails) snapshot.publicIp ?: unknownFieldLabel() else redactValue(snapshot.publicIp),
        ),
        field(R.string.diagnostics_field_asn, snapshot.publicAsn ?: unknownFieldLabel()),
        field(R.string.diagnostics_field_validated, snapshot.networkValidated.toString()),
        field(R.string.diagnostics_field_captive_portal, snapshot.captivePortalDetected.toString()),
    )

private fun DiagnosticsUiFactorySupport.transportSectionHeader(snapshot: NetworkSnapshotModel): String =
    when {
        snapshot.wifiDetails != null -> context.getString(R.string.diagnostics_section_wifi)
        snapshot.cellularDetails != null -> context.getString(R.string.diagnostics_section_carrier)
        else -> context.getString(R.string.diagnostics_section_transport)
    }

private fun DiagnosticsUiFactorySupport.transportSpecificFields(
    snapshot: NetworkSnapshotModel,
    showSensitiveDetails: Boolean,
): List<DiagnosticsFieldUiModel> =
    snapshot.wifiDetails?.let { wifiTransportFields(it, showSensitiveDetails) }
        ?: snapshot.cellularDetails?.let { cellularTransportFields(it) }
        ?: emptyList()

private fun DiagnosticsUiFactorySupport.wifiTransportFields(
    wifi: WifiNetworkDetails,
    showSensitiveDetails: Boolean,
): List<DiagnosticsFieldUiModel> =
    wifiIdentityFields(wifi, showSensitiveDetails) +
        wifiRadioFields(wifi) +
        wifiCapabilityFields(wifi) +
        wifiAddressFields(wifi, showSensitiveDetails) +
        wifiLeaseFields(wifi)

private fun DiagnosticsUiFactorySupport.wifiIdentityFields(
    wifi: WifiNetworkDetails,
    showSensitiveDetails: Boolean,
): List<DiagnosticsFieldUiModel> =
    listOf(
        field(
            R.string.diagnostics_field_wifi_ssid,
            if (showSensitiveDetails) wifi.ssid else redactValue(wifi.ssid.takeUnless { it == "unknown" }),
        ),
        field(
            R.string.diagnostics_field_wifi_bssid,
            if (showSensitiveDetails) wifi.bssid else redactValue(wifi.bssid.takeUnless { it == "unknown" }),
        ),
    )

private fun DiagnosticsUiFactorySupport.wifiRadioFields(wifi: WifiNetworkDetails): List<DiagnosticsFieldUiModel> =
    listOf(
        field(R.string.diagnostics_field_wifi_band, wifi.band),
        field(R.string.diagnostics_field_wifi_standard, wifi.wifiStandard),
        field(
            R.string.diagnostics_field_wifi_frequency,
            wifi.frequencyMhz?.let { context.getString(R.string.diagnostics_field_wifi_frequency_format, it) }
                ?: unknownFieldLabel(),
        ),
        field(R.string.diagnostics_field_wifi_channel_width, wifi.channelWidth),
        field(
            R.string.diagnostics_field_wifi_rssi,
            wifi.rssiDbm?.let { context.getString(R.string.diagnostics_field_wifi_rssi_format, it) }
                ?: unknownFieldLabel(),
        ),
        field(
            R.string.diagnostics_field_wifi_link,
            wifi.linkSpeedMbps?.let { context.getString(R.string.diagnostics_field_wifi_speed_format, it) }
                ?: unknownFieldLabel(),
        ),
        field(
            R.string.diagnostics_field_wifi_rx_link,
            wifi.rxLinkSpeedMbps?.let { context.getString(R.string.diagnostics_field_wifi_speed_format, it) }
                ?: unknownFieldLabel(),
        ),
        field(
            R.string.diagnostics_field_wifi_tx_link,
            wifi.txLinkSpeedMbps?.let { context.getString(R.string.diagnostics_field_wifi_speed_format, it) }
                ?: unknownFieldLabel(),
        ),
    )

private fun DiagnosticsUiFactorySupport.wifiCapabilityFields(wifi: WifiNetworkDetails): List<DiagnosticsFieldUiModel> =
    listOf(
        field(R.string.diagnostics_field_wifi_hidden_ssid, wifi.hiddenSsid?.toString() ?: unknownFieldLabel()),
        field(R.string.diagnostics_field_wifi_passpoint, wifi.isPasspoint?.toString() ?: unknownFieldLabel()),
        field(R.string.diagnostics_field_wifi_osu_ap, wifi.isOsuAp?.toString() ?: unknownFieldLabel()),
        field(R.string.diagnostics_field_wifi_network_id, wifi.networkId?.toString() ?: unknownFieldLabel()),
    )

private fun DiagnosticsUiFactorySupport.wifiAddressFields(
    wifi: WifiNetworkDetails,
    showSensitiveDetails: Boolean,
): List<DiagnosticsFieldUiModel> =
    listOf(
        field(
            R.string.diagnostics_field_wifi_gateway,
            if (showSensitiveDetails) wifi.gateway ?: unknownFieldLabel() else redactValue(wifi.gateway),
        ),
        field(
            R.string.diagnostics_field_wifi_dhcp_server,
            if (showSensitiveDetails) wifi.dhcpServer ?: unknownFieldLabel() else redactValue(wifi.dhcpServer),
        ),
        field(
            R.string.diagnostics_field_wifi_ip,
            if (showSensitiveDetails) wifi.ipAddress ?: unknownFieldLabel() else redactValue(wifi.ipAddress),
        ),
        field(
            R.string.diagnostics_field_wifi_subnet,
            if (showSensitiveDetails) wifi.subnetMask ?: unknownFieldLabel() else redactValue(wifi.subnetMask),
        ),
    )

private fun DiagnosticsUiFactorySupport.wifiLeaseFields(wifi: WifiNetworkDetails): List<DiagnosticsFieldUiModel> =
    listOf(
        field(
            R.string.diagnostics_field_wifi_lease,
            wifi.leaseDurationSeconds?.let { context.getString(R.string.diagnostics_field_wifi_lease_format, it) }
                ?: unknownFieldLabel(),
        ),
    )

private fun DiagnosticsUiFactorySupport.cellularTransportFields(
    cellular: CellularNetworkDetails,
): List<DiagnosticsFieldUiModel> =
    listOf(
        field(R.string.diagnostics_field_carrier, cellular.carrierName),
        field(R.string.diagnostics_field_sim_operator, cellular.simOperatorName),
        field(R.string.diagnostics_field_network_operator, cellular.networkOperatorName),
        field(R.string.diagnostics_field_network_country, cellular.networkCountryIso),
        field(R.string.diagnostics_field_sim_country, cellular.simCountryIso),
        field(R.string.diagnostics_field_operator_code, cellular.operatorCode),
        field(R.string.diagnostics_field_sim_operator_code, cellular.simOperatorCode),
        field(R.string.diagnostics_field_data_network, cellular.dataNetworkType),
        field(R.string.diagnostics_field_voice_network, cellular.voiceNetworkType),
        field(R.string.diagnostics_field_data_state, cellular.dataState),
        field(R.string.diagnostics_field_service_state, cellular.serviceState),
        field(R.string.diagnostics_field_roaming, cellular.isNetworkRoaming?.toString() ?: unknownFieldLabel()),
        field(R.string.diagnostics_field_carrier_id, cellular.carrierId?.toString() ?: unknownFieldLabel()),
        field(R.string.diagnostics_field_sim_carrier_id, cellular.simCarrierId?.toString() ?: unknownFieldLabel()),
        field(R.string.diagnostics_field_signal_level, cellular.signalLevel?.toString() ?: unknownFieldLabel()),
        field(
            R.string.diagnostics_field_signal_dbm,
            cellular.signalDbm?.let { context.getString(R.string.diagnostics_field_signal_dbm_format, it) }
                ?: unknownFieldLabel(),
        ),
    )

private fun DiagnosticsUiFactorySupport.field(
    labelRes: Int,
    value: String,
): DiagnosticsFieldUiModel = DiagnosticsFieldUiModel(context.getString(labelRes), value)

private fun DiagnosticsUiFactorySupport.unknownFieldLabel(): String =
    context.getString(R.string.diagnostics_field_unknown)
