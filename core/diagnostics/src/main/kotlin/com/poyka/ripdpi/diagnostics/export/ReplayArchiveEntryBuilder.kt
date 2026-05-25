package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.replay.ReplayErrorKind
import com.poyka.ripdpi.diagnostics.replay.ReplayProbeRequest
import com.poyka.ripdpi.diagnostics.replay.ReplayProbeResult
import com.poyka.ripdpi.diagnostics.replay.ReplayStepEvent
import com.poyka.ripdpi.diagnostics.replay.ReplayStepKind
import com.poyka.ripdpi.diagnostics.replay.ReplayVerdict
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named

/**
 * Maps recent in-memory [ReplayProbeResult] aggregates to the
 * `replay-results.json` archive entry (G010 P4.8). Each event's
 * `detail` string is run through [ReplayArchiveRedactor] before
 * serialization, so resolver IPs from the DNS step never reach disk.
 *
 * Returns `null` when [results] is empty so the renderer can omit the
 * entry entirely (avoiding a misleading empty-array file in the zip).
 */
class ReplayArchiveEntryBuilder
    @Inject
    constructor(
        private val redactor: ReplayArchiveRedactor,
        private val clock: DiagnosticsArchiveClock,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        internal fun build(results: List<ReplayProbeResult>): DiagnosticsArchiveEntry? {
            if (results.isEmpty()) return null
            val payload =
                ReplayArchivePayload(
                    schemaVersion = DiagnosticsArchiveFormat.schemaVersion,
                    generatedAt = clock.now(),
                    replays = results.map(::toEntry),
                )
            val bytes =
                json
                    .encodeToString(ReplayArchivePayload.serializer(), payload)
                    .toByteArray()
            return DiagnosticsArchiveEntry(name = ReplayResultsFileName, bytes = bytes)
        }

        private fun toEntry(result: ReplayProbeResult): ReplayArchiveEntry =
            ReplayArchiveEntry(
                request = result.request.toArchive(),
                verdict = result.verdict.toArchive(),
                terminalStep = result.terminalStep?.toArchive(),
                recommendationKey = result.recommendationKey,
                events = result.events.map(::toArchiveEvent),
            )

        private fun toArchiveEvent(event: ReplayStepEvent): ReplayArchiveStepEvent =
            when (event) {
                is ReplayStepEvent.StepStarted -> {
                    ReplayArchiveStepEvent.StepStarted(
                        step = event.step.toArchive(),
                        timestampMs = event.timestampMs,
                    )
                }

                is ReplayStepEvent.StepCompleted -> {
                    ReplayArchiveStepEvent.StepCompleted(
                        step = event.step.toArchive(),
                        durationMs = event.durationMs,
                        detail = redactor.redact(event.detail),
                    )
                }

                is ReplayStepEvent.StepFailed -> {
                    ReplayArchiveStepEvent.StepFailed(
                        step = event.step.toArchive(),
                        errorKind = event.errorKind.toArchive(),
                        detail = redactor.redact(event.detail),
                    )
                }

                is ReplayStepEvent.Finished -> {
                    ReplayArchiveStepEvent.Finished(
                        verdict = event.verdict.toArchive(),
                        terminalStep = event.terminalStep?.toArchive(),
                        recommendationKey = event.recommendationKey,
                    )
                }
            }

        companion object {
            const val ReplayResultsFileName: String = "replay-results.json"
        }
    }

private fun ReplayProbeRequest.toArchive(): ReplayArchiveRequest =
    ReplayArchiveRequest(domain = domain, strategyId = strategyId, timeoutMs = timeoutMs)

private fun ReplayVerdict.toArchive(): ReplayArchiveVerdict =
    when (this) {
        ReplayVerdict.Success -> ReplayArchiveVerdict.Success
        ReplayVerdict.Failure -> ReplayArchiveVerdict.Failure
        ReplayVerdict.Cancelled -> ReplayArchiveVerdict.Cancelled
    }

private fun ReplayStepKind.toArchive(): ReplayArchiveStepKind =
    when (this) {
        ReplayStepKind.DnsResolve -> ReplayArchiveStepKind.DnsResolve
        ReplayStepKind.TcpOpen -> ReplayArchiveStepKind.TcpOpen
        ReplayStepKind.TlsClientHello -> ReplayArchiveStepKind.TlsClientHello
        ReplayStepKind.TlsHandshake -> ReplayArchiveStepKind.TlsHandshake
        ReplayStepKind.FirstByte -> ReplayArchiveStepKind.FirstByte
    }

private fun ReplayErrorKind.toArchive(): ReplayArchiveErrorKind =
    when (this) {
        ReplayErrorKind.DnsTampered -> ReplayArchiveErrorKind.DnsTampered
        ReplayErrorKind.ConnectionRefused -> ReplayArchiveErrorKind.ConnectionRefused
        ReplayErrorKind.ConnectionReset -> ReplayArchiveErrorKind.ConnectionReset
        ReplayErrorKind.Timeout -> ReplayArchiveErrorKind.Timeout
        ReplayErrorKind.TlsHandshakeFailed -> ReplayArchiveErrorKind.TlsHandshakeFailed
        ReplayErrorKind.Unknown -> ReplayArchiveErrorKind.Unknown
    }
