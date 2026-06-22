package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindVless
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class DefaultRelayKindResolverTest {
    private val resolver = DefaultRelayKindResolver()

    private fun request(
        config: RipDpiRelayConfig,
        credentials: RelayCredentialRecord? =
            RelayCredentialRecord(
                profileId = config.profileId,
                vlessUuid = ValidVlessUuid,
            ),
    ): RelayResolverRequest =
        RelayResolverRequest(
            profileId = config.profileId,
            mergedConfig = config,
            credentials = credentials,
            requestedTlsProfile = TlsFingerprintProfileChromeStable,
            featureFlags = emptyMap(),
        )

    private fun vlessRealityConfig(): RipDpiRelayConfig =
        RipDpiRelayConfig(
            enabled = true,
            kind = RelayKindVlessReality,
            profileId = "p0",
            server = "relay.example.com",
            serverPort = 443,
            serverName = "relay.example.com",
            realityPublicKey = ValidRealityPublicKey,
            realityShortId = ValidRealityShortId,
            vlessTransport = RelayVlessTransportRealityTcp,
        )

    @Test
    fun `default resolver claims both vless reality and plain vless kinds`() {
        // The default resolver is the catch-all native VLESS path; the
        // dedicated resolvers must not steal the vless kinds.
        assertTrue(resolver.supports(RelayKindVlessReality))
        assertTrue(resolver.supports(RelayKindVless))
    }

    @Test
    fun `resolve routes vless reality tcp through the native vless path`() =
        runTest {
            val config =
                vlessRealityConfig()

            val result = resolver.resolve(request(config))

            assertEquals(RelayKindVlessReality, result.effectiveConfig.kind)
            assertEquals(RelayVlessTransportRealityTcp, result.effectiveConfig.vlessTransport)
        }

    @Test
    fun `resolve accepts vless reality without short id`() =
        runTest {
            val result = resolver.resolve(request(vlessRealityConfig().copy(realityShortId = "")))

            assertEquals("", result.effectiveConfig.realityShortId)
        }

    @Test
    fun `resolve accepts url safe unpadded vless reality public key`() =
        runTest {
            val publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { UrlSafeKeyByte })
            val result = resolver.resolve(request(vlessRealityConfig().copy(realityPublicKey = publicKey)))

            assertEquals(publicKey, result.effectiveConfig.realityPublicKey)
        }

    @Test
    fun `resolve rejects malformed vless reality public key`() =
        runTest {
            assertRejectsVlessRealityConfig(
                config = vlessRealityConfig().copy(realityPublicKey = "public-key"),
                expectedMessage = "VLESS Reality public key",
            )
        }

    @Test
    fun `resolve rejects malformed vless reality short id`() =
        runTest {
            assertRejectsVlessRealityConfig(
                config = vlessRealityConfig().copy(realityShortId = "short-id"),
                expectedMessage = "VLESS Reality short ID",
            )
        }

    @Test
    fun `resolve rejects malformed vless uuid`() =
        runTest {
            assertRejectsVlessRealityConfig(
                config = vlessRealityConfig(),
                credentials =
                    RelayCredentialRecord(
                        profileId = "p0",
                        vlessUuid = "not-a-vless-uuid",
                    ),
                expectedMessage = "Relay credentials missing",
            )
        }

    @Test
    fun `resolve routes plain tls vless xhttp through the native vless path`() =
        runTest {
            val config =
                RipDpiRelayConfig(
                    enabled = true,
                    kind = RelayKindVless,
                    profileId = "p1",
                    server = "edge.example.com",
                    serverPort = 8443,
                    serverName = "edge.example.com",
                    vlessTransport = RelayVlessTransportXhttp,
                    xhttpPath = "/xhttp",
                    xhttpHost = "origin.example.com",
                )

            val result = resolver.resolve(request(config))

            assertEquals(RelayKindVless, result.effectiveConfig.kind)
            assertEquals(RelayVlessTransportXhttp, result.effectiveConfig.vlessTransport)
            assertEquals("/xhttp", result.effectiveConfig.xhttpPath)
            assertEquals("origin.example.com", result.effectiveConfig.xhttpHost)
            assertTrue(result.effectiveConfig.realityPublicKey.isEmpty())
        }

    @Test
    fun `dedicated resolvers do not claim the plain vless kind`() {
        assertFalse(CloudflareTunnelRelayKindResolver().supports(RelayKindVless))
        assertFalse(SnowflakeRelayKindResolver().supports(RelayKindVless))
        assertFalse(NaiveRelayKindResolver().supports(RelayKindVless))
        assertFalse(LocalPathRelayKindResolver().supports(RelayKindVless))
        // sanity: they still claim their own kinds
        assertTrue(CloudflareTunnelRelayKindResolver().supports(RelayKindCloudflareTunnel))
    }

    private suspend fun assertRejectsVlessRealityConfig(
        config: RipDpiRelayConfig,
        credentials: RelayCredentialRecord? =
            RelayCredentialRecord(
                profileId = config.profileId,
                vlessUuid = ValidVlessUuid,
            ),
        expectedMessage: String,
    ) {
        try {
            resolver.resolve(request(config, credentials))
            fail("Expected VLESS Reality config to be rejected")
        } catch (exception: IllegalArgumentException) {
            assertTrue(exception.message.orEmpty().contains(expectedMessage))
        }
    }

    private companion object {
        private const val ValidVlessUuid = "00000000-0000-0000-0000-000000000000"
        private const val ValidRealityPublicKey = "q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s="
        private const val ValidRealityShortId = "abcd1234"
        private const val UrlSafeKeyByte: Byte = -5
    }
}
