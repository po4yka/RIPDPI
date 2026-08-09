package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.DeveloperStageTimingEvidence
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageStatus
import com.poyka.ripdpi.diagnostics.ProbeDetail

private const val UdpLatencyMs = "udpLatencyMs"
private const val EncryptedLatencyMs = "encryptedLatencyMs"
private const val TcpConnectMs = "tcpConnectMs"
private const val TlsHandshakeMs = "tlsHandshakeMs"
private const val TtfbMs = "ttfbMs"

internal fun buildDeveloperStageTimingEvidence(
    stage: DiagnosticsArchiveCompositeStageSelection,
): DeveloperStageTimingEvidence {
    val timings =
        stage.report
            ?.results
            .orEmpty()
            .flatMap { it.details }
            .measuredTimings()
    val summary = stage.stageSummary
    return DeveloperStageTimingEvidence(
        stageKey = summary.stageKey,
        wallClockMs = summary.wallClockMs,
        cpuMs = summary.cpuMs,
        dnsMs = timings.total(UdpLatencyMs, EncryptedLatencyMs),
        tcpHandshakeMs = timings.total(TcpConnectMs),
        tlsHandshakeMs = timings.total(TlsHandshakeMs),
        ttfbMs = timings.total(TtfbMs),
        notes =
            buildList {
                add("phase_timing_scope=sum_of_emitted_probe_measurements")
                if (summary.cpuMs != null) add("cpu_scope=app_process_during_stage_window")
                if (summary.status == DiagnosticsHomeCompositeStageStatus.SKIPPED) add("skipped")
                if (summary.status == DiagnosticsHomeCompositeStageStatus.FAILED) add("failed")
                summary.sessionId?.let { add("session=$it") }
            },
    )
}

private fun List<ProbeDetail>.measuredTimings(): Map<String, List<Long>> =
    asSequence()
        .filter { it.key in setOf(UdpLatencyMs, EncryptedLatencyMs, TcpConnectMs, TlsHandshakeMs, TtfbMs) }
        .mapNotNull { detail ->
            detail.value
                .toLongOrNull()
                ?.takeIf { it >= 0 }
                ?.let { detail.key to it }
        }.groupBy(keySelector = Pair<String, Long>::first, valueTransform = Pair<String, Long>::second)

private fun Map<String, List<Long>>.total(vararg keys: String): Long? {
    val values = keys.flatMap { get(it).orEmpty() }
    return values.takeIf(List<Long>::isNotEmpty)?.sum()
}
