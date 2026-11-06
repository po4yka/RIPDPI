package com.poyka.ripdpi.subscription

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.PayloadFailoverPolicy
import com.poyka.ripdpi.data.PayloadProbeControl
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.SelectorFailover
import com.poyka.ripdpi.data.Subscription
import com.poyka.ripdpi.data.selector.DefaultSelectorSelectionStore
import com.poyka.ripdpi.data.testPauseAuthority
import com.poyka.ripdpi.services.CandidateRelayProbeEnvironment
import com.poyka.ripdpi.services.RelayProbeEndpoint
import com.poyka.ripdpi.services.StrictPayloadSocksFixture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

/** Real repository/selection persistence and SOCKS/TLS bodies; no native engine or AndroidKeyStore claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class OfflineReservePayloadIntegrationTest {
    private lateinit var persistence: com.poyka.ripdpi.data.testing.RoomProfileFixture

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val body = "a".repeat(32_768)
    private var probeTimeMillis = 0L

    @Before
    fun createStorage() {
        persistence =
            com.poyka.ripdpi.data.testing
                .RoomProfileFixture(context, testPauseAuthority())
    }

    @After
    fun closeStorage() = persistence.close()

    @Test
    fun `reloaded reserve replaces truncated primary and choice survives another restart`() =
        runBlocking {
            StrictPayloadSocksFixture(List(4) { response(body.take(128)) }).use { primary ->
                StrictPayloadSocksFixture(List(2) { response(body) }).use { reserve ->
                    val group = persistAndReload(primary, reserve)
                    val store = restoredSelection(group)
                    val prober = prober(group, store, primary, reserve)

                    prober.runProbePass(group, "unused", 0)
                    assertEquals("primary", store.snapshot(group.id).profileId)
                    probeTimeMillis += 30_000
                    prober.runProbePass(group, "unused", 0)

                    assertEquals("reserve", store.snapshot(group.id).profileId)
                    assertEquals("reserve", selectionStore().snapshot(group.id).profileId)
                    assertFalse(selectionStore().snapshot(group.id).isManual)
                    assertEquals(
                        listOf("control-a.example.test", "control-b.example.test").repeatTwice(),
                        primary.hosts,
                    )
                    assertEquals(List(2) { "control-a.example.test" }, reserve.hosts)
                }
            }
        }

    @Test
    fun `reloaded all bad members preserve last active selection across restart`() =
        runBlocking {
            val corrupt = response("b".repeat(body.length))
            StrictPayloadSocksFixture(List(4) { corrupt }).use { primary ->
                StrictPayloadSocksFixture(List(4) { corrupt }).use { reserve ->
                    val group = persistAndReload(primary, reserve)
                    val store = restoredSelection(group)
                    val prober = prober(group, store, primary, reserve)

                    prober.runProbePass(group, "unused", 0)
                    probeTimeMillis += 60_000
                    prober.runProbePass(group, "unused", 0)

                    assertEquals("primary", store.snapshot(group.id).profileId)
                    assertEquals("primary", selectionStore().snapshot(group.id).profileId)
                    val expectedHosts = listOf("control-a.example.test", "control-b.example.test").repeatTwice()
                    assertEquals(expectedHosts, primary.hosts)
                    assertEquals(expectedHosts, reserve.hosts)
                }
            }
        }

    private suspend fun persistAndReload(
        primary: StrictPayloadSocksFixture,
        reserve: StrictPayloadSocksFixture,
    ): ProxyGroup {
        persistence.initialize()
        val policy =
            PayloadFailoverPolicy(
                schemaVersion = 2,
                selectorTag = "select",
                probes =
                    listOf("control-a", "control-b").map {
                        PayloadProbeControl(
                            "https://$it.example.test/payload",
                            body.length,
                            MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { byte ->
                                "%02x".format(byte)
                            },
                        )
                    },
                intervalSeconds = 30,
                timeoutSeconds = 3,
                failureThreshold = 2,
                recoveryThreshold = 2,
                cooldownSeconds = 60,
                maxBackoffSeconds = 120,
            )
        val accepted =
            ProxyGroup(
                id = "saved-reserves",
                name = "Saved reserves",
                type = ProxyGroupType.SUBSCRIPTION,
                order = 0,
                isSelector = true,
                subscription =
                    Subscription(
                        link = "https://subscription.example.test/unavailable",
                        token = "fixture-token",
                    ),
                members =
                    listOf("primary" to primary, "reserve" to reserve).map { (id, fixture) ->
                        ProxyProfile.Trojan(
                            id = id,
                            displayName = id,
                            groupId = "saved-reserves",
                            server = fixture.endpoint.host,
                            serverPort = fixture.endpoint.port,
                            password = "fixture-$id-password",
                            serverName = "$id.example.test",
                        )
                    },
                failover = SelectorFailover(policy.probes.first().url, 30, 0, policy),
            )
        val owner = persistence.groups
        owner.add(accepted)
        val stored = owner.list().single()
        assertTrue(stored.ownershipGeneration.isNotBlank())
        assertEquals(accepted.copy(ownershipGeneration = stored.ownershipGeneration), stored)
        persistence = persistence.reopen(testPauseAuthority())
        persistence.initialize()
        val reloaded = persistence.groups.list().single()
        assertEquals(stored, reloaded)
        return reloaded
    }

    private suspend fun restoredSelection(group: ProxyGroup): DefaultSelectorSelectionStore {
        val initial = selectionStore()
        assertTrue(initial.selectAutomatically(group.id, initial.snapshot(group.id), "primary"))
        return selectionStore().also { assertEquals("primary", it.snapshot(group.id).profileId) }
    }

    private suspend fun selectionStore(): DefaultSelectorSelectionStore {
        persistence = persistence.reopen(testPauseAuthority())
        persistence.initialize()
        return persistence.selectorSelections
    }

    private fun prober(
        group: ProxyGroup,
        store: DefaultSelectorSelectionStore,
        primary: StrictPayloadSocksFixture,
        reserve: StrictPayloadSocksFixture,
    ): SelectorUrltestProber {
        val scope =
            SelectorProbeScope(
                group.members,
                group.failover,
                group.cloudflareMemberIds,
                "opaque-test-network",
                1L,
                TestPhysicalNetworkEvidence().capture(),
                CandidateRelayProbeEnvironment(false, "chrome_stable", emptyMap(), false, false),
            )
        val fixtures = listOf(primary, reserve).associateBy { it.endpoint.port }
        return SelectorUrltestProber(
            store,
            MemberLatencyProbe { profile, target ->
                val member = profile as ProxyProfile.Trojan
                val payload = target as MemberProbeTarget.Payload
                val evidence =
                    fixtures.getValue(member.serverPort).httpProbe().probePayload(
                        RelayProbeEndpoint(member.server, member.serverPort),
                        payload.policy,
                    )
                if (evidence.succeeded) MemberProbeResult(evidence.latencyMillis) else null
            },
            SelectorProbeScopeProvider { scope },
            monotonicMillis = { probeTimeMillis },
        )
    }

    private fun response(payload: String) =
        "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$payload"

    private fun List<String>.repeatTwice() = this + this
}
