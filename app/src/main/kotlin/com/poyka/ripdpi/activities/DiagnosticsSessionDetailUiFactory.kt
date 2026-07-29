package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.DiagnosticSessionDetail
import com.poyka.ripdpi.diagnostics.DiagnosticsCapabilityEvidence
import com.poyka.ripdpi.ui.diagnostics.toStrategyProbeReportUiModel
import kotlinx.collections.immutable.toImmutableList
import javax.inject.Inject

internal interface DiagnosticsSessionDetailUiMapper {
    @Suppress("LongMethod")
    fun toSessionDetailUiModel(
        detail: DiagnosticSessionDetail,
        showSensitiveDetails: Boolean,
    ): DiagnosticsSessionDetailUiModel
}

internal class DiagnosticsSessionDetailUiFactory
    @Inject
    constructor(
        private val support: DiagnosticsUiFactorySupport,
    ) : DiagnosticsSessionDetailUiMapper {
        @Suppress("LongMethod")
        override fun toSessionDetailUiModel(
            detail: DiagnosticSessionDetail,
            showSensitiveDetails: Boolean,
        ): DiagnosticsSessionDetailUiModel {
            val report = detail.session.report
            val probeGroups =
                detail.results
                    .mapIndexed { index, result ->
                        support.toProbeResultUiModel(
                            index = index,
                            pathMode = support.parsePathMode(detail.session.pathMode),
                            result = result,
                            reportResults = detail.results,
                        )
                    }.groupBy { it.probeType }
                    .map { (title, items) ->
                        DiagnosticsProbeGroupUiModel(
                            title = title,
                            items = items.toImmutableList(),
                        )
                    }
            val diagnoses = report?.diagnoses?.map(support::toDiagnosisUiModel).orEmpty()
            val reportMetadata =
                buildList {
                    report?.let {
                        add(
                            DiagnosticsFieldUiModel(
                                support.context.getString(R.string.diagnostics_scan_metadata_completion),
                                support.core.completionKindLabel(it.completionKind),
                            ),
                        )
                        it.terminationReason?.let { reason ->
                            add(
                                DiagnosticsFieldUiModel(
                                    support.context.getString(R.string.diagnostics_scan_metadata_termination_reason),
                                    support.core.terminationReasonLabel(reason),
                                ),
                            )
                        }
                    }
                    if (detail.session.launchOrigin !=
                        com.poyka.ripdpi.diagnostics.DiagnosticsScanLaunchOrigin.UNKNOWN
                    ) {
                        add(
                            DiagnosticsFieldUiModel(
                                support
                                    .context
                                    .getString(R.string.diagnostics_scan_metadata_launch_source),
                                detail.session.launchOrigin.displayLabel(support.context),
                            ),
                        )
                    }
                    detail.session.launchTrigger?.let { trigger ->
                        add(
                            DiagnosticsFieldUiModel(
                                support.context.getString(R.string.diagnostics_scan_metadata_trigger),
                                trigger.type.displayLabel(support.context),
                            ),
                        )
                        trigger.classification?.let { classification ->
                            add(
                                DiagnosticsFieldUiModel(
                                    support.context.getString(R.string.diagnostics_scan_metadata_handover_class),
                                    classification.displayTriggerClassification(),
                                ),
                            )
                        }
                        trigger.occurredAt?.let { occurredAt ->
                            add(
                                DiagnosticsFieldUiModel(
                                    support.context.getString(R.string.diagnostics_scan_metadata_triggered_at),
                                    support.formatTimestamp(occurredAt),
                                ),
                            )
                        }
                        trigger.previousFingerprintHash.shortFingerprintHash()?.let { fingerprint ->
                            add(
                                DiagnosticsFieldUiModel(
                                    support.context.getString(R.string.diagnostics_scan_metadata_previous_fingerprint),
                                    fingerprint,
                                ),
                            )
                        }
                        trigger.currentFingerprintHash.shortFingerprintHash()?.let { fingerprint ->
                            add(
                                DiagnosticsFieldUiModel(
                                    support.context.getString(R.string.diagnostics_scan_metadata_current_fingerprint),
                                    fingerprint,
                                ),
                            )
                        }
                    }
                    report?.classifierVersion?.let {
                        add(
                            DiagnosticsFieldUiModel(
                                support.context.getString(R.string.diagnostics_field_classifier),
                                it,
                            ),
                        )
                    }
                    report
                        ?.packVersions
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { versions ->
                            add(
                                DiagnosticsFieldUiModel(
                                    support.context.getString(R.string.diagnostics_field_packs),
                                    versions.entries.joinToString(" · ") { (packId, version) -> "$packId@$version" },
                                ),
                            )
                        }
                }
            return DiagnosticsSessionDetailUiModel(
                session = support.toSessionRowUiModel(detail.session),
                diagnoses = diagnoses.toImmutableList(),
                reportMetadata = reportMetadata.toImmutableList(),
                capabilityEvidence =
                    detail.capabilityEvidence
                        .map { evidence ->
                            evidence.toUiModel()
                        }.toImmutableList(),
                probeGroups = probeGroups.toImmutableList(),
                snapshots =
                    detail.snapshots
                        .mapNotNull { snapshot ->
                            support.toNetworkSnapshotUiModel(
                                snapshot,
                                showSensitiveDetails,
                            )
                        }.toImmutableList(),
                events = detail.events.map(support::toEventUiModel).toImmutableList(),
                contextGroups =
                    detail.context
                        ?.context
                        ?.let { context -> support.toContextUiGroups(context, showSensitiveDetails) }
                        .orEmpty()
                        .toImmutableList(),
                strategyProbeReport =
                    report?.strategyProbeReport?.let { strategyReport ->
                        support.toStrategyProbeReportUiModel(
                            report = strategyReport,
                            reportResults = report.results,
                            serviceMode = detail.session.serviceMode,
                        )
                    },
                hasSensitiveDetails = true,
                sensitiveDetailsVisible = showSensitiveDetails,
            )
        }

        private fun DiagnosticsCapabilityEvidence.toUiModel(): DiagnosticsCapabilityEvidenceUiModel =
            DiagnosticsCapabilityEvidenceUiModel(
                authority = authority,
                summary = summary,
                fields =
                    buildList {
                        addAll(details.map { DiagnosticsFieldUiModel(it.label, it.value) })
                        if (source.isNotBlank()) {
                            add(
                                DiagnosticsFieldUiModel(
                                    support.context.getString(R.string.diagnostics_field_source),
                                    source,
                                ),
                            )
                        }
                        if (updatedAt > 0L) {
                            add(
                                DiagnosticsFieldUiModel(
                                    support.context.getString(R.string.diagnostics_field_recorded),
                                    support.formatTimestamp(updatedAt),
                                ),
                            )
                        }
                    }.toImmutableList(),
            )
    }
