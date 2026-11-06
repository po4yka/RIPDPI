package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.data.diagnostics.DnsMessages

object DnsWireBuilder {
    const val NXDOMAIN = "NXDOMAIN"
    const val PARSE_ERR = "PARSE_ERR"

    fun randomTransactionId(): Int = DnsMessages.randomTransactionId()

    fun buildQuery(
        domain: String,
        transactionId: Int = randomTransactionId(),
    ): ByteArray = DnsMessages.query(domain, transactionId = transactionId)

    fun parseResponse(
        bytes: ByteArray,
        query: ByteArray,
    ): DnsProbeAnswer = DnsWireResponseParser.parse(bytes, query)
}
