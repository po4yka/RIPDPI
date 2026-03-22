package com.poyka.ripdpi.activities

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
                    .mapIndexed(support::toProbeResultUiModel)
                    .groupBy { it.probeType }
                    .map { (title, items) ->
                        DiagnosticsProbeGroupUiModel(
                            title = title,
                            items = items,
                        )
                    }
            return DiagnosticsSessionDetailUiModel(
                session = support.toSessionRowUiModel(detail.session),
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
