package com.poyka.ripdpi.subscription

import app.cash.turbine.test
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import com.poyka.ripdpi.ui.screens.proxyimport.SubscriptionImportConfirmViewModel
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionInitialImportTest : SubscriptionRefreshTestSupport() {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `confirmed ordinary import fetches and saves members before success`() =
        runTest {
            val fixture = fixture(2_000L)
            val scheduled = mutableListOf<List<ProxyGroup>>()
            val viewModel = viewModel(fixture, scheduleAutoUpdate = { scheduled.add(it) })
            viewModel.setRequest(server.url("/new-subscription").toString(), "New fleet", bootstrap = false)
            assertEquals(0, server.requestCount)
            assertTrue(scheduled.isEmpty())
            server.enqueue(response(200, trojanPayload))

            viewModel.importedEvents.test {
                viewModel.confirm()
                awaitItem()
                val group = fixture.repository.list().single { it.id == "imported" }
                assertEquals(1, group.members.size)
                assertEquals(2_000L, group.subscription?.lastUpdated)
                assertTrue(group.subscription!!.autoUpdate)
                assertEquals(fixture.repository.list(), scheduled.single())
                assertTrue(subscriptionsDueForAutoUpdate(scheduled.single()).any { it.id == group.id })
                expectNoEvents()
            }
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `failed initial fetch stays on screen and retry reuses the same group`() =
        runTest {
            val fixture = fixture(2_000L)
            val scheduled = mutableListOf<List<ProxyGroup>>()
            val viewModel = viewModel(fixture, scheduleAutoUpdate = { scheduled.add(it) })
            viewModel.setRequest(server.url("/new-subscription").toString(), "New fleet", bootstrap = false)
            server.enqueue(response(403))

            viewModel.importedEvents.test {
                viewModel.confirm()
                viewModel.uiState.first { !it.importing }
                assertTrue(viewModel.uiState.value.importFailed)
                expectNoEvents()
                val failed = fixture.repository.list().single { it.id == "imported" }
                assertEquals(SubscriptionRefreshFailure.REVOKED, failed.subscription?.lastRefreshFailure)
                assertEquals(fixture.repository.list(), scheduled.single())
                assertFalse(subscriptionsDueForAutoUpdate(scheduled.single()).any { it.id == failed.id })

                server.enqueue(response(200, trojanPayload))
                viewModel.confirm()
                awaitItem()
                assertFalse(viewModel.uiState.value.importFailed)
                assertEquals(2, fixture.repository.list().size)
                assertEquals(2, scheduled.size)
                assertTrue(subscriptionsDueForAutoUpdate(scheduled.last()).any { it.id == failed.id })
                assertEquals(
                    1,
                    fixture.repository
                        .list()
                        .single { it.id == failed.id }
                        .members.size,
                )
            }
        }

    @Test
    fun `empty response does not report a successful empty import`() =
        runTest {
            val fixture = fixture(2_000L)
            val viewModel = viewModel(fixture)
            viewModel.setRequest(server.url("/new-subscription").toString(), "New fleet", bootstrap = false)
            server.enqueue(response(200, ""))

            viewModel.importedEvents.test {
                viewModel.confirm()
                viewModel.uiState.first { !it.importing }
                assertTrue(viewModel.uiState.value.importFailed)
                expectNoEvents()
            }
        }

    @Test
    fun `reimport of renewed URL uses manual recovery on the existing group`() =
        runTest {
            val fixture = fixture(2_000L, initialFailure = SubscriptionRefreshFailure.EXPIRED)
            fixture.repository.updateSubscription("subscription-group") { it.copy(tokenExpiresAtEpochMillis = 1_000L) }
            val viewModel = viewModel(fixture)
            viewModel.setRequest(server.url("/subscription").toString(), "Renewed fleet", bootstrap = false)
            server.enqueue(response(200, trojanPayload))

            viewModel.importedEvents.test {
                viewModel.confirm()
                awaitItem()
                val group = fixture.repository.list().single()
                assertEquals("subscription-group", group.id)
                assertEquals(1, group.members.size)
                assertEquals(2_000L, group.subscription?.lastUpdated)
            }
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `reimport preserves an existing subscription auto-update opt-out`() =
        runTest {
            val fixture = fixture(2_000L)
            fixture.repository.updateSubscription("subscription-group") { it.copy(autoUpdate = false) }
            val scheduled = mutableListOf<List<ProxyGroup>>()
            val viewModel = viewModel(fixture, scheduleAutoUpdate = { scheduled.add(it) })
            viewModel.setRequest(server.url("/subscription").toString(), "Existing fleet", bootstrap = false)
            server.enqueue(response(200, trojanPayload))

            viewModel.importedEvents.test {
                viewModel.confirm()
                awaitItem()
                assertFalse(
                    fixture.repository
                        .list()
                        .single()
                        .subscription!!
                        .autoUpdate,
                )
                assertEquals(fixture.repository.list(), scheduled.single())
                assertTrue(subscriptionsDueForAutoUpdate(scheduled.single()).isEmpty())
            }
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `transient initial failure still schedules the saved subscription for retry`() =
        runTest {
            val fixture = fixture(2_000L)
            val scheduled = mutableListOf<List<ProxyGroup>>()
            val viewModel = viewModel(fixture, scheduleAutoUpdate = { scheduled.add(it) })
            viewModel.setRequest(server.url("/new-subscription").toString(), "New fleet", bootstrap = false)
            server.enqueue(response(503))

            viewModel.importedEvents.test {
                viewModel.confirm()
                viewModel.uiState.first { !it.importing }
                assertTrue(viewModel.uiState.value.importFailed)
                val imported = fixture.repository.list().single { it.id == "imported" }
                assertEquals(SubscriptionRefreshFailure.SERVER_ERROR, imported.subscription?.lastRefreshFailure)
                assertEquals(fixture.repository.list(), scheduled.single())
                assertTrue(subscriptionsDueForAutoUpdate(scheduled.single()).any { it.id == imported.id })
                expectNoEvents()
            }
        }

    @Test
    fun `AWG-only ordinary import succeeds after the separate profiles are persisted`() =
        runTest {
            val fixture = fixture(2_000L)
            val viewModel = viewModel(fixture)
            viewModel.setRequest(server.url("/new-subscription").toString(), "AWG fleet", bootstrap = false)
            server.enqueue(response(200, AwgPayload))

            viewModel.importedEvents.test {
                viewModel.confirm()
                awaitItem()
                assertTrue(
                    fixture.repository
                        .list()
                        .single { it.id == "imported" }
                        .members
                        .isEmpty(),
                )
                assertEquals(
                    1,
                    fixture.awgRepository
                        .observeProfiles()
                        .first()
                        .size,
                )
            }
        }

    @Test
    fun `a first fetch that is not a subscription discards the new group`() =
        runTest {
            val fixture = fixture(2_000L)
            val discarded = mutableListOf<String>()
            val viewModel = viewModel(fixture, discardGroup = { discarded += it })
            viewModel.setRequest(server.url("/menu").toString(), "", bootstrap = false)
            server.enqueue(response(200, "<html><body>Today's menu</body></html>"))

            viewModel.confirm()
            viewModel.uiState.first { !it.importing }

            assertTrue(viewModel.uiState.value.importFailed)
            assertEquals(listOf("imported"), discarded)
        }

    @Test
    fun `a transient first failure keeps the new group for retry`() =
        runTest {
            val fixture = fixture(2_000L)
            val discarded = mutableListOf<String>()
            val viewModel = viewModel(fixture, discardGroup = { discarded += it })
            viewModel.setRequest(server.url("/new-subscription").toString(), "", bootstrap = false)
            server.enqueue(response(503))

            viewModel.confirm()
            viewModel.uiState.first { !it.importing }

            assertTrue(discarded.isEmpty())
        }

    @Test
    fun `server title names an auto-named group`() =
        runTest {
            val fixture = fixture(2_000L)
            val viewModel = viewModel(fixture)
            viewModel.setRequest(server.url("/new-subscription").toString(), "", bootstrap = false)
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(trojanPayload)
                    .addHeader("Profile-Title", "base64:" + Base64.getEncoder().encodeToString("Fleet ✓".toByteArray()))
                    .build(),
            )

            viewModel.importedEvents.test {
                viewModel.confirm()
                awaitItem()
            }

            val group = fixture.repository.list().single { it.id == "imported" }
            assertEquals("Fleet ✓", group.name)
        }

    @Test
    fun `a name the user chose is kept despite a server title`() =
        runTest {
            val fixture = fixture(2_000L)
            val viewModel = viewModel(fixture)
            viewModel.setRequest(server.url("/new-subscription").toString(), "Mine", bootstrap = false)
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(trojanPayload)
                    .addHeader("Profile-Title", "Theirs")
                    .build(),
            )

            viewModel.importedEvents.test {
                viewModel.confirm()
                awaitItem()
            }

            assertEquals(
                "Mine",
                fixture.repository
                    .list()
                    .single { it.id == "imported" }
                    .name,
            )
        }

    @Test
    fun `cancelled refresh does not report success or failure`() =
        runTest {
            val fixture = fixture(2_000L)
            var calls = 0
            val viewModel =
                SubscriptionImportConfirmViewModel(
                    repository = fixture.repository,
                    refreshSubscription = {
                        calls++
                        throw CancellationException("test cancellation")
                    },
                )
            viewModel.setRequest(server.url("/new-subscription").toString(), "New fleet", bootstrap = false)

            viewModel.importedEvents.test {
                viewModel.confirm()
                advanceUntilIdle()
                assertEquals(1, calls)
                assertFalse(viewModel.uiState.value.importing)
                assertFalse(viewModel.uiState.value.importFailed)
                expectNoEvents()
            }
        }

    private fun viewModel(
        fixture: Fixture,
        scheduleAutoUpdate: (List<ProxyGroup>) -> Unit = {},
        discardGroup: suspend (String) -> Unit = {},
    ) = SubscriptionImportConfirmViewModel(
        repository = fixture.repository,
        groupIdFactory = { "imported" },
        refreshSubscription = fixture.coordinator::refresh,
        scheduleAutoUpdate = scheduleAutoUpdate,
        discardGroup = discardGroup,
    )

    private companion object {
        const val AwgPayload =
            """{"outbounds":[],"ripdpi":{"schema_version":2,"amneziawg":[{
              "tag":"initial-awg","wire_profile":"awg-3.1-ranges-v1","jc":0,"jmin":0,"jmax":0,"s1":0,"s2":0,"h1":"1","h2":"2","h3":"3","h4":"4","private_key":"BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",
              "address": ["10.8.0.2/32", "fd77::2/128"],"mtu":1330,"peer":{
                "public_key":"CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC=",
                "endpoint":"192.0.2.10:51820","allowed_ips":["0.0.0.0/0"]
              }}]}}"""
    }
}
