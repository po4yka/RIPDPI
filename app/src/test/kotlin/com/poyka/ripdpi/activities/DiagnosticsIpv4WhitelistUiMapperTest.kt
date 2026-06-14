package com.poyka.ripdpi.activities

import android.app.Application
import com.poyka.ripdpi.diagnostics.dpich.WhitelistedSubnetResult
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DiagnosticsIpv4WhitelistUiMapperTest {
    private val stringResolver = ResourceStringResolver()

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

            val model = results.toIpv4WhitelistUiModel(stringResolver)

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

    private class ResourceStringResolver : StringResolver {
        private val application: Application = RuntimeEnvironment.getApplication()

        override fun getString(
            resId: Int,
            vararg formatArgs: Any,
        ): String = application.getString(resId, *formatArgs)
    }
}
