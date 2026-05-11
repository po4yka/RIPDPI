package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.dpich.WhitelistedSubnetResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsIpv4WhitelistUiMapperTest {
    @Test
    fun checkResultsMapToRowsAndCsv() =
        runTest {
            val results =
                listOf(
                    WhitelistedSubnetResult(
                        provider = "Yandex",
                        cidr = "203.0.113.0/24",
                        aliveCount = 4,
                        aliveSampled = 25,
                        whitelisted = true,
                    ),
                    WhitelistedSubnetResult(
                        provider = "VK",
                        cidr = "192.0.2.0/24",
                        aliveCount = 1,
                        aliveSampled = 25,
                        whitelisted = false,
                    ),
                )

            val model = results.toIpv4WhitelistUiModel()

            assertEquals(DiagnosticsIpv4WhitelistState.Complete, model.state)
            assertEquals("whitelisted", model.rows.first().verdict)
            assertEquals(DiagnosticsTone.Positive, model.rows.first().tone)
            assertEquals("regular", model.rows.last().verdict)
            assertEquals(DiagnosticsTone.Neutral, model.rows.last().tone)
            assertEquals(
                "provider,cidr,alive_count,whitelisted\nYandex,203.0.113.0/24,4,true\nVK,192.0.2.0/24,1,false\n",
                model.csv,
            )
        }
}
