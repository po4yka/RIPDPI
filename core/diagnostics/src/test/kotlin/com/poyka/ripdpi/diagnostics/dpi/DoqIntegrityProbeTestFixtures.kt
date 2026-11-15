package com.poyka.ripdpi.diagnostics.dpi

import java.io.ByteArrayOutputStream

internal object DoqIntegrityProbeTestFixtures {
    fun dnsResponse(
        domain: String,
        transactionId: Int,
        ip: String?,
        rcode: Int = 0,
    ): ByteArray {
        val question =
            DnsWireBuilder
                .buildQuery(domain, transactionId)
                .copyOfRange(12, 12 + domain.length + 6)
        val body =
            ByteArrayOutputStream()
                .apply {
                    write((transactionId shr Byte.SIZE_BITS) and ByteMask)
                    write(transactionId and ByteMask)
                    write(0x81)
                    write(0x80 or rcode)
                    write(0x00)
                    write(0x01)
                    write(0x00)
                    write(if (ip == null) 0 else 1)
                    write(0x00)
                    write(0x00)
                    write(0x00)
                    write(0x00)
                    write(question)
                    if (ip != null) {
                        write(0xC0)
                        write(0x0C)
                        write(0x00)
                        write(0x01)
                        write(0x00)
                        write(0x01)
                        write(0x00)
                        write(0x00)
                        write(0x00)
                        write(0x3C)
                        write(0x00)
                        write(0x04)
                        ip.split('.').forEach { octet -> write(octet.toInt()) }
                    }
                }.toByteArray()
        return ByteArrayOutputStream(body.size + DoqLengthPrefixBytes)
            .apply {
                write((body.size shr Byte.SIZE_BITS) and ByteMask)
                write(body.size and ByteMask)
                write(body)
            }.toByteArray()
    }

    private const val ByteMask = 0xFF
    private const val DoqLengthPrefixBytes = 2
}
