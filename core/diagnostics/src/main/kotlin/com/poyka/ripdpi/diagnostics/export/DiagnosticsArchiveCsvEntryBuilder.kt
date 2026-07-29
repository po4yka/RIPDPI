package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveEntry
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveSelection
import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.deriveProbeRetryCount
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal class DiagnosticsArchiveCsvEntryBuilder(
    private val json: Json,
    private val redactor: DiagnosticsArchiveRedactor,
) {
    internal fun buildCsvEntries(selection: DiagnosticsArchiveSelection): List<DiagnosticsArchiveEntry> =
        buildList {
            add(textEntry(name = "probe-results.csv", content = buildProbeResultsCsv(selection.primaryResults)))
            add(
                textEntry(
                    name = "native-events.csv",
                    content = buildNativeEventsCsv(selection.primaryEvents, selection.globalEvents),
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

    internal fun buildProbeResultsCsv(results: List<ProbeResultEntity>): String =
        buildString {
            appendLine("sessionId,probeType,target,outcome,probeRetryCount,createdAt,detailJson")
            results.map(redactor::redact).forEach { result ->
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
}
