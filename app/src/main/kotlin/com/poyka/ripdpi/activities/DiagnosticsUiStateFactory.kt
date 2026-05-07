package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.BypassApproachDetail
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.ui.diagnostics.toApproachDetailUiModel
import javax.inject.Inject

internal class DiagnosticsUiStateFactory
    @Inject
    constructor(
        private val support: DiagnosticsUiFactorySupport,
        private val sessionDetailUiMapper: DiagnosticsSessionDetailUiMapper,
        private val resolver: DiagnosticsUiInputResolver = DiagnosticsUiInputResolver(support),
        private val overviewFactory: DiagnosticsOverviewUiStateFactory = DiagnosticsOverviewUiStateFactory(support),
        private val scanFactory: DiagnosticsScanUiStateFactory = DiagnosticsScanUiStateFactory(support),
        private val liveFactory: DiagnosticsLiveUiStateFactory = DiagnosticsLiveUiStateFactory(support),
        private val sessionsFactory: DiagnosticsSessionsUiStateFactory = DiagnosticsSessionsUiStateFactory(support),
        private val approachesFactory: DiagnosticsApproachesUiStateFactory =
            DiagnosticsApproachesUiStateFactory(support),
        private val eventsFactory: DiagnosticsEventsUiStateFactory = DiagnosticsEventsUiStateFactory(support),
        private val shareFactory: DiagnosticsShareUiStateFactory = DiagnosticsShareUiStateFactory(support),
        private val performanceFactory: DiagnosticsPerformanceUiStateFactory = DiagnosticsPerformanceUiStateFactory(),
    ) {
        fun buildUiState(input: DiagnosticsUiStateInput): DiagnosticsUiState {
            val timer = performanceFactory.startTimer()
            val eventModels =
                timer.measureEventMapping {
                    eventsFactory.mapEvents(input.nativeEvents)
                }
            val sessionRows = sessionsFactory.mapRows(input.sessions)
            val resolvedInput =
                timer.measureResolve {
                    resolver.resolve(input, eventModels)
                }
            val eventsState =
                timer.measureEvents {
                    eventsFactory.build(input, eventModels)
                }

            return DiagnosticsUiState(
                selectedSection = resolvedInput.selectedSection,
                overview =
                    timer.measureOverview {
                        overviewFactory.build(input, resolvedInput, sessionRows)
                    },
                scan =
                    timer.measureScan {
                        scanFactory.build(input, resolvedInput)
                    },
                live =
                    timer.measureLive {
                        liveFactory.build(input)
                    },
                sessions =
                    timer.measureSessions {
                        sessionsFactory.build(input, sessionRows)
                    },
                approaches =
                    timer.measureApproaches {
                        approachesFactory.build(input)
                    },
                events = eventsState.model,
                share =
                    timer.measureShare {
                        shareFactory.build(input, resolvedInput)
                    },
                selectedSessionDetail = resolvedInput.sessionDetailWithVisibility,
                selectedApproachDetail = input.selectedApproachDetail,
                selectedEvent = eventsState.selectedEvent,
                selectedProbe = input.selectedProbe,
                selectedStrategyProbeCandidate = resolvedInput.selectedStrategyProbeCandidate,
                performance = performanceFactory.build(input, timer),
            )
        }

        fun toSessionDetailUiModel(
            detail: com.poyka.ripdpi.diagnostics.DiagnosticSessionDetail,
            showSensitiveDetails: Boolean,
        ): DiagnosticsSessionDetailUiModel =
            sessionDetailUiMapper.toSessionDetailUiModel(
                detail = detail,
                showSensitiveDetails = showSensitiveDetails,
            )

        fun toApproachDetailUiModel(detail: BypassApproachDetail): DiagnosticsApproachDetailUiModel =
            support.toApproachDetailUiModel(detail)

        fun toCompletedProbeUiModel(
            phase: String,
            target: String,
            outcome: String,
            pathMode: ScanPathMode,
            scanKind: ScanKind,
        ): CompletedProbeUiModel =
            CompletedProbeUiModel(
                target = target,
                outcome = outcome,
                tone =
                    when (scanKind) {
                        ScanKind.STRATEGY_PROBE -> {
                            strategyProgressTone(outcome)
                        }

                        ScanKind.CONNECTIVITY -> {
                            phaseToConnectivityProbeType(phase)
                                ?.let { probeType -> support.core.toneForProbeOutcome(probeType, pathMode, outcome) }
                                ?: DiagnosticsTone.Neutral
                        }
                    },
            )
    }

private fun phaseToConnectivityProbeType(phase: String): String? =
    when (phase) {
        "environment" -> {
            "network_environment"
        }

        "dns" -> {
            "dns_integrity"
        }

        "reachability" -> {
            "domain_reachability"
        }

        "quic" -> {
            "quic_reachability"
        }

        "tcp" -> {
            "tcp_fat_header"
        }

        "service" -> {
            "service_reachability"
        }

        "circumvention" -> {
            "circumvention_reachability"
        }

        "telegram" -> {
            "telegram_availability"
        }

        "throughput" -> {
            "throughput_window"
        }

        else -> {
            null
        }
    }

private fun strategyProgressTone(outcome: String): DiagnosticsTone =
    when {
        outcome.equals("success", ignoreCase = true) -> {
            DiagnosticsTone.Positive
        }

        outcome.equals("partial", ignoreCase = true) -> {
            DiagnosticsTone.Warning
        }

        outcome.equals("skipped", ignoreCase = true) ||
            outcome.equals("not_applicable", ignoreCase = true) -> {
            DiagnosticsTone.Neutral
        }

        else -> {
            DiagnosticsTone.Negative
        }
    }
