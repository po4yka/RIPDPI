package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpsRrResolverTest {
    @Test
    fun parsesEchSvcParamFromHttpsRecord() {
        val echConfig = byteArrayOf(0x00, 0x02, 0x03, 0x04)
        val record =
            Rfc9460HttpsRecordCodec.parseHttpsRdata(
                httpsRdata(
                    priority = 1,
                    targetName = ".",
                    params = listOf(svcParam(SvcParamKeyEch, echConfig)),
                ),
            )

        val parsed = requireNotNull(record)
        assertEquals(1, parsed.priority)
        assertEquals(".", parsed.targetName)
        assertArrayEquals(echConfig, parsed.echConfig)
    }

    @Test
    fun malformedSvcParamsAreRejected() {
        val echConfig = byteArrayOf(0x00, 0x02, 0x03, 0x04)
        val alpn = svcParam(SvcParamKeyAlpn, byteArrayOf(0x02, 'h'.code.toByte(), '2'.code.toByte()))
        val outOfOrder = listOf(svcParam(SvcParamKeyEch, echConfig), alpn)
        val duplicateEch = listOf(svcParam(SvcParamKeyEch, echConfig), svcParam(SvcParamKeyEch, echConfig))
        val badEchList = listOf(svcParam(SvcParamKeyEch, byteArrayOf(0x01, 0x02, 0x03, 0x04)))

        for (params in listOf(outOfOrder, duplicateEch, badEchList)) {
            assertEquals(null, Rfc9460HttpsRecordCodec.parseHttpsRdata(httpsRdata(1, ".", params)))
        }
        assertArrayEquals(
            echConfig,
            Rfc9460HttpsRecordCodec
                .parseHttpsRdata(
                    httpsRdata(1, ".", listOf(alpn, svcParam(SvcParamKeyEch, echConfig))),
                )?.echConfig,
        )
    }

    @Test
    fun aliasModeRecordSvcParamsAreIgnored() {
        val record =
            Rfc9460HttpsRecordCodec.parseHttpsRdata(
                httpsRdata(0, "alias.example", listOf(svcParam(SvcParamKeyEch, byteArrayOf(0x00, 0x01, 0x07)))),
            )

        assertEquals("alias.example", record?.targetName)
        assertEquals(null, record?.echConfig)
    }

    @Test
    fun multiplePrioritiesPicksHighestPriorityRecordWithEch() =
        runTest {
            val lowPriority = HttpsRrRecord(priority = 8, targetName = ".", echConfig = byteArrayOf(8))
            val highPriority = HttpsRrRecord(priority = 1, targetName = ".", echConfig = byteArrayOf(1))
            val resolver = HttpsRrResolver(query = HttpsRrQuery { listOf(lowPriority, highPriority) })

            val selected = resolver.fetch("cloudflare.com")

            assertEquals(highPriority, selected)
        }

    @Test
    fun recordWithoutEchConfigIsIgnoredWhenSelecting() =
        runTest {
            val noEch = HttpsRrRecord(priority = 1, targetName = ".", echConfig = null)
            val withEch = HttpsRrRecord(priority = 5, targetName = ".", echConfig = byteArrayOf(5))
            val resolver = HttpsRrResolver(query = HttpsRrQuery { listOf(noEch, withEch) })

            val selected = resolver.fetch("fastly.com")

            assertEquals(withEch, selected)
        }

    @Test
    fun fallbackQueryIsUsedWhenPrimaryQueryFails() =
        runTest {
            val fallback = HttpsRrRecord(priority = 1, targetName = ".", echConfig = byteArrayOf(9))
            val resolver =
                HttpsRrResolver(
                    query = HttpsRrQuery { error("DoH unavailable") },
                    fallbackQuery = HttpsRrQuery { listOf(fallback) },
                )

            val selected = resolver.fetch("cloudflare-ech.com")

            assertEquals(fallback, selected)
        }

    @Test
    fun fallbackQueryIsUsedWhenPrimaryHasNoEchConfig() =
        runTest {
            val fallback = HttpsRrRecord(priority = 1, targetName = ".", echConfig = byteArrayOf(9))
            val resolver =
                HttpsRrResolver(
                    query = HttpsRrQuery { listOf(HttpsRrRecord(priority = 1, targetName = ".", echConfig = null)) },
                    fallbackQuery = HttpsRrQuery { listOf(fallback) },
                )

            val selected = resolver.fetch("cloudflare-ech.com")

            assertEquals(fallback, selected)
        }

    @Test
    fun primaryQueryCancellationIsPropagated() =
        runTest {
            val resolver =
                HttpsRrResolver(
                    query = HttpsRrQuery { throw CancellationException("cancelled") },
                    fallbackQuery =
                        HttpsRrQuery {
                            listOf(HttpsRrRecord(priority = 1, targetName = ".", echConfig = byteArrayOf(9)))
                        },
                )

            val error = runCatching { resolver.fetch("cloudflare-ech.com") }.exceptionOrNull()

            assertTrue(error is CancellationException)
        }

    @Test
    fun fallbackQueryCancellationIsPropagated() =
        runTest {
            val resolver =
                HttpsRrResolver(
                    query = HttpsRrQuery { emptyList() },
                    fallbackQuery = HttpsRrQuery { throw CancellationException("cancelled") },
                )

            val error = runCatching { resolver.fetch("cloudflare-ech.com") }.exceptionOrNull()

            assertTrue(error is CancellationException)
        }

    @Test
    fun buildHttpsQueryUsesDnsType65() {
        val query = HttpsDnsWireBuilder.buildQuery("example.com", transactionId = 0x1234)

        assertEquals(0x00.toByte(), query[query.lastIndex - 3])
        assertEquals(0x41.toByte(), query[query.lastIndex - 2])
        assertEquals(0x00.toByte(), query[query.lastIndex - 1])
        assertEquals(0x01.toByte(), query[query.lastIndex])
    }

    @Test
    fun wireHttpsResponseMustMatchQuestionOwnerAndFlags() {
        val query = HttpsDnsWireBuilder.buildQuery("example.com", 0x1234)
        val valid = httpsResponse(query)
        val ech = byteArrayOf(0, 2, 3, 4)
        assertArrayEquals(ech, HttpsDnsWireParser.parseResponse(valid, query).single().echConfig)
        assertArrayEquals(ech, HttpsDnsWireParser.parseSystemResponse(valid, "example.com").single().echConfig)

        val wrongHost = HttpsDnsWireBuilder.buildQuery("unrelated.example", 0x1234)
        val wrongType = DnsWireBuilder.buildQuery("example.com", 0x1234)
        val wrongClass = query.copyOf().apply { this[lastIndex] = 3 }
        val wrongId = valid.copyOf().apply { this[0] = 0 }
        val truncated = valid.copyOf().apply { this[2] = 0x83.toByte() }
        for (response in listOf(
            httpsResponse(wrongHost),
            httpsResponse(wrongType),
            httpsResponse(wrongClass),
            httpsResponse(query, owner = "unrelated.example"),
            wrongId,
            truncated,
            valid + byteArrayOf(0),
        )) {
            assertTrue(HttpsDnsWireParser.parseResponse(response, query).isEmpty())
        }
        assertTrue(HttpsDnsWireParser.parseSystemResponse(valid, "unrelated.example").isEmpty())
    }

    @Test
    fun wireHttpsRejectsCompressedTargetAndInvalidParameterOrder() {
        val query = HttpsDnsWireBuilder.buildQuery("example.com", 0x1234)
        val ech = svcParam(SvcParamKeyEch, byteArrayOf(0, 2, 3, 4))
        val alpn = svcParam(SvcParamKeyAlpn, byteArrayOf(2, 'h'.code.toByte(), '2'.code.toByte()))
        val malformed =
            listOf(
                u16(1) + byteArrayOf(0xc0.toByte(), 0x0c) + ech,
                httpsRdata(1, ".", listOf(ech, alpn)),
                httpsRdata(1, ".", listOf(ech, ech)),
                httpsRdata(1, ".", listOf(svcParam(SvcParamKeyEch, byteArrayOf(0, 5, 1)))),
            )
        malformed.forEach { rdata ->
            assertTrue(HttpsDnsWireParser.parseResponse(httpsResponse(query, rdata = rdata), query).isEmpty())
        }
    }

    private fun httpsResponse(
        query: ByteArray,
        owner: String = "example.com",
        rdata: ByteArray = httpsRdata(1, ".", listOf(svcParam(SvcParamKeyEch, byteArrayOf(0, 2, 3, 4)))),
    ): ByteArray =
        query.copyOfRange(0, 2) + u16(0x8180) + u16(1) + u16(1) + u16(0) + u16(0) +
            query.copyOfRange(12, query.size) + dnsName(owner) + u16(65) + u16(1) +
            byteArrayOf(0, 0, 0, 60) + u16(rdata.size) + rdata

    private fun httpsRdata(
        priority: Int,
        targetName: String,
        params: List<ByteArray>,
    ): ByteArray = u16(priority) + dnsName(targetName) + params.fold(ByteArray(0)) { acc, param -> acc + param }

    private fun svcParam(
        key: Int,
        value: ByteArray,
    ): ByteArray = u16(key) + u16(value.size) + value

    private fun dnsName(name: String): ByteArray =
        if (name == ".") {
            byteArrayOf(0)
        } else {
            name
                .trimEnd('.')
                .split('.')
                .flatMap { label -> listOf(label.length.toByte()) + label.encodeToByteArray().toList() }
                .toByteArray() + byteArrayOf(0)
        }

    private fun u16(value: Int): ByteArray = byteArrayOf((value shr 8).toByte(), value.toByte())

    private companion object {
        private const val SvcParamKeyEch = 5
        private const val SvcParamKeyAlpn = 1
    }
}
