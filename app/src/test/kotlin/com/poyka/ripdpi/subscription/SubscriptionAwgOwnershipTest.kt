package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import com.poyka.ripdpi.data.awg.requireRuntimeReady
import com.poyka.ripdpi.data.testMutationPreparationSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class SubscriptionAwgOwnershipTest : SubscriptionRefreshTestSupport() {
    @Test
    fun `delayed valid AWG rotation cannot mutate a replaced source`() =
        runTest {
            val boundary = ResponseBoundary()
            val fixture = fixture(3_000L, httpClient = boundary.client)
            server.enqueue(response(200, ini("192.0.2.10:51820", "original-psk")))
            assertEquals(SubscriptionRefreshResult.Updated(0), fixture.coordinator.refresh("subscription-group"))
            val original =
                fixture.awgRepository
                    .observeProfiles()
                    .first()
                    .single()
            val replacement =
                fixture.repository.list().single().let {
                    it.copy(subscription = it.subscription!!.copy(link = server.url("/replacement").toString()))
                }
            boundary.beforeSecondDelivery = { fixture.repository.update(replacement) }
            server.enqueue(response(200, ini("192.0.2.20:443", "rotated-psk")))

            assertEquals(SubscriptionRefreshResult.Superseded, fixture.coordinator.refresh(replacement.id))
            assertEquals(replacement, fixture.repository.list().single())
            assertEquals(original, fixture.awgRepository.load(original.id))
            assertEquals(listOf(original), fixture.awgRepository.observeProfiles().first())
        }

    @Test
    fun `delayed valid AWG rotation cannot mutate an edited group`() =
        runTest {
            val boundary = ResponseBoundary()
            val fixture = fixture(3_000L, httpClient = boundary.client)
            server.enqueue(response(200, ini("192.0.2.10:51820", "original-psk")))
            assertEquals(SubscriptionRefreshResult.Updated(0), fixture.coordinator.refresh("subscription-group"))
            val original =
                fixture.awgRepository
                    .observeProfiles()
                    .first()
                    .single()
            val replacement =
                fixture.repository
                    .list()
                    .single()
                    .copy(name = "User edited group")
            boundary.beforeSecondDelivery = { fixture.repository.update(replacement) }
            server.enqueue(response(200, ini("192.0.2.20:443", "rotated-psk")))

            assertEquals(SubscriptionRefreshResult.Superseded, fixture.coordinator.refresh(replacement.id))
            assertEquals(replacement, fixture.repository.list().single())
            assertEquals(original, fixture.awgRepository.load(original.id))
        }

    @Test
    fun `delayed valid AWG rotation cannot mutate a deleted group`() =
        runTest {
            val boundary = ResponseBoundary()
            val fixture = fixture(3_000L, httpClient = boundary.client)
            server.enqueue(response(200, ini("192.0.2.10:51820", "original-psk")))
            assertEquals(SubscriptionRefreshResult.Updated(0), fixture.coordinator.refresh("subscription-group"))
            val original =
                fixture.awgRepository
                    .observeProfiles()
                    .first()
                    .single()
            boundary.beforeSecondDelivery = {
                fixture.repository.delete(
                    testMutationPreparationSource().captureMutation(ProfileMutationOrigin.ExplicitDeletion),
                    "subscription-group",
                )
            }
            server.enqueue(response(200, ini("192.0.2.20:443", "rotated-psk")))

            assertEquals(SubscriptionRefreshResult.Superseded, fixture.coordinator.refresh("subscription-group"))
            assertTrue(fixture.repository.list().isEmpty())
            assertEquals(original, fixture.awgRepository.load(original.id))
        }

    @Test
    fun `newer valid AWG rotation wins even when both refreshes have the same timestamp`() =
        runTest {
            val boundary = ResponseBoundary()
            val fixture = fixture(3_000L, httpClient = boundary.client)
            server.enqueue(response(200, ini("192.0.2.10:51820", "original-psk")))
            assertEquals(SubscriptionRefreshResult.Updated(0), fixture.coordinator.refresh("subscription-group"))
            val original =
                fixture.awgRepository
                    .observeProfiles()
                    .first()
                    .single()
            server.enqueue(response(200, ini("192.0.2.20:443", "obsolete-psk")))
            server.enqueue(response(200, ini("192.0.2.30:443", "newest-psk")))
            boundary.beforeSecondDelivery = {
                assertEquals(SubscriptionRefreshResult.Updated(0), fixture.coordinator.refresh("subscription-group"))
            }

            assertEquals(SubscriptionRefreshResult.Superseded, fixture.coordinator.refresh("subscription-group"))

            val accepted = checkNotNull(fixture.awgRepository.load(original.id))
            assertEquals("192.0.2.30", accepted.request.endpointHost)
            assertEquals(key("newest-psk"), accepted.request.presharedKey)
            accepted.request.requireRuntimeReady()
            assertEquals(original.id, accepted.id)
            assertEquals(
                2L,
                fixture.repository
                    .list()
                    .single()
                    .subscription!!
                    .refreshRevision,
            )
            assertEquals(
                3_000L,
                fixture.repository
                    .list()
                    .single()
                    .subscription!!
                    .lastUpdated,
            )
        }

    @Test
    fun `duplicate INI peer identity stays a parse failure before guarded persistence`() =
        runTest {
            val fixture = fixture(3_000L)
            server.enqueue(response(200, ini("192.0.2.10:51820", "original-psk")))
            assertEquals(SubscriptionRefreshResult.Updated(0), fixture.coordinator.refresh("subscription-group"))
            val original =
                fixture.awgRepository
                    .observeProfiles()
                    .first()
                    .single()
            val duplicate = ini("192.0.2.20:443", "rotated-psk")
            server.enqueue(response(200, duplicate + "\n[Peer]" + duplicate.substringAfter("[Peer]")))

            assertEquals(
                SubscriptionRefreshResult.Failed(SubscriptionRefreshFailure.PARSE_ERROR, false),
                fixture.coordinator.refresh("subscription-group"),
            )
            assertEquals(original, fixture.awgRepository.load(original.id))
            assertEquals(
                1L,
                fixture.repository
                    .list()
                    .single()
                    .subscription!!
                    .refreshRevision,
            )
        }

    private class ResponseBoundary {
        var beforeSecondDelivery: suspend () -> Unit = {}
        private val deliveries = AtomicInteger()
        val client =
            OkHttpClient
                .Builder()
                .addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    if (deliveries.incrementAndGet() == 2) runBlocking { beforeSecondDelivery() }
                    response
                }.build()
    }

    private fun ini(
        endpoint: String,
        psk: String,
    ) = """
        [Interface]
        PrivateKey = ${key("interface")}
        Address = 10.8.0.2/32
        Jc = 4
        [Peer]
        PublicKey = ${key("peer")}
        PresharedKey = ${key(psk)}
        Endpoint = $endpoint
        AllowedIPs = 0.0.0.0/0
        """.trimIndent()

    private fun key(label: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(label.toByteArray()))
}
