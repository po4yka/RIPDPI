package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.diagnostics.DeveloperAnalyticsPayload
import com.poyka.ripdpi.diagnostics.DiagnosticsSummaryProjector
import com.poyka.ripdpi.diagnostics.FileLogWriter
import com.poyka.ripdpi.diagnostics.LogcatPostRedactionAccounting
import com.poyka.ripdpi.diagnostics.LogcatSnapshotCollector
import com.poyka.ripdpi.diagnostics.readLineAlignedLogcatOutput
import com.poyka.ripdpi.diagnostics.tailUtf8Bytes
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named

class DiagnosticsArchiveRenderer
    @Inject
    constructor(
        private val redactor: DiagnosticsArchiveRedactor,
        private val projector: DiagnosticsSummaryProjector,
        private val replayArchiveEntryBuilder: ReplayArchiveEntryBuilder,
        @param:Named("diagnosticsJson")
        private val json: Json,
        private val serviceStateStore: ServiceStateStore,
    ) {
        private val jsonEntryBuilder = DiagnosticsArchiveJsonEntryBuilder(redactor, projector, json)
        private val csvEntryBuilder = DiagnosticsArchiveCsvEntryBuilder(json, redactor)

        internal fun render(
            target: DiagnosticsArchiveTarget,
            selection: DiagnosticsArchiveSelection,
            developerAnalytics: DeveloperAnalyticsPayload = DeveloperAnalyticsPayload(),
        ): List<DiagnosticsArchiveEntry> {
            val correlationRedactor = DiagnosticsArchiveCorrelationRedactor()
            val archiveSelection = selection.withRedactedBoundedLogs(correlationRedactor::redactText)
            val snapshotPayload = jsonEntryBuilder.buildSnapshotPayload(archiveSelection)
            val contextPayload = jsonEntryBuilder.buildContextPayload(archiveSelection)
            val sectionStatuses = buildSectionStatuses(archiveSelection)
            val completeness =
                buildCompleteness(
                    selection = archiveSelection,
                    sectionStatuses = sectionStatuses,
                    snapshotPayload = snapshotPayload,
                    contextPayload = contextPayload,
                )
            val compositeEntries =
                if (archiveSelection.runType == DiagnosticsArchiveRunType.HOME_COMPOSITE) {
                    jsonEntryBuilder.buildCompositeEntries(archiveSelection)
                } else {
                    emptyList()
                }
            val baseEntries =
                buildCoreEntries(
                    target = target,
                    selection = archiveSelection,
                    sectionStatuses = sectionStatuses,
                    snapshotPayload = snapshotPayload,
                    contextPayload = contextPayload,
                    completeness = completeness,
                    compositeEntries = compositeEntries,
                    developerAnalytics = developerAnalytics,
                )
            val providerContext = serviceStateStore.currentXrayExportContext()
            val contextualEntries =
                baseEntries.map { entry ->
                    if (entry.name == "summary.txt" && providerContext != null) {
                        entry.copy(bytes = "${entry.bytes.decodeToString()}\n\n$providerContext".toByteArray())
                    } else {
                        entry
                    }
                }
            val privacyEntries =
                contextualEntries.map { entry ->
                    if (entry.name == "logcat.txt") entry else correlationRedactor.redact(entry)
                }
            return privacyEntries +
                DiagnosticsArchiveEntry(
                    name = "integrity.json",
                    bytes =
                        json
                            .encodeToString(
                                DiagnosticsArchiveIntegrityPayload.serializer(),
                                buildIntegrityPayload(target, privacyEntries),
                            ).toByteArray(),
                )
        }

        private fun buildCoreEntries(
            target: DiagnosticsArchiveTarget,
            selection: DiagnosticsArchiveSelection,
            sectionStatuses: Map<String, DiagnosticsArchiveSectionStatus>,
            snapshotPayload: DiagnosticsArchiveSnapshotPayload,
            contextPayload: DiagnosticsArchiveContextPayload,
            completeness: DiagnosticsArchiveCompletenessPayload,
            compositeEntries: List<DiagnosticsArchiveEntry>,
            developerAnalytics: DeveloperAnalyticsPayload,
        ): List<DiagnosticsArchiveEntry> =
            buildList {
                addAll(
                    jsonEntryBuilder.buildJsonEntries(
                        target = target,
                        selection = selection,
                        sectionStatuses = sectionStatuses,
                        snapshotPayload = snapshotPayload,
                        contextPayload = contextPayload,
                        completeness = completeness,
                        compositeEntries = compositeEntries,
                        developerAnalytics = developerAnalytics,
                    ),
                )
                addAll(
                    csvEntryBuilder.buildCsvEntries(
                        selection = selection,
                    ),
                )
                replayArchiveEntryBuilder.build(selection.replayResults)?.let(::add)
            }

        internal fun buildSummary(
            createdAt: Long,
            selection: DiagnosticsArchiveSelection,
        ): String = jsonEntryBuilder.buildSummary(createdAt, selection)

        internal fun buildProbeResultsCsv(results: List<com.poyka.ripdpi.data.diagnostics.ProbeResultEntity>): String =
            csvEntryBuilder.buildProbeResultsCsv(results)

        private fun DiagnosticsArchiveSelection.withRedactedBoundedLogs(
            redactCorrelations: (String) -> String,
        ): DiagnosticsArchiveSelection =
            copy(
                logcatSnapshot =
                    logcatSnapshot?.let { snapshot ->
                        val completeContent =
                            if (snapshot.captureScope == LogcatSnapshotCollector.AppVisibleSnapshotScope) {
                                snapshot.content.dropLeadingPartialLineIf(snapshot.truncated)
                            } else {
                                snapshot.content
                            }
                        val redacted = redactCorrelations(redactDiagnosticsLogcat(completeContent))
                        val bounded =
                            readLineAlignedLogcatOutput(
                                bytes = redacted.toByteArray(Charsets.UTF_8),
                                retainHead =
                                    snapshot.captureScope == LogcatSnapshotCollector.TimeBoundSnapshotScope,
                                maxBytes = LogcatSnapshotCollector.MAX_LOGCAT_BYTES,
                            )
                        snapshot.copy(
                            content = bounded.content,
                            byteCount = bounded.byteCount,
                            truncated = snapshot.truncated || bounded.truncated,
                            earliestRetainedTimestamp = bounded.earliestRetainedTimestamp,
                            latestRetainedTimestamp = bounded.latestRetainedTimestamp,
                            postRedactionAccounting =
                                LogcatPostRedactionAccounting(
                                    sourceByteCount = bounded.sourceByteCount,
                                    retainedByteCount = bounded.retainedByteCount,
                                    droppedByteCount = bounded.droppedByteCount,
                                    entryByteCount = bounded.byteCount,
                                    earliestRetainedTimestamp = bounded.earliestRetainedTimestamp,
                                    latestRetainedTimestamp = bounded.latestRetainedTimestamp,
                                ),
                        )
                    },
                fileLogSnapshot =
                    fileLogSnapshot?.let { snapshot ->
                        val completeContent = snapshot.content.dropLeadingPartialLineIf(snapshot.truncated)
                        val redacted = redactDiagnosticsArchiveText(completeContent)
                        val redactedBytes = redacted.toByteArray(Charsets.UTF_8)
                        val bounded = tailUtf8Bytes(redacted, FileLogWriter.MAX_LOG_FILE_BYTES.toInt())
                        snapshot.copy(
                            content = bounded.toString(Charsets.UTF_8),
                            byteCount = bounded.size,
                            truncated = snapshot.truncated || redactedBytes.size > bounded.size,
                        )
                    },
            )

        private fun String.dropLeadingPartialLineIf(truncated: Boolean): String =
            if (truncated) substringAfter('\n', missingDelimiterValue = "") else this
    }

private class DiagnosticsArchiveCorrelationRedactor {
    private val aliases = linkedMapOf<String, String>()

    fun redact(entry: DiagnosticsArchiveEntry): DiagnosticsArchiveEntry {
        val text = entry.bytes.decodeToString()
        val redacted = redactText(text)
        return entry.copy(bytes = redacted.toByteArray())
    }

    fun redactText(text: String): String =
        CorrelationUuidRegex.replace(text) { match ->
            aliases.getOrPut(match.value.lowercase()) { "correlation-${aliases.size + 1}" }
        }
}

private val CorrelationUuidRegex =
    Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b")
