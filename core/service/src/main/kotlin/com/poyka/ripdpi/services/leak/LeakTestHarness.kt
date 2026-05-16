package com.poyka.ripdpi.services.leak

/**
 * Uniform entry point for all VPN leak-test scenarios.
 *
 * Each scenario drives a specific leak vector; calling [run] returns a
 * [LeakTestResult] that callers assert against.
 */
internal interface LeakTestHarness {
    /** Human-readable label used in assertion messages. */
    val label: String

    /** Executes the scenario and returns a pass/fail result with detail. */
    fun run(): LeakTestResult
}

/** Result returned by [LeakTestHarness.run]. */
internal sealed interface LeakTestResult {
    data object Pass : LeakTestResult

    data class Fail(
        val reason: String,
    ) : LeakTestResult
}
