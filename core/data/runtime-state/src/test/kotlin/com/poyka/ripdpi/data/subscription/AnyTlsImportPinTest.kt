package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnyTlsImportPinTest {
    private val pin = "ab".repeat(32)

    @Test
    fun `singbox anytls with insecure tls stays inert`() {
        assertTrue(singBox(""""tls":{"enabled":true,"insecure":true},""") is ProxyProfile.RawConfig)
        assertTrue(singBox(""""tls":{"enabled":true,"server_name":"a.example"},""") is ProxyProfile.AnyTls)
        assertNull((singBox("") as ProxyProfile.AnyTls).pinSha256)
    }

    @Test
    fun `clash anytls skip-cert-verify needs a valid fingerprint pin`() {
        listOf(
            "skip-cert-verify: true",
            "skip-cert-verify: true\n    fingerprint: not-a-pin",
            "fingerprint: not-a-pin",
        ).forEach { fields ->
            val parsed = clash(fields)
            assertTrue(fields, parsed.profiles.isEmpty())
            assertFalse(fields, parsed.errors.isEmpty())
        }
        val pinned = clash("skip-cert-verify: true\n    fingerprint: ${pin.uppercase()}").profiles.single()
        assertEquals(pin, (pinned as ProxyProfile.AnyTls).pinSha256)
        assertNull((clash("skip-cert-verify: false").profiles.single() as ProxyProfile.AnyTls).pinSha256)
    }

    private fun singBox(tls: String): ProxyProfile =
        (
            SingBoxSubscriptionParser.parse(
                """{"type":"anytls","tag":"x","server":"a.example","server_port":443,"password":"p",$tls"_":0}""",
                "group",
            ) as SingBoxParseResult.Success
        ).profiles.single()

    private fun clash(fields: String) =
        ClashSubscriptionParser.parse(
            "proxies:\n  - name: at\n    type: anytls\n    server: a.example\n" +
                "    port: 443\n    password: p\n    $fields\n",
            "group",
        )
}
