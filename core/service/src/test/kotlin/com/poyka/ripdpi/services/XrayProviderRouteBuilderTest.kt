package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.xray.DurableXrayProfileStore
import com.poyka.ripdpi.data.xray.VpnProviderKind
import com.poyka.ripdpi.data.xray.XrayProfile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Route builder: renders from the durable profile, never throws / leaks the
 * secret config on rejection, and aligns the route inbound port with the
 * profile (decision 3).
 */
class XrayProviderRouteBuilderTest {
    private val store = FakeDurableXrayProfileStore()

    private val validProfile =
        XrayProfile(
            name = "Tokyo",
            outbound =
                XrayProfile.Outbound(
                    serverAddress = "edge.example.com",
                    serverPort = 8443,
                    uuid = "11111111-2222-3333-4444-555555555555",
                    flow = "xtls-rprx-vision",
                    security = XrayProfile.Security.REALITY,
                    network = XrayProfile.Network.TCP,
                    reality =
                        XrayProfile.Reality(
                            publicKey = "PUBKEY_SECRET",
                            serverName = "www.cloudflare.com",
                            shortId = "ab12",
                        ),
                ),
            inbound = XrayProfile.LocalInbound(port = 10810),
        )

    @Test
    fun `relay endpoint is transiently resolved while profile DNS and SNI remain intact`() =
        runTest {
            val profile =
                validProfile.copy(
                    dns = XrayProfile.DnsSettings(servers = listOf("https://dns.example/dns-query")),
                )
            store.save("default", profile)
            val requested = mutableListOf<String>()
            val builder =
                XrayProviderRouteBuilder(store, resolveEndpoint = {
                    requested += it
                    listOf("192.0.2.${requested.size}")
                })
            val first = builder.build("default") as XrayProviderRouteBuilder.Result.Resolved
            val second = builder.build("default") as XrayProviderRouteBuilder.Result.Resolved
            assertTrue(first.renderedConfig.contains("192.0.2.1"))
            assertTrue(second.renderedConfig.contains("192.0.2.2"))
            assertTrue(first.renderedConfig.contains("www.cloudflare.com"))
            assertTrue(first.renderedConfig.contains("https://dns.example/dns-query"))
            assertEquals(listOf("edge.example.com", "edge.example.com"), requested)
            assertEquals(profile, store.load("default"))
        }

    @Test
    fun `numeric endpoint needs no DNS and empty bootstrap is rejected`() =
        runTest {
            val builder = XrayProviderRouteBuilder(store, resolveEndpoint = { emptyList() })
            store.save("numeric", validProfile.copy(outbound = validProfile.outbound.copy(serverAddress = "192.0.2.1")))
            assertTrue(builder.build("numeric") is XrayProviderRouteBuilder.Result.Resolved)
            store.save("hostname", validProfile)
            assertTrue(runCatching { builder.build("hostname") }.isFailure)
        }

    @Test
    fun `implicit TLS server name survives numeric endpoint replacement`() =
        runTest {
            val profile =
                validProfile.copy(
                    outbound =
                        validProfile.outbound.copy(
                            security = XrayProfile.Security.TLS,
                            reality = null,
                            tls = XrayProfile.Tls(serverName = ""),
                        ),
                )
            store.save("tls", profile)
            val builder = XrayProviderRouteBuilder(store, resolveEndpoint = { listOf("192.0.2.1") })
            val resolved = builder.build("tls") as XrayProviderRouteBuilder.Result.Resolved
            assertTrue(resolved.renderedConfig.contains("\"serverName\":\"edge.example.com\""))
            assertTrue(resolved.renderedConfig.contains("192.0.2.1"))
        }

    @Test
    fun `implicit server identity uses active security even with absent TLS settings`() =
        runTest {
            val reality = checkNotNull(validProfile.outbound.reality)
            val outbounds =
                listOf(
                    validProfile.outbound.copy(reality = reality.copy(serverName = "")),
                    validProfile.outbound.copy(security = XrayProfile.Security.TLS, tls = null),
                    validProfile.outbound.copy(
                        security = XrayProfile.Security.TLS,
                        network = XrayProfile.Network.XHTTP,
                        tls = XrayProfile.Tls(serverName = ""),
                        xhttp = XrayProfile.Xhttp(),
                    ),
                )
            val builder = XrayProviderRouteBuilder(store, resolveEndpoint = { listOf("192.0.2.1") })
            for (outbound in outbounds) {
                val profile = validProfile.copy(outbound = outbound)
                store.save("implicit", profile)
                val resolved = builder.build("implicit") as XrayProviderRouteBuilder.Result.Resolved
                assertTrue(resolved.renderedConfig.contains("\"serverName\":\"edge.example.com\""))
                if (outbound.network == XrayProfile.Network.XHTTP) {
                    assertTrue(resolved.renderedConfig.contains("\"host\":\"edge.example.com\""))
                }
                assertEquals(profile, store.load("implicit"))
            }
        }

    @Test
    fun `NoProfile when no durable profile persisted`() =
        runTest {
            val result = XrayProviderRouteBuilder(store, resolveEndpoint = { listOf("192.0.2.1") }).build("missing")
            assertEquals(XrayProviderRouteBuilder.Result.NoProfile, result)
        }

    @Test
    fun `Resolved aligns the route inbound port with the profile`() =
        runTest {
            store.save("default", validProfile)
            val result = XrayProviderRouteBuilder(store, resolveEndpoint = { listOf("192.0.2.1") }).build("default")
            assertTrue(result is XrayProviderRouteBuilder.Result.Resolved)
            val resolved = result as XrayProviderRouteBuilder.Result.Resolved
            assertEquals(VpnProviderKind.Xray, resolved.route.kind)
            assertEquals(10810, resolved.route.xrayConfig.localInboundPort)
        }

    @Test
    fun `Rejected carries findings but never the secret config`() =
        runTest {
            // VLESS flow empty -> validator rejects with VLESS_FLOW_MISSING.
            val invalid =
                validProfile.copy(outbound = validProfile.outbound.copy(flow = ""))
            store.save("bad", invalid)
            val result = XrayProviderRouteBuilder(store, resolveEndpoint = { listOf("192.0.2.1") }).build("bad")
            assertTrue(result is XrayProviderRouteBuilder.Result.Rejected)
            val rejected = result as XrayProviderRouteBuilder.Result.Rejected
            assertTrue(rejected.findings.isNotEmpty())
            // The rejection must never carry the UUID / key (only typed findings).
            val asText = rejected.findings.joinToString { "${it.code} ${it.path} ${it.message}" }
            assertFalse(asText.contains("11111111-2222-3333"))
            assertFalse(asText.contains("PUBKEY_SECRET"))
        }
}

internal class FakeDurableXrayProfileStore : DurableXrayProfileStore {
    private val profiles = mutableMapOf<String, XrayProfile>()
    var onLoad: suspend () -> Unit = {}

    override suspend fun load(profileId: String): XrayProfile? {
        onLoad()
        return profiles[profileId]
    }

    override suspend fun save(
        profileId: String,
        profile: XrayProfile,
    ) {
        profiles[profileId] = profile
    }

    override suspend fun clear(profileId: String) {
        profiles.remove(profileId)
    }

    override suspend fun listProfileIds(): List<String> = profiles.keys.sorted()
}
