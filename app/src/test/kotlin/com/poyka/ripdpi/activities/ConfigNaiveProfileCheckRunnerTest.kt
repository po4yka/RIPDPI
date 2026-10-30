package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.services.NaiveProxyCheckOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigNaiveProfileCheckRunnerTest {
    private val strings =
        object : StringResolver {
            override fun getString(
                resId: Int,
                vararg formatArgs: Any,
            ): String = "string-$resId"

            override fun getQuantityString(
                resId: Int,
                quantity: Int,
                vararg formatArgs: Any,
            ): String = "plural-$resId"
        }

    @Test
    fun `a check reports progress then its outcome and ignores a second click while running`() {
        val scope = TestScope(StandardTestDispatcher())
        val effects = MutableSharedFlow<ConfigEffect>(replay = 8)
        val gate = CompletableDeferred<NaiveProxyCheckOutcome>()
        var checks = 0
        val runner =
            ConfigNaiveProfileCheckRunner(
                scope = scope,
                check = { _, _ ->
                    checks++
                    gate.await()
                },
                effects = effects,
                strings = strings,
            )

        runner.start(ConfigDraft())
        runner.start(ConfigDraft())
        scope.advanceUntilIdle()
        gate.complete(NaiveProxyCheckOutcome.Auth)
        scope.advanceUntilIdle()

        assertEquals(1, checks)
        assertEquals(
            listOf(
                "string-${R.string.config_relay_naive_check_running}",
                "string-${R.string.config_relay_naive_check_auth}",
            ),
            effects.replayCache.map { (it as ConfigEffect.Message).text },
        )
    }
}
