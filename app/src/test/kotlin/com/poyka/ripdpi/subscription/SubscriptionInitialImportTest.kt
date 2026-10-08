package com.poyka.ripdpi.subscription

import app.cash.turbine.test
import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import com.poyka.ripdpi.ui.screens.proxyimport.SubscriptionImportConfirmViewModel
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionInitialImportTest : SubscriptionRefreshTestSupport() {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `confirmed ordinary import fetches and saves members before success`() =
        runTest {
            val fixture = fixture(2_000L)
            val viewModel = viewModel(fixture)
            viewModel.setRequest(server.url("/new-subscription").toString(), "New fleet", bootstrap = false)
            assertEquals(0, server.requestCount)
            server.enqueue(response(200, trojanPayload))

            viewModel.importedEvents.test {
                viewModel.confirm()
                awaitItem()
                val group = fixture.repository.list().single { it.id == "imported" }
                assertEquals(1, group.members.size)
                assertEquals(2_000L, group.subscription?.lastUpdated)
                assertFalse(group.subscription!!.autoUpdate)
                expectNoEvents()
            }
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `failed initial fetch stays on screen and retry reuses the same group`() =
        runTest {
            val fixture = fixture(2_000L)
            val viewModel = viewModel(fixture)
            viewModel.setRequest(server.url("/new-subscription").toString(), "New fleet", bootstrap = false)
            server.enqueue(response(403))

            viewModel.importedEvents.test {
                viewModel.confirm()
                viewModel.uiState.first { !it.importing }
                assertTrue(viewModel.uiState.value.importFailed)
                expectNoEvents()
                val failed = fixture.repository.list().single { it.id == "imported" }
                assertEquals(SubscriptionRefreshFailure.REVOKED, failed.subscription?.lastRefreshFailure)

                server.enqueue(response(200, trojanPayload))
                viewModel.confirm()
                awaitItem()
                assertFalse(viewModel.uiState.value.importFailed)
                assertEquals(2, fixture.repository.list().size)
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

    private fun viewModel(fixture: Fixture) =
        SubscriptionImportConfirmViewModel(
            repository = fixture.repository,
            groupIdFactory = { "imported" },
            refreshSubscription = fixture.coordinator::refresh,
        )

    private companion object {
        const val AwgPayload =
            """{"outbounds":[],"ripdpi":{"schema_version":1,"amneziawg":[{
              "tag":"initial-awg","private_key":"BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",
              "address":["10.8.0.2/32"],"peer":{
                "public_key":"CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC=",
                "endpoint":"192.0.2.10:51820","allowed_ips":["0.0.0.0/0"]
              }}]}}"""
    }
}
