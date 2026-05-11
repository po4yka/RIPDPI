package com.poyka.ripdpi.diagnostics.dpi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuicFingerprintFactoryTest {
    @Test
    fun fingerprintInitialsUseQuicV1() {
        listOf(
            QuicFingerprint.CHROME_120,
            QuicFingerprint.FIREFOX_121,
            QuicFingerprint.GENERIC_V1,
        ).forEach { fingerprint ->
            val packet = QuicFingerprintFactory.create(fingerprint, "cloudflare.com")

            assertTrue(packet.first().toInt() and 0x80 != 0)
            assertEquals(QuicFingerprintFactory.QuicV1Version, packet.version())
        }
    }

    @Test
    fun vnProbeUsesReservedVersion() {
        val packet = QuicFingerprintFactory.create(QuicFingerprint.VN_PROBE, "cloudflare.com")

        assertEquals(QuicFingerprintFactory.ReservedVersion, packet.version())
    }

    @Test
    fun fingerprintsHaveDifferentConnectionIds() {
        val chrome = QuicFingerprintFactory.create(QuicFingerprint.CHROME_120, "cloudflare.com")
        val firefox = QuicFingerprintFactory.create(QuicFingerprint.FIREFOX_121, "cloudflare.com")

        assertNotEquals(chrome.copyOfRange(6, 14).toList(), firefox.copyOfRange(6, 14).toList())
    }

    private fun ByteArray.version(): Int =
        ((this[1].toInt() and 0xFF) shl 24) or
            ((this[2].toInt() and 0xFF) shl 16) or
            ((this[3].toInt() and 0xFF) shl 8) or
            (this[4].toInt() and 0xFF)
}
