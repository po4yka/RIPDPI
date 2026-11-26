package com.poyka.ripdpi.subscription

import app.cash.turbine.test
import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import com.poyka.ripdpi.data.subscription.ImportRejection
import com.poyka.ripdpi.data.subscription.SingBoxSkipReason
import com.poyka.ripdpi.data.subscription.SingBoxSkippedNode
import com.poyka.ripdpi.proxyimport.PendingProxyImportStore
import com.poyka.ripdpi.proxyimport.PendingSubscriptionImportRequest
import com.poyka.ripdpi.ui.screens.proxyimport.ImportedServer
import com.poyka.ripdpi.ui.screens.proxyimport.ImportedServerSelectionResult
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
            assertEquals(
                0,
                (fixture.coordinator.refresh("subscription-group") as SubscriptionRefreshResult.Updated).memberCount,
            )

            val imported = fixture.coordinator.importLocalBundle("Home", bundle()) as SubscriptionRefreshResult.Updated
            assertEquals(0, imported.memberCount)

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
    fun `re-import under another file name updates the same group and keeps an entered AWG key`() =
        runTest {
            val fixture = fixture(1_800_000_000_000L)
            fixture.coordinator.importLocalBundle("ripdpi-bundle", bundle())
            val saved =
                fixture.awgRepository
                    .observeProfiles()
                    .first()
                    .single()
            fixture.awgRepository.save("User label", saved.request.copy(privateKey = "local-private-key"), saved.id)
            val groupsBefore = fixture.repository.list().map { it.id }

            assertTrue(
                fixture.coordinator.importLocalBundle("ripdpi-bundle (1)", bundle(psk = "rotated-psk"))
                    is SubscriptionRefreshResult.Updated,
            )

            assertEquals(groupsBefore, fixture.repository.list().map { it.id })
            assertTrue(fixture.repository.list().none { it.name == "ripdpi-bundle (1)" })
            val updated =
                fixture.awgRepository
                    .observeProfiles()
                    .first()
                    .single()
            assertEquals(saved.id, updated.id)
            assertEquals("local-private-key", updated.request.privateKey)
            assertEquals("rotated-psk", updated.request.presharedKey)
        }

    @Test
    fun `a bundle with other server tags becomes a second group even under the same name`() =
        runTest {
            val fixture = fixture(1_800_000_000_000L)
            fixture.coordinator.importLocalBundle("Home", bundle())

            assertTrue(
                fixture.coordinator.importLocalBundle("Home", bundle().replace("fleet-awg", "other-awg"))
                    is SubscriptionRefreshResult.Updated,
            )

            assertEquals(2, fixture.repository.list().count { it.name == "Home" && it.isLocalBundle() })
            assertEquals(
                setOf("fleet-awg", "other-awg"),
                fixture.awgRepository
                    .observeProfiles()
                    .first()
                    .map { it.name }
                    .toSet(),
            )
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
            for ((invalid, foundVersion) in listOf(
                bundle().replace("\"schema_version\":3", "\"schema_version\":99") to 99,
                "{\"ripdpi\":{}}" to null,
            )) {
                assertEquals(
                    SubscriptionRefreshResult.Failed(
                        SubscriptionRefreshFailure.PARSE_ERROR,
                        false,
                        ImportRejection.SchemaVersion(found = foundVersion, supported = 3),
                    ),
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
    fun `a bundle AmneziaWG profile without its key is handed to the key screen`() =
        runTest {
            val fixture = fixture(1_800_000_000_000L)
            val viewModel =
                SubscriptionImportConfirmViewModel(
                    repository = fixture.repository,
                    importLocalBundle = fixture.coordinator::importLocalBundle,
                )
            val store = PendingProxyImportStore()
            val request = PendingSubscriptionImportRequest("", "Home", false, bundle = bundle())
            assertTrue(viewModel.claimRequest(store.stageSubscription(request)!!, store))

            viewModel.confirm()
            val keyProfileId = viewModel.importedEvents.first()

            val saved =
                fixture.awgRepository
                    .observeProfiles()
                    .first()
                    .single { it.request.privateKey.isBlank() }
            assertEquals(saved.id, keyProfileId)
        }

    @Test
    fun `an entry the backend cannot run is skipped by tag and the rest import`() =
        runTest {
            val fixture = fixture(1_800_000_000_000L)
            val wsNode = SingBoxSkippedNode(1, "ws-node", SingBoxSkipReason.UNSUPPORTED_TRANSPORT, "ws")

            assertEquals(
                SubscriptionRefreshResult.Updated(1, listOf(wsNode)),
                fixture.coordinator.importLocalBundle("Home", relayBundle(usable = true)),
            )
            assertEquals(
                listOf("Fresh"),
                fixture.repository
                    .list()
                    .single { it.name == "Home" }
                    .members
                    .map { it.displayName },
            )

            val viewModel =
                SubscriptionImportConfirmViewModel(
                    repository = fixture.repository,
                    importLocalBundle = fixture.coordinator::importLocalBundle,
                )
            val store = PendingProxyImportStore()
            val request = PendingSubscriptionImportRequest("", "Other", false, bundle = relayBundle(usable = true))
            assertTrue(viewModel.claimRequest(store.stageSubscription(request)!!, store))
            viewModel.confirm()
            assertEquals(listOf(wsNode), viewModel.uiState.first { it.skipped.isNotEmpty() }.skipped)
        }

    @Test
    fun `a bundle import lists its servers and Connect selects one by its saved id`() =
        runTest {
            val fixture = fixture(1_800_000_000_000L)
            val selected = mutableListOf<String>()
            val viewModel =
                SubscriptionImportConfirmViewModel(
                    repository = fixture.repository,
                    importLocalBundle = fixture.coordinator::importLocalBundle,
                    serverSelection = { id ->
                        selected += id
                        ImportedServerSelectionResult.Selected
                    },
                )
            val store = PendingProxyImportStore()
            val request = PendingSubscriptionImportRequest("", "Home", false, bundle = relayBundle(usable = true))
            assertTrue(viewModel.claimRequest(store.stageSubscription(request)!!, store))

            viewModel.importedEvents.test {
                viewModel.confirm()
                val servers = viewModel.uiState.first { it.servers.isNotEmpty() }.servers
                val member =
                    fixture.repository
                        .list()
                        .single { it.name == "Home" }
                        .members
                        .single()
                assertEquals(listOf(ImportedServer(member.id, "Fresh")), servers)
                expectNoEvents()
            }

            viewModel.selectedEvents.test {
                viewModel.select(servers(viewModel).single().id, connect = true)
                assertEquals(true, awaitItem())
            }
            assertEquals(listOf(servers(viewModel).single().id), selected)
        }

    @Test
    fun `Connect on a bundle server does not start a service that already follows the selection`() =
        runTest {
            val fixture = fixture(1_800_000_000_000L)
            val viewModel =
                SubscriptionImportConfirmViewModel(
                    repository = fixture.repository,
                    importLocalBundle = fixture.coordinator::importLocalBundle,
                    serverSelection = { ImportedServerSelectionResult.AppliedToRunningService },
                )
            val store = PendingProxyImportStore()
            val request = PendingSubscriptionImportRequest("", "Home", false, bundle = relayBundle(usable = true))
            assertTrue(viewModel.claimRequest(store.stageSubscription(request)!!, store))
            viewModel.confirm()
            val id =
                viewModel.uiState
                    .first { it.servers.isNotEmpty() }
                    .servers
                    .single()
                    .id

            viewModel.selectedEvents.test {
                viewModel.select(id, connect = true)
                assertEquals(false, awaitItem())
            }
        }

    @Test
    fun `a bundle server that cannot be selected shows an error and stays on screen`() =
        runTest {
            val fixture = fixture(1_800_000_000_000L)
            val viewModel =
                SubscriptionImportConfirmViewModel(
                    repository = fixture.repository,
                    importLocalBundle = fixture.coordinator::importLocalBundle,
                    serverSelection = { ImportedServerSelectionResult.Failed },
                )
            val store = PendingProxyImportStore()
            val request = PendingSubscriptionImportRequest("", "Home", false, bundle = relayBundle(usable = true))
            assertTrue(viewModel.claimRequest(store.stageSubscription(request)!!, store))
            viewModel.confirm()
            val id =
                viewModel.uiState
                    .first { it.servers.isNotEmpty() }
                    .servers
                    .single()
                    .id

            viewModel.selectedEvents.test {
                viewModel.select(id, connect = false)
                assertTrue(viewModel.uiState.first { it.selectingServerId == null }.selectionFailed)
                expectNoEvents()
            }
        }

    @Test
    fun `a bundle with servers and a keyless AmneziaWG profile keeps the key handoff for when the result closes`() =
        runTest {
            val fixture = fixture(1_800_000_000_000L)
            val viewModel =
                SubscriptionImportConfirmViewModel(
                    repository = fixture.repository,
                    importLocalBundle = fixture.coordinator::importLocalBundle,
                )
            val fresh =
                """{"type":"shadowsocks","tag":"Fresh","server":"fresh.example","server_port":443,""" +
                    """"method":"aes-256-gcm","password":"fixture"}"""
            val store = PendingProxyImportStore()
            val request = PendingSubscriptionImportRequest("", "Home", false, bundle = bundle(outbounds = fresh))
            assertTrue(viewModel.claimRequest(store.stageSubscription(request)!!, store))

            viewModel.importedEvents.test {
                viewModel.confirm()
                val state = viewModel.uiState.first { it.servers.isNotEmpty() }
                val keyless =
                    fixture.awgRepository
                        .observeProfiles()
                        .first()
                        .single { it.request.privateKey.isBlank() }
                assertEquals(listOf("Fresh"), state.servers.map { it.name })
                assertEquals(keyless.id, state.awgKeyProfileId)
                expectNoEvents()
            }
        }

    private fun servers(viewModel: SubscriptionImportConfirmViewModel) = viewModel.uiState.value.servers

    @Test
    fun `a bundle with no usable entry is refused and names the entry`() =
        runTest {
            val fixture = fixture(1_800_000_000_000L)
            val groupsBefore = fixture.repository.list()
            val rejection =
                ImportRejection.Entry(SingBoxSkippedNode(0, "ws-node", SingBoxSkipReason.UNSUPPORTED_TRANSPORT, "ws"))

            assertEquals(
                SubscriptionRefreshResult.Failed(SubscriptionRefreshFailure.PARSE_ERROR, false, rejection),
                fixture.coordinator.importLocalBundle("Home", relayBundle(usable = false)),
            )
            assertEquals(groupsBefore, fixture.repository.list())

            val viewModel =
                SubscriptionImportConfirmViewModel(
                    repository = fixture.repository,
                    importLocalBundle = fixture.coordinator::importLocalBundle,
                )
            val store = PendingProxyImportStore()
            val request = PendingSubscriptionImportRequest("", "Home", false, bundle = relayBundle(usable = false))
            assertTrue(viewModel.claimRequest(store.stageSubscription(request)!!, store))
            viewModel.confirm()
            assertEquals(rejection, viewModel.uiState.first { it.importFailed }.rejection)
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

    private fun relayBundle(usable: Boolean): String {
        val fresh =
            """{"type":"shadowsocks","tag":"Fresh","server":"fresh.example","server_port":443,""" +
                """"method":"aes-256-gcm","password":"fixture"},"""
        val wsNode =
            """{"type":"vless","tag":"ws-node","server":"ws.example","server_port":443,""" +
                """"uuid":"11111111-1111-1111-1111-111111111111","transport":{"type":"ws"}}"""
        return """{"outbounds":[${if (usable) fresh else ""}$wsNode],""" +
            """"ripdpi":{"schema_version":3,"amneziawg":[],"hysteria_extras":{}}}"""
    }

    private fun bundle(
        endpoint: String = "192.0.2.10:51820",
        psk: String = "original-psk",
        outbounds: String = "",
    ): String =
        """
        {"outbounds":[$outbounds],"ripdpi":{"schema_version":3,"amneziawg":[{
          "tag":"fleet-awg","wire_profile":"awg-3.1-ranges-v1","jmin":0,"jmax":0,"s1":0,"s2":0,"h1":"1","h2":"2","h3":"3","h4":"4","private_key_placeholder":true,"address": ["10.8.0.2/32", "fd77::2/128"],"mtu":1330,"jc":4,
          "peer":{"public_key":"CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC=",
          "endpoint":"$endpoint","preshared_key":"$psk","allowed_ips":["0.0.0.0/0"]}
        }],"hysteria_extras":{}}}
        """.trimIndent()
}
