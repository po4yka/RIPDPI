package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.toSettingsSections
import com.poyka.ripdpi.proto.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Equivalence guard for the `RipDpiProxyUIPreferences` listen-config assembly
 * after `buildListenConfig` was migrated to consume [com.poyka.ripdpi.data.ProxySettingsSection]
 * instead of the whole `AppSettings` proto.
 *
 * The section is a verbatim copy of the proxy/listen proto fields, so the
 * derived `RipDpiListenConfig` — including every normalization (the IP/port/
 * buffer fallbacks and the `customTtl`-gated `defaultTtl`) — is unchanged.
 */
class ListenConfigSectionTest {
    @Test
    fun `listen config is built from the proxy section`() {
        val settings =
            AppSettings
                .newBuilder()
                .setProxyIp("10.0.0.1")
                .setProxyPort(8080)
                .setMaxConnections(256)
                .setTcpFastOpen(true)
                .setCustomTtl(true)
                .setDefaultTtl(48)
                .setFreezeDetectionEnabled(true)
                .build()

        val listen = buildListenConfig(settings.toSettingsSections().proxy)

        assertEquals("10.0.0.1", listen.ip)
        assertEquals(8080, listen.port)
        assertEquals(256, listen.maxConnections)
        assertEquals(true, listen.tcpFastOpen)
        assertEquals(true, listen.customTtl)
        assertEquals(48, listen.defaultTtl)
        assertEquals(true, listen.freezeDetectionEnabled)
        // buffer_size left 0 -> normalized to the built-in default.
        assertEquals(16384, listen.bufferSize)
    }

    @Test
    fun `default ttl is suppressed when customTtl is off`() {
        val settings = AppSettings.newBuilder().setDefaultTtl(48).build()

        val listen = buildListenConfig(settings.toSettingsSections().proxy)

        assertEquals(false, listen.customTtl)
        assertEquals(0, listen.defaultTtl)
    }

    @Test
    fun `mixed inbound enabled with zero port defaults to 2080`() {
        val settings = AppSettings.newBuilder().setMixedInboundEnabled(true).build()

        val listen = buildListenConfig(settings.toSettingsSections().proxy)

        assertEquals(true, listen.mixed)
        assertEquals(2080, listen.port)
    }

    @Test
    fun `mixed inbound disabled with zero port defaults to 1080`() {
        val settings = AppSettings.newBuilder().build()

        val listen = buildListenConfig(settings.toSettingsSections().proxy)

        assertEquals(false, listen.mixed)
        assertEquals(1080, listen.port)
    }
}
