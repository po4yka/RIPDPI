package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import com.poyka.ripdpi.proxyimport.PendingProxyImportStore
import com.poyka.ripdpi.proxyimport.PendingSubscriptionImportRequest
import com.poyka.ripdpi.ui.screens.proxyimport.SubscriptionImportConfirmViewModel
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionLocalBundleImportTest : SubscriptionRefreshTestSupport() {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `a commit that fails after the group is added discards that group`() =
        runTest {
            val fixture = fixture(1_800_000_000_000L)
            val discarded = mutableListOf<String>()
            val viewModel =
                SubscriptionImportConfirmViewModel(
                    repository = fixture.repository,
                    discardGroup = { discarded += it },
                    importLocalBundle = { name, text ->
                        fixture.coordinator.importLocalBundle(name, text)
                        error("commit failed")
                    },
                )
            val store = PendingProxyImportStore()
            val token = store.stageSubscription(PendingSubscriptionImportRequest("", "", false, bundle = bundle()))!!
            assertTrue(viewModel.claimRequest(token, store))
            assertEquals("RIPDPI", viewModel.uiState.value.name)

            viewModel.confirm()
            viewModel.uiState.first { !it.importing }

            assertTrue(viewModel.uiState.value.importFailed)
            assertEquals(
                listOf(
                    fixture.repository
                        .list()
                        .single { it.name == "RIPDPI" }
                        .id,
                ),
                discarded,
            )
        }

    @Test
    fun `local bundle persists the same AWG profile as the https path`() =
        runTest {
            val fixture = fixture(1_800_000_000_000L)
            server.enqueue(response(200, bundle()))
            assertEquals(SubscriptionRefreshResult.Updated(0), fixture.coordinator.refresh("subscription-group"))

            assertEquals(SubscriptionRefreshResult.Updated(0), fixture.coordinator.importLocalBundle("Home", bundle()))

            val local = fixture.repository.list().single { it.name == "Home" }
            val subscription = local.subscription!!
            assertTrue(local.isLocalBundle())
            assertEquals("", subscription.link)
            assertFalse(subscription.autoUpdate)
            assertEquals(null, subscription.lastRefreshFailure)
            assertTrue(
                subscriptionsDueForAutoUpdate(fixture.repository.list(), 1_800_000_000_000L).none {
                    it.id ==
                        local.id
                },
            )
            // One row per source group, with identical parsed content.
            val rows = fixture.awgRepository.observeProfiles().first()
            assertEquals(2, rows.size)
            assertEquals(rows[0].request.copy(profileId = rows[1].request.profileId), rows[1].request)
            assertEquals(rows[0].name, rows[1].name)
        }

    @Test
    fun `re-import updates the same group and keeps an entered AWG key`() =
        runTest {
            val fixture = fixture(1_800_000_000_000L)
            fixture.coordinator.importLocalBundle("Home", bundle())
            val saved =
                fixture.awgRepository
                    .observeProfiles()
                    .first()
                    .single()
            fixture.awgRepository.save("User label", saved.request.copy(privateKey = "local-private-key"), saved.id)
            val groupsBefore = fixture.repository.list().size

            assertEquals(
                SubscriptionRefreshResult.Updated(0),
                fixture.coordinator.importLocalBundle("Home", bundle("192.0.2.20:443", "rotated-psk")),
            )

            assertEquals(groupsBefore, fixture.repository.list().size)
            val updated =
                fixture.awgRepository
                    .observeProfiles()
                    .first()
                    .single()
            assertEquals(saved.id, updated.id)
            assertEquals("local-private-key", updated.request.privateKey)
            assertEquals("rotated-psk", updated.request.presharedKey)
            assertEquals("192.0.2.20", updated.request.endpointHost)
        }

    @Test
    fun `invalid or unsupported bundle persists nothing`() =
        runTest {
            val fixture = fixture(1_800_000_000_000L)
            val groupsBefore = fixture.repository.list()
            for (invalid in listOf(
                bundle().replace("\"schema_version\":2", "\"schema_version\":99"),
                "{\"ripdpi\":{}}",
            )) {
                assertEquals(
                    SubscriptionRefreshResult.Failed(SubscriptionRefreshFailure.PARSE_ERROR, false),
                    fixture.coordinator.importLocalBundle("Home", invalid),
                )
            }
            assertEquals(groupsBefore, fixture.repository.list())
            assertTrue(
                fixture.awgRepository
                    .observeProfiles()
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun `manual refresh of a local bundle records no failure`() =
        runTest {
            val fixture = fixture(1_800_000_000_000L)
            fixture.coordinator.importLocalBundle("Home", bundle())
            val local = fixture.repository.list().single { it.name == "Home" }

            fixture.coordinator.refresh(local.id)

            assertEquals(local, fixture.repository.list().single { it.id == local.id })
        }

    private fun bundle(
        endpoint: String = "192.0.2.10:51820",
        psk: String = "original-psk",
    ): String =
        """
        {"outbounds":[],"ripdpi":{"schema_version":2,"amneziawg":[{
          "tag":"fleet-awg","wire_profile":"awg-3.1-ranges-v1","jmin":0,"jmax":0,"s1":0,"s2":0,"h1":"1","h2":"2","h3":"3","h4":"4","private_key_placeholder":true,"address": ["10.8.0.2/32", "fd77::2/128"],"mtu":1330,"jc":4,
          "peer":{"public_key":"CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC=",
          "endpoint":"$endpoint","preshared_key":"$psk","allowed_ips":["0.0.0.0/0"]}
        }],"hysteria_extras":{}}}
        """.trimIndent()
}
