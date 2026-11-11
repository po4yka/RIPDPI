package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayFinalmaskConfig
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.ServiceStartupRejectedException
import com.poyka.ripdpi.data.StrategyFeatureFinalmask
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

internal class FinalmaskValidationTest {
    @Test
    fun `unknown finalmask is rejected when its feature flag is enabled`() {
        val config = RipDpiRelayConfig(finalmask = RipDpiRelayFinalmaskConfig(type = "sudkou"))

        val error =
            assertThrows(ServiceStartupRejectedException::class.java) {
                validateFinalmaskFeature(config, mapOf(StrategyFeatureFinalmask to true))
            }

        assertTrue(error.reason is FailureReason.RelayConfigRejected)
    }

    @Test
    fun `blank and off finalmask need no feature flag`() {
        for (type in listOf("", " ", "off")) {
            validateFinalmaskFeature(RipDpiRelayConfig(finalmask = RipDpiRelayFinalmaskConfig(type = type)), emptyMap())
        }
    }
}
