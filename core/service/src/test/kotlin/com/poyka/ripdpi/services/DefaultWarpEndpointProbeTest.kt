package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiWarpEndpointProbeBindings
import com.poyka.ripdpi.core.WarpProbeSocketController
import com.poyka.ripdpi.data.WarpCredentials
import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DefaultWarpEndpointProbeTest {
    @Test
    fun `frozen credentials and wire settings survive concurrent live changes`() =
        runTest {
            val settings = TestAppSettingsRepository()
            val store =
                FakeWarpCredentialStore().apply {
                    save(
                        "frozen",
                        WarpCredentials(
                            profileId = "frozen",
                            deviceId = "device",
                            accessToken = "fixture",
                            privateKey = "wrong-live-key",
                            peerPublicKey = "wrong-live-peer",
                        ),
                    )
                }
            val context =
                WarpEndpointScanContext(
                    WarpCredentials(
                        profileId = "frozen",
                        deviceId = "device",
                        accessToken = "fixture",
                        privateKey = "frozen-key",
                        peerPublicKey = "frozen-peer",
                    ),
                    com.poyka.ripdpi.core
                        .RipDpiWarpConfig(
                            amnezia =
                                com.poyka.ripdpi.core
                                    .RipDpiWarpAmneziaConfig(h1 = "101"),
                        ),
                )
            val native =
                object : RipDpiWarpEndpointProbeBindings {
                    override fun probeEndpoint(
                        requestJson: String,
                        controller: WarpProbeSocketController,
                    ): String? {
                        val request =
                            com.poyka.ripdpi.serialization.RipDpiContractJson.decodeFromString<
                                com.poyka.ripdpi.core.WarpEndpointProbeNativeRequest,
                            >(requestJson)
                        org.junit.Assert.assertEquals("frozen-key", request.privateKey)
                        org.junit.Assert.assertEquals("frozen-peer", request.peerPublicKey)
                        org.junit.Assert.assertEquals("101", request.amnezia.h1)
                        if (!controller.protect(1)) return null
                        return """{"host":"127.0.0.1","ipv4":"127.0.0.1","port":2408,"rttMs":7}"""
                    }
                }
            val result =
                DefaultWarpEndpointProbe(settings, store, native, ActiveProtectSocketPathProvider()).probe(
                    WarpEndpointCacheEntry(
                        profileId = "frozen",
                        networkScopeKey = "current",
                        ipv4 = "127.0.0.1",
                        port = 2408,
                    ),
                    500,
                    context,
                )
            org.junit.Assert.assertEquals(7L, result?.rttMs)
            org.junit.Assert.assertEquals("127.0.0.1", result?.ipv4)
        }

    @Test
    fun `an endpoint that never answers the handshake is not a candidate`() =
        runTest {
            // A bound UDP port that reads but never replies: the same view as a black-holed endpoint.
            DatagramSocket(0, InetAddress.getLoopbackAddress()).use { silentEndpoint ->
                val credentials =
                    FakeWarpCredentialStore().apply {
                        save(
                            "warp",
                            WarpCredentials(
                                deviceId = "device",
                                accessToken = "token",
                                privateKey = "private-key",
                                peerPublicKey = "peer-public-key",
                            ),
                        )
                    }
                val probe =
                    DefaultWarpEndpointProbe(
                        appSettingsRepository = TestAppSettingsRepository(),
                        credentialStore = credentials,
                        nativeBindings =
                            object : RipDpiWarpEndpointProbeBindings {
                                override fun probeEndpoint(
                                    requestJson: String,
                                    controller: WarpProbeSocketController,
                                ): String? = null
                            },
                        protection = ActiveProtectSocketPathProvider(),
                    )

                val probed =
                    probe.probe(
                        WarpEndpointCacheEntry(
                            profileId = "warp",
                            networkScopeKey = "wifi",
                            ipv4 = "127.0.0.1",
                            port = silentEndpoint.localPort,
                        ),
                        timeoutMillis = 250,
                    )

                assertNull(probed)
            }
        }

    @Test
    fun `inactive reservation remains owned until blocking probe returns after cancellation`() =
        runTest {
            val protection = ActiveProtectSocketPathProvider()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val credentials =
                FakeWarpCredentialStore().apply {
                    save(
                        "warp",
                        WarpCredentials(
                            deviceId = "device",
                            accessToken = "fixture",
                            privateKey = "private",
                            peerPublicKey = "peer",
                        ),
                    )
                }
            val bindings =
                object : RipDpiWarpEndpointProbeBindings {
                    override fun probeEndpoint(
                        requestJson: String,
                        controller: WarpProbeSocketController,
                    ): String? {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        return null
                    }
                }
            val probe = DefaultWarpEndpointProbe(TestAppSettingsRepository(), credentials, bindings, protection)
            val operation =
                launch {
                    probe.probe(
                        WarpEndpointCacheEntry(
                            profileId = "warp",
                            networkScopeKey = "",
                            host = "127.0.0.1",
                            port = 2408,
                        ),
                        250,
                    )
                }
            try {
                withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
                operation.cancel()
                assertTrue(runCatching { protection.set("vpn") { true } }.isFailure)
            } finally {
                release.countDown()
                operation.join()
            }
            val owner = protection.set("vpn") { true }
            protection.clear(owner)
        }

    @Test
    fun `cleanup pending owner rejects probe before native admission`() =
        runTest {
            val protection = ActiveProtectSocketPathProvider()
            val owner = protection.set("/tmp/cleanup-pending-vpn.sock") { true }
            protection.closeProbeAdmission(owner)
            val credentials =
                FakeWarpCredentialStore().apply {
                    save(
                        "warp",
                        WarpCredentials(
                            profileId = "warp",
                            deviceId = "device",
                            accessToken = "fixture-access",
                            privateKey = "synthetic-private-key",
                            publicKey = "synthetic-public-key",
                            peerPublicKey = "synthetic-peer-key",
                            interfaceAddressV4 = "172.16.0.2/32",
                        ),
                    )
                }
            val native =
                object : RipDpiWarpEndpointProbeBindings {
                    override fun probeEndpoint(
                        requestJson: String,
                        controller: WarpProbeSocketController,
                    ): String? = throw AssertionError("Closed protection owner must not enter native probing")
                }
            val probe = DefaultWarpEndpointProbe(TestAppSettingsRepository(), credentials, native, protection)

            val result =
                probe.probe(
                    WarpEndpointCacheEntry(
                        profileId = "warp",
                        networkScopeKey = "current",
                        host = "example.invalid",
                        port = 2408,
                    ),
                    250,
                )

            assertNull(result)
            org.junit.Assert.assertEquals("/tmp/cleanup-pending-vpn.sock", protection.current())
            protection.clear(owner)
        }
}
