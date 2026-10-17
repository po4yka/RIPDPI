package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RuntimeConfigurationApplyReason
import com.poyka.ripdpi.data.RuntimeConfigurationDns
import com.poyka.ripdpi.data.RuntimeConfigurationSelection
import com.poyka.ripdpi.data.RuntimeConfigurationStrategy
import com.poyka.ripdpi.platform.AndroidStringResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReconnectSettingsComparisonTest {
    private val strings = AndroidStringResolver(RuntimeEnvironment.getApplication())

    @Test
    fun `saved DNS protocol differs from confirmed applied DNS without predicting selected route`() {
        val saved =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setDnsMode(DnsModeEncrypted)
                .setEncryptedDnsProtocol("odoh")
                .build()
        val comparison = buildReconnectComparison(applied(), saved, strings)
        assertTrue(comparison.currentSummary.contains("DoT"))
        assertEquals("ODoH", comparison.savedDns)
    }

    @Test
    fun `unknown applied and saved facets show unknown instead of raw values or defaults`() {
        val unknown = "private-unknown-value"
        val saved =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setDnsMode(unknown)
                .build()
        val comparison =
            buildReconnectComparison(
                applied().copy(
                    effectiveSelection =
                        RuntimeConfigurationSelection(
                            unknown,
                            transport = unknown,
                            relayKind = unknown,
                        ),
                    dns = RuntimeConfigurationDns(unknown, unknown, unknown),
                    strategy = RuntimeConfigurationStrategy(true, family = unknown),
                ),
                saved,
                strings,
            )
        assertEquals(strings.getString(R.string.diagnostics_field_unknown), comparison.savedDns)
        assertFalse(comparison.toString().contains(unknown))
        assertFalse(comparison.currentSummary.contains(strings.getString(R.string.dns_mode_plain)))
        assertFalse(comparison.currentSummary.contains("RIPDPI"))
    }

    @Test
    fun `custom saved strategy displays category without command line endpoint or profile data`() {
        val privateValue = "private-endpoint-token"
        val saved =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setEnableCmdSettings(true)
                .setCmdArgs(privateValue)
                .setRelayProfileId(privateValue)
                .setRelayServer(privateValue)
                .setEncryptedDnsHost(privateValue)
                .build()
        val comparison =
            buildReconnectComparison(
                applied().copy(
                    effectiveSelection = RuntimeConfigurationSelection("native", profileId = privateValue),
                ),
                saved,
                strings,
            )
        assertEquals(strings.getString(R.string.adaptive_split_badge_custom), comparison.savedTcpStrategy)
        assertFalse(comparison.toString().contains(privateValue))
    }

    @Test
    fun `DoQ applied protocol is identified without falling back to DoH`() {
        val comparison =
            buildReconnectComparison(
                applied().copy(dns = RuntimeConfigurationDns(DnsModeEncrypted, "custom", "doq")),
                AppSettingsSerializer.defaultValue,
                strings,
            )
        assertTrue(comparison.currentSummary.contains("DoQ"))
        assertFalse(comparison.currentSummary.contains("DoH"))
    }

    @Test
    fun `identity is opaque and different snapshots never compare equal`() {
        val first = ReconnectSettingsIdentity()
        val second = ReconnectSettingsIdentity()
        assertNotEquals(first, second)
        assertEquals("ReconnectSettingsIdentity", first.toString())
        assertEquals(first.toString(), second.toString())
    }

    private fun applied(): AppliedRuntimeConfiguration {
        val selection = RuntimeConfigurationSelection("native")
        return AppliedRuntimeConfiguration(
            "runtime",
            1,
            1,
            Mode.VPN,
            selection,
            selection,
            RuntimeConfigurationDns(DnsModeEncrypted, "custom", "dot"),
            RuntimeConfigurationStrategy(false),
            RuntimeConfigurationApplyReason.InitialStart,
        )
    }
}
