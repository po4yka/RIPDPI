package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.diagnostics.DnsResponseOutcome
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
        val request = DnsWireBuilder.buildQuery("example.com", 42, edns = true)
        val result = DnsWireBuilder.parseResponse(bytes, request)
        assertNull(parse(bytes).response?.observedExtendedDnsErrorCodes)
        assertEquals(listOf("1.2.3.4"), result.addresses)
        assertEquals(listOf("alias.example.com"), result.response?.cnameTargets)
        assertEquals(0L, result.response?.ttlMinSeconds)
        assertNull(result.response?.negativeTtlSeconds)
        assertEquals(listOf(17), result.response?.observedExtendedDnsErrorCodes)
        assertTrue(!result.toString().contains("secret"))
    }

    @Test
    fun edeAbsenceIsKnownOnlyForACompleteEdnsZeroExchange() {
        val request = DnsWireBuilder.buildQuery("example.com", 42, edns = true)
        val opt = record("", 41, byteArrayOf(), ttl = 0, recordClass = 1232)
        val complete = packet(additional = listOf(opt))

        assertEquals(
            emptyList<Int>(),
            DnsWireBuilder.parseResponse(complete, request).response?.observedExtendedDnsErrorCodes,
        )
        assertNull(DnsWireBuilder.parseResponse(packet(), request).response?.observedExtendedDnsErrorCodes)
        val truncated = packet(flags = 0x8380, additional = listOf(opt))
        assertNull(DnsWireBuilder.parseResponse(truncated, request).response?.observedExtendedDnsErrorCodes)
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
    fun negativeTtlUsesTheEffectiveSoaTtl() {
        val authority =
            record(
                "example.com",
                6,
                name("ns.example.com") + name("hostmaster.example.com") + numbers(1, 2, 3, 4, 30),
                ttl = 0x80000000L,
            )
        val result = parse(packet(rcode = 3, authorities = listOf(authority)))
        assertEquals(DnsResponseOutcome.NXDOMAIN, result.response?.outcome)
        assertEquals(0L, result.response?.negativeTtlSeconds)
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
    fun mappedIpv6AnswerRetainsItsAaaaFamily() {
        val ipv6Query = query.copyOf().also { it[it.lastIndex - 2] = 28 }
        val mapped = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, -64, 0, 2, 1)
        val response =
            packet(answers = listOf(record("example.com", 28, mapped)))
                .also { it[query.lastIndex - 2] = 28 }
        val answer = DnsWireBuilder.parseResponse(response, ipv6Query)

        assertEquals("AAAA", answer.response?.queryType)
        assertEquals(DnsResponseOutcome.ANSWER, answer.response?.outcome)
        assertEquals(listOf("0:0:0:0:0:ffff:c000:201"), answer.addresses)
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

    @Test
    fun jsonRejectsInvalidSoaOwnerAndEveryMalformedSoaField() {
        val aliasOnly = """{"Status":0,"Question":[{"name":"example.com.","type":1}],
            "Answer":[{"name":"example.com","type":5,"TTL":60,"data":"alias.example.com."}]"""
        val goodSoa = """{"name":".","type":6,"TTL":120,"data":"ns.example.com. hostmaster.example.com. 1 2 3 4 60"}"""
        assertEquals(
            DnsResponseOutcome.NOT_OBSERVED,
            DnsJsonResponseParser.parse("$aliasOnly}", "example.com", Json).response?.outcome,
        )
        val rootSoa = DnsJsonResponseParser.parse("$aliasOnly,\"Authority\":[$goodSoa]}", "example.com", Json)
        assertEquals(DnsResponseOutcome.NODATA, rootSoa.response?.outcome)
        assertEquals(60L, rootSoa.response?.negativeTtlSeconds)
        val invalidAuthorities =
            listOf(
                goodSoa.replace(""""name":".",""", ""),
                goodSoa.replace(""""name":".",""", """"name":"","""),
                goodSoa.replace("1 2 3 4 60", "serial 2 3 4 60"),
                goodSoa.replace("1 2 3 4 60", "1 4294967296 3 4 60"),
                goodSoa.replace("hostmaster.example.com.", "hostmaster.example.com.."),
            )
        invalidAuthorities.forEach { soa ->
            assertThrows(IllegalArgumentException::class.java) {
                DnsJsonResponseParser.parse("$aliasOnly,\"Authority\":[$soa]}", "example.com", Json)
            }
        }
    }

    @Test
    fun jsonRejectsWrongPrimitiveTypesAndInvalidHostNames() {
        val payload = """{"Status":0,"TC":false,"Question":[{"name":"example.com.","type":1}],
            "Answer":[{"name":"example.com","type":1,"TTL":60,"data":"192.0.2.10"}]}"""
        val malformed =
            listOf(
                payload.replace(""""Status":0""", """"Status":"0""""),
                payload.replace(""""type":1""", """"type":"1""""),
                payload.replace(""""TC":false""", """"TC":"false""""),
                payload.replace(""""TTL":60""", """"TTL":-1"""),
                payload.replace(""""TTL":60""", """"TTL":"60""""),
                payload.replace("example.com.", "example.com.."),
                payload.replace(
                    """"type":1,"TTL":60,"data":"192.0.2.10"""",
                    """"type":5,"TTL":60,"data":"not a host"""",
                ),
            )
        malformed.forEach {
            assertThrows(IllegalArgumentException::class.java) {
                DnsJsonResponseParser.parse(it, "example.com", Json)
            }
        }
        val highTtl = DnsJsonResponseParser.parse(payload.replace("60", "2147483648"), "example.com", Json)
        assertEquals(listOf("192.0.2.10"), highTtl.addresses)
        assertEquals(0L, highTtl.response?.ttlMinSeconds)
    }

    @Test
    fun jsonLimitsNestingAndRecordCount() {
        val question = """"Status":0,"Question":[{"name":"example.com","type":1}]"""
        val nested = "{$question,\"Extension\":${"[".repeat(33)}0${"]".repeat(33)}}"
        val record = """{"name":"example.com","type":1,"TTL":60,"data":"192.0.2.10"}"""
        val tooMany = List(513) { record }.joinToString(",")
        val atLimit = List(512) { record }.joinToString(",")
        val answer = DnsJsonResponseParser.parse("{$question,\"Answer\":[$atLimit]}", "example.com", Json)
        assertEquals(listOf("192.0.2.10"), answer.addresses)
        listOf(nested, "{$question,\"Answer\":[$tooMany]}").forEach {
            assertThrows(IllegalArgumentException::class.java) {
                DnsJsonResponseParser.parse(it, "example.com", Json)
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun jsonQuestionMismatchIsRejected() {
        DnsJsonResponseParser.parse(
            """{"Status":0,"Question":[{"name":"other.example","type":1}]}""",
            "example.com",
            Json,
        )
    }

    @Test
    fun malformedAuthorityAndAdditionalRdataCannotSupplyAnAnswer() {
        val answers = listOf(record("example.com", 1, ip()))
        val malformedRecords =
            listOf(
                record("example.com", 6, name("ns.example.com") + byteArrayOf(0)),
                record("example.com", 5, byteArrayOf(0xc0.toByte(), 0xff.toByte())),
                record("example.com", 28, byteArrayOf(1, 2, 3)),
            )
        malformedRecords.forEach { malformed ->
            assertEquals(
                DnsResponseOutcome.MALFORMED,
                parse(packet(answers = answers, additional = listOf(malformed))).response?.outcome,
            )
        }
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

    @Test
    fun highBitExtendedRcodeCannotBecomeSuccessfulAnswer() {
        for (extended in listOf(128, 255)) {
            val result =
                parse(
                    packet(
                        answers = listOf(record("example.com", 1, ip())),
                        additional =
                            listOf(
                                record(
                                    "",
                                    41,
                                    byteArrayOf(),
                                    ttl = extended.toLong() shl 24,
                                    recordClass = 1232,
                                ),
                            ),
                    ),
                )
            assertEquals(extended shl 4, result.response?.rcode)
            assertEquals(DnsResponseOutcome.OTHER_RCODE, result.response?.outcome)
            assertTrue(result.addresses.isEmpty())
        }
    }

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
