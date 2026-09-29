package com.poyka.ripdpi.integration

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.e2e.E2eFixtureRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.AssumptionViolatedException
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement

class E2eFixtureRuleInstrumentedTest {
    @Test
    fun missingFixtureReportsSkipWithoutRunningBody() {
        withArguments(Bundle()) {
            var executed = false
            val skipped =
                assertThrows(AssumptionViolatedException::class.java) {
                    evaluateRule { executed = true }
                }
            assertFalse(executed)
            assertEquals(
                "E2E fixture control arguments are required for com.poyka.ripdpi.e2e tests.",
                skipped.message,
            )
        }
    }

    @Test
    fun eitherFixtureArgumentRunsBodyExactlyOnce() {
        for (key in listOf("ripdpi.fixtureControlHost", "ripdpi.fixtureControlPort")) {
            withArguments(Bundle().apply { putString(key, "fixture") }) {
                var executions = 0
                evaluateRule { executions++ }
                assertEquals(1, executions)
            }
        }
    }

    @Test
    fun configuredFixturePreservesBodyFailure() {
        withArguments(Bundle().apply { putString("ripdpi.fixtureControlHost", "fixture") }) {
            val failure = AssertionError("E2E body failed")
            assertSame(failure, assertThrows(AssertionError::class.java) { evaluateRule { throw failure } })
        }
    }

    @Test
    fun physicalPacketSmokeAssertRunsWithoutFixtureOnlyWhenAllowed() {
        withArguments(packetSmokeArguments("physical_indirect", "assert")) {
            assertThrows(AssumptionViolatedException::class.java) { evaluateRule {} }
            var executions = 0
            evaluateRule(allowPacketSmokeAssertWithoutFixture = true) { executions++ }
            assertEquals(1, executions)
        }
    }

    @Test
    fun otherPacketSmokeModesStillRequireFixture() {
        for ((profile, phase) in listOf(
            "physical_indirect" to "prepare",
            "physical_indirect" to "single",
            "emulator_raw" to "assert",
        )) {
            withArguments(packetSmokeArguments(profile, phase)) {
                assertThrows(AssumptionViolatedException::class.java) {
                    evaluateRule(allowPacketSmokeAssertWithoutFixture = true) {}
                }
            }
        }
    }

    private fun packetSmokeArguments(
        profile: String,
        phase: String,
    ): Bundle =
        Bundle().apply {
            putString("ripdpi.packetSmokeDeviceProfile", profile)
            putString("ripdpi.packetSmokePhase", phase)
        }

    private fun evaluateRule(
        allowPacketSmokeAssertWithoutFixture: Boolean = false,
        body: () -> Unit,
    ) {
        E2eFixtureRule(allowPacketSmokeAssertWithoutFixture)
            .apply(
                object : Statement() {
                    override fun evaluate() = body()
                },
                Description.createTestDescription(javaClass, "fixtureRule"),
            ).evaluate()
    }

    private fun withArguments(
        arguments: Bundle,
        body: () -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originalArguments = InstrumentationRegistry.getArguments()
        try {
            InstrumentationRegistry.registerInstance(instrumentation, arguments)
            body()
        } finally {
            InstrumentationRegistry.registerInstance(instrumentation, originalArguments)
        }
    }
}
