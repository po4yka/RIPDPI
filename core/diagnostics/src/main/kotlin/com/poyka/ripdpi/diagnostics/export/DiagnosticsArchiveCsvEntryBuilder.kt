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
                        bytes = redactDiagnosticsLogcat(snapshot.content).toByteArray(),
                    ),
                )
            }
            selection.fileLogSnapshot?.let { content ->
                add(
                    DiagnosticsArchiveEntry(
                        name = "app-log.txt",
                        bytes = redactDiagnosticsArchiveText(content).toByteArray(),
                    ),
                )
            }
            if (selection.request.includePcap && selection.appSettings.rootModeEnabled) {
                selection.pcapFiles.forEach { pcapFile ->
                    add(DiagnosticsArchiveEntry(name = pcapFile.name, bytes = pcapFile.readBytes()))
                }
            }
        }

    internal fun buildProbeResultsCsv(results: List<ProbeResultEntity>): String =
        buildString {
            appendLine("sessionId,probeType,target,outcome,probeRetryCount,createdAt,detailJson")
            results.forEach { result ->
                val redactedResult = redactor.redact(result)
                appendLine(
                    listOf(
                        csvField(result.sessionId),
                        csvField(result.probeType),
                        csvField(redactedResult.target),
                        csvField(result.outcome),
                        csvField(result.probeRetryCount().orEmpty()),
                        csvField(result.createdAt),
                        csvField(redactedResult.detailJson),
                    ).joinToString(","),
                )
            }
        }

    private fun ProbeResultEntity.probeRetryCount(): String? =
        runCatching {
            json.decodeFromString(ListSerializer(ProbeDetail.serializer()), detailJson)
        }.getOrNull()?.let(::deriveProbeRetryCount)?.toString()
}
