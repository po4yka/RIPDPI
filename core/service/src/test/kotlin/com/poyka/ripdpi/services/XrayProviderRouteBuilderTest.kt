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
    fun `NoProfile when no durable profile persisted`() =
        runTest {
            val result = XrayProviderRouteBuilder(store).build("missing")
            assertEquals(XrayProviderRouteBuilder.Result.NoProfile, result)
        }

    @Test
    fun `Resolved aligns the route inbound port with the profile`() =
        runTest {
            store.save("default", validProfile)
            val result = XrayProviderRouteBuilder(store).build("default")
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
            val result = XrayProviderRouteBuilder(store).build("bad")
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

    override suspend fun load(profileId: String): XrayProfile? = profiles[profileId]

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
