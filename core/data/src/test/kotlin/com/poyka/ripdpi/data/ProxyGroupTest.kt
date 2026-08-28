package com.poyka.ripdpi.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProxyGroupTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `Mieru diagnostic string hides authentication material`() {
        val profile =
            ProxyProfile.Mieru(
                id = "mieru-fixture",
                displayName = "Fixture",
                groupId = "fixture",
                server = "relay.example",
                serverPort = 443,
                username = "fixture-mieru-user",
                password = "fixture-mieru-password",
            )

        val diagnostic = profile.toString()

        assertFalse(diagnostic.contains(profile.username))
        assertFalse(diagnostic.contains(profile.password))
    }

    @Test
    fun `proxy group round-trips without a subscription child`() {
        val group =
            ProxyGroup(
                id = "default",
                name = "Ungrouped",
                type = ProxyGroupType.BASIC,
                order = 0,
                isSelector = false,
            )

        val encoded = json.encodeToString(ProxyGroup.serializer(), group)
        val decoded = json.decodeFromString(ProxyGroup.serializer(), encoded)

        assertEquals(group, decoded)
        assertEquals(null, decoded.subscription)
    }

    @Test
    fun `proxy group round-trips with a subscription child`() {
        val group =
            ProxyGroup(
                id = "sub-1",
                name = "Friends pool",
                type = ProxyGroupType.SUBSCRIPTION,
                order = 3,
                isSelector = true,
                subscription =
                    Subscription(
                        link = "https://example.com/sub",
                        token = "token-fixture",
                        customUserAgent = "RIPDPI/1.0",
                        autoUpdate = true,
                        autoUpdateDelay = 360L,
                        lastUpdated = 1_700_000_000_000L,
                        updateWhenConnectedOnly = true,
                        forceResolve = false,
                        deduplication = true,
                        subscriptionUserinfo = "upload=0; download=12345; total=99999",
                        bytesUsed = 12_345L,
                        bytesRemaining = 87_654L,
                        expiryDate = 1_800_000_000L,
                        tokenExpiresAtEpochMillis = 1_900_000_000_000L,
                        lastRefreshFailure = SubscriptionRefreshFailure.INVALIDATED,
                    ),
            )

        val encoded = json.encodeToString(ProxyGroup.serializer(), group)
        val decoded = json.decodeFromString(ProxyGroup.serializer(), encoded)

        assertEquals(group, decoded)
        assertEquals(group.subscription, decoded.subscription)
        assertEquals(1_900_000_000_000L, decoded.subscription?.tokenExpiresAtEpochMillis)
        assertEquals(SubscriptionRefreshFailure.INVALIDATED, decoded.subscription?.lastRefreshFailure)
    }

    @Test
    fun `vless proxy profile round-trips`() {
        val profile: ProxyProfile =
            ProxyProfile.Vless(
                id = "p-vless",
                displayName = "VLESS node",
                groupId = "sub-1",
                server = "edge.example.com",
                serverPort = 443,
                uuid = "00000000-0000-0000-0000-000000000000",
            )

        val encoded = json.encodeToString(ProxyProfile.serializer(), profile)
        val decoded = json.decodeFromString(ProxyProfile.serializer(), encoded)

        assertEquals(profile, decoded)
    }

    @Test
    fun `shadowsocks proxy profile round-trips`() {
        val profile: ProxyProfile =
            ProxyProfile.Shadowsocks(
                id = "p-ss",
                displayName = "SS node",
                groupId = "sub-1",
                server = "ss.example.com",
                serverPort = 8388,
                method = "aes-256-gcm",
                password = "ss-fixture",
            )

        val encoded = json.encodeToString(ProxyProfile.serializer(), profile)
        val decoded = json.decodeFromString(ProxyProfile.serializer(), encoded)

        assertEquals(profile, decoded)
    }

    @Test
    fun `trojan proxy profile round-trips`() {
        val profile: ProxyProfile =
            ProxyProfile.Trojan(
                id = "p-trojan",
                displayName = "Trojan node",
                groupId = "sub-1",
                server = "trojan.example.com",
                serverPort = 443,
                password = "trojan-fixture",
            )

        val encoded = json.encodeToString(ProxyProfile.serializer(), profile)
        val decoded = json.decodeFromString(ProxyProfile.serializer(), encoded)

        assertEquals(profile, decoded)
    }

    @Test
    fun `hysteria2 proxy profile round-trips`() {
        val profile: ProxyProfile =
            ProxyProfile.Hysteria2(
                id = "p-hy2",
                displayName = "Hysteria2 node",
                groupId = "sub-1",
                server = "hy2.example.com",
                serverPort = 443,
                password = "hy2-fixture",
            )

        val encoded = json.encodeToString(ProxyProfile.serializer(), profile)
        val decoded = json.decodeFromString(ProxyProfile.serializer(), encoded)

        assertEquals(profile, decoded)
    }

    @Test
    fun `raw config proxy profile round-trips`() {
        val profile: ProxyProfile =
            ProxyProfile.RawConfig(
                id = "p-raw",
                displayName = "Imported config",
                groupId = "sub-1",
                config = "{\"outbounds\":[]}",
            )

        val encoded = json.encodeToString(ProxyProfile.serializer(), profile)
        val decoded = json.decodeFromString(ProxyProfile.serializer(), encoded)

        assertEquals(profile, decoded)
    }

    @Test
    fun `schema version is exposed and migration hook is identity for current version`() {
        val payload = "{\"id\":\"x\"}"

        assertEquals(payload, ProxyGroupSchema.migrate(payload, ProxyGroupSchema.SCHEMA_VERSION))
    }
}
