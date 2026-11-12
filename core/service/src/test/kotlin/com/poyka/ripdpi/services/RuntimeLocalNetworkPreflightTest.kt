package com.poyka.ripdpi.services

import android.app.Application
import com.poyka.ripdpi.data.AndroidLocalNetworkAccess
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.LocalNetworkAccessRequiredException
import com.poyka.ripdpi.data.LocalNetworkPermission
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindObfs4
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindTor
import com.poyka.ripdpi.data.RelayKindWebTunnel
import com.poyka.ripdpi.data.torBridgeValidationError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
@LooperMode(LooperMode.Mode.PAUSED)
class RuntimeLocalNetworkPreflightTest {
    @Test
    fun `tor web tunnel LAN URL requires permission when bridge address is synthetic`() =
        runTest {
            val context: Application = RuntimeEnvironment.getApplication()
            shadowOf(context).denyPermissions(LocalNetworkPermission)
            val config =
                sampleResolvedRelayConfig(kind = RelayKindTor).copy(
                    server = "8.8.8.8",
                    serverPort = 443,
                    ptBridgeLine =
                        "Bridge webtunnel 192.0.2.3:1 " +
                            "url=https://192.168.50.2:8443/secret utls=hellochrome_auto",
                )

            val failure =
                runCatching {
                    AndroidLocalNetworkAccess(context).requireRelayEndpoints(config)
                }.exceptionOrNull()

            assertEquals(
                FailureReason.PermissionLost(LocalNetworkPermission),
                (failure as? LocalNetworkAccessRequiredException)?.reason,
            )
        }

    @Test
    fun `tor obfs4 LAN bridge requires permission when generic relay server is public`() =
        runTest {
            val context: Application = RuntimeEnvironment.getApplication()
            shadowOf(context).denyPermissions(LocalNetworkPermission)
            val access = AndroidLocalNetworkAccess(context)
            torObfs4BridgeLines("192.168.50.2:443", "[fd00::2]:443").forEach { bridgeLine ->
                assertNull(bridgeLine, torBridgeValidationError(bridgeLine))
                val config =
                    sampleResolvedRelayConfig(kind = RelayKindTor).copy(
                        server = "8.8.8.8",
                        serverPort = 443,
                        ptBridgeLine = bridgeLine,
                    )

                val failure = runCatching { access.requireRelayEndpoints(config) }.exceptionOrNull()

                assertEquals(
                    bridgeLine,
                    FailureReason.PermissionLost(LocalNetworkPermission),
                    (failure as? LocalNetworkAccessRequiredException)?.reason,
                )
            }
        }

    @Test
    fun `tor obfs4 valid identities retain LAN dependency after permission is granted`() =
        runTest {
            val context: Application = RuntimeEnvironment.getApplication()
            shadowOf(context).grantPermissions(LocalNetworkPermission)
            val access = AndroidLocalNetworkAccess(context)
            torObfs4BridgeLines("192.168.50.2:443", "[fd00::2]:443").forEach { bridgeLine ->
                assertNull(bridgeLine, torBridgeValidationError(bridgeLine))
                val config = sampleResolvedRelayConfig(kind = RelayKindTor).copy(ptBridgeLine = bridgeLine)

                assertTrue(bridgeLine, access.requireRelayEndpoints(config))
            }
        }

    @Test
    fun `tor obfs4 public bridge identities do not require LAN permission`() =
        runTest {
            val context: Application = RuntimeEnvironment.getApplication()
            shadowOf(context).denyPermissions(LocalNetworkPermission)
            val access = AndroidLocalNetworkAccess(context)
            torObfs4BridgeLines("8.8.8.8:443", "[2001:4860:4860::8888]:443").forEach { bridgeLine ->
                assertNull(bridgeLine, torBridgeValidationError(bridgeLine))
                val config = sampleResolvedRelayConfig(kind = RelayKindTor).copy(ptBridgeLine = bridgeLine)

                assertFalse(bridgeLine, access.requireRelayEndpoints(config))
            }
        }

    @Test
    fun `apps script LAN Google IP requires permission when generic relay server is public`() =
        runTest {
            val context: Application = RuntimeEnvironment.getApplication()
            shadowOf(context).denyPermissions(LocalNetworkPermission)
            val config =
                sampleResolvedRelayConfig(kind = RelayKindGoogleAppsScript).copy(
                    server = "8.8.8.8",
                    serverPort = 443,
                    appsScriptGoogleIp = "192.168.50.2",
                )

            val failure =
                runCatching {
                    AndroidLocalNetworkAccess(context).requireRelayEndpoints(config)
                }.exceptionOrNull()

            assertEquals(
                FailureReason.PermissionLost(LocalNetworkPermission),
                (failure as? LocalNetworkAccessRequiredException)?.reason,
            )
        }

    @Test
    fun `masque LAN privacy pass provider requires permission when tunnel is public`() =
        runTest {
            val context: Application = RuntimeEnvironment.getApplication()
            shadowOf(context).denyPermissions(LocalNetworkPermission)
            val config =
                sampleResolvedRelayConfig(kind = RelayKindMasque).copy(
                    masqueUrl = "https://8.8.8.8:443/.well-known/masque",
                    masquePrivacyPassProviderUrl = "https://192.168.50.2:8443/token",
                )

            val failure =
                runCatching {
                    AndroidLocalNetworkAccess(context).requireRelayEndpoints(config)
                }.exceptionOrNull()

            assertEquals(
                FailureReason.PermissionLost(LocalNetworkPermission),
                (failure as? LocalNetworkAccessRequiredException)?.reason,
            )
        }

    @Test
    fun `obfs4 LAN bridge requires permission when generic relay server is public`() =
        runTest {
            val context: Application = RuntimeEnvironment.getApplication()
            shadowOf(context).denyPermissions(LocalNetworkPermission)
            val config =
                sampleResolvedRelayConfig(kind = RelayKindObfs4).copy(
                    server = "8.8.8.8",
                    serverPort = 443,
                    ptBridgeLine =
                        "Bridge obfs4 192.168.50.2:443 AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA " +
                            "cert=abcd1234 iat-mode=2",
                )

            val failure =
                runCatching {
                    AndroidLocalNetworkAccess(context).requireRelayEndpoints(config)
                }.exceptionOrNull()

            assertEquals(
                FailureReason.PermissionLost(LocalNetworkPermission),
                (failure as? LocalNetworkAccessRequiredException)?.reason,
            )
        }

    @Test
    fun `snowflake LAN broker requires permission when generic relay server is public`() =
        runTest {
            val context: Application = RuntimeEnvironment.getApplication()
            shadowOf(context).denyPermissions(LocalNetworkPermission)
            val config =
                sampleResolvedRelayConfig(kind = RelayKindSnowflake).copy(
                    server = "8.8.8.8",
                    serverPort = 443,
                    ptSnowflakeBrokerUrl = "https://192.168.50.2:443/broker",
                )

            val failure =
                runCatching {
                    AndroidLocalNetworkAccess(context).requireRelayEndpoints(config)
                }.exceptionOrNull()

            assertEquals(
                FailureReason.PermissionLost(LocalNetworkPermission),
                (failure as? LocalNetworkAccessRequiredException)?.reason,
            )
        }

    @Test
    fun `web tunnel LAN target requires permission when generic relay server is public`() =
        runTest {
            val context: Application = RuntimeEnvironment.getApplication()
            shadowOf(context).denyPermissions(LocalNetworkPermission)
            val config =
                sampleResolvedRelayConfig(kind = RelayKindWebTunnel).copy(
                    server = "8.8.8.8",
                    serverPort = 443,
                    ptWebTunnelUrl = "https://192.168.50.2:443/tunnel",
                )

            val failure =
                runCatching {
                    AndroidLocalNetworkAccess(context).requireRelayEndpoints(config)
                }.exceptionOrNull()

            assertEquals(
                FailureReason.PermissionLost(LocalNetworkPermission),
                (failure as? LocalNetworkAccessRequiredException)?.reason,
            )
        }

    private fun torObfs4BridgeLines(vararg endpoints: String): List<String> {
        val rsa = "0BAC39417268B96B9F514E7F63FA6FBA1A788955"
        val ed = "ed25519:dGhpcyBpcyBpbmNyZWRpYmx5IHNpbGx5ISEhISEhISE"
        val identities = listOf(rsa, "\$$rsa", "$rsa $ed", "$ed $rsa", "rsa:q83vq83vq83vq83vq83vEjRWeJA")
        val cert = Base64.getEncoder().withoutPadding().encodeToString(ByteArray(52) { it.toByte() })
        return endpoints.flatMap { endpoint ->
            identities.flatMap { identity ->
                listOf("", "Bridge ").map { prefix ->
                    "${prefix}obfs4 $endpoint $identity cert=$cert iat-mode=2"
                }
            }
        }
    }
}
