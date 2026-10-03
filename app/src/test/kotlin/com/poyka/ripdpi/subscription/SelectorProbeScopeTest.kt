package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.activities.TestEmptyProxyGroupRepository
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.SelectorFailover
import com.poyka.ripdpi.services.CandidateRelayProbeEnvironment
import com.poyka.ripdpi.services.RuntimeExperimentSelection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectorProbeScopeTest {
    @Test
    fun `actual group member credentials policy classification and existence fence old input`() =
        runTest {
            val group = group()
            var current = group
            var present = true
            val groups =
                object : ProxyGroupRepository by TestEmptyProxyGroupRepository {
                    override suspend fun list(): List<ProxyGroup> = if (present) listOf(current) else emptyList()
                }
            val provider = CurrentSelectorProbeScopeProvider(groups, network(), { environment() }, { 1L to 1L })
            val original = provider.capture(group)
            current = group.copy(name = "new display name")
            assertEquals(original, provider.capture(group))
            current =
                group.copy(
                    members =
                        listOf(
                            (group.members.single() as ProxyProfile.Trojan).copy(password = "changed-fixture"),
                        ),
                )
            assertNull(provider.capture(group))
            current = group.copy(failover = group.failover?.copy(probeUrl = "https://changed.example"))
            assertNull(provider.capture(group))
            current = group.copy(cloudflareMemberIds = setOf("member"))
            assertNull(provider.capture(group))
            current = group
            present = false
            assertNull(provider.capture(group))
        }

    @Test
    fun `same remembered network with different underlay generation invalidates evidence`() =
        runTest {
            val group = group()
            val groups =
                object : ProxyGroupRepository by TestEmptyProxyGroupRepository {
                    override suspend fun list() = listOf(group)
                }
            var fingerprint = fingerprint(1)
            val provider =
                CurrentSelectorProbeScopeProvider(
                    groups,
                    object : NetworkFingerprintProvider {
                        override fun capture() = fingerprint
                    },
                    { environment() },
                    { 1L to 1L },
                )
            val original = requireNotNull(provider.capture(group))
            fingerprint = fingerprint(2)
            val changed = requireNotNull(provider.capture(group))
            assertEquals(original.networkScope, changed.networkScope)
            assertNotEquals(original, changed)
        }

    @Test
    fun `absent network refuses to create payload evidence`() =
        runTest {
            val group = group()
            val groups =
                object : ProxyGroupRepository by TestEmptyProxyGroupRepository {
                    override suspend fun list() = listOf(group)
                }
            val provider =
                CurrentSelectorProbeScopeProvider(
                    groups,
                    object : NetworkFingerprintProvider {
                        override fun capture(): NetworkFingerprint? = null
                    },
                    { environment() },
                    { 1L to 1L },
                )
            assertNull(provider.capture(group))
        }

    @Test
    fun `network callback during suspending environment capture cannot issue a stale scope`() =
        runTest {
            val group = group()
            val groups =
                object : ProxyGroupRepository by TestEmptyProxyGroupRepository {
                    override suspend fun list() = listOf(group)
                }
            var epoch = 1L
            val provider =
                CurrentSelectorProbeScopeProvider(
                    groups,
                    network(),
                    {
                        epoch++
                        environment()
                    },
                    { epoch to 0L },
                )
            assertNull(provider.capture(group))
        }

    private fun group() =
        ProxyGroup(
            "g",
            "name",
            ProxyGroupType.SUBSCRIPTION,
            0,
            isSelector = true,
            members = listOf(ProxyProfile.Trojan("member", "name", "g", "endpoint.example", 443, "fixture")),
            failover = SelectorFailover("https://probe.example", 10, 50),
        )

    private fun network() =
        object : NetworkFingerprintProvider {
            override fun capture() = fingerprint(1)
        }

    private fun fingerprint(generation: Long) =
        NetworkFingerprint(
            "wifi",
            true,
            false,
            "system",
            listOf("192.0.2.53"),
            directDnsUnderlayGeneration = generation,
        )

    private fun environment() =
        CandidateRelayProbeEnvironment(
            false,
            "chrome_stable",
            RuntimeExperimentSelection(),
            false,
            false,
        )
}
