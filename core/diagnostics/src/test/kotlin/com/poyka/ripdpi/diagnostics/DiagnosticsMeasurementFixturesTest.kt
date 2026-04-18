package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsMeasurementFixturesTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    @Test
    fun `phase 16 acceptance corpus fixture stays aligned with archive constants`() {
        val element =
            json.parseToJsonElement(
                GoldenContractSupport.readSharedFixture("phase16_acceptance_corpus.json"),
            )
        assertEquals(
            DiagnosticsMeasurementConstants.AcceptanceCorpusVersion,
            element.jsonObject
                .getValue("version")
                .jsonPrimitive.content,
        )
        val entries = element.jsonObject.getValue("entries").jsonArray
        assertTrue(entries.size >= 8)
        assertTrue(
            entries.any {
                it.jsonObject
                    .getValue("echAdvertised")
                    .jsonPrimitive.boolean
            },
        )
        assertTrue(
            entries.any {
                !it.jsonObject
                    .getValue("quicCapable")
                    .jsonPrimitive.boolean
            },
        )
    }

    @Test
    fun `phase 16 rollout gate fixture stays aligned with archive constants`() {
        val element =
            json.parseToJsonElement(
                GoldenContractSupport.readSharedFixture("phase16_rollout_gates.json"),
            )
        assertEquals(
            DiagnosticsMeasurementConstants.RolloutPolicyVersion,
            element.jsonObject
                .getValue("version")
                .jsonPrimitive.content,
        )
        val gateIds =
            element.jsonObject
                .getValue("gates")
                .jsonArray
                .map {
                    it.jsonObject
                        .getValue("id")
                        .jsonPrimitive.content
                }
        assertEquals(
            listOf(
                "acceptance",
                "latency_budget",
                "instability_budget",
                "detectability_budget",
                "android_compat_budget",
            ),
            gateIds,
        )
    }
}
