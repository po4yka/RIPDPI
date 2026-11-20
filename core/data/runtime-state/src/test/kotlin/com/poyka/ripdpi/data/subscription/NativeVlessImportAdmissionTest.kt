package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.uri.ProxyUriCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeVlessImportAdmissionTest {
    @Test
    fun `representable share links stay available for preview and fail unavailable native admission`() {
        val authority = "vless://11111111-2222-3333-4444-555555555555@relay.example:443"
        val reality = "security=reality&pbk=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8&type=xhttp"
        val queries =
            listOf(
                "security=tls&type=xhttp&mode=stream-down",
                "$reality&mode=stream-one&flow=xtls-rprx-vision",
                "$reality&mode=stream-one&flow=unavailable-flow",
                "$reality&mode=stream-down&flow=",
            )
        queries.forEach { query ->
            val link = "$authority?$query"
            assertNotNull(ProxyUriCodec.parse(link))
            val native = XrayConfigImportParser.parse(link, "group") as XrayConfigImportResult.Translated
            assertTrue(native.profiles.isEmpty())
            assertEquals(XraySkipReason.UNSUPPORTED_TRANSPORT, native.skipped.single().reason)
        }
        listOf(
            "security=tls&type=xhttp&mode=stream-one&flow=",
            "security=tls&type=xhttp&mode=auto&flow=",
            "security=tls&type=xhttp&mode=packet-up&flow=",
            "$reality&mode=packet-up&flow=",
        ).forEach { query ->
            val accepted =
                XrayConfigImportParser.parse(
                    "$authority?$query",
                    "group",
                ) as XrayConfigImportResult.Translated
            assertEquals(1, accepted.profiles.size)
            assertTrue(accepted.skipped.isEmpty())
        }
    }
}
