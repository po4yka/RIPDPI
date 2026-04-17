package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal class DiagnosticsArchiveCsvEntryBuilder(
    private val json: Json,
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
            add(textEntry(name = "telemetry.csv", content = buildTelemetryCsv(selection.payload)))
            selection.logcatSnapshot?.let { snapshot ->
                add(DiagnosticsArchiveEntry(name = "logcat.txt", bytes = snapshot.content.toByteArray()))
            }
            selection.fileLogSnapshot?.let { content ->
                add(DiagnosticsArchiveEntry(name = "app-log.txt", bytes = content.toByteArray()))
            }
            selection.pcapFiles.forEach { pcapFile ->
                add(DiagnosticsArchiveEntry(name = pcapFile.name, bytes = pcapFile.readBytes()))
            }
        }

    internal fun buildProbeResultsCsv(results: List<ProbeResultEntity>): String =
        buildString {
            appendLine("sessionId,probeType,target,outcome,probeRetryCount,createdAt,detailJson")
            results.forEach { result ->
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
