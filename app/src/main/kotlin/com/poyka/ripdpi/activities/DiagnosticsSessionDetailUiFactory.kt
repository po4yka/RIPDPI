package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.DiagnosticSessionDetail
import javax.inject.Inject

internal interface DiagnosticsSessionDetailUiMapper {
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
                        )
                    }.groupBy { it.probeType }
                    .map { (title, items) ->
                        DiagnosticsProbeGroupUiModel(
                            title = title,
                            items = items,
                        )
                    }
            val diagnoses = report?.diagnoses?.map(support::toDiagnosisUiModel).orEmpty()
            val reportMetadata =
                buildList {
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
                    report?.classifierVersion?.let { add(DiagnosticsFieldUiModel("Classifier", it)) }
                    report
                        ?.packVersions
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { versions ->
                            add(
                                DiagnosticsFieldUiModel(
                                    "Packs",
                                    versions.entries.joinToString(" · ") { (packId, version) -> "$packId@$version" },
                                ),
                            )
                        }
                }
            return DiagnosticsSessionDetailUiModel(
                session = support.toSessionRowUiModel(detail.session),
                diagnoses = diagnoses,
                reportMetadata = reportMetadata,
                probeGroups = probeGroups,
                snapshots =
                    detail.snapshots.mapNotNull { snapshot ->
                        support.toNetworkSnapshotUiModel(
                            snapshot,
                            showSensitiveDetails,
                        )
                    },
                events = detail.events.map(support::toEventUiModel),
                contextGroups =
                    detail.context
                        ?.context
                        ?.let { context -> support.toContextUiGroups(context, showSensitiveDetails) }
                        .orEmpty(),
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
    }
