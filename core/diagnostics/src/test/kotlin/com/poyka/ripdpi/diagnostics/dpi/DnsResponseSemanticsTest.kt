package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.diagnostics.DnsResponseOutcome
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class DnsResponseSemanticsTest {
    private val query = DnsWireBuilder.buildQuery("example.com", 42)

    @Test
    fun negativeRepliesRemainDistinct() {
        mapOf(
            0 to DnsResponseOutcome.NODATA,
            2 to DnsResponseOutcome.SERVFAIL,
            3 to DnsResponseOutcome.NXDOMAIN,
            5 to DnsResponseOutcome.REFUSED,
            9 to DnsResponseOutcome.OTHER_RCODE,
        ).forEach { (rcode, expected) ->
            val answer = parse(packet(rcode = rcode))
            assertEquals(expected, answer.response?.outcome)
            assertEquals(rcode, answer.response?.rcode)
            assertTrue(answer.addresses.isEmpty())
        }
    }

    @Test
    fun responseMustMatchIdentityQuestionTypeAndClass() {
        val response = packet(rcode = 3)
        listOf(0, 13, query.lastIndex - 2, query.lastIndex).forEach { index ->
            val corrupted = response.copyOf().also { it[index] = (it[index] + 1).toByte() }
            assertEquals(DnsResponseOutcome.MALFORMED, parse(corrupted).response?.outcome)
        }
        assertEquals(DnsResponseOutcome.MALFORMED, parse(response.copyOf(12)).response?.outcome)
    }

    @Test
    fun truncatedResponseCannotSupplyAddressesOrNodata() {
        val bytes = packet(answers = listOf(record("example.com", 1, ip())), flags = 0x8380)
        assertEquals(DnsResponseOutcome.TRUNCATED, parse(bytes).response?.outcome)
        assertTrue(parse(bytes).addresses.isEmpty())
    }

    @Test
    fun aliasesTtlSoaAndEdeArePreservedWithoutFreeText() {
        val bytes =
            packet(
                answers =
                    listOf(
                        record("example.com", 5, name("alias.example.com")),
                        record("alias.example.com", 1, ip(), ttl = 4294967295L),
                    ),
                authorities =
                    listOf(
                        record(
                            "example.com",
                            6,
                            name("ns.example.com") + name("hostmaster.example.com") + numbers(1, 2, 3, 4, 60),
                            ttl = 120,
                        ),
                    ),
                additional =
                    listOf(
                        record("", 41, short(15) + short(8) + short(17) + "secret".toByteArray(), recordClass = 1232),
                    ),
            )
        val result = parse(bytes)
        assertEquals(listOf("1.2.3.4"), result.addresses)
        assertEquals(listOf("alias.example.com"), result.response?.cnameTargets)
        assertEquals(4294967295L, result.response?.ttlMinSeconds)
        assertNull(result.response?.negativeTtlSeconds)
        assertEquals(listOf(17), result.response?.extendedDnsErrorCodes)
        assertTrue(!result.toString().contains("secret"))
    }

    @Test
    fun unrelatedAnswerAliasLoopAndBrokenPointerAreMalformed() {
        val cases =
            listOf(
                packet(answers = listOf(record("unrelated.example", 1, ip()))),
                packet(
                    answers =
                        listOf(
                            record("example.com", 5, name("alias.example")),
                            record("alias.example", 5, name("example.com")),
                        ),
                ),
                packet(answers = listOf(record("example.com", 5, byteArrayOf(0xc0.toByte(), 0xff.toByte())))),
            )
        cases.forEach { assertEquals(DnsResponseOutcome.MALFORMED, parse(it).response?.outcome) }
    }

    @Test
    fun aliasesWithoutTerminalAddressAndReferralsAreNotNodata() {
        val cases =
            listOf(
                packet(answers = listOf(record("example.com", 5, name("alias.example.com")))),
                packet(authorities = listOf(record("example.com", 2, name("ns.example.com")))),
            )
        cases.forEach { assertEquals(DnsResponseOutcome.NOT_OBSERVED, parse(it).response?.outcome) }
    }

    @Test
    fun wireNegativeSoaBindsToTerminalAliasZone() {
        listOf("other.example", "alias.other.example").forEach { owner ->
            val result = parse(aliasNegativePacket(owner))
            assertEquals(DnsResponseOutcome.NODATA, result.response?.outcome)
            assertEquals(true, result.response?.hasSoa)
            assertEquals(60L, result.response?.negativeTtlSeconds)
            assertEquals(listOf("alias.other.example"), result.response?.cnameTargets)
        }
        listOf("example.com", "unrelated.example", "ther.example").forEach { owner ->
            val result = parse(aliasNegativePacket(owner))
            assertEquals(DnsResponseOutcome.NOT_OBSERVED, result.response?.outcome)
            assertEquals(false, result.response?.hasSoa)
            assertNull(result.response?.negativeTtlSeconds)
        }
    }

    @Test
    fun jsonNegativeSoaBindsToNormalizedTerminalAliasZone() {
        listOf("OTHER.EXAMPLE.", "alias.other.example").forEach { owner ->
            val result = jsonAliasNegative(owner)
            assertEquals(DnsResponseOutcome.NODATA, result.response?.outcome)
            assertEquals(true, result.response?.hasSoa)
            assertEquals(60L, result.response?.negativeTtlSeconds)
            assertEquals(listOf("alias.other.example"), result.response?.cnameTargets)
        }
        listOf("example.com", "unrelated.example", "ther.example").forEach { owner ->
            val result = jsonAliasNegative(owner)
            assertEquals(DnsResponseOutcome.NOT_OBSERVED, result.response?.outcome)
            assertEquals(false, result.response?.hasSoa)
            assertNull(result.response?.negativeTtlSeconds)
        }
    }

    @Test
    fun negativeTtlRequiresNodataOrNxdomain() {
        listOf(0, 3, 2, 5).forEach { rcode ->
            val expectedTtl = if (rcode == 0 || rcode == 3) 60L else null
            val wire = parse(aliasNegativePacket("other.example", rcode))
            val json = jsonAliasNegative("other.example", rcode)
            listOf(wire, json).forEach {
                assertEquals(dnsOutcome(rcode, false), it.response?.outcome)
                assertEquals(true, it.response?.hasSoa)
                assertEquals(expectedTtl, it.response?.negativeTtlSeconds)
            }
        }
    }

    private fun aliasNegativePacket(
        soaOwner: String,
        rcode: Int = 0,
    ): ByteArray =
        packet(
            rcode = rcode,
            answers = listOf(record("example.com", 5, name("alias.other.example"))),
            authorities =
                listOf(
                    record(
                        soaOwner,
                        6,
                        name("ns.other.example") + name("hostmaster.other.example") + numbers(1, 2, 3, 4, 60),
                        ttl = 120,
                    ),
                ),
        )

    private fun jsonAliasNegative(
        soaOwner: String,
        rcode: Int = 0,
    ): DnsProbeAnswer =
        DnsJsonResponseParser.parse(
            """{"Status":$rcode,"Question":[{"name":"example.com.","type":1}],
            "Answer":[{"name":"example.com","type":5,"TTL":60,"data":"ALIAS.OTHER.EXAMPLE."}],
            "Authority":[{"name":"$soaOwner","type":6,"TTL":120,
            "data":"ns.other.example. hostmaster.other.example. 1 2 3 4 60"}]}""",
            "example.com",
            Json,
        )

    @Test
    fun extendedRcodeAndIpv6QueryAreDecoded() {
        val result =
            parse(packet(additional = listOf(record("", 41, byteArrayOf(), ttl = 1L shl 24, recordClass = 1232))))
        assertEquals(16, result.response?.rcode)
        val ipv6Query = query.copyOf().also { it[it.lastIndex - 2] = 28 }
        val response =
            packet(answers = listOf(record("example.com", 28, ByteArray(16).also { it[15] = 1 })))
                .also { it[query.lastIndex - 2] = 28 }
        val ipv6 = DnsWireBuilder.parseResponse(response, ipv6Query)
        assertEquals("AAAA", ipv6.response?.queryType)
        assertEquals(DnsResponseOutcome.ANSWER, ipv6.response?.outcome)
    }

    @Test
    fun jsonPreservesNegativeAndAliasFactsWithNullableAbsentFlags() {
        val result =
            DnsJsonResponseParser.parse(
                """{"Status":3,"Question":[{"name":"example.com.","type":1}],
                "Authority":[{"name":"example.com.","type":6,"TTL":90,
                "data":"ns.example.com. hostmaster.example.com. 1 2 3 4 60"}]}""",
                "example.com",
                Json,
            )
        assertEquals(DnsResponseOutcome.NXDOMAIN, result.response?.outcome)
        assertEquals(60L, result.response?.negativeTtlSeconds)
        assertNull(result.response?.authenticatedData)
        val answer =
            DnsJsonResponseParser.parse(
                """{"Status":0,"Question":[{"name":"example.com","type":1}],
                "Answer":[{"name":"example.com","type":5,"TTL":60,"data":"alias.example.com."},
                {"name":"alias.example.com","type":1,"TTL":30,"data":"1.2.3.4"}]}""",
                "example.com",
                Json,
            )
        assertEquals(listOf("alias.example.com"), answer.response?.cnameTargets)
        assertEquals(30L, answer.response?.ttlMaxSeconds)
    }

    @Test(expected = IllegalArgumentException::class)
    fun jsonQuestionMismatchIsRejected() {
        DnsJsonResponseParser.parse(
            """{"Status":0,"Question":[{"name":"other.example","type":1}]}""",
            "example.com",
            Json,
        )
    }

    private fun parse(bytes: ByteArray) = DnsWireBuilder.parseResponse(bytes, query)

    private fun packet(
        rcode: Int = 0,
        flags: Int = 0x8180,
        answers: List<ByteArray> = emptyList(),
        authorities: List<ByteArray> = emptyList(),
        additional: List<ByteArray> = emptyList(),
    ): ByteArray =
        short(42) + short(flags or rcode) + short(1) + short(answers.size) + short(authorities.size) +
            short(additional.size) +
            query.copyOfRange(12, query.size) +
            (answers + authorities + additional).fold(byteArrayOf()) { total, item -> total + item }

    private fun record(
        owner: String,
        type: Int,
        data: ByteArray,
        ttl: Long = 60,
        recordClass: Int = 1,
    ): ByteArray = name(owner) + short(type) + short(recordClass) + numbers(ttl) + short(data.size) + data

    private fun name(value: String): ByteArray =
        ByteArrayOutputStream()
            .apply {
                if (value.isNotEmpty()) {
                    value.split('.').forEach {
                        write(it.length)
                        write(it.toByteArray())
                    }
                }
                write(0)
            }.toByteArray()

    private fun short(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())

    private fun numbers(vararg values: Long): ByteArray =
        values.fold(byteArrayOf()) { bytes, value ->
            bytes + short((value shr 16).toInt()) + short(value.toInt())
        }

    private fun ip() = byteArrayOf(1, 2, 3, 4)
}
