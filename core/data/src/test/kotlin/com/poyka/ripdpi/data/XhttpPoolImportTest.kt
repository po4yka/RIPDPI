package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.subscription.SingBoxParseResult
import com.poyka.ripdpi.data.subscription.SingBoxSubscriptionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XhttpPoolImportTest {
    @Test
    fun `bounded stream-up pools retain all admitted limits`() {
        (1..4).forEach { limit ->
            val result =
                SingBoxSubscriptionParser.parse(
                    bundle(pool(limit.toString())),
                    "g",
                ) as SingBoxParseResult.Success
            val profile = result.profiles.single() as ProxyProfile.Vless
            assertEquals(limit, profile.xhttpMaxConnections)
            org.junit.Assert.assertNull(
                com.poyka.ripdpi.data.uri.ProxyUriCodec
                    .encodeOrNull(profile),
            )
        }
    }

    @Test
    fun `invalid XHTTP tuning refuses the recognized bundle`() {
        val invalid =
            listOf("0", "5", "-1", "true", "1.5", "\"2\"", "null")
                .map { pool(it) } +
                listOf(
                    pool("2").replace("\"hMaxRequestTimes\":0", "\"hMaxRequestTimes\":1"),
                    pool("2").replace("\"hMaxReusableSecs\":0", "\"hMaxReusableSecs\":1"),
                    pool("2").replace("\"cMaxReuseTimes\":0", "\"cMaxReuseTimes\":1"),
                    pool("2").dropLast(1) + ",\"extra\":0}",
                    "{}",
                    "null",
                    "[]",
                )
        invalid.forEach { tuning ->
            assertTrue(tuning, SingBoxSubscriptionParser.parse(bundle(tuning), "g") is SingBoxParseResult.Error)
        }
        listOf("auto", "stream-one", "packet-up").forEach { mode ->
            assertTrue(mode, SingBoxSubscriptionParser.parse(bundle(pool("2"), mode), "g") is SingBoxParseResult.Error)
        }
    }

    private fun pool(limit: String) =
        """{"maxConnections":$limit,"hMaxRequestTimes":0,"hMaxReusableSecs":0,"cMaxReuseTimes":0}"""

    private fun bundle(
        pool: String,
        mode: String = "stream-up",
    ) = """{"outbounds":[{"type":"vless","tag":"p1","server":"relay.example","server_port":443,
            "uuid":"55555555-5555-5555-5555-555555555555","tls":{"enabled":true,"server_name":"relay.example"},
            "transport":{"type":"xhttp","path":"/stream","mode":"$mode","xmux":$pool}}]}"""
}
