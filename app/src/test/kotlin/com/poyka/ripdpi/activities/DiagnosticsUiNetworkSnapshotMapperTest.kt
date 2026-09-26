package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.CellularNetworkDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DiagnosticsUiNetworkSnapshotMapperTest {
    private val support = DiagnosticsUiFactorySupport(RuntimeEnvironment.getApplication())

    @Test
    fun `new stored snapshot explains redaction and preserves address counts`() {
        val original = historySnapshot()
        val snapshot = requireNotNull(original.snapshot)
        val stored =
            original.copy(
                snapshot =
                    snapshot.copy(
                        dnsServers = listOf("redacted", "redacted"),
                        localAddresses = listOf("redacted"),
                        wifiDetails =
                            requireNotNull(snapshot.wifiDetails).copy(
                                ssid = "redacted",
                                bssid = "redacted",
                                networkId = null,
                                gateway = "redacted",
                                dhcpServer = "redacted",
                                ipAddress = "redacted",
                                subnetMask = "redacted",
                            ),
                    ),
            )

        val fields = fieldsFor(stored, showSensitiveDetails = true)
        val marker = "Redacted (not stored)"

        assertEquals(marker, fields[valueLabel(R.string.diagnostics_field_wifi_ssid)])
        assertEquals(marker, fields[valueLabel(R.string.diagnostics_field_wifi_gateway)])
        assertEquals(marker, fields[valueLabel(R.string.diagnostics_field_wifi_network_id)])
        assertEquals(
            marker + " · " + support.context.getString(R.string.diagnostics_field_hidden_count_format, 2),
            fields[valueLabel(R.string.diagnostics_field_dns)],
        )
        assertEquals(
            marker + " · " + support.context.getString(R.string.diagnostics_field_hidden_count_format, 1),
            fields[valueLabel(R.string.diagnostics_field_local)],
        )
        assertEquals("5 GHz", fields[valueLabel(R.string.diagnostics_field_wifi_band)])
    }

    @Test
    fun `hidden mode conceals legacy cellular identities and ASN while visible mode keeps them`() {
        val original = historySnapshot(transport = "cellular")
        val cellular =
            original.copy(
                snapshot =
                    requireNotNull(original.snapshot).copy(
                        wifiDetails = null,
                        cellularDetails =
                            CellularNetworkDetails(
                                carrierName = "Secret Carrier",
                                simOperatorName = "Secret SIM",
                                networkOperatorName = "Secret Operator",
                                operatorCode = "99901",
                                simOperatorCode = "99902",
                                dataNetworkType = "LTE",
                                carrierId = 12345,
                            ),
                    ),
            )

        val hidden = fieldsFor(cellular, showSensitiveDetails = false)
        val visible = fieldsFor(cellular, showSensitiveDetails = true)

        listOf("Secret Carrier", "Secret SIM", "Secret Operator", "99901", "99902", "12345", "AS64500")
            .forEach { raw -> assertFalse(hidden.values.contains(raw)) }
        assertEquals("Secret Carrier", visible[valueLabel(R.string.diagnostics_field_carrier)])
        assertEquals("99901", visible[valueLabel(R.string.diagnostics_field_operator_code)])
        assertEquals("AS64500", visible[valueLabel(R.string.diagnostics_field_asn)])
        assertEquals("LTE", hidden[valueLabel(R.string.diagnostics_field_data_network)])
        assertTrue(hidden.values.contains(support.context.getString(R.string.diagnostics_field_hidden)))
    }

    @Test
    fun `hidden mode conceals legacy wifi network ID and private DNS hostname`() {
        val original = historySnapshot()
        val legacy =
            original.copy(
                snapshot = requireNotNull(original.snapshot).copy(privateDnsMode = "resolver.private.example"),
            )

        val hidden = fieldsFor(legacy, showSensitiveDetails = false)
        val visible = fieldsFor(legacy, showSensitiveDetails = true)

        assertFalse(hidden.values.contains("7"))
        assertFalse(hidden.values.contains("resolver.private.example"))
        assertEquals("7", visible[valueLabel(R.string.diagnostics_field_wifi_network_id)])
        assertEquals("resolver.private.example", visible[valueLabel(R.string.diagnostics_field_private_dns)])
    }

    @Test
    fun `unknown identity and missing network ID remain unknown`() {
        val original = historySnapshot()
        val source = requireNotNull(original.snapshot)
        val unknown =
            original.copy(
                snapshot =
                    source.copy(
                        wifiDetails =
                            requireNotNull(source.wifiDetails).copy(
                                ssid = "unknown",
                                bssid = "unknown",
                                networkId = null,
                            ),
                    ),
            )

        val hidden = fieldsFor(unknown, showSensitiveDetails = false)
        val visible = fieldsFor(unknown, showSensitiveDetails = true)
        val unknownLabel = support.context.getString(R.string.diagnostics_field_unknown)

        assertEquals(unknownLabel, hidden[valueLabel(R.string.diagnostics_field_wifi_ssid)])
        assertEquals(unknownLabel, visible[valueLabel(R.string.diagnostics_field_wifi_network_id)])
    }

    private fun fieldsFor(
        snapshot: com.poyka.ripdpi.diagnostics.DiagnosticNetworkSnapshot,
        showSensitiveDetails: Boolean,
    ): Map<String, String> =
        requireNotNull(support.toNetworkSnapshotUiModel(snapshot, showSensitiveDetails))
            .fieldGroups
            .flatMap { it.fields }
            .associate { it.label to it.value }

    private fun valueLabel(id: Int): String = support.context.getString(id)
}
