package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveEntry
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveSelection
import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.deriveProbeRetryCount
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class DiagnosticsArchiveCsvEntryBuilder(
    private val json: Json,
    private val redactor: DiagnosticsArchiveRedactor,
) {
    internal fun buildCsvEntries(
        selection: DiagnosticsArchiveSelection,
        targetAliases: DiagnosticsArchiveTargetAliasRegistry,
    ): List<DiagnosticsArchiveEntry> =
        buildList {
            add(
                textEntry(
                    name = "probe-results.csv",
                    content = buildProbeResultsCsv(selection.primaryResults, targetAliases),
                ),
            )
            add(
                textEntry(
                    name = "native-events.csv",
                    content = buildNativeEventsCsv(selection.primaryEvents, selection.globalEvents),
                ),
            )
            add(
                textEntry(
                    name = "relay-attempt-traces.jsonl",
                    content = buildRelayAttemptTracesJsonl(selection.primaryEvents, selection.globalEvents),
                ),
            )
            add(textEntry(name = "telemetry.csv", content = buildTelemetryCsv(selection)))
            selection.logcatSnapshot?.let { snapshot ->
                add(
                    DiagnosticsArchiveEntry(
                        name = "logcat.txt",
                        bytes = snapshot.content.toByteArray(),
                    ),
                )
            }
            selection.fileLogSnapshot?.let { snapshot ->
                add(
                    DiagnosticsArchiveEntry(
                        name = "app-log.txt",
                        bytes = snapshot.content.toByteArray(),
                    ),
                )
            }
        }

    internal fun buildProbeResultsCsv(
        results: List<ProbeResultEntity>,
        targetAliases: DiagnosticsArchiveTargetAliasRegistry,
    ): String =
        buildString {
            appendLine("sessionId,probeType,target,outcome,probeRetryCount,createdAt,detailJson")
            results.forEach { rawResult ->
                val result =
                    redactor.redact(rawResult).copy(
                        target = targetAliases.aliasFor(rawResult.target) ?: UnknownProbeResultTargetAlias,
                    )
                appendLine(
                    listOf(
                        csvField(result.sessionId),
                        csvField(result.probeType),
                        csvField(result.target),
                        csvField(result.outcome),
                        csvField(result.probeRetryCount().orEmpty()),
                        csvField(result.createdAt),
                        csvField(result.detailJson),
                    ).joinToString(","),
                )
            }
        }

    private fun ProbeResultEntity.probeRetryCount(): String? =
        runCatching {
            json.decodeFromString(ListSerializer(ProbeDetail.serializer()), detailJson)
        }.getOrNull()?.let(::deriveProbeRetryCount)?.toString()

    private fun buildRelayAttemptTracesJsonl(
        primaryEvents: List<com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity>,
        globalEvents: List<com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity>,
    ): String {
        val jsonLines = Json(json) { prettyPrint = false }
        val events =
            (primaryEvents + globalEvents)
                .filter { event ->
                    !event.connectionSessionId.isNullOrBlank() &&
                        !event.runtimeId.isNullOrBlank() &&
                        event.attemptId != null &&
                        event.attemptSequence != null &&
                        event.stage in RelayTraceStages &&
                        event.outcome in RelayTraceOutcomes
                }.sortedWith(
                    compareBy(
                        { it.connectionSessionId.orEmpty() },
                        { it.runtimeId.orEmpty() },
                        { it.attemptId },
                        { it.attemptSequence },
                    ),
                )
        val connectionAliases =
            events.map { it.connectionSessionId.orEmpty() }.distinct().withIndex().associate {
                it.value to "connection-${it.index + 1}"
            }
        val runtimeAliases =
            events.map { it.runtimeId.orEmpty() }.distinct().withIndex().associate {
                it.value to "runtime-${it.index + 1}"
            }
        return buildString {
            events.forEach { event ->
                appendLine(
                    jsonLines.encodeToString(
                        DiagnosticsArchiveRelayAttemptTraceRecord(
                            connectionCorrelation = connectionAliases.getValue(event.connectionSessionId.orEmpty()),
                            runtimeCorrelation = runtimeAliases.getValue(event.runtimeId.orEmpty()),
                            attemptId = requireNotNull(event.attemptId),
                            sequence = requireNotNull(event.attemptSequence),
                            stage = requireNotNull(event.stage),
                            outcome = requireNotNull(event.outcome),
                            durationMs = event.durationMs?.coerceAtLeast(0),
                            failureStage = event.failureStage?.takeIf(RelayTraceStages::contains),
                            failureClass = event.failureClass?.takeIf(::isSafeRelayTraceToken),
                            ioErrorKind = event.ioErrorKind?.takeIf(::isSafeRelayTraceToken),
                            osErrorCode = event.osErrorCode,
                            peerClosePhase = event.peerClosePhase?.takeIf(::isSafeRelayTraceToken),
                            carrierDisposition = event.carrierDisposition?.takeIf(::isSafeRelayTraceToken),
                        ),
                    ),
                )
            }
        }
    }
}

private const val UnknownProbeResultTargetAlias = "target-unknown"
private val RelayTraceStages =
    setOf("tcp_connect", "reality_tls", "vless_request", "vless_response", "socks_reply", "relay_stream")
private val RelayTraceOutcomes = setOf("started", "succeeded", "failed", "cancelled", "closed")
private val RelayTraceToken = Regex("[a-z0-9_]{1,48}")

private fun isSafeRelayTraceToken(value: String): Boolean = value.matches(RelayTraceToken)
