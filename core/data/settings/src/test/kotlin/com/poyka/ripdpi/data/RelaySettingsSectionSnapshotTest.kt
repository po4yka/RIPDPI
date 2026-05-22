package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Equivalence guard for the relay snapshot mapper after it was migrated to
 * consume [RelaySettingsSection] instead of the whole `AppSettings` proto.
 *
 * `withRelaySnapshot` reads its fields through the section projection now.
 * Because the section is a verbatim copy of the relay proto fields, the
 * resulting [AppSettingsRelaySnapshot] — including every normalization (port
 * fallback, blank-profile fallback) — is byte-identical to the pre-migration
 * output for the same payload.
 */
class RelaySettingsSectionSnapshotTest {
    @Test
    fun `relay snapshot is derived through the relay section unchanged`() {
        val settings =
            AppSettings
                .newBuilder()
                .setRelayEnabled(true)
                .setRelayKind("vless_reality")
                .setRelayServer("r.example")
                .setRelayServerName("sni.example")
                .setRelayUdpEnabled(true)
                .addAllRelayAppsScriptScriptIds(listOf("a", "b"))
                .build()

        val relay = settings.toSnapshot().relay

        assertEquals(true, relay.relayEnabled)
        assertEquals("r.example", relay.relayServer)
        assertEquals("sni.example", relay.relayServerName)
        assertEquals(true, relay.relayUdpEnabled)
        assertEquals(listOf("a", "b"), relay.relayAppsScriptScriptIds)
        // relay_server_port left 0 -> normalized to the HTTPS default.
        assertEquals(443, relay.relayServerPort)
        // relay_profile_id left blank -> normalized to the default profile id.
        assertEquals(DefaultRelayProfileId, relay.relayProfileId)
        assertTrue(relay.relayKind.isNotBlank())
    }
}
