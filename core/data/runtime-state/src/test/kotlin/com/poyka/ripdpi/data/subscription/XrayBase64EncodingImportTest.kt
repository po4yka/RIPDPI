package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class XrayBase64EncodingImportTest {
    @Test
    fun `malformed UTF-8 JSON credentials are rejected without replacement`() {
        val json =
            """{"protocol":"trojan","settings":{"servers":[{"address":"t.example","port":443,
            "password":"broken"}]},"streamSettings":{"security":"tls"}}"""

        assertMalformedEncodingRejected(json)
    }

    @Test
    fun `malformed UTF-8 link credentials are rejected without replacement`() {
        assertMalformedEncodingRejected("trojan://broken@t.example:443#node")
    }

    @Test
    fun `valid UTF-8 Base64 credentials retain non ASCII text`() {
        val text = "trojan://пароль@t.example:443#node"
        val result = XrayConfigImportParser.parse(encode(text.toByteArray(Charsets.UTF_8)), "group")
        val profile = (result as XrayConfigImportResult.Translated).profiles.single() as ProxyProfile.Trojan

        assertEquals("пароль", profile.password)
    }

    private fun assertMalformedEncodingRejected(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val offset = text.indexOf("broken")
        val malformed =
            bytes.copyOfRange(0, offset) + byteArrayOf(0xC3.toByte(), 0x28) +
                bytes.copyOfRange(offset + "broken".length, bytes.size)
        val result = XrayConfigImportParser.parse(encode(malformed), "group")

        assertTrue("malformed bytes must not become a changed password", result is XrayConfigImportResult.Unparseable)
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
