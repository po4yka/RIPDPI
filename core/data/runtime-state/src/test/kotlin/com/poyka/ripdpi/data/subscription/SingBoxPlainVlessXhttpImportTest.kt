package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyProfile
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxPlainVlessXhttpImportTest {
    @Test
    fun `plain vless xhttp with enabled verified tls imports as a native profile`() {
        assertTrue(parse(""""tls":{"enabled":true,"server_name":"relay.example"},""") is ProxyProfile.Vless)
    }

    @Test
    fun `plain vless xhttp without tls or with options native cannot honour stays inert`() {
        listOf(
            "",
            """"tls":{"enabled":false,"server_name":"relay.example"},""",
            """"tls":{"server_name":"relay.example"},""",
            """"tls":{"enabled":true,"server_name":"relay.example","insecure":true},""",
            """"tls":{"enabled":true,"server_name":"relay.example","ech":{"enabled":true}},""",
            """"tls":{"enabled":true,"server_name":"relay.example","certificate":"pem"},""",
        ).forEach { tls -> assertTrue(tls, parse(tls) is ProxyProfile.RawConfig) }
    }

    private fun parse(tls: String): ProxyProfile =
        (
            SingBoxSubscriptionParser.parse(
                """{"type":"vless","tag":"x","server":"relay.example","server_port":443,""" +
                    """"uuid":"55555555-5555-5555-5555-555555555555",$tls""" +
                    """"transport":{"type":"xhttp","path":"/stream","mode":"stream-one"}}""",
                "group",
            ) as SingBoxParseResult.Success
        ).profiles.single()
}
