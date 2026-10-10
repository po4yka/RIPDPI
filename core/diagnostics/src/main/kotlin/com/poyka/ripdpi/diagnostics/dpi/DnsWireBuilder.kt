package com.poyka.ripdpi.diagnostics.dpi

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import kotlin.random.asKotlinRandom

object DnsWireBuilder {
    const val NXDOMAIN = "NXDOMAIN"
    const val PARSE_ERR = "PARSE_ERR"

    private val random = SecureRandom().asKotlinRandom()

    fun randomTransactionId(): Int = random.nextInt(0, TransactionIdBound)

    fun buildQuery(
        domain: String,
        transactionId: Int = randomTransactionId(),
    ): ByteArray {
        val normalized = domain.trimEnd('.')
        require(normalized.length in 1..MaxDnsNameLength && normalized.all { it.code < AsciiLimit })
        val labels = normalized.split('.')
        val output = ByteArrayOutputStream()
        output.write((transactionId shr Byte.SIZE_BITS) and ByteMask)
        output.write(transactionId and ByteMask)
        output.write(0x01)
        output.write(0x00)
        output.write(0x00)
        output.write(0x01)
        output.write(0x00)
        output.write(0x00)
        output.write(0x00)
        output.write(0x00)
        output.write(0x00)
        output.write(0x00)
        labels.forEach { label ->
            require(
                label.length in 1..MaxDnsLabelLength &&
                    label.all {
                        it.isLetterOrDigit() || it in "-_"
                    },
            ) { "DNS label is too long: $label" }
            output.write(label.length)
            output.write(label.toByteArray(StandardCharsets.US_ASCII))
        }
        output.write(0x00)
        output.write(0x00)
        output.write(ARecordType)
        output.write(0x00)
        output.write(InClass)
        return output.toByteArray()
    }

    fun parseResponse(
        bytes: ByteArray,
        transactionId: Int,
    ): List<String> = DnsWireResponseParser.parseLegacy(bytes, transactionId).legacyRecords()

    fun parseResponse(
        bytes: ByteArray,
        query: ByteArray,
    ): DnsProbeAnswer = DnsWireResponseParser.parse(bytes, query)

    private const val TransactionIdBound = 0x1_0000
    private const val ByteMask = 0xFF
    private const val ARecordType = 1
    private const val InClass = 1
    private const val MaxDnsNameLength = 253
    private const val AsciiLimit = 128
    private const val MaxDnsLabelLength = 63
}
