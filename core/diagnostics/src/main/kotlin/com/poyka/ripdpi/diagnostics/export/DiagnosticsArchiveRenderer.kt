package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.DeveloperAnalyticsPayload
import com.poyka.ripdpi.diagnostics.DiagnosticsSummaryProjector
import com.poyka.ripdpi.diagnostics.FileLogWriter
import com.poyka.ripdpi.diagnostics.LogcatSnapshotCollector
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
    ) {
        private val jsonEntryBuilder = DiagnosticsArchiveJsonEntryBuilder(redactor, projector, json)
        private val csvEntryBuilder = DiagnosticsArchiveCsvEntryBuilder(json, redactor)

        internal fun render(
            target: DiagnosticsArchiveTarget,
            selection: DiagnosticsArchiveSelection,
            developerAnalytics: DeveloperAnalyticsPayload = DeveloperAnalyticsPayload(),
        ): List<DiagnosticsArchiveEntry> {
            val archiveSelection = selection.withRedactedBoundedLogs()
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
            return baseEntries +
                DiagnosticsArchiveEntry(
                    name = "integrity.json",
                    bytes =
                        json
                            .encodeToString(
                                DiagnosticsArchiveIntegrityPayload.serializer(),
                                buildIntegrityPayload(target, baseEntries),
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

        private fun DiagnosticsArchiveSelection.withRedactedBoundedLogs(): DiagnosticsArchiveSelection =
            copy(
                logcatSnapshot =
                    logcatSnapshot?.let { snapshot ->
                        val redacted = redactDiagnosticsLogcat(snapshot.content)
                        val redactedBytes = redacted.toByteArray(Charsets.UTF_8)
                        val bounded = tailUtf8Bytes(redacted, LogcatSnapshotCollector.MAX_LOGCAT_BYTES)
                        snapshot.copy(
                            content = bounded.toString(Charsets.UTF_8),
                            byteCount = bounded.size,
                            truncated = snapshot.truncated || redactedBytes.size > bounded.size,
                        )
                    },
                fileLogSnapshot =
                    fileLogSnapshot?.let { snapshot ->
                        val redacted = redactDiagnosticsArchiveText(snapshot.content)
                        val redactedBytes = redacted.toByteArray(Charsets.UTF_8)
                        val bounded = tailUtf8Bytes(redacted, FileLogWriter.MAX_LOG_FILE_BYTES.toInt())
                        snapshot.copy(
                            content = bounded.toString(Charsets.UTF_8),
                            byteCount = bounded.size,
                            truncated = snapshot.truncated || redactedBytes.size > bounded.size,
                        )
                    },
            )
    }
