package com.poyka.ripdpi.data.xray

import kotlinx.serialization.json.JsonObject

/**
 * Seam for the `libXray.TestXray` config check.
 *
 * The static [com.poyka.ripdpi.data.XrayConfigValidator] catches the product-
 * known error classes, but only the embedded xray-core can fully parse a
 * config (routing rule shape, unknown transports, etc.). The real
 * implementation lives behind gomobile/libXray and CANNOT be exercised in this
 * module's offline unit tests; it is injected so the renderer stays free of the
 * native dependency.
 *
 * Default is [NoOp] — accept everything — so callers that have not yet wired
 * the native tester still get static-validation gating.
 *
 * Tracks the completed `render-validated-xray-client-configs` task (see git history).
 */
fun interface XrayConfigTester {
    fun test(config: JsonObject): TestResult

    sealed interface TestResult {
        data object Ok : TestResult

        /** xray-core rejected the config; [message] is already secret-safe. */
        data class Invalid(
            val message: String,
        ) : TestResult
    }

    companion object {
        /** Accepts every config. Static validation still runs upstream. */
        val NoOp: XrayConfigTester = XrayConfigTester { TestResult.Ok }
    }
}
