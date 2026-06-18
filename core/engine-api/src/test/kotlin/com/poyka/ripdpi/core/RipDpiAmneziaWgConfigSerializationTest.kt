package com.poyka.ripdpi.core

import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-language contract guard: the keys emitted when [ResolvedRipDpiAmneziaWgConfig]
 * is serialized with [RipDpiJson] must match, byte-for-byte, the serde camelCase
 * field names of the Rust `AmneziaWgProfileConfig` deserialized by
 * `ripdpi-amneziawg-android::jniCreate`. A rename on either side breaks the native
 * `create()` call silently (an unknown key is ignored, a missing key fails
 * deserialization), so this test pins the field names. Update both sides together
 * if the wire contract changes.
 */
class RipDpiAmneziaWgConfigSerializationTest {
    private val fullConfig =
        ResolvedRipDpiAmneziaWgConfig(
            enabled = true,
            profileId = "profile-amneziawg-1",
            privateKey = "cHJpdmF0ZUtleQ==",
            peerPublicKey = "cGVlclB1YmxpY0tleQ==",
            presharedKey = "cHJlc2hhcmVkS2V5",
            endpointHost = "vpn.example.org",
            endpointIpv4 = "203.0.113.7",
            endpointIpv6 = "2001:db8::7",
            endpointPort = 51820,
            interfaceAddressV4 = "10.8.0.2/32",
            interfaceAddressV6 = "fd00::2/128",
            mtu = 1280,
            persistentKeepalive = 25,
            amnezia =
                RipDpiAmneziaWgObfuscationConfig(
                    jc = 4,
                    jmin = 8,
                    jmax = 80,
                    s1 = 15,
                    s2 = 30,
                    s3 = 45,
                    s4 = 60,
                    h1 = 1_111_111_111L,
                    h2 = 2_222_222_222L,
                    h3 = 3_333_333_333L,
                    h4 = 4_444_444_444L,
                    i1 = "<b 0x01>",
                    i2 = "<b 0x02>",
                    i3 = "<b 0x03>",
                    i4 = "<b 0x04>",
                    i5 = "<b 0x05>",
                ),
            // Non-default carrier so the additive keys surface under
            // RipDpiJson (encodeDefaults = false omits default-valued fields).
            carrier = RipDpiAmneziaWgCarrierKind.Ws,
            carrierWsUrl = "wss://carrier.example.org:443/wg",
            localSocksHost = "127.0.0.1",
            localSocksPort = 10808,
        )

    @Test
    fun `top-level keys match the Rust AmneziaWgProfileConfig field names`() {
        val obj = RipDpiJson.parseToJsonElement(RipDpiJson.encodeToString(fullConfig)).jsonObject

        val expectedTopLevel =
            setOf(
                "enabled",
                "profileId",
                "privateKey",
                "peerPublicKey",
                "presharedKey",
                "endpointHost",
                "endpointIpv4",
                "endpointIpv6",
                "endpointPort",
                "interfaceAddressV4",
                "interfaceAddressV6",
                "mtu",
                "persistentKeepalive",
                "amnezia",
                "carrier",
                "carrierWsUrl",
                "localSocksHost",
                "localSocksPort",
            )

        assertEquals(expectedTopLevel, obj.keys)
    }

    @Test
    fun `carrier kind serializes as the snake_case wire token`() {
        val obj = RipDpiJson.parseToJsonElement(RipDpiJson.encodeToString(fullConfig)).jsonObject
        // The Rust AmneziaWgCarrierKind is serde snake_case: udp / ws.
        assertEquals(JsonPrimitive("ws"), obj["carrier"])
        assertEquals(JsonPrimitive("wss://carrier.example.org:443/wg"), obj["carrierWsUrl"])
    }

    @Test
    fun `udp carrier default is omitted under RipDpiJson`() {
        // encodeDefaults = false means a default UDP carrier (today's behavior)
        // emits no carrier/carrierWsUrl keys, so the Rust serde(default) restores
        // them — the field is fully additive and back-compatible.
        val udpConfig = fullConfig.copy(carrier = RipDpiAmneziaWgCarrierKind.Udp, carrierWsUrl = "")
        val obj = RipDpiJson.parseToJsonElement(RipDpiJson.encodeToString(udpConfig)).jsonObject
        assertEquals(false, obj.containsKey("carrier"))
        assertEquals(false, obj.containsKey("carrierWsUrl"))
    }

    @Test
    fun `nested amnezia object keys match the Rust amnezia field names`() {
        val obj = RipDpiJson.parseToJsonElement(RipDpiJson.encodeToString(fullConfig)).jsonObject
        val amnezia = obj["amnezia"]
        assertTrue("amnezia must serialize as a JSON object", amnezia is JsonObject)

        val expectedAmnezia =
            setOf(
                "jc",
                "jmin",
                "jmax",
                "s1",
                "s2",
                "s3",
                "s4",
                "h1",
                "h2",
                "h3",
                "h4",
                "i1",
                "i2",
                "i3",
                "i4",
                "i5",
            )

        assertEquals(expectedAmnezia, (amnezia as JsonObject).keys)
    }

    @Test
    fun `h1 through h4 serialize as numeric Long values`() {
        val amnezia =
            RipDpiJson
                .parseToJsonElement(RipDpiJson.encodeToString(fullConfig))
                .jsonObject["amnezia"] as JsonObject

        // Long fields must not become strings or floats; assert the exact wire token.
        assertEquals(JsonPrimitive(1_111_111_111L), amnezia["h1"])
        assertEquals(JsonPrimitive(4_444_444_444L), amnezia["h4"])
        assertEquals("4444444444", (amnezia["h4"] as JsonPrimitive).content)
    }

    @Test
    fun `round-trips back to an equal config`() {
        val encoded = RipDpiJson.encodeToString(fullConfig)
        val decoded = RipDpiJson.decodeFromString(ResolvedRipDpiAmneziaWgConfig.serializer(), encoded)
        assertEquals(fullConfig, decoded)
    }
}
