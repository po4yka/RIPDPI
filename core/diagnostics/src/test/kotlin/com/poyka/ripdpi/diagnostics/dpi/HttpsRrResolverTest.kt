package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpsRrResolverTest {
    @Test
    fun parsesEchSvcParamFromHttpsRecord() {
        val echConfig = byteArrayOf(0x01, 0x02, 0x03, 0x04)
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
            val noEch = HttpsRrRecord(priority = 0, targetName = ".", echConfig = null)
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
                    query = HttpsRrQuery { listOf(HttpsRrRecord(priority = 0, targetName = ".", echConfig = null)) },
                    fallbackQuery = HttpsRrQuery { listOf(fallback) },
                )

            val selected = resolver.fetch("cloudflare-ech.com")

            assertEquals(fallback, selected)
        }

    @Test
    fun buildHttpsQueryUsesDnsType65() {
        val query = HttpsDnsWireBuilder.buildQuery("example.com", transactionId = 0x1234)

        assertEquals(0x00.toByte(), query[query.lastIndex - 3])
        assertEquals(0x41.toByte(), query[query.lastIndex - 2])
        assertEquals(0x00.toByte(), query[query.lastIndex - 1])
        assertEquals(0x01.toByte(), query[query.lastIndex])
    }

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
    }
}
