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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

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
            val config =
                sampleResolvedRelayConfig(kind = RelayKindTor).copy(
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
}
