package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.subscription.SingBoxParseResult
import com.poyka.ripdpi.data.subscription.SingBoxSubscriptionParser
import com.poyka.ripdpi.serialization.RipDpiEncodeDefaultsJson
import com.poyka.ripdpi.serialization.RipDpiJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorRelayProfileMappingTest {
    @Test
    fun `Shadowsocks imports enable UDP only for SS2022 and preserve TCP credentials`() {
        listOf(
            "aes-128-gcm" to "legacy-test-key",
            "aes-256-gcm" to "legacy-test-key",
            "chacha20-ietf-poly1305" to "legacy-test-key",
            "2022-blake3-aes-128-gcm" to "AAECAwQFBgcICQoLDA0ODw==",
            "2022-blake3-aes-256-gcm" to "Zml4dHVyZS1wc2stYWVzMjU2Z2NtLTMyYnl0ZWtleSE=",
            "2022-blake3-chacha20-poly1305" to "Zml4dHVyZS1wc2stY2hhY2hhMjBwb2x5MTMwNS1rZXk=",
        ).forEach { (method, password) ->
            val imported = ProxyProfile.Shadowsocks("ss", "ss", "group", "ss.example", 8388, method, password)
            val mapped = checkNotNull(mapRelayProfile(imported))
            assertEquals(method, method.startsWith("2022-"), mapped.profile.udpEnabled)
            assertEquals(method, mapped.credentials.shadowsocksMethod)
            assertEquals(password, mapped.credentials.shadowsocksPassword)
        }
    }

    @Test
    fun `canonical selector mapping has stable timestamp and complete credential bytes`() {
        val member = member("original")
        val first = checkNotNull(mapRelayProfile(member))
        val second = checkNotNull(mapRelayProfile(member))

        assertEquals(0L, first.credentials.updatedAtEpochMillis)
        assertEquals(first, second)
        assertEquals(encoded(first.credentials), encoded(second.credentials))
        assertEquals(member.password, first.credentials.trojanPassword)
        assertEquals(member.server, first.profile.server)
        assertEquals(member.serverName, first.profile.serverName)
    }

    @Test
    fun `changing selector secret remains significant in canonical credential material`() {
        val original = checkNotNull(mapRelayProfile(member("original")))
        val changed = checkNotNull(mapRelayProfile(member("changed")))

        assertEquals(original.profile, changed.profile)
        assertNotEquals(encoded(original.credentials), encoded(changed.credentials))
        assertEquals(0L, changed.credentials.updatedAtEpochMillis)
    }

    @Test
    fun `hysteria2 certificate pin reaches the relay credentials with insecure (REL-3)`() {
        val pin = "ab".repeat(32)
        val profile =
            ProxyProfile.Hysteria2(
                id = "hy2",
                displayName = "hy2",
                groupId = "group-a",
                server = "hy2.example",
                serverPort = 443,
                password = "hy2-fixture-pw",
                insecure = true,
                pinSha256 = pin,
            )

        val credentials = checkNotNull(mapRelayProfile(profile)).credentials

        assertEquals(true, credentials.hysteriaInsecure)
        assertEquals(pin, credentials.hysteriaPinSha256)
    }

    @Test
    fun `stored imported Reality Vision profiles preserve UDP capability`() {
        listOf(RelayVlessFlowVision, RelayVlessFlowVisionUdp443).forEach { flow ->
            val imported = importedReality(flow)
            val stored = storedProfile(imported)
            val mapped = checkNotNull(mapRelayProfile(stored))

            assertEquals(imported, stored)
            assertEquals(RelayKindVlessReality, mapped.profile.kind)
            assertEquals(RelayVlessTransportRealityTcp, mapped.profile.vlessTransport)
            assertEquals(flow, mapped.profile.vlessFlow)
            assertTrue(flow, mapped.profile.udpEnabled)
            assertEquals(imported.uuid, mapped.credentials.vlessUuid)
        }
    }

    @Test
    fun `Reality modes without native XUDP remain disabled after storage`() {
        val reality = importedReality(RelayVlessFlowVision)
        val unsupported =
            listOf(
                reality.copy(flow = ""),
                reality.copy(flow = " "),
                reality.copy(xhttpPath = "/stream", flow = ""),
                reality.copy(xhttpHost = "front.example", flow = ""),
                reality.copy(xhttpPath = "", flow = ""),
            )
        listOf(reality.copy(flow = "unsupported-flow"), reality.copy(xhttpPath = "/stream")).forEach { profile ->
            assertTrue(runCatching { mapRelayProfile(storedProfile(profile)) }.isFailure)
        }
        unsupported.forEach { profile ->
            assertFalse(checkNotNull(mapRelayProfile(storedProfile(profile))).profile.udpEnabled)
        }
    }

    @Test
    fun `TLS VLESS SSH and Mieru mappings remain TCP only`() {
        val profiles =
            listOf(
                ProxyProfile.Vless(
                    id = "tls",
                    displayName = "TLS",
                    groupId = "group-a",
                    server = "relay.example",
                    serverPort = 443,
                    uuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                    serverName = "relay.example",
                    xhttpPath = "/stream",
                    flow = "",
                    xhttpMode = "stream-one",
                ),
                ProxyProfile.Ssh(
                    id = "ssh",
                    displayName = "SSH",
                    groupId = "group-a",
                    server = "relay.example",
                    serverPort = 22,
                    username = "fixture",
                ),
                ProxyProfile.Mieru(
                    id = "mieru",
                    displayName = "Mieru",
                    groupId = "group-a",
                    server = "relay.example",
                    serverPort = 443,
                    username = "fixture",
                    password = "fixture",
                ),
            )
        profiles.forEach { profile ->
            assertFalse(checkNotNull(mapRelayProfile(storedProfile(profile))).profile.udpEnabled)
        }
    }

    @Test
    fun `naive profile activates as naiveproxy with its credentials and empty password`() {
        val naive =
            ProxyProfile.Naive(
                id = "naive",
                displayName = "Naive",
                groupId = "group-a",
                server = "relay.example",
                serverPort = 8443,
                username = "alice",
                password = "",
                extraHeaders = "X-Token: s3cret",
            )

        val mapped = checkNotNull(mapRelayProfile(naive))

        assertEquals(RelayKindNaiveProxy, mapped.profile.kind)
        assertEquals("relay.example", mapped.profile.serverName)
        assertEquals(8443, mapped.profile.serverPort)
        assertEquals("alice", mapped.credentials.naiveUsername)
        assertEquals("", mapped.credentials.naivePassword)
        assertEquals("X-Token: s3cret", mapped.credentials.naiveExtraHeaders)
        assertFalse(mapped.profile.udpEnabled)
    }

    @Test
    fun `hysteria hopping is public profile configuration and affects selector identity`() {
        val hopping = Hysteria2PortHopping("5000-5002", 5000, 15000)
        val scalar = ProxyProfile.Hysteria2("hy", "hy", "group", "peer.example", 443, "fixture-password")
        val configured = scalar.copy(portHopping = hopping)
        val mapped = checkNotNull(mapRelayProfile(configured))
        assertEquals(hopping, mapped.profile.hysteriaPortHopping)
        assertEquals(checkNotNull(mapRelayProfile(scalar)).credentials, mapped.credentials)
        assertNotEquals(checkNotNull(mapRelayProfile(scalar)).profile, mapped.profile)
        val restored =
            RipDpiEncodeDefaultsJson.decodeFromString(
                RelayProfileRecord.serializer(),
                RipDpiEncodeDefaultsJson.encodeToString(RelayProfileRecord.serializer(), mapped.profile),
            )
        assertEquals(mapped.profile, restored)
    }

    private fun importedReality(flow: String): ProxyProfile.VlessReality {
        val source =
            """
            {"outbounds":[{
              "type":"vless","tag":"reality","server":"relay.example","server_port":443,
              "uuid":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","flow":"$flow",
              "tls":{"enabled":true,"server_name":"relay.example",
                "reality":{"enabled":true,"public_key":"${"A".repeat(43)}","short_id":"abcd"}}
            }]}
            """.trimIndent()
        val imported = SingBoxSubscriptionParser.parse(source, "group-a") as SingBoxParseResult.Success
        return imported.profiles.single() as ProxyProfile.VlessReality
    }

    private fun storedProfile(profile: ProxyProfile): ProxyProfile =
        RipDpiJson.decodeFromString(
            ProxyProfile.serializer(),
            RipDpiJson.encodeToString(ProxyProfile.serializer(), profile),
        )

    private fun member(label: String) =
        ProxyProfile.Trojan(
            id = "shared-member",
            displayName = "Member",
            groupId = "group-a",
            server = "relay.example",
            serverPort = 443,
            serverName = "relay.example",
            password = "fixture-" + label,
        )

    private fun encoded(credentials: RelayCredentialRecord) =
        RipDpiEncodeDefaultsJson.encodeToString(RelayCredentialRecord.serializer(), credentials)
}
