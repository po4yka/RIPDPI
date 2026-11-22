package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.PayloadFailoverPolicy
import com.poyka.ripdpi.data.PayloadProbePurpose
import com.poyka.ripdpi.data.ProfileQualityBudget
import com.poyka.ripdpi.data.ProfileQualityReason
import com.poyka.ripdpi.data.SelectorFailover
import com.poyka.ripdpi.data.subscription.EndpointHealth
import com.poyka.ripdpi.data.subscription.EndpointHealthStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectorCloudHealthTest {
    @Test
    fun `payload checks the active member reported down even when a regular reserve is available`() =
        runTest {
            val check =
                fixture(down = setOf("active", "down-a")) { member ->
                    if (member == "active") MemberProbeResult(300L) else MemberProbeResult(10L)
                }

            check.pass()

            assertEquals(listOf("active"), check.measured)
            assertEquals("active", check.selected)
        }

    @Test
    fun `payload spends the reserve slot on one down member after the regular reserve fails`() =
        runTest {
            val check = fixture { member -> if (member.startsWith("down-")) MemberProbeResult(20L) else null }

            check.pass()
            assertEquals(listOf("active", "regular"), check.measured)
            check.pass()
            assertEquals(listOf("active", "down-a"), check.measured.takeLast(2))
            assertEquals("active", check.selected)

            check.pass()
            assertEquals(listOf("active", "down-a", "active", "down-a"), check.measured.takeLast(4))
            assertEquals("down-a", check.selected)
            assertEquals(List(3) { PayloadProbePurpose.Scheduled }, check.admissions)
        }

    @Test
    fun `a failed down probe ends the sweep and no second down member is tried during backoff`() =
        runTest {
            val check = fixture { member -> if (member == "down-b") MemberProbeResult(20L) else null }

            repeat(3) { check.pass() }

            assertEquals(listOf("active", "regular", "active", "down-a"), check.measured)
            assertEquals("active", check.selected)
        }

    @Test
    fun `the sweep after backoff gives the down slot to the next down member`() =
        runTest {
            val check = fixture { member -> if (member == "down-b") MemberProbeResult(20L) else null }

            repeat(12) { check.pass() }

            assertEquals(
                listOf("active", "regular", "active", "down-a", "active", "regular", "active", "down-b"),
                check.measured.take(8),
            )
        }

    @Test
    fun `reachability gives the down slot to the next down member after a failure`() =
        runTest {
            val check = fixture(payload = false) { member -> if (member == "down-b") MemberProbeResult(20L) else null }

            repeat(2) { check.pass() }

            assertEquals(listOf("active", "regular", "down-a", "active", "regular", "down-b"), check.measured)
            assertEquals("down-b", check.selected)
        }

    @Test
    fun `budget denial before the down slot starts neither candidate`() =
        runTest {
            val check = fixture { null }

            check.pass()
            check.budgetReason = ProfileQualityReason.BudgetExhausted
            check.pass()

            assertEquals(listOf("active", "regular"), check.measured)
            assertEquals(List(2) { PayloadProbePurpose.Scheduled }, check.admissions)
            assertEquals("active", check.selected)
        }

    @Test
    fun `an undecided regular probe cannot unlock a down reserve`() =
        runTest {
            val check =
                fixture { member ->
                    if (member == "regular") {
                        MemberProbeResult(null, reason = ProfileQualityReason.CleanupPending)
                    } else {
                        null
                    }
                }

            repeat(3) { check.pass() }

            assertEquals(List(3) { listOf("active", "regular") }.flatten(), check.measured)
        }

    @Test
    fun `reachability tries only one down reserve after regular candidates fail and excludes Cloudflare`() =
        runTest {
            val check =
                fixture(payload = false) { member ->
                    if (member in setOf("down-a", "down-b", "cloudflare")) {
                        MemberProbeResult(20L)
                    } else {
                        null
                    }
                }

            repeat(2) { check.pass() }

            assertEquals(List(2) { listOf("active", "regular", "down-a") }.flatten(), check.measured)
            assertEquals("down-a", check.selected)
        }

    private fun TestScope.fixture(
        payload: Boolean = true,
        down: Set<String> = setOf("down-a", "down-b", "cloudflare"),
        outcome: (String) -> MemberProbeResult?,
    ): CloudHealthFixture = CloudHealthFixture(this, payload, down, outcome)
}

@OptIn(ExperimentalCoroutinesApi::class)
private class CloudHealthFixture(
    private val time: TestScope,
    payload: Boolean,
    down: Set<String>,
    outcome: (String) -> MemberProbeResult?,
) {
    private val store = ProbeTestSelectionStore().also { it.set("g", "active") }
    private val group =
        selectorTestGroup(listOf("active", "regular", "cloudflare", "down-a", "down-b").map(::selectorTestMember)).copy(
            failover = SelectorFailover("https://probe", 60, 0, if (payload) policy() else null),
            cloudflareMemberIds = setOf("cloudflare"),
            memberHealth = down.associateWith { EndpointHealth(EndpointHealthStatus.DOWN, 1_000L, 3_600) },
        )
    val measured = mutableListOf<String>()
    val admissions = mutableListOf<PayloadProbePurpose>()
    val selected: String? get() = store.selectedProfileId(group.id).value
    var budgetReason = ProfileQualityReason.Verified

    private val prober =
        SelectorUrltestProber(
            store,
            MemberLatencyProbe { member, _ ->
                measured.add(member.id)
                outcome(member.id)
            },
            SelectorProbeScopeProvider { selectorTestScope(it) },
            monotonicMillis = { time.testScheduler.currentTime },
            wallClockMillis = { 1_000L },
            reserveBudget = ::admit,
        )

    suspend fun pass() {
        prober.runProbePass(group, "https://probe", 0)
        time.advanceTimeBy(60_000)
    }

    private fun admit(
        groupId: String,
        policy: PayloadFailoverPolicy,
        purpose: PayloadProbePurpose,
    ): ProfileQualityBudget {
        admissions.add(purpose)
        return admitTestPayloadPass(groupId, policy, purpose).copy(reason = budgetReason)
    }
}
