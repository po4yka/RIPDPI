package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusSuppressed
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusValidated
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class RememberedPolicyTerminalOutcomeTest {
    private val storedRow =
        RememberedNetworkPolicyEntity(
            id = 5L,
            fingerprintHash = "fingerprint",
            mode = "vpn",
            summaryJson = "{}",
            proxyConfigJson = "{}",
            strategySignatureJson = "strategy-old",
            source = RememberedNetworkPolicySource.MANUAL_SESSION.encodeStorageValue(),
            status = RememberedNetworkPolicyStatusValidated,
            successCount = 3,
            failureCount = 1,
            consecutiveFailureCount = 1,
            firstObservedAt = 10L,
            lastValidatedAt = 10L,
            updatedAt = 20L,
        )

    @Test
    fun `a failure outcome adds to the failures stored since its snapshot`() {
        val outcome =
            RuntimeHistoryJson.decodeFromString(
                RememberedPolicyTerminalOutcome.serializer(),
                """{"policyId":5,"mode":"vpn","status":"validated","successCount":3,"failureCount":1,""" +
                    """"consecutiveFailureCount":1,"suppressedUntil":null,"lastValidatedAt":10,""" +
                    """"updateStrategySignature":false,"updatedAt":100}""",
            )

        val result = outcome.applyTo(storedRow, strategySignatureJson = "strategy-new")

        assertEquals(RememberedNetworkPolicyStatusSuppressed, result?.status)
        assertEquals(2, result?.failureCount)
        assertEquals(2, result?.consecutiveFailureCount)
        assertEquals("strategy-old", result?.strategySignatureJson)
    }

    @Test
    fun `a proved outcome keeps the failures stored since its snapshot`() {
        val outcome =
            RememberedPolicyTerminalOutcome(
                policyId = 5L,
                mode = "vpn",
                updateStrategySignature = true,
                updatedAt = 100L,
            )

        val result = outcome.applyTo(storedRow, strategySignatureJson = "strategy-new")

        assertEquals(4, result?.successCount)
        assertEquals(1, result?.failureCount)
        assertEquals(0, result?.consecutiveFailureCount)
        assertEquals("strategy-new", result?.strategySignatureJson)
        assertEquals(10L, result?.lastValidatedAt)
        assertEquals(RememberedNetworkPolicyStatusValidated, result?.status)
    }
}
