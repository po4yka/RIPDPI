package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.CurrentDeviceProfileSchemaVersion
import com.poyka.ripdpi.data.DeviceProfile
import com.poyka.ripdpi.data.DeviceProfileSchema
import com.poyka.ripdpi.data.GroupStrategy
import com.poyka.ripdpi.data.Hysteria2Outbound
import com.poyka.ripdpi.data.Ipv6Policy
import com.poyka.ripdpi.data.KillSwitchMode
import com.poyka.ripdpi.data.OutboundGroup
import com.poyka.ripdpi.data.OutboundSecurityLayer
import com.poyka.ripdpi.data.ProfileDnsConfig
import com.poyka.ripdpi.data.ProfileDnsMode
import com.poyka.ripdpi.data.ProfileRoutingConfig
import com.poyka.ripdpi.data.RedactionMetadata
import com.poyka.ripdpi.data.SecretString
import com.poyka.ripdpi.data.SubscriptionLifecycleState
import com.poyka.ripdpi.data.SubscriptionState
import com.poyka.ripdpi.data.TypedOutbound
import com.poyka.ripdpi.data.VlessOutbound
import com.poyka.ripdpi.data.VlessTransport
import com.poyka.ripdpi.data.XhttpOutbound
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [DeviceProfile] schema: version, secrets, outbound shapes, lifecycle. */
class DeviceProfileSchemaTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    // ------------------------------------------------------------------
    // 1. profile_version is present and migration slot exists
    // ------------------------------------------------------------------

    @Test
    fun `default DeviceProfile carries profileVersion equal to current schema version`() {
        val profile = DeviceProfile()
        assertEquals(CurrentDeviceProfileSchemaVersion, profile.profileVersion)
    }

    @Test
    fun `profileVersion is serialized into JSON`() {
        val profile = DeviceProfile(profileVersion = 1)
        val encoded = json.encodeToString(DeviceProfile.serializer(), profile)
        assertTrue("profileVersion should appear in JSON", encoded.contains("profileVersion"))
    }

    @Test
    fun `DeviceProfileSchema migrate is identity for current version`() {
        val original = """{"profileVersion":1,"deviceId":"d1"}"""
        val migrated = DeviceProfileSchema.migrate(original, fromVersion = 1)
        assertEquals(original, migrated)
    }

    @Test
    fun `DeviceProfileSchema SCHEMA_VERSION matches CurrentDeviceProfileSchemaVersion`() {
        assertEquals(CurrentDeviceProfileSchemaVersion, DeviceProfileSchema.SCHEMA_VERSION)
    }

    // ------------------------------------------------------------------
    // 2. SecretString / Redacted wrapper masks toString
    // ------------------------------------------------------------------

    @Test
    fun `SecretString toString returns redacted placeholder`() {
        val secret = SecretString("super-secret-uuid")
        assertFalse(
            "toString must not expose the raw value",
            secret.toString().contains("super-secret-uuid"),
        )
        assertEquals("<redacted>", secret.toString())
    }

    @Test
    fun `SecretString serializes to raw string value`() {
        val secret = SecretString("my-password")
        val encoded = json.encodeToString(SecretString.serializer(), secret)
        // Encoded value should be the JSON string "my-password"
        assertEquals("\"my-password\"", encoded)
    }

    @Test
    fun `SecretString deserializes from JSON string`() {
        val decoded = json.decodeFromString(SecretString.serializer(), "\"round-trip-value\"")
        assertEquals("round-trip-value", decoded.value)
        // toString still masks after deserialization
        assertEquals("<redacted>", decoded.toString())
    }

    @Test
    fun `SecretString in VlessOutbound does not leak via toString`() {
        val outbound =
            VlessOutbound(
                server = "example.com",
                serverPort = 443,
                uuid = SecretString("secret-uuid-1234"),
            )
        val str = outbound.toString()
        assertFalse("uuid secret must not appear in toString", str.contains("secret-uuid-1234"))
    }

    // ------------------------------------------------------------------
    // 3. VLESS+REALITY outbound round-trip
    // ------------------------------------------------------------------

    @Test
    fun `VLESS REALITY outbound round-trips through JSON without raw JSON`() {
        val outbound =
            TypedOutbound.Vless(
                VlessOutbound(
                    server = "vpn.example.com",
                    serverPort = 443,
                    uuid = SecretString("test-uuid-abcd"),
                    serverName = "vpn.example.com",
                    securityLayer = OutboundSecurityLayer.REALITY,
                    realityPublicKey = "abc123publickey",
                    realityShortId = "deadbeef",
                    transport = VlessTransport.TCP,
                ),
            )
        val encoded = json.encodeToString(TypedOutbound.serializer(), outbound)
        val decoded = json.decodeFromString(TypedOutbound.serializer(), encoded)
        assertTrue(decoded is TypedOutbound.Vless)
        val vless = (decoded as TypedOutbound.Vless).outbound
        assertEquals(OutboundSecurityLayer.REALITY, vless.securityLayer)
        assertEquals("abc123publickey", vless.realityPublicKey)
        assertEquals("test-uuid-abcd", vless.uuid.value)
        // toString must still not expose the secret
        assertEquals("<redacted>", vless.uuid.toString())
    }

    @Test
    fun `VLESS XHTTP outbound round-trips with xhttpPath and xhttpHost`() {
        val outbound =
            TypedOutbound.Vless(
                VlessOutbound(
                    server = "xhttp.example.com",
                    serverPort = 8443,
                    uuid = SecretString("xhttp-uuid"),
                    securityLayer = OutboundSecurityLayer.TLS,
                    transport = VlessTransport.XHTTP,
                    xhttpPath = "/api/v1",
                    xhttpHost = "xhttp.example.com",
                ),
            )
        val encoded = json.encodeToString(TypedOutbound.serializer(), outbound)
        val decoded = json.decodeFromString(TypedOutbound.serializer(), encoded) as TypedOutbound.Vless
        assertEquals(VlessTransport.XHTTP, decoded.outbound.transport)
        assertEquals("/api/v1", decoded.outbound.xhttpPath)
        assertEquals("xhttp.example.com", decoded.outbound.xhttpHost)
    }

    // ------------------------------------------------------------------
    // 4. XHTTP/HTTPS outbound round-trip
    // ------------------------------------------------------------------

    @Test
    fun `XHTTP HTTPS outbound round-trips through JSON`() {
        val outbound =
            TypedOutbound.Xhttp(
                XhttpOutbound(
                    server = "https-proxy.example.com",
                    serverPort = 443,
                    serverName = "https-proxy.example.com",
                    securityLayer = OutboundSecurityLayer.TLS,
                    path = "/traffic",
                    host = "https-proxy.example.com",
                    uuid = SecretString("xhttp-secret"),
                ),
            )
        val encoded = json.encodeToString(TypedOutbound.serializer(), outbound)
        val decoded = json.decodeFromString(TypedOutbound.serializer(), encoded)
        assertTrue(decoded is TypedOutbound.Xhttp)
        val xhttp = (decoded as TypedOutbound.Xhttp).outbound
        assertEquals("/traffic", xhttp.path)
        assertEquals(OutboundSecurityLayer.TLS, xhttp.securityLayer)
        assertEquals("xhttp-secret", xhttp.uuid.value)
    }

    // ------------------------------------------------------------------
    // 5. Hysteria2 outbound round-trip
    // ------------------------------------------------------------------

    @Test
    fun `Hysteria2 outbound round-trips through JSON`() {
        val outbound =
            TypedOutbound.Hysteria2(
                Hysteria2Outbound(
                    server = "hy2.example.com",
                    serverPort = 443,
                    password = SecretString("hy2-password"),
                    serverName = "hy2.example.com",
                ),
            )
        val encoded = json.encodeToString(TypedOutbound.serializer(), outbound)
        val decoded = json.decodeFromString(TypedOutbound.serializer(), encoded)
        assertTrue(decoded is TypedOutbound.Hysteria2)
        val hy2 = (decoded as TypedOutbound.Hysteria2).outbound
        assertEquals("hy2-password", hy2.password.value)
        assertEquals("<redacted>", hy2.password.toString())
    }

    @Test
    fun `Hysteria2 salamander key is Redacted and does not leak via toString`() {
        val outbound =
            Hysteria2Outbound(
                server = "hy2.example.com",
                serverPort = 443,
                password = SecretString("pw"),
                salamanderPassword = SecretString("salamndr-key"),
            )
        assertFalse(outbound.toString().contains("salamndr-key"))
        assertFalse(outbound.toString().contains("pw"))
    }

    // ------------------------------------------------------------------
    // 6. expiresAt and SubscriptionState serialization
    // ------------------------------------------------------------------

    @Test
    fun `expiresAtEpochMillis can be null and round-trips`() {
        val profile = DeviceProfile(expiresAtEpochMillis = null)
        val encoded = json.encodeToString(DeviceProfile.serializer(), profile)
        val decoded = json.decodeFromString(DeviceProfile.serializer(), encoded)
        assertNull(decoded.expiresAtEpochMillis)
    }

    @Test
    fun `expiresAtEpochMillis round-trips a non-null timestamp`() {
        val ts = 1_800_000_000_000L
        val profile = DeviceProfile(expiresAtEpochMillis = ts)
        val encoded = json.encodeToString(DeviceProfile.serializer(), profile)
        val decoded = json.decodeFromString(DeviceProfile.serializer(), encoded)
        assertEquals(ts, decoded.expiresAtEpochMillis)
    }

    @Test
    fun `SubscriptionState with ACTIVE lifecycle round-trips`() {
        val state =
            SubscriptionState(
                lifecycleState = SubscriptionLifecycleState.ACTIVE,
                bytesUsed = 1024L,
                bytesRemaining = 99_000L,
                expiresAtEpochMillis = 1_700_000_000_000L,
            )
        val profile = DeviceProfile(subscriptionState = state)
        val encoded = json.encodeToString(DeviceProfile.serializer(), profile)
        val decoded = json.decodeFromString(DeviceProfile.serializer(), encoded)
        assertEquals(SubscriptionLifecycleState.ACTIVE, decoded.subscriptionState.lifecycleState)
        assertEquals(1024L, decoded.subscriptionState.bytesUsed)
        assertEquals(1_700_000_000_000L, decoded.subscriptionState.expiresAtEpochMillis)
    }

    // ------------------------------------------------------------------
    // 7. Full DeviceProfile round-trip preserving all policy fields
    // ------------------------------------------------------------------

    @Test
    fun `full DeviceProfile with mixed outbounds and policy fields round-trips`() {
        val profile =
            DeviceProfile(
                deviceId = "device-001",
                profileId = "profile-abc",
                profileVersion = 1,
                displayName = "Test Profile",
                outbounds =
                    listOf(
                        TypedOutbound.Vless(
                            VlessOutbound(
                                server = "vless.example.com",
                                serverPort = 443,
                                uuid = SecretString("vless-uuid"),
                                securityLayer = OutboundSecurityLayer.REALITY,
                                realityPublicKey = "pubkey",
                                realityShortId = "sid",
                                transport = VlessTransport.TCP,
                            ),
                        ),
                        TypedOutbound.Hysteria2(
                            Hysteria2Outbound(
                                server = "hy2.example.com",
                                serverPort = 443,
                                password = SecretString("hy2pw"),
                            ),
                        ),
                    ),
                groups =
                    listOf(
                        OutboundGroup(
                            tag = "auto",
                            strategy = GroupStrategy.URLTEST,
                            outboundTags = listOf("vless-tag", "hy2-tag"),
                        ),
                    ),
                activeOutboundTag = "auto",
                dns = ProfileDnsConfig(mode = ProfileDnsMode.DOH),
                routing = ProfileRoutingConfig(bypassLan = true, bypassCn = false),
                ipv6Policy = Ipv6Policy.BLOCK,
                killSwitchMode = KillSwitchMode.STRICT,
                subscriptionState =
                    SubscriptionState(
                        lifecycleState = SubscriptionLifecycleState.ACTIVE,
                        expiresAtEpochMillis = 9_999_999_999_999L,
                    ),
                expiresAtEpochMillis = 9_999_999_999_999L,
                redactionMetadata =
                    RedactionMetadata(
                        redactedFieldNames = listOf("rawExtraField"),
                        rawExtension = "",
                    ),
            )

        val encoded = json.encodeToString(DeviceProfile.serializer(), profile)
        val decoded = json.decodeFromString(DeviceProfile.serializer(), encoded)

        assertEquals("device-001", decoded.deviceId)
        assertEquals("profile-abc", decoded.profileId)
        assertEquals(1, decoded.profileVersion)
        assertEquals(2, decoded.outbounds.size)
        assertNotNull(decoded.outbounds.filterIsInstance<TypedOutbound.Vless>().firstOrNull())
        assertNotNull(decoded.outbounds.filterIsInstance<TypedOutbound.Hysteria2>().firstOrNull())
        assertEquals(Ipv6Policy.BLOCK, decoded.ipv6Policy)
        assertEquals(KillSwitchMode.STRICT, decoded.killSwitchMode)
        assertEquals(SubscriptionLifecycleState.ACTIVE, decoded.subscriptionState.lifecycleState)
        assertEquals(9_999_999_999_999L, decoded.expiresAtEpochMillis)
        assertEquals(listOf("rawExtraField"), decoded.redactionMetadata.redactedFieldNames)

        // Secrets must not be exposed in any toString path
        assertFalse(encoded.contains("<redacted>"))
        assertTrue(encoded.contains("vless-uuid"))
        assertTrue(encoded.contains("hy2pw"))
    }

    @Test
    fun `DeviceProfile group selector strategy round-trips`() {
        val group =
            OutboundGroup(
                tag = "proxy",
                strategy = GroupStrategy.SELECTOR,
                outboundTags = listOf("a", "b"),
                selectedTag = "a",
            )
        val profile = DeviceProfile(groups = listOf(group))
        val encoded = json.encodeToString(DeviceProfile.serializer(), profile)
        val decoded = json.decodeFromString(DeviceProfile.serializer(), encoded)
        assertEquals(1, decoded.groups.size)
        assertEquals(GroupStrategy.SELECTOR, decoded.groups[0].strategy)
        assertEquals("a", decoded.groups[0].selectedTag)
    }
}
