package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.uri.ProxyUriCodec
import com.poyka.ripdpi.serialization.RipDpiEncodeDefaultsJson
import com.poyka.ripdpi.serialization.RipDpiJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MieruAdvancedProfileMappingTest {
    @Test
    fun `advanced imported profile retains original presence in both stored models`() {
        val uri =
            "mierus://alice:fixture@example.test?profile=p&port=443&protocol=TCP" +
                "&handshake-mode=HANDSHAKE_DEFAULT&traffic-pattern=CCoQABoA"
        val imported = ProxyUriCodec.parse(uri) as ProxyProfile.Mieru
        assertTrue(validateNativeRelayProfile(imported))
        val proxyWire = RipDpiEncodeDefaultsJson.encodeToString(ProxyProfile.serializer(), imported)
        val proxy = RipDpiJson.decodeFromString(ProxyProfile.serializer(), proxyWire) as ProxyProfile.Mieru
        val mapped = requireNotNull(mapRelayProfile(proxy))
        val relayWire = RipDpiEncodeDefaultsJson.encodeToString(RelayProfileRecord.serializer(), mapped.profile)
        val stored = RipDpiJson.decodeFromString(RelayProfileRecord.serializer(), relayWire)
        assertEquals(MieruHandshakeDefault, stored.mieruHandshakeMode)
        assertEquals(
            MieruTrafficPattern(seed = 42, unlockAll = false, tcpFragment = MieruTcpFragment()),
            stored.mieruTrafficPattern,
        )
        assertNull(stored.mieruTrafficPattern?.nonce)
        assertEquals("alice", mapped.credentials.mieruUsername)
        assertEquals("fixture", mapped.credentials.mieruPassword)
    }

    @Test
    fun `activation rejects invalid programmatic advanced settings`() {
        val valid = ProxyUriCodec.parse("mieru://alice:fixture@example.test:443") as ProxyProfile.Mieru
        for (invalid in listOf(
            valid.copy(handshakeMode = "unknown"),
            valid.copy(trafficPattern = MieruTrafficPattern(tcpFragment = MieruTcpFragment(maxSleepMs = 101))),
            valid.copy(
                trafficPattern = MieruTrafficPattern(nonce = MieruNoncePattern(customHexStrings = listOf("zz"))),
            ),
        )) {
            assertThrows(IllegalArgumentException::class.java) { validateNativeRelayProfile(invalid) }
            assertNull(ProxyUriCodec.encodeOrNull(invalid))
        }
    }
}
