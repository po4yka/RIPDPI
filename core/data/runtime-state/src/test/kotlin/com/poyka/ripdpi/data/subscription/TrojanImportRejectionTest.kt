package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrojanImportRejectionTest {
    @Test
    fun `singbox trojan without verified tls stays inert`() {
        listOf(
            "",
            """"tls":{"server_name":"tj.example"},""",
            """"tls":{"enabled":false},""",
            """"tls":{"enabled":true,"reality":{"enabled":true,"public_key":"k"}},""",
            """"tls":{"enabled":true,"insecure":true},""",
        ).forEach { tls -> assertTrue(tls, singBox(tls) is ProxyProfile.RawConfig) }
        assertTrue(singBox(""""tls":{"enabled":true,"server_name":"tj.example"},""") is ProxyProfile.Trojan)
    }

    @Test
    fun `clash trojan with reality or skipped verification is an error`() {
        listOf(
            "reality-opts:\n      public-key: abc",
            "skip-cert-verify: true",
            "skip-cert-verify: True",
        ).forEach { fields ->
            val parsed = clash(fields)
            assertTrue(fields, parsed.profiles.isEmpty())
            assertFalse(fields, parsed.errors.isEmpty())
        }
        assertTrue(clash("skip-cert-verify: false").profiles.single() is ProxyProfile.Trojan)
    }

    private fun singBox(tls: String): ProxyProfile =
        (
            SingBoxSubscriptionParser.parse(
                """{"type":"trojan","tag":"x","server":"tj.example","server_port":443,"password":"p",$tls"_":0}""",
                "group",
            ) as SingBoxParseResult.Success
        ).profiles.single()

    private fun clash(fields: String) =
        ClashSubscriptionParser.parse(
            "proxies:\n  - name: tj\n    type: trojan\n    server: tj.example\n" +
                "    port: 443\n    password: p\n    $fields\n",
            "group",
        )
}
