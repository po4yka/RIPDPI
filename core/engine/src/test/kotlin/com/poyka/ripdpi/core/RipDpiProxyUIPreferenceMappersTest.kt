package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.EnvironmentKind
import com.poyka.ripdpi.data.HttpFakeProfileCloudflareGet
import com.poyka.ripdpi.data.QuicFakeProfileRealisticInitial
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.WarpRouteModeRules
import com.poyka.ripdpi.proto.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RipDpiProxyUIPreferenceMappersTest {
    @Test
    fun desyncMapperBuildsChainAndFakeTransportSlices() {
        val settings =
            AppSettings
                .getDefaultInstance()
                .toBuilder()
                .setDesyncAnyProtocol(true)
                .setFakeTtl(4)
                .setFakeSni("front.example")
                .setHttpFakeProfile(HttpFakeProfileCloudflareGet)
                .setOobData("z")
                .setQuicBindLowPort(true)
                .build()

        val chain = buildChainConfig(settings)
        val fakePackets = buildFakePacketConfig(settings)

        assertTrue(chain.anyProtocol)
        assertEquals(4, fakePackets.fakeTtl)
        assertEquals("front.example", fakePackets.fakeSni)
        assertEquals(HttpFakeProfileCloudflareGet, fakePackets.httpFakeProfile)
        assertEquals('z', fakePackets.oobChar)
        assertTrue(fakePackets.quicBindLowPort)
    }

    @Test
    fun quicRelayAndWarpMappersKeepFeatureFieldsIsolated() {
        val settings =
            AppSettings
                .getDefaultInstance()
                .toBuilder()
                .setQuicFakeProfile(QuicFakeProfileRealisticInitial)
                .setQuicFakeHost("video.example")
                .setRelayEnabled(true)
                .setRelayKind(RelayKindMasque)
                .setRelayMasqueUrl("https://masque.example/proxy")
                .setWarpEnabled(true)
                .setWarpRouteMode(WarpRouteModeRules)
                .setWarpRouteHosts("blocked.example")
                .build()

        val quic = buildQuicConfig(settings)
        val relay = buildRelayConfig(settings)
        val warp = buildWarpConfig(settings)

        assertEquals(QuicFakeProfileRealisticInitial, quic.fakeProfile)
        assertEquals("video.example", quic.fakeHost)
        assertTrue(relay.enabled)
        assertEquals(RelayKindMasque, relay.kind)
        assertEquals("https://masque.example/proxy", relay.masqueUrl)
        assertTrue(warp.enabled)
        assertEquals(WarpRouteModeRules, warp.routeMode)
        assertEquals("blocked.example", warp.routeHosts)
    }

    @Test
    fun runtimeMapperCarriesSessionRootAndEnvironmentInputs() {
        val runtimeContext = RipDpiRuntimeContext(protectPath = "/tmp/protect.sock")
        val logContext = RipDpiLogContext(runtimeId = "runtime-1", mode = "VPN")
        val preferences =
            RipDpiProxyUIPreferences.fromSettings(
                settings =
                    AppSettings
                        .getDefaultInstance()
                        .toBuilder()
                        .setHostAutolearnEnabled(true)
                        .build(),
                hostAutolearnStorePath = "/tmp/hosts.json",
                networkScopeKey = "network-a",
                runtimeContext = runtimeContext,
                logContext = logContext,
                rootMode = true,
                rootHelperSocketPath = "/tmp/root-helper.sock",
                geoipDbPath = " /tmp/geo/geoip.db ",
                geositeDbPath = "/tmp/geo/geosite.db",
                environmentKind = EnvironmentKind.Emulator,
            )

        assertEquals("/tmp/hosts.json", preferences.hostAutolearn.storePath)
        assertEquals("network-a", preferences.hostAutolearn.networkScopeKey)
        assertEquals(runtimeContext, preferences.runtimeContext)
        assertEquals("vpn", preferences.logContext?.mode)
        assertTrue(preferences.rootMode)
        assertEquals("/tmp/root-helper.sock", preferences.rootHelperSocketPath)
        assertEquals("/tmp/geo/geoip.db", preferences.geoipDbPath)
        assertEquals("/tmp/geo/geosite.db", preferences.geositeDbPath)
        assertEquals(EnvironmentKind.Emulator, preferences.environmentKind)
    }
}
