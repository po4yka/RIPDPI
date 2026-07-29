package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.replay.ReplayErrorKind
import com.poyka.ripdpi.diagnostics.replay.ReplayProbeRequest
import com.poyka.ripdpi.diagnostics.replay.ReplayProbeResult
import com.poyka.ripdpi.diagnostics.replay.ReplayStepEvent
import com.poyka.ripdpi.diagnostics.replay.ReplayStepKind
import com.poyka.ripdpi.diagnostics.replay.ReplayVerdict
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayArchiveEntryBuilderTest {
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = false
        }
    private val redactor = ReplayArchiveRedactor()
    private val clock = DiagnosticsArchiveClock { FixedNowMs }
    private val builder = ReplayArchiveEntryBuilder(redactor, clock, json)

    @Test
    fun emptyList_returnsNull() {
        assertNull(builder.build(emptyList()))
    }

    @Test
    fun build_producesReplayResultsJsonEntry() {
        val entry = builder.build(listOf(successResult()))
        assertNotNull(entry)
        assertEquals(ReplayArchiveEntryBuilder.ReplayResultsFileName, entry!!.name)
    }

    @Test
    fun build_pinsSchemaVersion_toArchiveFormat() {
        val entry = builder.build(listOf(successResult()))!!
        val payload = json.parseToJsonElement(String(entry.bytes)).jsonObject
        assertEquals(
            DiagnosticsArchiveFormat.schemaVersion,
            payload["schemaVersion"]!!.jsonPrimitive.content.toInt(),
        )
    }

    @Test
    fun build_emitsGeneratedAt_fromClock() {
        val entry = builder.build(listOf(successResult()))!!
        val payload = json.parseToJsonElement(String(entry.bytes)).jsonObject
        assertEquals(FixedNowMs, payload["generatedAt"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun build_redactsIpFromDnsStepDetail() {
        val entry = builder.build(listOf(dnsResolvedResult("203.0.113.45")))!!
        val serialized = String(entry.bytes)
        assertFalse(
            "raw IPv4 must NOT appear in serialized payload",
            serialized.contains("203.0.113.45"),
        )
        assertTrue(
            "redaction placeholder must appear in serialized payload",
            serialized.contains(ReplayArchiveRedactor.Placeholder),
        )
    }

    @Test
    fun build_redactsIpv6FromDnsStepDetail() {
        val entry = builder.build(listOf(dnsResolvedResult("2001:db8::a:b:c")))!!
        val serialized = String(entry.bytes)
        assertFalse(
            "raw IPv6 must NOT appear in serialized payload",
            serialized.contains("2001:db8::a:b:c"),
        )
        assertTrue(serialized.contains(ReplayArchiveRedactor.Placeholder))
    }

    @Test
    fun build_redactsRequestDomainAndPreservesExecutionFields() {
        val request = ReplayProbeRequest(domain = "x.test", strategyId = "xtls", timeoutMs = 7_500)
        val result =
            ReplayProbeResult(
                request = request,
                events = persistentListOf(),
                verdict = ReplayVerdict.Success,
                terminalStep = null,
                recommendationKey = "",
            )
        val entry = builder.build(listOf(result))!!
        val replay =
            json
                .parseToJsonElement(String(entry.bytes))
                .jsonObject["replays"]!!
                .jsonArray[0]
                .jsonObject["request"]!!
                .jsonObject
        assertEquals("redacted", replay["domain"]!!.jsonPrimitive.content)
        assertEquals("xtls", replay["strategyId"]!!.jsonPrimitive.content)
        assertEquals(7_500L, replay["timeoutMs"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun build_emitsAllEventDiscriminators() {
        val entry = builder.build(listOf(failureResult()))!!
        val events: List<JsonObject> =
            json
                .parseToJsonElement(String(entry.bytes))
                .jsonObject["replays"]!!
                .jsonArray[0]
                .jsonObject["events"]!!
                .jsonArray
                .map { it.jsonObject }
        val types = events.map { it["type"]!!.jsonPrimitive.content }
        assertEquals(
            listOf("step_started", "step_failed", "finished"),
            types,
        )
    }

    private fun successResult(): ReplayProbeResult =
        ReplayProbeResult(
            request = ReplayProbeRequest(domain = "ok.test", strategyId = "default", timeoutMs = 1_000),
            events =
                persistentListOf(
                    ReplayStepEvent.StepStarted(ReplayStepKind.DnsResolve, 0),
                    ReplayStepEvent.StepCompleted(ReplayStepKind.DnsResolve, 12, "resolved"),
                    ReplayStepEvent.Finished(
                        verdict = ReplayVerdict.Success,
                        terminalStep = null,
                        recommendationKey = "",
                    ),
                ),
            verdict = ReplayVerdict.Success,
            terminalStep = null,
            recommendationKey = "",
        )

    private fun dnsResolvedResult(ip: String): ReplayProbeResult =
        ReplayProbeResult(
            request = ReplayProbeRequest(domain = "dns.test", strategyId = "default", timeoutMs = 1_000),
            events =
                persistentListOf(
                    ReplayStepEvent.StepStarted(ReplayStepKind.DnsResolve, 0),
                    ReplayStepEvent.StepCompleted(ReplayStepKind.DnsResolve, 12, "resolved $ip"),
                    ReplayStepEvent.Finished(
                        verdict = ReplayVerdict.Success,
                        terminalStep = null,
                        recommendationKey = "",
                    ),
                ),
            verdict = ReplayVerdict.Success,
            terminalStep = null,
            recommendationKey = "",
        )

    private fun failureResult(): ReplayProbeResult =
        ReplayProbeResult(
            request = ReplayProbeRequest(domain = "x.test", strategyId = "default", timeoutMs = 1_000),
            events =
                persistentListOf(
                    ReplayStepEvent.StepStarted(ReplayStepKind.TlsHandshake, 0),
                    ReplayStepEvent.StepFailed(
                        step = ReplayStepKind.TlsHandshake,
                        errorKind = ReplayErrorKind.TlsHandshakeFailed,
                        detail = "unexpected EOF",
                    ),
                    ReplayStepEvent.Finished(
                        verdict = ReplayVerdict.Failure,
                        terminalStep = ReplayStepKind.TlsHandshake,
                        recommendationKey = "vpn_replay_rec_rst_after_client_hello",
                    ),
                ),
            verdict = ReplayVerdict.Failure,
            terminalStep = ReplayStepKind.TlsHandshake,
            recommendationKey = "vpn_replay_rec_rst_after_client_hello",
        )

    companion object {
        private const val FixedNowMs: Long = 1_748_137_920_123L
    }
}
