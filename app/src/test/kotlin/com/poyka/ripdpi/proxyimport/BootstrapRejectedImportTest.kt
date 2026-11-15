package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.Subscription
import com.poyka.ripdpi.data.SubscriptionKind
import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import com.poyka.ripdpi.data.subscription.BootstrapConsumer
import com.poyka.ripdpi.ui.screens.proxyimport.SubscriptionImportConfirmViewModel
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BootstrapRejectedImportTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `rejected delivered payload keeps a durable spent receipt and never reports success on reconfirm`() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().body("trojan://fixture@node.example:443#node\ninvalid").build())
                server.start()
                val repository = FakeProxyGroupRepository()
                val url = server.url("/bootstrap/rejected").toString()

                fun screen() =
                    SubscriptionImportConfirmViewModel(
                        repository = repository,
                        bootstrapConsumer = BootstrapConsumer(clockMillis = { 42L }),
                        groupIdFactory = { "rejected" },
                    ).apply { setRequest(url, "Rejected", bootstrap = true) }

                val first = screen()
                first.confirm()
                first.uiState.first { it.importFailed && !it.importing }

                val saved = repository.list().single()
                assertTrue(saved.members.isEmpty())
                assertEquals(42L, saved.subscription?.consumedAt)
                assertEquals(SubscriptionRefreshFailure.PARSE_ERROR, saved.subscription?.lastRefreshFailure)
                val reopened = screen()
                reopened.confirm()
                reopened.uiState.first { it.importFailed && !it.importing }
                assertEquals(1, server.requestCount)
                assertTrue(reopened.uiState.value.importFailed)
            }
        }

    @Test
    fun `a rejected delivery receipt preserves concurrent edits deletion and delivery changes`() =
        runTest {
            for (change in listOf("member", "delete", "url", "delivery")) {
                MockWebServer().use { server ->
                    val release = CountDownLatch(1)
                    server.dispatcher =
                        object : Dispatcher() {
                            override fun dispatch(request: RecordedRequest): MockResponse {
                                check(release.await(5, TimeUnit.SECONDS))
                                return MockResponse.Builder().body("invalid-payload").build()
                            }
                        }
                    server.start()
                    val url = server.url("/bootstrap/edit-race").toString()
                    val repository = FakeProxyGroupRepository()
                    val old = ProxyProfile.Trojan("old", "Old", "g", "old.example", 443, "fixture")
                    val edited = old.copy(id = "edited", displayName = "Edited", server = "edited.example")
                    repository.add(
                        ProxyGroup(
                            "g",
                            "Group",
                            ProxyGroupType.SUBSCRIPTION,
                            0,
                            true,
                            subscription = Subscription(link = url, kind = SubscriptionKind.BOOTSTRAP),
                            members = listOf(old),
                            ownershipGeneration = "original",
                        ),
                    )
                    val screen =
                        SubscriptionImportConfirmViewModel(repository, BootstrapConsumer(clockMillis = { 42L }))
                    screen.setRequest(url, "Group", bootstrap = true)
                    screen.confirm()
                    try {
                        withContext(Dispatchers.IO) { checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
                        when (change) {
                            "member" -> {
                                repository.updateGroup(
                                    "g",
                                ) { it.copy(members = listOf(edited), ownershipGeneration = "edited") }
                            }

                            "delete" -> {
                                repository.compensateReplacement(emptyList())
                            }

                            "url" -> {
                                repository.updateGroup("g") {
                                    it.copy(
                                        subscription =
                                            it.subscription!!.copy(
                                                link = "https://new.example/bootstrap/token",
                                            ),
                                    )
                                }
                            }

                            "delivery" -> {
                                repository.updateGroup("g") {
                                    it.copy(subscription = it.subscription!!.copy(customUserAgent = "changed"))
                                }
                            }
                        }
                    } finally {
                        release.countDown()
                    }
                    screen.uiState.first { it.importFailed && !it.importing }
                    when (change) {
                        "delete" -> {
                            assertTrue(repository.list().isEmpty())
                        }

                        "member" -> {
                            val saved = repository.list().single()
                            assertEquals(listOf(edited), saved.members)
                            assertNull(saved.subscription?.consumedAt)
                        }

                        else -> {
                            val saved = repository.list().single()
                            assertEquals(listOf(old), saved.members)
                            assertNull(saved.subscription?.consumedAt)
                            assertNull(saved.subscription?.lastRefreshFailure)
                        }
                    }
                }
            }
        }
}
