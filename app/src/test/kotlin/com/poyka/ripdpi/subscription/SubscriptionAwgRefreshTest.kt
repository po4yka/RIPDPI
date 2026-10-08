package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.SubscriptionLifecycleState
import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SubscriptionAwgRefreshTest : SubscriptionRefreshTestSupport() {
    @Test
    fun `refresh rotates same AWG row and keeps edited local key`() =
        runTest {
            val fixture = fixture(1_800_000_000_000L)
            server.enqueue(response(200, bundle()))
            fixture.coordinator.refresh("subscription-group")
            val saved =
                fixture.awgRepository
                    .observeProfiles()
                    .first()
                    .single()
            fixture.awgRepository.save("User label", saved.request.copy(privateKey = "local-private-key"), saved.id)

            repeat(2) {
                server.enqueue(response(200, bundle("192.0.2.20:443", "rotated-psk", 7)))
                fixture.coordinator.refresh("subscription-group")
            }
            val updated =
                fixture.awgRepository
                    .observeProfiles()
                    .first()
                    .single()
            assertEquals(saved.id, updated.id)
            assertEquals("local-private-key", updated.request.privateKey)
            assertEquals("rotated-psk", updated.request.presharedKey)
            assertEquals("192.0.2.20", updated.request.endpointHost)
            assertEquals(443, updated.request.endpointPort)
            assertEquals(7, updated.request.obfuscation.jc)
        }

    @Test
    fun `equal labels in separate subscriptions and manual imports remain separate`() =
        runTest {
            val fixture = fixture(1_800_000_000_000L)
            fixture.repository.add(subscriptionGroup("second", 1, SubscriptionLifecycleState.UNKNOWN))
            repeat(2) { server.enqueue(response(200, bundle())) }
            fixture.coordinator.refresh("subscription-group")
            val first =
                fixture.awgRepository
                    .observeProfiles()
                    .first()
                    .single()
            val manualId = fixture.awgRepository.save(first.name, first.request.copy(privateKey = "manual-key"))
            fixture.coordinator.refresh("second")
            val all = fixture.awgRepository.observeProfiles().first()
            assertEquals(3, all.size)
            assertNotEquals(first.id, manualId)
            assertEquals(
                "manual-key",
                fixture.awgRepository
                    .load(manualId)!!
                    .request.privateKey,
            )
            repeat(2) { server.enqueue(response(200, bundle())) }
            fixture.coordinator.refresh("subscription-group")
            fixture.coordinator.refresh("second")
            assertEquals(
                3,
                fixture.awgRepository
                    .observeProfiles()
                    .first()
                    .size,
            )
        }

    @Test
    fun `duplicate AWG tags fail before changing saved credentials`() =
        runTest {
            val fixture = fixture(1_800_000_000_000L)
            server.enqueue(response(200, bundle()))
            fixture.coordinator.refresh("subscription-group")
            val original =
                fixture.awgRepository
                    .observeProfiles()
                    .first()
                    .single()
            val changed = bundle("192.0.2.30:443", "untrusted-rotation", 8)
            val member = changed.substringAfter("\"amneziawg\":[").substringBefore("],\"hysteria_extras\"")
            server.enqueue(response(200, changed.replace(member, "$member,$member")))
            val result = fixture.coordinator.refresh("subscription-group")
            assertEquals(SubscriptionRefreshResult.Failed(SubscriptionRefreshFailure.PARSE_ERROR, false), result)
            assertEquals(
                original,
                fixture.awgRepository
                    .observeProfiles()
                    .first()
                    .single(),
            )
        }

    @Test
    fun `INI peer identity survives endpoint change`() =
        runTest {
            val fixture = fixture(1_800_000_000_000L)

            fun ini(endpoint: String) =
                """
                [Interface]
                PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
                Address = 10.8.0.2/32
                Jc = 4
                [Peer]
                PublicKey = CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC=
                Endpoint = $endpoint
                AllowedIPs = 0.0.0.0/0
                """.trimIndent()
            server.enqueue(response(200, ini("192.0.2.10:51820")))
            fixture.coordinator.refresh("subscription-group")
            val original =
                fixture.awgRepository
                    .observeProfiles()
                    .first()
                    .single()
            server.enqueue(response(200, ini("192.0.2.20:443")))
            fixture.coordinator.refresh("subscription-group")
            val updated =
                fixture.awgRepository
                    .observeProfiles()
                    .first()
                    .single()
            assertEquals(original.id, updated.id)
            assertEquals("192.0.2.20", updated.request.endpointHost)
            assertEquals(443, updated.request.endpointPort)
        }

    private fun bundle(
        endpoint: String = "192.0.2.10:51820",
        psk: String = "original-psk",
        jc: Int = 4,
    ): String =
        """
        {"outbounds":[],"ripdpi":{"schema_version":1,"amneziawg":[{
          "tag":"fleet-awg","private_key_placeholder":true,"address":["10.8.0.2/32"],"jc":$jc,
          "peer":{"public_key":"CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC=",
          "endpoint":"$endpoint","preshared_key":"$psk","allowed_ips":["0.0.0.0/0"]}
        }],"hysteria_extras":{}}}
        """.trimIndent()
}
