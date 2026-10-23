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
        var failCommit = false

        override fun read() = value

        override fun commit(value: String) {
            check(!failCommit)
            this.value = value
        }
    }
}
