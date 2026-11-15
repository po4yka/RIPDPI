package com.poyka.ripdpi.core.detection.checker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class StunBindingResponseDecoderTest {
    @Test
    fun decodesIndependentRfc5769AddressVectors() {
        assertEquals("192.0.2.1", decode(RfcIpv4Response))
        assertEquals("2001:db8:1234:5678:11:2233:4455:6677", decode(RfcIpv6Response))
        assertTrue(StunBindingResponseDecoder.isValidBindingResponse(RfcIpv4Response, TransactionId))
        assertTrue(StunBindingResponseDecoder.isValidBindingResponse(RfcIpv6Response, TransactionId))
    }

    @Test
    fun rejectsEveryTruncatedRfcResponse() {
        for (size in RfcIpv4Response.indices) {
            assertNull("response prefix: $size bytes", decode(RfcIpv4Response.copyOf(size)))
        }
    }

    @Test
    fun rejectsUnalignedOrTrailingBodyBytes() {
        assertNull(decode(response(XorIpv4, advertisedLength = XorIpv4.size + 1)))
        assertNull(decode(response(XorIpv4) + byteArrayOf(0)))
    }

    @Test
    fun rejectsIncompleteAttributeAfterValidAddress() {
        assertNull(decode(response(XorIpv4 + hex("80220004"))))
        assertNull(decode(response(XorIpv4 + hex("802200036162"))))
    }

    @Test
    fun rejectsUnknownRequiredAttributeBeforeAndAfterAddress() {
        val unknownRequired = hex("002f0000")
        assertNull(decode(response(unknownRequired + XorIpv4)))
        assertNull(decode(response(XorIpv4 + unknownRequired)))
    }

    @Test
    fun rejectsIncorrectAddressLengthsEvenAfterValidAddress() {
        val oversizedIpv4 = hex("0020000c0001a147e112a64300000000")
        val shortIpv6 = hex("002000100002a1470113a9faa5d3f179bc25f4b5")
        assertNull(decode(response(oversizedIpv4)))
        assertNull(decode(response(XorIpv4 + oversizedIpv4)))
        assertNull(decode(response(shortIpv6)))
    }

    @Test
    fun acceptsOptionalAttributeWithArbitraryPadding() {
        assertEquals("192.0.2.1", decode(response(hex("8022000361626399") + XorIpv4)))
    }

    @Test
    fun keepsLegacyMappedCompatibilityAndXorPriorityInEitherOrder() {
        assertEquals("198.51.100.7", decode(response(MappedIpv4)))
        assertEquals("2001:db8:0:0:0:0:0:7", decode(response(MappedIpv6)))
        assertFalse(StunBindingResponseDecoder.isValidBindingResponse(response(MappedIpv4), TransactionId))
        assertEquals("192.0.2.1", decode(response(MappedIpv4 + XorIpv4)))
        assertEquals("192.0.2.1", decode(response(XorIpv4 + MappedIpv4)))
    }

    @Test
    fun acceptsKnownIntegrityAttributesWithoutAuthenticationAndRejectsInvalidLengths() {
        val integritySha256 = hex("001c0020") + ByteArray(32)
        assertEquals("192.0.2.1", decode(response(XorIpv4 + integritySha256)))
        assertNull(decode(response(XorIpv4 + hex("001c0014") + ByteArray(20))))
        assertNull(decode(response(XorIpv4 + hex("00080010") + ByteArray(16))))
    }

    @Test
    fun ignoresUnsupportedAddressFamilyAndFindsSupportedAddress() {
        val unsupported = hex("0020000400030000")
        assertNull(decode(response(unsupported)))
        assertEquals("192.0.2.1", decode(response(unsupported + XorIpv4)))
    }

    @Test
    fun rejectsWrongTypeCookieAndTransaction() {
        assertNull(decode(response(XorIpv4).also { it[0] = 0 }))
        assertNull(decode(response(XorIpv4).also { it[4] = 0 }))
        assertNull(decode(response(XorIpv4).also { it[8] = 0 }))
        assertNull(StunBindingResponseDecoder.reflexiveAddress(response(XorIpv4), ByteArray(11)))
    }

    private fun decode(response: ByteArray): String? =
        StunBindingResponseDecoder.reflexiveAddress(response, TransactionId)

    private fun response(
        attributes: ByteArray,
        advertisedLength: Int = attributes.size,
    ): ByteArray =
        ByteBuffer
            .allocate(20 + attributes.size)
            .putShort(0x0101)
            .putShort(advertisedLength.toShort())
            .putInt(0x2112a442)
            .put(TransactionId)
            .put(attributes)
            .array()

    private companion object {
        fun hex(value: String): ByteArray =
            value
                .filterNot(Char::isWhitespace)
                .chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()

        val TransactionId = hex("b7e7a701bc34d686fa87dfae")
        val XorIpv4 = hex("002000080001a147e112a643")
        val MappedIpv4 = hex("0001000800018055c6336407")
        val MappedIpv6 = hex("000100140002805520010db8000000000000000000000007")

        // RFC 5769 sections 2.2 and 2.3. Expected addresses come from the RFC, not a test encoder.
        val RfcIpv4Response =
            hex(
                """
                0101003c2112a442b7e7a701bc34d686fa87dfae
                8022000b7465737420766563746f7220
                002000080001a147e112a643
                000800142b91f599fd9e90c38c7489f92af9ba53f06be7d7
                80280004c07d4c96
                """.trimIndent(),
            )
        val RfcIpv6Response =
            hex(
                """
                010100482112a442b7e7a701bc34d686fa87dfae
                8022000b7465737420766563746f7220
                002000140002a1470113a9faa5d3f179bc25f4b5bed2b9d9
                00080014a382954e4be67bf11784c97c8292c275bfe3ed41
                80280004c8fb0b4c
                """.trimIndent(),
            )
    }
}
