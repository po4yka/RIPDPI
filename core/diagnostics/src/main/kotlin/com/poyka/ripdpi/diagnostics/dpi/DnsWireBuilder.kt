package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.data.diagnostics.DnsMessages

internal const val DohTransactionId = 0

object DnsWireBuilder {
    const val NXDOMAIN = "NXDOMAIN"
    const val PARSE_ERR = "PARSE_ERR"

    fun randomTransactionId(): Int = DnsMessages.randomTransactionId()

    fun buildQuery(
        domain: String,
        transactionId: Int = randomTransactionId(),
        edns: Boolean = false,
    ): ByteArray = DnsMessages.query(domain, transactionId = transactionId, edns = edns)

    fun parseResponse(
        bytes: ByteArray,
        query: ByteArray,
    ): DnsProbeAnswer = DnsWireResponseParser.parse(bytes, query)
}
