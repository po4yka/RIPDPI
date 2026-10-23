package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.DiagnosticsNetworkEpoch
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.subscription.MigrationMetadata
import com.poyka.ripdpi.data.subscription.MigrationTarget
import com.poyka.ripdpi.data.subscription.migrationFingerprint
import com.poyka.ripdpi.data.subscription.migrationSha256
import com.poyka.ripdpi.data.subscription.receivedMigration
import com.poyka.ripdpi.services.AwgMigrationMeasurement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceMigrationCoordinatorTest : SubscriptionRefreshTestSupport() {
    @Test
    fun `ordinary Clash JSON and flow YAML still refresh without migration authority`() =
        runTest {
            val fixture = fixture(1000)
            for (body in listOf(ClashJson, ClashJson.replace('"', '\''))) {
                server.enqueue(response(200, body))
                assertTrue(fixture.coordinator.refresh(Group) is SubscriptionRefreshResult.Updated)
                val saved = fixture.repository.list().single()
                assertEquals("clash-node", saved.members.single().displayName)
                assertNull(saved.subscription!!.migration)
            }
        }

    @Test
    fun `recognized invalid RIPDPI cannot downgrade through valid Clash fields`() =
        runTest {
            val fixture = fixture(1000)
            server.enqueue(response(200, payload()))
            fixture.coordinator.refresh(Group)
            val before = fixture.repository.list().single()
            val ambiguous = ClashJson.dropLast(1) + """, "ripdpi":{"schema_version":1,"migration":{}}}"""
            for (body in listOf(ambiguous, ambiguous.replace('"', '\''))) {
                server.enqueue(response(200, body))
                assertTrue(fixture.coordinator.refresh(Group) is SubscriptionRefreshResult.Failed)
                val after = fixture.repository.list().single()
                assertEquals(before.members, after.members)
                assertEquals(before.subscription!!.migration, after.subscription!!.migration)
            }
        }

    @Test
    fun `refresh persists exact body and metadata-free refresh removes authority`() =
        runTest {
            val fixture = fixture(1000)
            val body = payload()
            server.enqueue(response(200, body))
            assertTrue(fixture.coordinator.refresh(Group) is SubscriptionRefreshResult.Updated)
            val delivery =
                fixture.repository
                    .list()
                    .single()
                    .subscription!!
                    .migration!!
            assertEquals(migrationSha256(body.toByteArray()), delivery.payloadSha256)
            assertNull(delivery.pathTest)
            val coordinator = coordinator(fixture.repository)
            assertEquals("received", state(coordinator.receipt(Group)))
            assertEquals(MigrationCheckResult.Tested, coordinator.check(Group, "green", "https://probe.example/body"))
            assertEquals("path-tested", state(coordinator.receipt(Group)))
            server.enqueue(response(200, body))
            fixture.coordinator.refresh(Group)
            assertEquals("path-tested", state(coordinator.receipt(Group)))
            server.enqueue(response(200, trojanPayload))
            fixture.coordinator.refresh(Group)
            assertNull(coordinator.receipt(Group))
        }

    @Test
    fun `invalid metadata does not overwrite last delivery or fall back to ordinary parsing`() =
        runTest {
            val fixture = fixture(1000)
            server.enqueue(response(200, payload()))
            fixture.coordinator.refresh(Group)
            val delivery =
                fixture.repository
                    .list()
                    .single()
                    .subscription!!
                    .migration
            server.enqueue(response(200, payload().replace("\"receipt_key\"", "\"unexpected\"")))
            assertTrue(fixture.coordinator.refresh(Group) is SubscriptionRefreshResult.Failed)
            assertEquals(
                delivery,
                fixture.repository
                    .list()
                    .single()
                    .subscription!!
                    .migration,
            )
        }

    @Test
    fun `failed cancelled changed-network and replaced-delivery tests never qualify`() =
        runTest {
            val fixture = fixture(1000)
            server.enqueue(response(200, payload()))
            fixture.coordinator.refresh(Group)
            for (mode in listOf(
                "failed",
                "cancelled",
                "network",
                "delivery",
                "profile",
                "catalog",
                "recreated",
                "reverted",
            )) {
                val original = fixture.repository.list().single()
                var epoch: DiagnosticsNetworkEpoch = Epoch(1)
                var generation = 0L
                val coordinator =
                    coordinator(fixture.repository, epoch = { epoch }, generation = { generation }, relay = { _, _ ->
                        when (mode) {
                            "failed" -> {
                                return@coordinator false
                            }

                            "cancelled" -> {
                                throw CancellationException()
                            }

                            "network" -> {
                                epoch = Epoch(2)
                            }

                            "catalog" -> {
                                generation++
                            }

                            "delivery" -> {
                                fixture.repository.updateGroup(Group) { current ->
                                    current.copy(subscription = current.subscription!!.copy(migration = null))
                                }
                            }

                            "recreated" -> {
                                fixture.repository.delete(
                                    com.poyka.ripdpi.data.testMutationPreparationSource().captureMutation(
                                        com.poyka.ripdpi.data.ProfileMutationOrigin.ExplicitDeletion,
                                    ),
                                    Group,
                                )
                                fixture.repository.add(original)
                            }

                            "reverted" -> {
                                fixture.repository.update(original.copy(name = "Temporary edit"))
                                fixture.repository.update(original)
                            }

                            "profile" -> {
                                fixture.repository.updateGroup(Group) { it.copy(members = emptyList()) }
                            }
                        }
                        true
                    })
                val result = runCatching { coordinator.check(Group, "green", "https://probe.example/body") }
                if (mode == "cancelled") {
                    assertTrue(result.exceptionOrNull() is CancellationException)
                } else {
                    assertFalse(result.getOrThrow() == MigrationCheckResult.Tested)
                }
                assertNull(
                    fixture.repository
                        .list()
                        .single()
                        .subscription!!
                        .migration
                        ?.pathTest,
                )
                fixture.repository.update(original)
            }
        }

    @Test
    fun `AWG exact local configuration qualifies and subsequent key change downgrades export`() =
        runTest {
            val fixture = fixture(1000)
            var request =
                AwgActivationRequest(
                    profileId = "awg-local",
                    privateKey = "local-key",
                    peerPublicKey = "peer",
                    endpointHost = "new.example",
                    endpointPort = 443,
                    interfaceAddressV4 = "10.0.0.2/32",
                )
            val metadata = identity.copy(awgPreviousTags = mapOf("green" to "previous"))
            val delivery =
                receivedMigration(
                    metadata,
                    "b".repeat(64),
                    1000,
                    emptyList(),
                    listOf(MigrationTarget("green", request.profileId, true, request.migrationFingerprint())),
                )
            fixture.repository.updateGroup(
                Group,
            ) { it.copy(subscription = it.subscription!!.copy(migration = delivery)) }
            var measured: AwgActivationRequest? = null
            val coordinator =
                DeviceMigrationCoordinator(
                    fixture.repository,
                    { request },
                    { expected, action -> expected == request && action() },
                    { expected, action -> expected == request && action() },
                    { 0 },
                    { _, _ -> error("relay not AWG") },
                    { actual, _ ->
                        measured = actual
                        AwgMigrationMeasurement.Succeeded
                    },
                    { Epoch(1) },
                    { 2000 },
                )
            assertEquals(MigrationCheckResult.Tested, coordinator.check(Group, "green", "https://probe.example/body"))
            assertEquals(request, measured)
            assertEquals("path-tested", state(coordinator.receipt(Group)))
            request = request.copy(privateKey = "changed-key")
            assertEquals("received", state(coordinator.receipt(Group)))
            assertFalse(coordinator.hasCurrentTest(Group))
        }

    @Test
    fun `AWG edit after durable import cannot redefine delivered target fingerprint`() =
        runTest {
            val fixture = fixture(1000)
            val repository = fixture.awgRepository
            val request =
                AwgActivationRequest(
                    profileId = "",
                    privateKey = "local-key",
                    peerPublicKey = "peer",
                    endpointHost = "delivered.example",
                    endpointPort = 443,
                    interfaceAddressV4 = "10.0.0.2/32",
                )
            val imported =
                repository
                    .saveSubscriptionProfiles(
                        listOf(
                            com.poyka.ripdpi.data.awg
                                .AwgSubscriptionProfile(Group, "tag:green", "green", request),
                        ),
                    ).single()
            // The editor wins after the durable row write, before the group receipt authority is committed.
            repository.save("edited", imported.request.copy(endpointHost = "edited.example"), imported.id)
            val target = MigrationTarget(imported.name, imported.id, true, imported.request.migrationFingerprint())
            val delivery = receivedMigration(identity, "b".repeat(64), 1000, emptyList(), listOf(target))
            fixture.repository.updateGroup(
                Group,
            ) { it.copy(subscription = it.subscription!!.copy(migration = delivery)) }
            val coordinator =
                DeviceMigrationCoordinator(
                    fixture.repository,
                    { repository.load(it)?.request },
                    repository::withCurrentRequest,
                    repository::readCurrentRequest,
                    { 0 },
                    { _, _ -> error("not relay") },
                    { _, _ -> error("edited target must not be probed") },
                    { Epoch(1) },
                    { 2000 },
                )
            assertEquals("delivered.example", imported.request.endpointHost)
            assertEquals(
                MigrationCheckResult.InvalidTarget,
                coordinator.check(Group, "green", "https://probe.example/body"),
            )
            assertEquals("received", state(coordinator.receipt(Group)))
        }

    @Test
    fun `superseded migration refresh cannot create AWG rows or receipt authority`() =
        runTest {
            lateinit var repository: ProxyGroupRepository
            val client =
                OkHttpClient
                    .Builder()
                    .addNetworkInterceptor { chain ->
                        chain.proceed(chain.request()).also {
                            runBlocking { repository.updateGroup(Group) { it.copy(name = "Edited during delivery") } }
                        }
                    }.build()
            val fixture = fixture(1000, httpClient = client)
            repository = fixture.repository
            server.enqueue(response(200, awgPayload()))

            assertEquals(SubscriptionRefreshResult.Superseded, fixture.coordinator.refresh(Group))
            val group = repository.list().single()
            assertEquals("Edited during delivery", group.name)
            val subscription = checkNotNull(group.subscription)
            assertEquals(0L, subscription.lastUpdated)
            assertNull(subscription.migration)
            assertTrue(
                fixture.awgRepository
                    .observeProfiles()
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun `AWG refresh receipt fingerprints exact assigned rows and retains proof only for the same payload`() =
        runTest {
            val fixture = fixture(1000)
            val body = awgPayload()
            server.enqueue(response(200, body))
            assertEquals(SubscriptionRefreshResult.Updated(0), fixture.coordinator.refresh(Group))
            val first = fixture.repository.list().single()
            val firstSubscription = checkNotNull(first.subscription)
            val receipt = checkNotNull(firstSubscription.migration)
            val row =
                fixture.awgRepository
                    .observeProfiles()
                    .first()
                    .single()
            assertEquals(row.id, receipt.targets.single().profileId)
            assertEquals(row.request.migrationFingerprint(), receipt.targets.single().fingerprint)
            assertEquals(migrationSha256(body.toByteArray()), receipt.payloadSha256)
            val tested =
                receipt.copy(
                    pathTest =
                        com.poyka.ripdpi.data.subscription.MigrationPathTest(
                            "green",
                            1001,
                            receipt.targets.single().fingerprint,
                        ),
                )
            fixture.repository.update(first.copy(subscription = firstSubscription.copy(migration = tested)))
            server.enqueue(response(200, body))
            assertEquals(SubscriptionRefreshResult.Updated(0), fixture.coordinator.refresh(Group))
            val second = fixture.repository.list().single()
            val secondSubscription = checkNotNull(second.subscription)
            assertEquals(tested, secondSubscription.migration)
            assertNotEquals(first.ownershipGeneration, second.ownershipGeneration)
            server.enqueue(response(200, trojanPayload))
            assertTrue(fixture.coordinator.refresh(Group) is SubscriptionRefreshResult.Updated)
            assertNull(
                fixture.repository
                    .list()
                    .single()
                    .subscription!!
                    .migration,
            )
        }

    private fun awgPayload() =
        """{"outbounds":[],"ripdpi":{"schema_version":1,"amneziawg":[{
          "tag":"green","private_key_placeholder":true,"address":["10.8.0.2/32","fd77::2/128"],"mtu":1330,"jc":4,
          "peer":{"public_key":"CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC=",
          "endpoint":"192.0.2.20:443","preshared_key":"fixture-psk","allowed_ips":["0.0.0.0/0"]}
        }],"migration":{"schema_version":1,
          "migration_id":"${identity.migrationId}","delivery_id":"${identity.deliveryId}",
          "target_tags":["green"],"receipt_key":"${identity.receiptKey}","awg_previous_tags":{}}}}"""

    private fun coordinator(
        repository: ProxyGroupRepository,
        epoch: () -> DiagnosticsNetworkEpoch = { Epoch(1) },
        generation: () -> Long = { 0 },
        relay: suspend (ProxyProfile, String) -> Boolean = { _, _ -> true },
    ) = DeviceMigrationCoordinator(
        groups = repository,
        loadAwg = { null },
        withCurrentAwg = { _, _ -> false },
        readCurrentAwg = { _, _ -> false },
        mutationGeneration = generation,
        relayProbe = relay,
        awgProbe = { _, _ -> error("not AWG") },
        epoch = epoch,
        now = { 2000 },
    )

    private fun state(receipt: String?): String {
        assertNotNull(receipt)
        return Json
            .parseToJsonElement(receipt!!)
            .jsonObject
            .getValue("state")
            .jsonPrimitive.content
    }

    private data class Epoch(
        val value: Int,
    ) : DiagnosticsNetworkEpoch

    private fun payload() =
        """ {
        "outbounds":[{"type":"hysteria2","tag":"green","server":"new.example",
          "server_port":443,"password":"fixture"}],
        "ripdpi":{"schema_version":1,"migration":{"schema_version":1,
          "migration_id":"${identity.migrationId}","delivery_id":"${identity.deliveryId}",
          "target_tags":["green"],"receipt_key":"${identity.receiptKey}","awg_previous_tags":{}}}
    } """

    companion object {
        private const val Group = "subscription-group"
        private const val ClashJson =
            """{"proxies":[{"name":"clash-node","type":"trojan","server":"clash.example",
          "port":443,"password":"fixture","sni":"clash.example"}]}"""
        private val identity =
            MigrationMetadata(
                "11111111-1111-1111-1111-111111111111",
                "22222222-2222-2222-2222-222222222222",
                listOf("green"),
                "a".repeat(64),
                emptyMap(),
            )
    }
}
