package com.poyka.ripdpi.ui.journeys.diagnostics

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import com.poyka.ripdpi.activities.DiagnosticsApproachMode
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.HistorySection
import com.poyka.ripdpi.activities.LogSeverity
import com.poyka.ripdpi.activities.LogSubsystem
import com.poyka.ripdpi.ui.journeys.JourneyPage
import com.poyka.ripdpi.ui.journeys.JourneyRule
import com.poyka.ripdpi.ui.journeys.exists
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags

internal class LogsPage(
    rule: JourneyRule,
) : JourneyPage<LogsPage>(rule, RipDpiTestTags.LogsScreen) {
    val filters = RipDpiTestTags.LogsFilters
    val filtersSheet = RipDpiTestTags.LogsFiltersSheet
    val sheetDone = RipDpiTestTags.LogsSheetDone
    val actions = RipDpiTestTags.LogsActions
    val save = RipDpiTestTags.LogsSave
    val clear = RipDpiTestTags.LogsClear
    val stream = RipDpiTestTags.LogsStream
    val emptyState = RipDpiTestTags.LogsEmptyState
    val autoScroll = RipDpiTestTags.LogsAutoScroll
    val activeSessionOnly = RipDpiTestTags.LogsActiveSessionOnly

    fun subsystemFilter(subsystem: LogSubsystem): String = RipDpiTestTags.logsSubsystemFilter(subsystem)

    fun severityFilter(severity: LogSeverity): String = RipDpiTestTags.logsSeverityFilter(severity)

    fun openFilters() {
        click(filters)
        waitFor(filtersSheet)
    }

    fun closeSheet() {
        click(sheetDone)
        waitGone(filtersSheet)
    }

    // A service failure is also surfaced outside the logs screen, so only count rows inside the stream.
    fun rowShown(text: String): Boolean =
        rule.exists(hasText(text, substring = true) and hasAnyAncestor(hasTestTag(stream)))
}

internal class HistoryPage(
    rule: JourneyRule,
) : JourneyPage<HistoryPage>(rule, RipDpiTestTags.screen(Route.History)) {
    val eventsSearch = RipDpiTestTags.HistoryEventsSearch
    val eventsAutoScroll = RipDpiTestTags.HistoryEventsAutoScroll
    val eventsStateEmpty = RipDpiTestTags.HistoryEventsStateEmpty
    val eventsStateContent = RipDpiTestTags.HistoryEventsStateContent
    val connectionsSearch = RipDpiTestTags.HistoryConnectionsSearch
    val connectionsStateEmpty = RipDpiTestTags.HistoryConnectionsStateEmpty
    val diagnosticsSearch = RipDpiTestTags.HistoryDiagnosticsSearch
    val diagnosticsStateEmpty = RipDpiTestTags.HistoryDiagnosticsStateEmpty
    val diagnosticsStateContent = RipDpiTestTags.HistoryDiagnosticsStateContent
    val diagnosticsDetailSheet = RipDpiTestTags.HistoryDiagnosticsDetailSheet
    val noResults = RipDpiTestTags.HistoryNoResults
    val emptyClearFilters = RipDpiTestTags.HistoryEmptyClearFilters
    val filterClearAll = RipDpiTestTags.HistoryFilterClearAll

    fun section(section: HistorySection): String = RipDpiTestTags.historySection(section)

    fun filterDisclosure(search: String): String = RipDpiTestTags.historyFilterDisclosure(search)

    fun filterOptions(search: String): String = RipDpiTestTags.historyFilterOptions(search)

    fun filterRemove(
        search: String,
        slot: String,
    ): String = RipDpiTestTags.historyFilterRemove(search, slot)

    fun eventSeverityFilter(severity: String): String = RipDpiTestTags.historyEventSeverityFilter(severity)

    fun diagnosticsSession(sessionId: String): String = RipDpiTestTags.historyDiagnosticsSession(sessionId)

    fun diagnosticsPathFilter(path: String): String = RipDpiTestTags.historyDiagnosticsPathFilter(path)

    fun diagnosticsStatusFilter(status: String): String = RipDpiTestTags.historyDiagnosticsStatusFilter(status)

    fun textShown(text: String): Boolean = rule.exists(hasText(text, substring = true))

    fun filterCountShown(
        search: String,
        count: Int,
    ): Boolean = rule.exists(hasTestTag(filterDisclosure(search)) and hasText("· $count", substring = true))
}

internal class DiagnosticsPage(
    rule: JourneyRule,
) : JourneyPage<DiagnosticsPage>(rule, RipDpiTestTags.screen(Route.Diagnostics())) {
    val overviewHero = RipDpiTestTags.DiagnosticsOverviewHero
    val overviewRunScanAction = RipDpiTestTags.DiagnosticsOverviewRunScanAction
    val simpleFunnel = RipDpiTestTags.DiagnosticsSimpleFunnel
    val simpleApply = RipDpiTestTags.DiagnosticsSimpleApply
    val scanStateContent = RipDpiTestTags.DiagnosticsScanStateContent
    val scanStateIdle = RipDpiTestTags.DiagnosticsScanStateIdle
    val scanStateProgress = RipDpiTestTags.DiagnosticsScanStateProgress
    val scanRunRawAction = RipDpiTestTags.DiagnosticsScanRunRawAction
    val scanRunInPathAction = RipDpiTestTags.DiagnosticsScanRunInPathAction
    val scanCancelAction = RipDpiTestTags.DiagnosticsScanCancelAction
    val strategyProbeReport = RipDpiTestTags.DiagnosticsStrategyProbeReport
    val strategyWinningPath = RipDpiTestTags.DiagnosticsStrategyWinningPath
    val strategyWinningTcpAction = RipDpiTestTags.DiagnosticsStrategyWinningTcpAction
    val strategyWinningQuicAction = RipDpiTestTags.DiagnosticsStrategyWinningQuicAction
    val strategyProbeSummary = RipDpiTestTags.DiagnosticsStrategyProbeSummary
    val strategyAuditAssessment = RipDpiTestTags.DiagnosticsStrategyAuditAssessment
    val strategyAuditMediumConfidenceNote = RipDpiTestTags.DiagnosticsStrategyAuditMediumConfidenceNote
    val strategyAuditLowConfidenceBanner = RipDpiTestTags.DiagnosticsStrategyAuditLowConfidenceBanner
    val strategyFullMatrixToggle = RipDpiTestTags.DiagnosticsStrategyFullMatrixToggle
    val strategyCandidateDetailSheet = RipDpiTestTags.DiagnosticsStrategyCandidateDetailSheet
    val strategyCandidateNotesSection = RipDpiTestTags.DiagnosticsStrategyCandidateNotesSection
    val sessionDetailSheet = RipDpiTestTags.DiagnosticsSessionDetailSheet
    val probeDetailSheet = RipDpiTestTags.DiagnosticsProbeDetailSheet
    val expertToggle = RipDpiTestTags.DiagnosticsExpertToggle
    val shareArchive = RipDpiTestTags.DiagnosticsShareArchive
    val saveArchive = RipDpiTestTags.DiagnosticsSaveArchive
    val shareSummary = RipDpiTestTags.DiagnosticsShareSummary
    val saveLogs = RipDpiTestTags.DiagnosticsSaveLogs
    val openLogs = RipDpiTestTags.DiagnosticsOpenLogs

    fun section(section: DiagnosticsSection): String = RipDpiTestTags.diagnosticsSection(section)

    fun approachMode(mode: DiagnosticsApproachMode): String = RipDpiTestTags.diagnosticsApproachMode(mode)

    fun strategyCandidate(id: String): String = RipDpiTestTags.diagnosticsStrategyCandidate(id)

    fun session(sessionId: String): String = RipDpiTestTags.diagnosticsSession(sessionId)

    fun openSection(target: DiagnosticsSection) {
        awaitScreen()
        click(section(target))
    }
}
