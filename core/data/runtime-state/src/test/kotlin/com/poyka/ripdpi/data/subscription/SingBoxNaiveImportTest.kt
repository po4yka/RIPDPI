package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.validateNativeRelayProfileResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxNaiveImportTest {
    @Test
    fun `naive outbound imports its options as a native profile`() {
        val profile =
            parse(
                """"quic":true,"insecure_concurrency":2,""" +
                    """"extra_headers":{"X-Token":"one","X-List":["a","b"]},"udp_over_tcp":false,""",
            ) as ProxyProfile.Naive

        assertEquals("naive.example.test", profile.server)
        assertEquals(443, profile.serverPort)
        assertEquals("device", profile.username)
        assertEquals("secret", profile.password)
        assertEquals("X-Token: one\r\nX-List: a\r\nX-List: b", profile.extraHeaders)
        assertTrue(profile.quic)
        assertEquals(2, profile.concurrency)
        assertEquals(Result.success(true), validateNativeRelayProfileResult(profile))
    }

    @Test
    fun `naive outbound with unset concurrency uses one connection`() {
        listOf("", """"insecure_concurrency":0,""").forEach { options ->
            assertEquals(options, 1, (parse(options) as ProxyProfile.Naive).concurrency)
        }
    }

    @Test
    fun `naive outbound the helper cannot run as written stays inert`() {
        val verified = """"tls":{"enabled":true,"server_name":"naive.example.test"}"""
        listOf(
            """"tls":{"enabled":true,"server_name":"other.example.test"}""" to "",
            """"tls":{"enabled":true,"insecure":true}""" to "",
            """"tls":{"enabled":true,"certificate":"pem"}""" to "",
            """"tls":{"enabled":true,"ech":{"enabled":true}}""" to "",
            """"tls":{"enabled":false}""" to "",
            verified to """"udp_over_tcp":true,""",
            verified to """"udp_over_tcp":{"enabled":true},""",
            verified to """"insecure_concurrency":5,""",
            verified to """"quic_congestion_control":"bbr2",""",
            verified to """"extra_headers":{"Host":"other.example.test"},""",
            verified to """"username":"a:b",""",
            """"tls":{"enabled":true,"certificate_path":"/ca.pem"}""" to "",
            """"tls":{"enabled":true,"disable_sni":true}""" to "",
            verified to """"server_port":0,""",
            verified to """"server_port":70000,""",
            verified to """"password":123456,""",
            verified to """"insecure_concurrency":"two",""",
            verified to """"quic":"yes",""",
            verified to """"stream_receive_window":{"size":1},""",
            verified to """"extra_headers":"X-Token: one",""",
            verified to """"extra_headers":{"X-Token":1},""",
            verified to """"extra_headers":{"X-A":"1\nX-B: 2"},""",
            verified to """"extra_headers":{"X-A:b":"1"},""",
        ).forEach { (tls, options) -> assertTrue("$tls $options", parse(options, tls) is ProxyProfile.RawConfig) }
    }

    private fun parse(
        options: String,
        tls: String = """"tls":{"enabled":true}""",
    ): ProxyProfile =
        (
            SingBoxSubscriptionParser.parse(
                """{"type":"naive","tag":"n","server":"naive.example.test","server_port":443,""" +
                    """"username":"device","password":"secret",$options$tls}""",
                "group",
            ) as SingBoxParseResult.Success
        ).profiles.single()
}
