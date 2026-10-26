package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.awg.AwgActivationObfuscation
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.awg.AwgSubscriptionProfile
import com.poyka.ripdpi.data.awg.toAwgSubscriptionProfile
import com.poyka.ripdpi.data.subscription.SingBoxParseResult
import com.poyka.ripdpi.data.subscription.SingBoxSubscriptionParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
internal class AwgSubscriptionProfileRepositoryRoomTest : AwgProfileRepositoryRoomFixture() {
    @Test
    fun `migration copies only same-group origin and preserves source`() =
        runTest {
            val source = subscriptionProfile(request = sampleRequest().copy(privateKey = "local-key"))
            val sourceId = repository.saveSubscriptionProfiles(listOf(source)).map { it.id }.single()
            val incoming =
                source.copy(
                    memberId = "tag:migration-new",
                    name = "migration-new",
                    previousMemberId = source.memberId,
                    request =
                        source.request.copy(
                            privateKey = "",
                            peerPublicKey = "new-peer",
                            endpointHost = "new.example",
                        ),
                )
            val newId = repository.saveSubscriptionProfiles(listOf(incoming)).map { it.id }.single()
            assertTrue(newId != sourceId)
            assertEquals("local-key", repository.load(newId)!!.request.privateKey)
            assertEquals("new-peer", repository.load(newId)!!.request.peerPublicKey)
            assertEquals("new.example", repository.load(newId)!!.request.endpointHost)
            assertEquals(source.request.endpointHost, repository.load(sourceId)!!.request.endpointHost)
            assertEquals(newId, repository.saveSubscriptionProfiles(listOf(incoming)).map { it.id }.single())
            val unrelated = incoming.copy(groupId = "other-subscription")
            val otherId = repository.saveSubscriptionProfiles(listOf(unrelated)).map { it.id }.single()
            assertEquals("", repository.load(otherId)!!.request.privateKey)
        }

    @Test
    fun `migration from quarantined numeric headers preserves the device key without old wire parameters`() =
        runTest {
            val source = subscriptionProfile(request = sampleRequest().copy(privateKey = "local-key"))
            val sourceId = repository.saveSubscriptionProfiles(listOf(source)).single().id
            val original = requireNotNull(dao.getProfile(sourceId))
            val oldRow =
                original.copy(
                    requestJson =
                        original.requestJson
                            .replace("\"wireProfile\":\"awg-3.1-ranges-v1\",", "")
                            .replace(Regex("\"h([1-4])\":\"([0-9]+)\""), "\"h$1\":$2"),
                )
            dao.upsertProfile(oldRow)
            val incoming =
                source.copy(
                    memberId = "tag:new",
                    previousMemberId = source.memberId,
                    request =
                        source.request.copy(
                            privateKey = "",
                            obfuscation =
                                AwgActivationObfuscation(
                                    h1 = "100-199",
                                    h2 = "200-299",
                                    h3 = "300-399",
                                    h4 = "400-499",
                                    i1 = "<r 16>",
                                ),
                        ),
                )
            val replacement = repository.saveSubscriptionProfiles(listOf(incoming)).single()
            assertTrue(replacement.id != sourceId)
            assertEquals("local-key", replacement.request.privateKey)
            assertEquals(incoming.request.obfuscation, replacement.request.obfuscation)
            assertEquals(oldRow, dao.getProfile(sourceId))
            assertEquals("local-key", credentialStore.load(sourceId)?.privateKey)
            assertEquals("psk==", credentialStore.load(sourceId)?.presharedKey)
            assertNull(repository.load(sourceId))
            assertEquals(listOf(replacement.id), repository.observeProfiles().first().map { it.id })
        }

    @Test
    fun `current-request guard rejects edited row before committing other state`() =
        runTest {
            val id = repository.saveSubscriptionProfiles(listOf(subscriptionProfile())).map { it.id }.single()
            val expected = repository.load(id)!!.request
            var commits = 0
            assertTrue(
                repository.withCurrentRequest(expected) {
                    commits++
                    true
                },
            )
            repository.save("edited", expected.copy(endpointHost = "changed.example"), id)
            assertFalse(
                repository.withCurrentRequest(expected) {
                    commits++
                    true
                },
            )
            assertEquals(1, commits)
        }

    private fun subscriptionProfile(
        groupId: String = "subscription-one",
        memberId: String = "tag:home",
        request: AwgActivationRequest = sampleRequest(),
    ) = AwgSubscriptionProfile(groupId, memberId, "home", request)

    @Test
    fun `subscription rotation survives editor save and repository restart`() =
        runTest {
            val id = repository.saveSubscriptionProfiles(listOf(subscriptionProfile())).map { it.id }.single()
            val local =
                repository.load(id)!!.request.copy(
                    privateKey = "local-key",
                    dnsServers = listOf("9.9.9.9"),
                    allowedIps = listOf("10.0.0.0/8"),
                    carrier = AwgActivationRequest.CARRIER_WS,
                    carrierWsUrl = "wss://carrier.example/ws",
                )
            repository.save("local label", local, existingId = id)
            val restarted =
                AwgProfileRepository(
                    dao,
                    credentialStore,
                    com.poyka.ripdpi.data.awg
                        .TestDirectAwgProfileMutationCoordinator(dao, credentialStore),
                )
            val rotated =
                sampleRequest().copy(
                    privateKey = "",
                    presharedKey = "new-psk",
                    endpointHost = "rotated.example",
                    endpointPort = 443,
                    obfuscation = AwgActivationObfuscation(jc = 7),
                )
            repeat(2) {
                assertEquals(
                    id,
                    restarted
                        .saveSubscriptionProfiles(
                            listOf(subscriptionProfile(request = rotated)),
                        ).map { it.id }
                        .single(),
                )
            }
            val saved = restarted.observeProfiles().first().single()
            assertEquals(id, saved.id)
            assertEquals("local-key", saved.request.privateKey)
            assertEquals("new-psk", saved.request.presharedKey)
            assertEquals("rotated.example", saved.request.endpointHost)
            assertEquals(443, saved.request.endpointPort)
            assertEquals(7, saved.request.obfuscation.jc)
            assertEquals(local.dnsServers, saved.request.dnsServers)
            assertEquals(local.allowedIps, saved.request.allowedIps)
            assertEquals(local.carrier, saved.request.carrier)
            assertEquals(local.carrierWsUrl, saved.request.carrierWsUrl)
            val blob = dao.getProfile(id)!!.requestJson
            assertFalse(blob.contains("local-key"))
            assertFalse(blob.contains("new-psk"))
        }

    @Test
    fun `subscription identities are structured and never adopt manual profiles`() =
        runTest {
            val manual = repository.save("home", sampleRequest())
            val first = subscriptionProfile(groupId = "a:b", memberId = "c")
            val second = subscriptionProfile(groupId = "a", memberId = "b:c")
            val ids = repository.saveSubscriptionProfiles(listOf(first, second)).map { it.id }
            assertEquals(2, ids.distinct().size)
            assertFalse(ids.contains(manual))
            assertEquals(ids, repository.saveSubscriptionProfiles(listOf(first, second)).map { it.id })
            assertEquals(3, dao.allProfiles().size)
            assertEquals(sampleRequest(profileId = manual), repository.load(manual)!!.request)
        }

    @Test
    fun `explicit imported key replaces old key and empty PSK removes old PSK`() =
        runTest {
            val profile = subscriptionProfile()
            val id = repository.saveSubscriptionProfiles(listOf(profile)).map { it.id }.single()
            repository
                .saveSubscriptionProfiles(
                    listOf(
                        profile.copy(
                            request =
                                sampleRequest().copy(
                                    privateKey = "replacement-key",
                                    presharedKey = "",
                                ),
                        ),
                    ),
                ).map { it.id }
            assertEquals("replacement-key", repository.load(id)!!.request.privateKey)
            assertEquals("", repository.load(id)!!.request.presharedKey)
        }

    @Test
    fun `duplicate subscription members fail before any row changes`() =
        runTest {
            val profile = subscriptionProfile()
            val id = repository.saveSubscriptionProfiles(listOf(profile)).map { it.id }.single()
            val original = dao.getProfile(id)
            val duplicate = profile.copy(request = sampleRequest().copy(endpointPort = 443))
            assertTrue(
                runCatching { repository.saveSubscriptionProfiles(listOf(profile, duplicate)).map { it.id } }.isFailure,
            )
            assertEquals(listOf(original), dao.allProfiles())
        }

    @Test
    fun `failed subscription write restores credentials and association for retry`() =
        runTest {
            val profile = subscriptionProfile()
            val id = repository.saveSubscriptionProfiles(listOf(profile)).map { it.id }.single()
            val original = dao.getProfile(id)
            val rotated = profile.copy(request = sampleRequest().copy(privateKey = "", presharedKey = "rotated"))
            failingDao.failNextUpsertAfterWrite = true
            assertTrue(runCatching { repository.saveSubscriptionProfiles(listOf(rotated)).map { it.id } }.isFailure)
            assertEquals(original, dao.getProfile(id))
            assertEquals("psk==", repository.load(id)!!.request.presharedKey)
            assertEquals(id, repository.saveSubscriptionProfiles(listOf(rotated)).map { it.id }.single())
            assertEquals("privkey==", repository.load(id)!!.request.privateKey)
            assertEquals("rotated", repository.load(id)!!.request.presharedKey)
        }

    @Test
    fun `concurrent imports keep one opaque profile id`() =
        runTest {
            val ids =
                List(
                    10,
                ) {
                    async {
                        repository
                            .saveSubscriptionProfiles(
                                listOf(subscriptionProfile()),
                            ).map { it.id }
                            .single()
                    }
                }.awaitAll()
            assertEquals(1, ids.distinct().size)
            assertEquals(1, dao.allProfiles().size)
            assertTrue(ids.first().matches(Regex("awg-[0-9a-f-]{36}")))
        }

    @Test
    fun `new subscription placeholder remains empty and does not inherit unrelated key`() =
        runTest {
            repository.save("home", sampleRequest())
            val id =
                repository
                    .saveSubscriptionProfiles(
                        listOf(subscriptionProfile(request = sampleRequest().copy(privateKey = ""))),
                    ).map { it.id }
                    .single()
            assertEquals("", repository.load(id)!!.request.privateKey)
            assertEquals(2, dao.allProfiles().size)
        }

    @Test
    fun `empty subscription import does not decode unrelated malformed rows`() =
        runTest {
            dao.upsertProfile(
                com.poyka.ripdpi.data.awg
                    .AwgProfileEntity("legacy", "legacy", "invalid-json", 0),
            )
            assertTrue(repository.saveSubscriptionProfiles(emptyList()).map { it.id }.isEmpty())
            assertEquals(1, dao.allProfiles().size)
        }

    @Test
    fun `ambiguous stored membership fails before updating either row`() =
        runTest {
            val profile = subscriptionProfile()
            val id = repository.saveSubscriptionProfiles(listOf(profile)).map { it.id }.single()
            val original = dao.getProfile(id)!!
            dao.upsertProfile(original.copy(id = "duplicate"))
            val before = dao.allProfiles()
            assertTrue(runCatching { repository.saveSubscriptionProfiles(listOf(profile)).map { it.id } }.isFailure)
            assertEquals(before, dao.allProfiles())
        }

    @Test
    fun `subscription refresh applies supplied network policy and keeps local key`() =
        runTest {
            val profile = subscriptionProfile()
            val id = repository.saveSubscriptionProfiles(listOf(profile)).map { it.id }.single()
            val supplied =
                networkPolicyProfile(
                    "\"dns\":[\"9.9.9.9\"],",
                    "\"allowed_ips\":[\"10.0.0.0/8\",\"fd00::/8\"],",
                )
            assertEquals(id, repository.saveSubscriptionProfiles(listOf(supplied)).map { it.id }.single())
            val saved = repository.load(id)!!.request
            assertEquals(listOf("9.9.9.9"), saved.dnsServers)
            assertEquals(listOf("10.0.0.0/8", "fd00::/8"), saved.allowedIps)
            assertEquals(profile.request.privateKey, saved.privateKey)
        }

    @Test
    fun `subscription empty policy clears overrides but omission preserves them`() =
        runTest {
            val profile =
                subscriptionProfile(
                    request =
                        sampleRequest().copy(
                            dnsServers = listOf("9.9.9.9"),
                            allowedIps = listOf("10.0.0.0/8"),
                        ),
                )
            val id = repository.saveSubscriptionProfiles(listOf(profile)).map { it.id }.single()
            repository.saveSubscriptionProfiles(listOf(networkPolicyProfile())).map { it.id }
            assertEquals(profile.request.dnsServers, repository.load(id)!!.request.dnsServers)
            assertEquals(profile.request.allowedIps, repository.load(id)!!.request.allowedIps)
            repository
                .saveSubscriptionProfiles(
                    listOf(networkPolicyProfile("\"dns\":[],", "\"allowed_ips\":[],")),
                ).map { it.id }
            assertEquals(emptyList<String>(), repository.load(id)!!.request.dnsServers)
            assertEquals(emptyList<String>(), repository.load(id)!!.request.allowedIps)
        }

    @Test
    fun `malformed supplied policy is rejected without changing saved profile`() =
        runTest {
            val profile =
                subscriptionProfile().let {
                    it.copy(
                        request =
                            it.request.copy(
                                dnsServers = listOf("9.9.9.9"),
                                allowedIps = listOf("10.0.0.0/8"),
                            ),
                    )
                }
            val id = repository.saveSubscriptionProfiles(listOf(profile)).map { it.id }.single()
            val before = requireNotNull(repository.load(id))
            val invalid =
                listOf(
                    "null",
                    "{}",
                    "\"secret-value\"",
                    "[{}]",
                    "[42]",
                    "[true]",
                    "[null]",
                    "[\" \"]",
                    "[\"1.1.1.1\",{}]",
                    "[\"0.0.0.0/0\",null]",
                )
            for (field in listOf("dns", "allowed_ips")) {
                for (value in invalid) {
                    val jsonField = "\"$field\":$value,"
                    val parsed =
                        networkPolicyParse(
                            interfacePolicy = if (field == "dns") jsonField else "",
                            peerPolicy = if (field == "allowed_ips") jsonField else "",
                        )
                    repository
                        .saveSubscriptionProfiles(
                            parsed.amneziaWgProfiles.map { it.toAwgSubscriptionProfile() },
                        ).map { it.id }
                    assertEquals(before, repository.load(id))
                    assertTrue(parsed.amneziaWgProfiles.isEmpty())
                    val skipped = parsed.skipped.single()
                    assertEquals("INVALID_NETWORK_POLICY", skipped.reason.name)
                    assertEquals(field, skipped.detail)
                }
            }
        }

    private fun networkPolicyProfile(
        interfacePolicy: String = "",
        peerPolicy: String = "",
    ): AwgSubscriptionProfile =
        networkPolicyParse(interfacePolicy, peerPolicy)
            .amneziaWgProfiles
            .single()
            .toAwgSubscriptionProfile()

    private fun networkPolicyParse(
        interfacePolicy: String = "",
        peerPolicy: String = "",
    ): SingBoxParseResult.Success =
        SingBoxSubscriptionParser.parse(
            """
            {"outbounds":[],"ripdpi":{"schema_version":2,"amneziawg":[{
              "tag":"home","wire_profile":"awg-3.1-ranges-v1",
              "jc":0,"jmin":0,"jmax":0,"s1":0,"s2":0,
              "h1":"1","h2":"2","h3":"3","h4":"4","private_key_placeholder":true,"address":["10.8.0.2/32","fd00::2/128"],"mtu":1330,$interfacePolicy
              "peer":{$peerPolicy"public_key":"peerpub==","endpoint":"vpn.example.com:51820"}
            }]}}
            """.trimIndent(),
            "subscription-one",
        ) as SingBoxParseResult.Success
}
