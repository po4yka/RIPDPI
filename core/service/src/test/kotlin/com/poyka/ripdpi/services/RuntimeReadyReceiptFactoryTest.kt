package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RuntimeConfigurationApplyReason
import com.poyka.ripdpi.data.RuntimeConfigurationAttempt
import com.poyka.ripdpi.data.RuntimeConfigurationSelection
import com.poyka.ripdpi.data.activeDnsSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeReadyReceiptFactoryTest {
    private val identities = RuntimeConfigurationIdentityFactory()
    private val factory = RuntimeReadyReceiptFactory(identities)

    @Test fun `forced encrypted resolver is confirmed from actual TUN receipt for native and provider`() {
        val resolution = sampleResolution(Mode.VPN)
        val tunnel =
            RuntimeTunnelReadyEvidence(
                resolution.requestedConfiguration.tunnelInput,
                true,
                vpnTunnelDnsPlan(resolution.activeDns, true).resolverDns,
                null,
                "interface",
            )
        val native = RuntimeStartEvidence.VpnSnapshot(proxy(), tunnel)
        val provider =
            RuntimeStartEvidence.ProviderReady(
                RuntimeConfigurationSelection("xray"),
                identities.capture(listOf("provider"), tunnel.dnsMaterial()),
                tunnel,
            )
        for (evidence in listOf(native, provider)) {
            val receipt = factory.build(VpnRuntimeSession("current"), resolution, evidence, 10, attempt(Mode.VPN))
            assertEquals(tunnel.resolverDns.runtimeDnsSummary(), receipt.configuration.dns)
            assertTrue(tunnel.resolverDns.isEncrypted)
        }
    }

    @Test fun `saved custom command does not label normalized consumed UI preferences custom`() {
        val settings =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setEnableCmdSettings(
                    true,
                ).setCmdArgs("--port 1080")
                .build()
        val resolution = sampleResolution(Mode.Proxy, settings = settings, activeDns = settings.activeDnsSettings())
        assertTrue(resolution.requestedConfiguration.strategy.custom)
        val receipt = factory.build(ProxyRuntimeSession("current"), resolution, proxy(), 10, attempt(Mode.Proxy))
        assertFalse(receipt.configuration.strategy.custom)
    }

    @Test fun `consumed JSON command line remains custom despite saved UI settings`() {
        val resolution = sampleResolution(Mode.Proxy)
        val command =
            com.poyka.ripdpi.core
                .RipDpiProxyCmdPreferences("--port 1080")
        val evidence =
            RuntimeStartEvidence.ProxySnapshot(
                NativeRuntimeSnapshot("proxy", state = "running"),
                com.poyka.ripdpi.core
                    .RipDpiProxyJsonPreferences(command.toNativeConfigJson()),
                emptyList(),
                null,
            )
        val receipt = factory.build(ProxyRuntimeSession("current"), resolution, evidence, 10, attempt(Mode.Proxy))
        assertTrue(receipt.configuration.strategy.custom)
        assertTrue(receipt.configuration.strategy.enabled)
    }

    @Test fun `DNS only receipt preserves effective transport and acknowledges actual forced resolver`() {
        val store =
            AppliedRuntimeConfigurationStore(
                PauseAppliedReceiptConsumer(
                    com.poyka.ripdpi.data
                        .testPauseAuthority(),
                ),
            )
        val lifecycle = RuntimeConfigurationLifecycle(store, identities)
        val resolution = sampleResolution(Mode.VPN)
        val session = VpnRuntimeSession("current")
        val tunnel =
            RuntimeTunnelReadyEvidence(
                resolution.requestedConfiguration.tunnelInput,
                true,
                vpnTunnelDnsPlan(resolution.activeDns, true).resolverDns,
                null,
                "interface",
            )
        lifecycle.begin(session, resolution, "initial")
        lifecycle.ready(session, resolution, RuntimeStartEvidence.VpnSnapshot(proxy(), tunnel), 10, null)
        val before = requireNotNull(session.effectiveConfigurationIdentity)
        lifecycle.beginDns(session, resolution)
        lifecycle.dnsReady(session, 11, tunnel)
        assertTrue(before.transport.matches(requireNotNull(session.effectiveConfigurationIdentity).transport))
        assertEquals(tunnel.resolverDns.runtimeDnsSummary(), store.lastConfirmed(Mode.VPN)?.dns)
    }

    private fun proxy() =
        RuntimeStartEvidence.ProxySnapshot(
            NativeRuntimeSnapshot("proxy", state = "running"),
            RipDpiProxyUIPreferences(),
            emptyList(),
            null,
        )

    private fun attempt(mode: Mode) =
        RuntimeConfigurationAttempt(
            "current",
            1,
            mode,
            RuntimeConfigurationSelection("native"),
            RuntimeConfigurationApplyReason.InitialStart,
        )
}
