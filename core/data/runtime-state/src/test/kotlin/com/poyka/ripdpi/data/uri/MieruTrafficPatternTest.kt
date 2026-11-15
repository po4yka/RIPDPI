package com.poyka.ripdpi.data.uri

import com.poyka.ripdpi.data.MieruHandshakeNoWait
import com.poyka.ripdpi.data.MieruLowEntropyPattern
import com.poyka.ripdpi.data.MieruNoncePattern
import com.poyka.ripdpi.data.MieruPaddingPattern
import com.poyka.ripdpi.data.MieruTcpFragment
import com.poyka.ripdpi.data.MieruTrafficPattern
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.effective
import com.poyka.ripdpi.data.validateMieruAdvancedSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class MieruTrafficPatternTest {
    @Test
    fun `pinned upstream example preserves explicit traffic pattern fields`() {
        // enfein/mieru 155ebbd60 docs/client-install.md simple share example.
        val encoded = "CCoQARoECAEQCiIYCAMQASoIMDAwMTAyMDMqCDA0MDUwNjA3"
        val expected =
            MieruTrafficPattern(
                seed = 42,
                unlockAll = true,
                tcpFragment = MieruTcpFragment(true, 10),
                nonce =
                    MieruNoncePattern(
                        type = 3,
                        applyToAllUDPPacket = true,
                        customHexStrings = listOf("00010203", "04050607"),
                    ),
            )
        assertEquals(expected, MieruTrafficPatternCodec.decodeBase64(encoded))
        assertEquals(encoded, MieruTrafficPatternCodec.encodeBase64(expected))
    }

    @Test
    fun `advanced simple link retains settings after canonical share`() {
        val wire = "CCoQARoECAEQCiIYCAMQASoIMDAwMTAyMDMqCDA0MDUwNjA3"
        val original =
            ProxyUriCodec.parse(
                "mierus://alice:fixture@[2001:db8::1]?profile=My+Profile&port=443&protocol=TCP" +
                    "&handshake-mode=HANDSHAKE_NO_WAIT&traffic-pattern=$wire",
            ) as ProxyProfile.Mieru
        assertEquals("My Profile", original.displayName)
        assertEquals(MieruHandshakeNoWait, original.handshakeMode)
        assertEquals(MieruTrafficPatternCodec.decodeBase64(wire), original.trafficPattern)
        val reimported = ProxyUriCodec.parse(ProxyUriCodec.encode(original)) as ProxyProfile.Mieru
        assertEquals(original.copy(id = reimported.id), reimported)
        for (suffix in listOf(
            "&handshake-mode=HANDSHAKE_STANDARD&handshake-mode=HANDSHAKE_NO_WAIT",
            "&traffic-pattern=$wire&traffic-pattern=$wire",
            "&unknown=value",
            "&mtu=1400&mtu=1401",
            "&profile=duplicate",
            "&handshake-mode=NO_WAIT",
            "&traffic-pattern=%FF",
        )) {
            assertNull(
                ProxyUriCodec.parse("mierus://alice:fixture@example.test?profile=p&port=443&protocol=TCP$suffix"),
            )
        }
    }

    @Test
    fun `empty message and explicit zeros retain distinct protobuf presence`() {
        val absent = MieruTrafficPatternCodec.decode(byteArrayOf())
        val explicit = MieruTrafficPattern(seed = 0, unlockAll = false, tcpFragment = MieruTcpFragment(false, 0))
        assertNull(absent.seed)
        assertNotEquals(absent, explicit)
        assertEquals(explicit, MieruTrafficPatternCodec.decode(MieruTrafficPatternCodec.encode(explicit)))
        val emptyNested = MieruTrafficPatternCodec.decode(byteArrayOf(0x1a, 0))
        assertEquals(MieruTcpFragment(), emptyNested.tcpFragment)
        assertEquals("GgA=", MieruTrafficPatternCodec.encodeBase64(emptyNested))
    }

    @Test
    fun `resolved explicit settings reach native without different defaults`() {
        val pattern =
            MieruTrafficPattern(
                seed = -1,
                unlockAll = false,
                tcpFragment = MieruTcpFragment(true, 100),
                nonce = MieruNoncePattern(3, false, 0, 12, listOf("00010203")),
                padding = MieruPaddingPattern(0, 255),
                lowEntropy = MieruLowEntropyPattern(4, 240),
            )
        validateMieruAdvancedSettings(MieruHandshakeNoWait, pattern)
        assertEquals(pattern, pattern.effective())
        assertEquals(pattern, MieruTrafficPatternCodec.decode(MieruTrafficPatternCodec.encode(pattern)))
    }

    @Test
    fun `explicit short nonce maximum clamps its generated minimum at activation`() {
        val original = MieruTrafficPattern(seed = 42, nonce = MieruNoncePattern(maxLen = 0))
        val effective = original.effective()
        assertEquals(0, effective.nonce?.minLen)
        assertEquals(0, effective.nonce?.maxLen)
        assertEquals(effective, effective.effective())
        assertEquals(original, MieruTrafficPatternCodec.decode(MieruTrafficPatternCodec.encode(original)))
    }

    @Test
    fun `restricted seed defaults match pinned production Go config`() {
        // trafficpattern.NewConfig at commit 155ebbd60, executed with Go 1.27.4.
        // config.go SHA-256: cdf429b8c7506a71231badf96c34f594c325bc33bac7302cc83b9e0fa97d1c02.
        val vectors =
            listOf(
                MieruTrafficPattern(
                    seed = 0,
                    unlockAll = false,
                    tcpFragment = MieruTcpFragment(false, 0),
                    nonce = MieruNoncePattern(1, true, 9, 11),
                    padding = MieruPaddingPattern(77, 255),
                    lowEntropy = MieruLowEntropyPattern(0, 160),
                ),
                MieruTrafficPattern(
                    seed = 1,
                    unlockAll = false,
                    tcpFragment = MieruTcpFragment(false, 0),
                    nonce = MieruNoncePattern(1, false, 12, 12),
                    padding = MieruPaddingPattern(23, 255),
                    lowEntropy = MieruLowEntropyPattern(0, 240),
                ),
                MieruTrafficPattern(
                    seed = 42,
                    unlockAll = false,
                    tcpFragment = MieruTcpFragment(false, 0),
                    nonce = MieruNoncePattern(1, false, 12, 12),
                    padding = MieruPaddingPattern(82, 255),
                    lowEntropy = MieruLowEntropyPattern(0, 240),
                ),
                MieruTrafficPattern(
                    seed = -1,
                    unlockAll = false,
                    tcpFragment = MieruTcpFragment(false, 0),
                    nonce = MieruNoncePattern(1, true, 12, 12),
                    padding = MieruPaddingPattern(126, 255),
                    lowEntropy = MieruLowEntropyPattern(0, 12),
                ),
                MieruTrafficPattern(
                    seed = 2147483647,
                    unlockAll = false,
                    tcpFragment = MieruTcpFragment(false, 0),
                    nonce = MieruNoncePattern(2, false, 9, 11),
                    padding = MieruPaddingPattern(0, 255),
                    lowEntropy = MieruLowEntropyPattern(0, 8),
                ),
            )
        assertSeedDefaults(vectors)
    }

    @Test
    fun `unlocked seed defaults match pinned production Go config`() {
        // trafficpattern.NewConfig at commit 155ebbd60, executed with Go 1.27.4.
        // config.go SHA-256: cdf429b8c7506a71231badf96c34f594c325bc33bac7302cc83b9e0fa97d1c02.
        val vectors =
            listOf(
                MieruTrafficPattern(
                    seed = 0,
                    unlockAll = true,
                    tcpFragment = MieruTcpFragment(false, 37),
                    nonce = MieruNoncePattern(1, true, 6, 10),
                    padding = MieruPaddingPattern(77, 213),
                    lowEntropy = MieruLowEntropyPattern(0, 160),
                ),
                MieruTrafficPattern(
                    seed = 1,
                    unlockAll = true,
                    tcpFragment = MieruTcpFragment(true, 90),
                    nonce = MieruNoncePattern(1, false, 1, 3),
                    padding = MieruPaddingPattern(23, 213),
                    lowEntropy = MieruLowEntropyPattern(4, 240),
                ),
                MieruTrafficPattern(
                    seed = 42,
                    unlockAll = true,
                    tcpFragment = MieruTcpFragment(true, 55),
                    nonce = MieruNoncePattern(2, false, 9, 12),
                    padding = MieruPaddingPattern(82, 115),
                    lowEntropy = MieruLowEntropyPattern(3, 240),
                ),
                MieruTrafficPattern(
                    seed = -1,
                    unlockAll = true,
                    tcpFragment = MieruTcpFragment(false, 85),
                    nonce = MieruNoncePattern(1, true, 9, 12),
                    padding = MieruPaddingPattern(126, 63),
                    lowEntropy = MieruLowEntropyPattern(3, 12),
                ),
                MieruTrafficPattern(
                    seed = 2147483647,
                    unlockAll = true,
                    tcpFragment = MieruTcpFragment(true, 20),
                    nonce = MieruNoncePattern(0, false, 9, 11),
                    padding = MieruPaddingPattern(0, 42),
                    lowEntropy = MieruLowEntropyPattern(0, 8),
                ),
            )
        assertSeedDefaults(vectors)
    }

    private fun assertSeedDefaults(vectors: List<MieruTrafficPattern>) {
        vectors.forEach { expected ->
            assertEquals(
                expected,
                MieruTrafficPattern(seed = expected.seed, unlockAll = expected.unlockAll).effective(),
            )
        }
    }

    @Test
    fun `pinned production protobuf share preserves handshake and pattern`() {
        val imported =
            ProxyUriCodec.parse(
                "mieru://ClcKBm9yYWNsZRIQCgVhbGljZRIHZml4dHVyZRoVEgxleGFtcGxlLnRlc3QaBQi7AxACMAI6IggqEAEaBAgBEAoi" +
                    "FggDKggwMDAxMDIwMyoIMDQwNTA2MDcSBm9yYWNsZQ==",
            ) as ProxyProfile.Mieru
        assertEquals("oracle", imported.displayName)
        assertEquals(MieruHandshakeNoWait, imported.handshakeMode)
        val expected =
            MieruTrafficPattern(
                seed = 42,
                unlockAll = true,
                tcpFragment = MieruTcpFragment(true, 10),
                nonce = MieruNoncePattern(type = 3, customHexStrings = listOf("00010203", "04050607")),
            )
        assertEquals(expected, imported.trafficPattern)
        val reimported = ProxyUriCodec.parse(ProxyUriCodec.encode(imported)) as ProxyProfile.Mieru
        assertEquals(imported.copy(id = reimported.id), reimported)
    }

    @Test
    fun `standard password hash must preserve the pinned raw password key`() {
        // enfein/mieru 155ebbd60 docs/client-install.md: HashPassword(manlianpenfen, baozi).
        val hash = "40abac40f59da55dad6a99831a5116522fbc80e385bb1ab14dc52db368f738a4"

        fun link(
            password: String?,
            storedHash: String?,
        ): String {
            fun field(
                id: Int,
                bytes: ByteArray,
            ): ByteArray = byteArrayOf(((id shl 3) or 2).toByte(), bytes.size.toByte()) + bytes

            fun text(
                id: Int,
                value: String,
            ): ByteArray = field(id, value.toByteArray())
            val user =
                text(1, "baozi") +
                    (password?.let { text(2, it) } ?: byteArrayOf()) +
                    (storedHash?.let { text(3, it) } ?: byteArrayOf())
            val server = text(2, "h") + field(3, byteArrayOf(8, 0xbb.toByte(), 3, 16, 2))
            val profile = text(1, "p") + field(2, user) + field(3, server)
            return "mieru://" + Base64.getEncoder().encodeToString(field(1, profile))
        }
        for (storedHash in listOf(null, "", hash, hash.uppercase())) {
            val parsed = ProxyUriCodec.parse(link("manlianpenfen", storedHash)) as ProxyProfile.Mieru
            assertEquals("baozi", parsed.username)
            assertEquals("manlianpenfen", parsed.password)
        }
        for (storedHash in listOf("0".repeat(64), hash.dropLast(1), "g".repeat(64), "é" + "0".repeat(63))) {
            assertNull(ProxyUriCodec.parse(link("manlianpenfen", storedHash)))
        }
        assertNull(ProxyUriCodec.parse(link(null, hash)))
        assertNull(ProxyUriCodec.parse(link("", hash)))
    }

    @Test
    fun `unknown repeated overflowing and invalid wire fields are rejected`() {
        val invalid =
            listOf(
                byteArrayOf(0x38, 1),
                byteArrayOf(8, 1, 8, 2),
                byteArrayOf(0x0a, 1, 1),
                byteArrayOf(0x1a, 2, 0x10, 101),
                byteArrayOf(0x22, 2, 8, 4),
                byteArrayOf(0x32, 2, 0x10, 17),
                byteArrayOf(0x2a, 3, 8, 0x80.toByte(), 2),
                ByteArray(11) { if (it == 0) 8 else 0xff.toByte() },
            )
        invalid.forEach { bytes ->
            assertThrows(IllegalArgumentException::class.java) { MieruTrafficPatternCodec.decode(bytes) }
        }
        assertThrows(IllegalArgumentException::class.java) { MieruTrafficPatternCodec.decodeBase64("not-base64") }
        assertThrows(IllegalArgumentException::class.java) {
            MieruTrafficPatternCodec.decodeBase64(Base64.getEncoder().encodeToString(ByteArray(65537)))
        }
    }
}
