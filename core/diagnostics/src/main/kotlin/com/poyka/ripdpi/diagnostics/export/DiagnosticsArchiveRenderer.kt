package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.DeveloperAnalyticsPayload
import com.poyka.ripdpi.diagnostics.DiagnosticsSummaryProjector
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named

class DiagnosticsArchiveRenderer
    @Inject
    constructor(
        private val redactor: DiagnosticsArchiveRedactor,
        private val projector: DiagnosticsSummaryProjector,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        private val jsonEntryBuilder = DiagnosticsArchiveJsonEntryBuilder(redactor, projector, json)
        private val csvEntryBuilder = DiagnosticsArchiveCsvEntryBuilder(json)

        internal fun render(
            target: DiagnosticsArchiveTarget,
            selection: DiagnosticsArchiveSelection,
            developerAnalytics: DeveloperAnalyticsPayload = DeveloperAnalyticsPayload(),
        ): List<DiagnosticsArchiveEntry> {
            val snapshotPayload = jsonEntryBuilder.buildSnapshotPayload(selection)
            val contextPayload = jsonEntryBuilder.buildContextPayload(selection)
            val sectionStatuses = buildSectionStatuses(selection)
            val completeness =
                buildCompleteness(
                    selection = selection,
                    sectionStatuses = sectionStatuses,
                    snapshotPayload = snapshotPayload,
                    contextPayload = contextPayload,
                )
            val compositeEntries =
                if (selection.runType == DiagnosticsArchiveRunType.HOME_COMPOSITE) {
                    jsonEntryBuilder.buildCompositeEntries(selection)
                } else {
                    emptyList()
                }
            val baseEntries =
                buildCoreEntries(
                    target = target,
                    selection = selection,
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
            }

        internal fun buildSummary(
            createdAt: Long,
            selection: DiagnosticsArchiveSelection,
        ): String = jsonEntryBuilder.buildSummary(createdAt, selection)

        internal fun buildProbeResultsCsv(results: List<com.poyka.ripdpi.data.diagnostics.ProbeResultEntity>): String =
            csvEntryBuilder.buildProbeResultsCsv(results)
    }
