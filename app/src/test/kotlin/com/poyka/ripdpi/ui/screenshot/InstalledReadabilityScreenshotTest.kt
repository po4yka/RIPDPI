package com.poyka.ripdpi.ui.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsEventUiModel
import com.poyka.ripdpi.activities.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.activities.DiagnosticsSessionRowUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeCandidateDetailUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeCandidateUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeFamilyUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeReportUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeWinningCandidateUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeWinningPathUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.DiagnosticsUiFactorySupport
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditAssessment
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditConfidence
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditConfidenceLevel
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditCoverage
import com.poyka.ripdpi.diagnostics.StrategyProbeCompletionKind
import com.poyka.ripdpi.diagnostics.StrategyProbeReport
import com.poyka.ripdpi.ui.diagnostics.buildStrategyProbeReportPresentation
import com.poyka.ripdpi.ui.screens.diagnostics.EventRow
import com.poyka.ripdpi.ui.screens.diagnostics.ProbeResultRow
import com.poyka.ripdpi.ui.screens.diagnostics.SessionRow
import com.poyka.ripdpi.ui.screens.diagnostics.StrategyProbeReportCard
import com.poyka.ripdpi.ui.screens.history.FilterCard
import com.poyka.ripdpi.ui.screens.history.HistoryFilterChipsConfig
import com.poyka.ripdpi.ui.screens.settings.SettingsAppearanceSection
import com.poyka.ripdpi.ui.screens.settings.SettingsScreenActions
import com.poyka.ripdpi.ui.state.SettingsUiState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-rRU")
class InstalledReadabilityScreenshotTest {
    @Test
    fun sessionHeaderRussianMaximumFont() {
        capture("sessionHeaderRussianMaximumFont", heightDp = 600) {
            SessionRow(
                session =
                    DiagnosticsSessionRowUiModel(
                        id = "session",
                        profileId = "profile",
                        title = "Automation diagnostics report ready",
                        subtitle = "Отчёт диагностики готов",
                        pathMode = "raw",
                        serviceMode = "proxy",
                        status = "completed",
                        startedAtLabel = "12:34",
                        summary = "",
                        metrics = persistentListOf(),
                        tone = DiagnosticsTone.Positive,
                        completionLabel = stringResource(R.string.diagnostics_scan_completion_normal),
                    ),
                onClick = {},
            )
        }
    }

    @Test
    fun probeHeaderRussianMaximumFont() {
        capture("probeHeaderRussianMaximumFont", heightDp = 480) {
            ProbeResultRow(
                probe =
                    DiagnosticsProbeResultUiModel(
                        id = "probe",
                        probeType = "Проверка доступности",
                        target = "TLS connection to example.org",
                        outcome = "Соединение установлено",
                        tone = DiagnosticsTone.Positive,
                        details = persistentListOf(),
                    ),
                onClick = {},
            )
        }
    }

    @Test
    fun eventHeaderRussianMaximumFont() {
        capture("eventHeaderRussianMaximumFont", heightDp = 480) {
            EventRow(
                event =
                    DiagnosticsEventUiModel(
                        id = "event",
                        source = "Диагностика",
                        severity = "Предупреждение",
                        message = "Соединение не установлено",
                        createdAtLabel = "13.10.2026 12:34:56",
                        tone = DiagnosticsTone.Warning,
                    ),
                onClick = {},
            )
        }
    }

    @Test
    fun completedHistoryFilterRussianMaximumFont() = captureHistoryStatus("completed")

    @Test
    fun runningHistoryFilterRussianMaximumFont() = captureHistoryStatus("running")

    @Test
    fun failedHistoryFilterRussianMaximumFont() = captureHistoryStatus("failed")

    @Test
    fun cancelledHistoryFilterRussianMaximumFont() = captureHistoryStatus("cancelled")

    @Test
    fun systemThemeRussianMaximumFont() = captureThemeChoice("system")

    @Test
    fun lightThemeRussianMaximumFont() = captureThemeChoice("light")

    @Test
    fun darkThemeRussianMaximumFont() = captureThemeChoice("dark")

    @Test
    fun strategyWinnersRussianMaximumFont() {
        capture("strategyWinnersRussianMaximumFont", heightDp = 2400) {
            StrategyProbeReportCard(report = strategyReport(fullMatrix = false), onSelectCandidate = {})
        }
    }

    @Test
    fun strategyMatrixRussianMaximumFont() {
        capture("strategyMatrixRussianMaximumFont", heightDp = 4200) {
            StrategyProbeReportCard(report = strategyReport(fullMatrix = true), onSelectCandidate = {})
        }
    }

    private fun captureHistoryStatus(status: String) {
        capture("${status}HistoryFilterRussianMaximumFont", heightDp = 660) {
            FilterCard(
                title = stringResource(R.string.history_diagnostics_section),
                searchValue = "",
                searchPlaceholder = stringResource(R.string.diagnostics_search_placeholder),
                onSearch = {},
                searchTestTag = "history-search",
                primaryFilter = HistoryFilterChipsConfig(emptyList(), null, {}, { it }),
                secondaryFilter =
                    HistoryFilterChipsConfig(
                        options = listOf(status),
                        selected = status,
                        onSelect = {},
                        tagForOption = { "history-status-$it" },
                    ),
            )
        }
    }

    private fun captureThemeChoice(preference: String) {
        capture("${preference}ThemeRussianMaximumFont", heightDp = 1600) {
            SettingsAppearanceSection(
                uiState = SettingsUiState(appTheme = preference),
                actions = screenshotSettingsActions,
            )
        }
    }

    private fun capture(
        name: String,
        heightDp: Int,
        content: @Composable () -> Unit,
    ) {
        captureBothThemes(
            name = name,
            widthDp = 411,
            heightDp = heightDp,
            fontScale = 2f,
            testClassFqn = javaClass.name,
            content = content,
        )
    }
}

// Same report rows and audit assessment as DiagnosticsStrategyReportLayoutTest.
private fun strategyReport(fullMatrix: Boolean): DiagnosticsStrategyProbeReportUiModel {
    val candidates =
        listOf(
            strategyCandidate("tlsrec-split-host", "TLS record split", recommended = true),
            strategyCandidate("split-host", "Host split", recommended = true, skipped = true),
            strategyCandidate("quic-sni-split", "QUIC SNI split", skipped = true),
            strategyCandidate("quic-disabled", "QUIC disabled"),
        )
    val assessment =
        StrategyProbeAuditAssessment(
            coverage = StrategyProbeAuditCoverage(4, 4, 0, 0, 2, 2, 0, 0, 3, 3, 1, 1, 100, 100),
            confidence = StrategyProbeAuditConfidence(StrategyProbeAuditConfidenceLevel.MEDIUM, 70, "Аудит завершён"),
        )
    val winningPath =
        DiagnosticsStrategyProbeWinningPathUiModel(strategyWinner(candidates[0]), strategyWinner(candidates[3]))
    val presentation =
        DiagnosticsUiFactorySupport(RuntimeEnvironment.getApplication())
            .buildStrategyProbeReportPresentation(
                report = StrategyProbeReport(suiteId = "full_matrix_v1", auditAssessment = assessment),
                winningPath = winningPath,
            ).copy(showFullMatrixInitially = fullMatrix)
    return DiagnosticsStrategyProbeReportUiModel(
        suiteId = "full_matrix_v1",
        suiteLabel = "Automatic audit",
        summaryMetrics = persistentListOf(),
        completionKind = StrategyProbeCompletionKind.NORMAL,
        auditAssessment = assessment,
        winningPath = winningPath,
        families =
            persistentListOf(
                DiagnosticsStrategyProbeFamilyUiModel("Полная матрица", candidates.toPersistentList()),
            ),
        candidateDetails = candidates.associate { it.id to strategyCandidateDetail(it) }.toPersistentMap(),
        presentation = presentation,
    )
}

private fun strategyCandidate(
    id: String,
    label: String,
    recommended: Boolean = false,
    skipped: Boolean = false,
) = DiagnosticsStrategyProbeCandidateUiModel(
    id = id,
    label = label,
    outcome = "Соединение установлено",
    rationale = "Проверка кандидата завершена",
    metrics = persistentListOf(),
    tone = DiagnosticsTone.Positive,
    skipped = skipped,
    recommended = recommended,
)

private fun strategyCandidateDetail(candidate: DiagnosticsStrategyProbeCandidateUiModel) =
    DiagnosticsStrategyProbeCandidateDetailUiModel(
        id = candidate.id,
        label = candidate.label,
        familyLabel = "TCP / QUIC",
        suiteLabel = "Automatic audit",
        outcome = candidate.outcome,
        rationale = candidate.rationale,
        tone = candidate.tone,
        recommended = candidate.recommended,
        notes = persistentListOf(),
        metrics = persistentListOf(),
        signature = persistentListOf(),
        resultGroups = persistentListOf(),
    )

private fun strategyWinner(candidate: DiagnosticsStrategyProbeCandidateUiModel) =
    DiagnosticsStrategyProbeWinningCandidateUiModel(
        id = candidate.id,
        label = candidate.label,
        familyLabel = "TCP / QUIC",
        outcome = candidate.outcome,
        rationale = candidate.rationale,
        metrics = candidate.metrics,
        tone = candidate.tone,
        hiddenCandidateCount = 0,
    )

private val screenshotSettingsActions =
    SettingsScreenActions(
        onOpenProfileUtility = {},
        onOpenDnsSettings = {},
        onOpenAdvancedSettings = {},
        onOpenCustomization = {},
        onOpenAbout = {},
        onOpenDataTransparency = {},
        onShareDebugBundle = {},
        onRepairPermission = {},
        onOpenVpnPermissionDialog = {},
        onThemeSelected = {},
        onPersonaSelected = {},
        onWebRtcProtectionChanged = {},
        onExcludeRussianAppsChanged = {},
        onFullTunnelModeChanged = {},
        onStartOnBootChanged = {},
        onBiometricChanged = {},
        onSaveBackupPin = {},
    )
