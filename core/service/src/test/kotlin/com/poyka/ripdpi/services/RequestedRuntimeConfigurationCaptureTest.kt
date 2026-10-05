package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RequestedRuntimeConfigurationCaptureTest {
    @Test
    fun `UI only preferences do not create unapplied runtime changes`() =
        runTest {
            val capture = testRequestedRuntimeConfigurationCapture()
            val baseline = AppSettingsSerializer.defaultValue
            val original = capture.capture(Mode.Proxy, baseline, null)
            val appearance = baseline.toBuilder().setAppTheme("dark").build()
            assertTrue(original.identity.matches(capture.capture(Mode.Proxy, appearance, null).identity))
            assertFalse(original.toString().contains("AppSettings"))
        }

    @Test
    fun `same listener with changed LAN authentication remains a pending transport change`() =
        runTest {
            val capture = testRequestedRuntimeConfigurationCapture()
            val initial =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setProxyAllowLan(
                        true,
                    ).setProxyLanAuthToken("first-secret")
                    .build()
            val updated = initial.toBuilder().setProxyLanAuthToken("second-secret").build()
            val old = capture.capture(Mode.Proxy, initial, null)
            val current = capture.capture(Mode.Proxy, updated, null)
            assertFalse(old.identity.transport.matches(current.identity.transport))
            assertTrue(old.identity.dns.matches(current.identity.dns))
            assertFalse(old.toString().contains("first-secret"))
            assertFalse(old.tunnelInput.toString().contains("first-secret"))
        }

    @Test
    fun `per app VPN routing creates pending transport while resolver changes remain DNS only`() =
        runTest {
            val capture = testRequestedRuntimeConfigurationCapture()
            val initial =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setDnsMode(
                        com.poyka.ripdpi.data.DnsModePlainUdp,
                    ).build()
            val old = capture.capture(Mode.VPN, initial, null)
            val route =
                capture.capture(
                    Mode.VPN,
                    initial
                        .toBuilder()
                        .setSplitTunnelMode("exclude")
                        .addSplitTunnelPackages("example.app")
                        .build(),
                    null,
                )
            assertFalse(old.identity.transport.matches(route.identity.transport))
            val dns = capture.capture(Mode.VPN, initial.toBuilder().setDnsIp("9.9.9.9").build(), null)
            assertFalse(old.identity.dns.matches(dns.identity.dns))
        }
}
