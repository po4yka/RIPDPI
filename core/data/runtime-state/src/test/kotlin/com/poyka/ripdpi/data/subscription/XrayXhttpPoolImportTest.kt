package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayXhttpPoolImportTest {
    @Test
    fun `TLS and Reality XHTTP imports preserve bounded pools`() {
        listOf("tls", "reality").forEach { security ->
            (1..4).forEach { limit ->
                val parsed = parse(outbound(security, pool(limit.toString())))
                assertTrue(parsed.skipped.toString(), parsed.skipped.isEmpty())
                val profile = parsed.profiles.single()
                val actual =
                    when (profile) {
                        is ProxyProfile.Vless -> profile.xhttpMaxConnections
                        is ProxyProfile.VlessReality -> profile.xhttpMaxConnections
                        else -> error("Unexpected profile $profile")
                    }
                assertEquals(limit, actual)
            }
        }
    }

    @Test
    fun `invalid XHTTP pools are skipped with a typed reason without leaking values`() {
        val invalid =
            listOf("0", "5", "-1", "true", "1.5", "\"2\"", "null").map(::pool) +
                listOf(
                    pool("2").replace("\"hMaxRequestTimes\":0", "\"hMaxRequestTimes\":1"),
                    pool("2").replace("\"hMaxReusableSecs\":0", "\"hMaxReusableSecs\":1"),
                    pool("2").replace("\"cMaxReuseTimes\":0", "\"cMaxReuseTimes\":1"),
                    pool("2").dropLast(1) + ",\"private-marker\":0}",
                    "{}",
                    "null",
                    "[]",
                )
        listOf("tls", "reality").forEach { security ->
            val inputs =
                invalid.map { outbound(security, it) } +
                    listOf("auto", "stream-one", "packet-up").map { outbound(security, pool("2"), it) }
            inputs.forEach { input ->
                val parsed = parse(input)
                assertTrue(parsed.profiles.isEmpty())
                assertEquals(XraySkipReason.UNSUPPORTED_TRANSPORT, parsed.skipped.single().reason)
                assertEquals("XHTTP pool settings", parsed.skipped.single().detail)
            }
        }
    }

    private fun parse(input: String) = XrayConfigImportParser.parse(input, "g") as XrayConfigImportResult.Translated

    private fun pool(limit: String) =
        """{"maxConnections":$limit,"hMaxRequestTimes":0,"hMaxReusableSecs":0,"cMaxReuseTimes":0}"""

    private fun outbound(
        security: String,
        pool: String,
        mode: String = "stream-up",
    ): String {
        val settings =
            if (security == "reality") {
                """"realitySettings":{"publicKey":"AbCdEf0123456789AbCdEf0123456789AbCdEf01234"}"""
            } else {
                """"tlsSettings":{"serverName":"relay.example"}"""
            }
        return """{"protocol":"vless","tag":"bounded",
          "settings":{"address":"relay.example","port":443,"id":"550e8400-e29b-41d4-a716-446655440000","flow":""},
          "streamSettings":{"network":"xhttp","security":"$security",$settings,
          "xhttpSettings":{"path":"/stream","mode":"$mode","xmux":$pool}}}"""
    }
}
