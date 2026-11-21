package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileQualityBudgetLedgerTest {
    private val persistence = MemoryPersistence()
    private var elapsed = 100L
    private var boot: Int? = 7
    private var wall = 0L
    private val clock =
        object : PauseClock {
            override fun read() = PauseClockReading(wall, elapsed, boot)
        }

    @Test
    fun `routine measurements leave room for one recovery pass`() {
        val single = policy()
        val configured = single.copy(quality = requireNotNull(single.quality).copy(maxWindowBytes = 262_152L))
        val owner = ledger()
        assertEquals(ProfileQualityReason.Verified, owner.reserve("scheduled", configured).reason)
        assertEquals(ProfileQualityReason.BudgetExhausted, owner.reserve("other-group", configured).reason)
        val recovery = ledger().reserve("other-group", configured, PayloadProbePurpose.Recovery)
        assertEquals(ProfileQualityReason.Verified, recovery.reason)
        assertEquals(262_152L, recovery.consumedWindowBytes)
        val noQuality = configured.copy(quality = null)
        assertEquals(
            ProfileQualityReason.BudgetExhausted,
            ledger().reserve("no-quality", noQuality, PayloadProbePurpose.Recovery).reason,
        )
    }

    @Test
    fun `recovery charges release the held bytes for regular scheduling within the cap`() {
        val single = policy()
        val configured = single.copy(quality = requireNotNull(single.quality).copy(maxWindowBytes = 262_152L))
        assertEquals(
            ProfileQualityReason.Verified,
            ledger().reserve("recovery", configured, PayloadProbePurpose.Recovery).reason,
        )
        assertFalse(requireNotNull(persistence.value).contains("recovery"))
        val scheduled = ledger().reserve("scheduled", configured)
        assertEquals(ProfileQualityReason.Verified, scheduled.reason)
        assertEquals(262_152L, scheduled.consumedWindowBytes)
        assertEquals(ProfileQualityReason.BudgetExhausted, ledger().reserve("another", configured).reason)
    }

    @Test
    fun `legacy ledger writes invalidate stale recovery metadata but retain charged bytes`() {
        val single = policy()
        val configured = single.copy(quality = requireNotNull(single.quality).copy(maxWindowBytes = 262_152L))
        ledger().reserve("recovery", configured, PayloadProbePurpose.Recovery)
        assertTrue(persistence.recovery != null)
        persistence.value =
            """
            {"version":1,"bootCount":7,"entries":[
              {"elapsedMillis":99,"bytes":131076,"windowSeconds":60,"maxWindowBytes":262152}
            ]}
            """.trimIndent()

        val scheduled = ledger().reserve("scheduled", configured)
        assertEquals(ProfileQualityReason.BudgetExhausted, scheduled.reason)
        assertEquals(131_076L, scheduled.consumedWindowBytes)
        assertEquals(null, persistence.recovery)
    }

    @Test
    fun `maximum payload passes without a quality policy share the default window`() {
        val single = policy()
        val maximum = single.copy(quality = null, probes = single.probes.map { it.copy(expectedBytes = 1_048_576) })
        val scheduled = ledger().reserve("scheduled", maximum)
        assertEquals(4_194_308L, scheduled.reservedPassBytes)
        assertEquals(ProfileQualityReason.BudgetExhausted, ledger().reserve("another", maximum).reason)
        assertEquals(
            8_388_616L,
            ledger().reserve("recovery", maximum, PayloadProbePurpose.Recovery).consumedWindowBytes,
        )
        assertEquals(
            ProfileQualityReason.BudgetExhausted,
            ledger().reserve("another", maximum, PayloadProbePurpose.Recovery).reason,
        )
    }

    @Test
    fun `reservation is persisted before admission and survives restart and policy reimport`() {
        val policy = policy()
        val first = ledger().reserve("a", policy)
        assertEquals(ProfileQualityReason.Verified, first.reason)
        assertEquals(policy.reservedQualityPassBytes(), first.reservedPassBytes)
        assertTrue(persistence.value != null)
        val restarted = ledger().reserve("different-group", policy.copy())
        assertEquals(ProfileQualityReason.BudgetExhausted, restarted.reason)
        assertEquals(first.reservedPassBytes, restarted.consumedWindowBytes)
        assertFalse(requireNotNull(persistence.value).contains("different-group"))
    }

    @Test
    fun `reboot rebases retained charges once and allows only later monotonic expiry`() {
        assertEquals(ProfileQualityReason.Verified, ledger().reserve("a", policy()).reason)
        boot = 8
        elapsed = 10L
        assertEquals(ProfileQualityReason.BudgetExhausted, ledger().reserve("a", policy()).reason)
        elapsed += 59_999
        assertEquals(ProfileQualityReason.BudgetExhausted, ledger().reserve("a", policy()).reason)
        elapsed++
        assertEquals(ProfileQualityReason.Verified, ledger().reserve("a", policy()).reason)
    }

    @Test
    fun `wall clock or changing window cannot clear earlier stricter constraints`() {
        val original = policy(window = 120)
        ledger().reserve("a", original)
        elapsed += 60_000
        wall = Long.MAX_VALUE
        assertEquals(ProfileQualityReason.BudgetExhausted, ledger().reserve("b", policy()).reason)
        elapsed += 60_000
        wall = 0
        assertEquals(ProfileQualityReason.Verified, ledger().reserve("b", policy()).reason)
    }

    @Test
    fun `longer policy window still counts retained charges from shorter windows`() {
        ledger().reserve("a", policy())
        elapsed += 60_000
        assertEquals(ProfileQualityReason.BudgetExhausted, ledger().reserve("a", policy(window = 120)).reason)
    }

    @Test
    fun `clock uncertainty corrupt state and failed commit never admit IO`() {
        boot = null
        assertEquals(ProfileQualityReason.BudgetUnavailable, ledger().reserve("a", policy()).reason)
        boot = 7
        persistence.value = "corrupt"
        assertEquals(ProfileQualityReason.BudgetUnavailable, ledger().reserve("a", policy()).reason)
        persistence.value = null
        persistence.failCommit = true
        assertEquals(ProfileQualityReason.BudgetUnavailable, ledger().reserve("a", policy()).reason)
        assertEquals(null, persistence.value)
    }

    @Test
    fun `same boot elapsed rollback cannot replenish budget`() {
        ledger().reserve("a", policy())
        elapsed--
        assertEquals(ProfileQualityReason.BudgetUnavailable, ledger().reserve("a", policy()).reason)
    }

    @Test
    fun `independent owners serialize through the single injected application ledger`() {
        val owner = ledger()
        val attempts =
            (1..8)
                .map { index ->
                    java.util.concurrent.CompletableFuture
                        .supplyAsync { owner.reserve("$index", policy()).reason }
                }.map { it.get() }
        assertEquals(1, attempts.count { it == ProfileQualityReason.Verified })
        assertEquals(7, attempts.count { it == ProfileQualityReason.BudgetExhausted })
    }

    private fun ledger() = ProfileQualityBudgetLedger(persistence, clock)

    private fun policy(window: Int = 60) =
        PayloadFailoverPolicy(
            2,
            "select",
            listOf(
                PayloadProbeControl("https://a.example.test/body", 32_768, "a".repeat(64)),
                PayloadProbeControl("https://b.example.test/body", 32_768, "a".repeat(64)),
            ),
            60,
            10,
            2,
            2,
            60,
            120,
            ProfileQualityPolicy(131_076, 131_076, window, 20, 50),
        )

    private class MemoryPersistence : ProfileQualityBudgetPersistence {
        var value: String? = null
        var recovery: String? = null
        var failCommit = false

        override fun read() = ProfileQualityBudgetRecords(value, recovery)

        override fun commit(value: ProfileQualityBudgetRecords) {
            check(!failCommit)
            this.value = value.ledger
            recovery = value.recovery
        }
    }
}
