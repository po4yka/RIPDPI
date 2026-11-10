package com.poyka.ripdpi.ui.screens.connection

import com.poyka.ripdpi.data.RelayKindTor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionTorValidationTest {
    @Test
    fun `Tor validates bridge credentials before save`() {
        val draft = ConnectionDraft(relayEnabled = true, relayKind = RelayKindTor)
        assertEquals("required", validateConfigDraft(draft)[ConnectionFieldRelayCredentials])
        listOf(
            "Bridge 192.0.2.1:443",
            "obfs4 192.0.2.1:443 0BAC39417268B96B9F514E7F63FA6FBA1A788955 cert=fixture",
            "obfs4 192.0.2.1:443 0BAC39417268B96B9F514E7F63FA6FBA1A788955 cert=fixture iat-mode=3",
            "webtunnel 192.0.2.1:443 0BAC39417268B96B9F514E7F63FA6FBA1A788955 url=http://bridge.example/secret",
            "webtunnel 192.0.2.1:443 0BAC39417268B96B9F514E7F63FA6FBA1A788955 url=https://bridge.example/",
        ).forEach { line ->
            assertEquals(
                line,
                "invalid",
                validateConfigDraft(draft.copy(relayPtBridgeLine = line))[ConnectionFieldRelayCredentials],
            )
        }
        listOf(
            "obfs4 192.0.2.1:443 0BAC39417268B96B9F514E7F63FA6FBA1A788955 cert=fixture iat-mode=0",
            "Bridge snowflake 192.0.2.1:80 0BAC39417268B96B9F514E7F63FA6FBA1A788955",
            "webtunnel [2001:db8::1]:443 0BAC39417268B96B9F514E7F63FA6FBA1A788955 url=https://bridge.example/secret",
        ).forEach { line ->
            assertNull(line, validateConfigDraft(draft.copy(relayPtBridgeLine = line))[ConnectionFieldRelayCredentials])
        }
    }
}
