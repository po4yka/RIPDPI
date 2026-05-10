package com.poyka.ripdpi.activities

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityChecker
import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityResult
import com.poyka.ripdpi.diagnostics.dpi.DnsIntegrityVerdict
import com.poyka.ripdpi.diagnostics.dpi.DpiAssetLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

private const val DnsIntegrityPreviewDomainLimit = 5

@Suppress("TooManyFunctions")
@HiltViewModel
class DiagnosticsViewModel
    @Inject
    internal constructor(
        savedStateHandle: SavedStateHandle,
        private val diagnosticsInteractionDependencies: DiagnosticsInteractionDependencies,
        private val diagnosticsContextDependencies: DiagnosticsContextDependencies,
        private val diagnosticsViewModelBootstrapper: DiagnosticsViewModelBootstrapper,
        diagnosticsUiStateAssembler: DiagnosticsUiStateAssembler,
        uiStateFactory: DiagnosticsUiStateFactory,
    ) : ViewModel() {
        private val autoStartScan: Boolean =
            savedStateHandle.get<Boolean>("auto_start_scan") ?: false
        private var initialized = false
        private var autoStartScanHandled = false
        private val selectionState = MutableStateFlow(SelectionState())
        private val filterState = MutableStateFlow(FilterState())
        private val sessionDetailState = MutableStateFlow(SessionDetailState())
        private val scanLifecycleState = MutableStateFlow(ScanLifecycleState())
        private val _effects =
            MutableSharedFlow<DiagnosticsEffect>(
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        val effects: SharedFlow<DiagnosticsEffect> = _effects.asSharedFlow()

        val uiState: StateFlow<DiagnosticsUiState> =
            diagnosticsUiStateAssembler.assemble(
                scope = viewModelScope,
                interactionDependencies = diagnosticsInteractionDependencies,
                contextDependencies = diagnosticsContextDependencies,
                selectionState = selectionState,
                filterState = filterState,
                sessionDetailState = sessionDetailState,
                scanLifecycleState = scanLifecycleState,
            )

        private val _pcapRecording = MutableStateFlow(false)
        val pcapRecording: StateFlow<Boolean> = _pcapRecording.asStateFlow()
        private val _dnsIntegrityTool = MutableStateFlow(DiagnosticsDnsIntegrityToolUiModel())
        val dnsIntegrityTool: StateFlow<DiagnosticsDnsIntegrityToolUiModel> = _dnsIntegrityTool.asStateFlow()
        private val dnsIntegrityChecker = DnsIntegrityChecker()

        private val mutations =
            DiagnosticsMutationRunner(
                scope = viewModelScope,
                diagnosticsTimelineSource = diagnosticsInteractionDependencies.diagnosticsTimelineSource,
                diagnosticsScanController = diagnosticsInteractionDependencies.diagnosticsScanController,
                diagnosticsDetailLoader = diagnosticsInteractionDependencies.diagnosticsDetailLoader,
                diagnosticsShareService = diagnosticsInteractionDependencies.diagnosticsShareService,
                diagnosticsResolverActions = diagnosticsInteractionDependencies.diagnosticsResolverActions,
                uiStateFactory = uiStateFactory,
                effects = _effects,
                currentUiState = { uiState.value },
            )

        private val selectionActions =
            DiagnosticsSelectionActions(mutations, selectionState, sessionDetailState)

        private val filterActions = DiagnosticsFilterActions(filterState)

        private val scanActions =
            DiagnosticsScanActions(
                mutations = mutations,
                scanLifecycle = scanLifecycleState,
                appContext = diagnosticsContextDependencies.appContext,
                loadSessionDetail = { sessionId, showSensitive ->
                    mutations.loadSessionDetail(
                        sessionId = sessionId,
                        showSensitiveDetails = showSensitive,
                        selection = selectionState,
                        sessionDetail = sessionDetailState,
                    )
                },
            )

        private val shareActions = DiagnosticsShareActions(mutations, scanLifecycleState)

        fun initialize() {
            if (initialized) {
                return
            }
            initialized = true
            diagnosticsViewModelBootstrapper.initialize(
                scope = viewModelScope,
                initializeScanActions = scanActions::initialize,
            )
            if (autoStartScan && !autoStartScanHandled) {
                autoStartScanHandled = true
                runScan()
            }
        }

        fun selectSection(section: DiagnosticsSection) = selectionActions.selectSection(section)

        val lastScanSummary: String?
            get() =
                uiState.value.scan.latestSession
                    ?.toLastScanSummary()

        fun selectProfile(profileId: String) = selectionActions.selectProfile(profileId)

        fun selectSession(sessionId: String) = selectionActions.selectSession(sessionId)

        fun selectApproachMode(mode: DiagnosticsApproachMode) = selectionActions.selectApproachMode(mode)

        fun selectApproach(approachId: String) = selectionActions.selectApproach(approachId)

        fun dismissSessionDetail() = selectionActions.dismissSessionDetail()

        fun dismissApproachDetail() = selectionActions.dismissApproachDetail()

        fun selectEvent(eventId: String) = selectionActions.selectEvent(eventId)

        fun dismissEventDetail() = selectionActions.dismissEventDetail()

        fun selectProbe(probe: DiagnosticsProbeResultUiModel) = selectionActions.selectProbe(probe)

        fun dismissProbeDetail() = selectionActions.dismissProbeDetail()

        fun selectStrategyProbeCandidate(detail: DiagnosticsStrategyProbeCandidateDetailUiModel) =
            selectionActions.selectStrategyProbeCandidate(detail)

        fun dismissStrategyProbeCandidate() = selectionActions.dismissStrategyProbeCandidate()

        fun toggleSensitiveSessionDetails() = selectionActions.toggleSensitiveSessionDetails()

        fun setSessionPathModeFilter(pathMode: String?) = filterActions.setSessionPathModeFilter(pathMode)

        fun setSessionStatusFilter(status: String?) = filterActions.setSessionStatusFilter(status)

        fun setSessionSearch(query: String) = filterActions.setSessionSearch(query)

        fun toggleEventFilter(
            source: String? = null,
            severity: String? = null,
        ) = filterActions.toggleEventFilter(source, severity)

        fun setEventSearch(query: String) = filterActions.setEventSearch(query)

        fun setEventAutoScroll(enabled: Boolean) = filterActions.setEventAutoScroll(enabled)

        fun startRawScan() = scanActions.startRawScan()

        fun startInPathScan() = scanActions.startInPathScan()

        fun runScan() = startInPathScan()

        fun waitForHiddenProbeAndRun() = scanActions.waitForHiddenProbeAndRun()

        fun cancelHiddenProbeAndRun() = scanActions.cancelHiddenProbeAndRun()

        fun dismissHiddenProbeConflictDialog() = scanActions.dismissHiddenProbeConflictDialog()

        fun confirmSensitiveProfileRun() = scanActions.confirmSensitiveProfileRun()

        fun dismissSensitiveProfileConsentDialog() = scanActions.dismissSensitiveProfileConsentDialog()

        fun cancelScan() = scanActions.cancelScan()

        fun keepResolverRecommendationForSession(
            sessionId: String? =
                uiState.value.scan.latestSession
                    ?.id,
        ) = scanActions.keepResolverRecommendationForSession(sessionId)

        fun saveResolverRecommendation(
            sessionId: String? =
                uiState.value.scan.latestSession
                    ?.id,
        ) = scanActions.saveResolverRecommendation(sessionId)

        fun shareSummary(sessionId: String? = null) = shareActions.shareSummary(sessionId)

        fun shareArchive(sessionId: String? = null) = shareActions.shareArchive(sessionId)

        fun saveArchive(sessionId: String? = null) = shareActions.saveArchive(sessionId)

        fun togglePcapRecording() {
            _pcapRecording.value = !_pcapRecording.value
        }

        fun runDnsIntegrityCheck() {
            if (_dnsIntegrityTool.value.state == DiagnosticsDnsIntegrityState.Running) {
                return
            }
            _dnsIntegrityTool.value =
                DiagnosticsDnsIntegrityToolUiModel(
                    state = DiagnosticsDnsIntegrityState.Running,
                    summary = "Checking UDP/53 answers against DoH controls...",
                )
            viewModelScope.launch {
                try {
                    val domains =
                        withContext(Dispatchers.IO) {
                            DpiAssetLoader(diagnosticsContextDependencies.appContext)
                                .loadDomains()
                                .take(DnsIntegrityPreviewDomainLimit)
                        }
                    if (domains.isEmpty()) {
                        _dnsIntegrityTool.value =
                            DiagnosticsDnsIntegrityToolUiModel(
                                state = DiagnosticsDnsIntegrityState.Failed,
                                summary = "DNS integrity check could not start.",
                                errorMessage = "No bundled DPI domains are available.",
                            )
                        return@launch
                    }
                    _dnsIntegrityTool.value = dnsIntegrityChecker.check(domains).toUiModel()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    _dnsIntegrityTool.value =
                        DiagnosticsDnsIntegrityToolUiModel(
                            state = DiagnosticsDnsIntegrityState.Failed,
                            summary = "DNS integrity check failed.",
                            errorMessage = error.message ?: error.javaClass.simpleName,
                        )
                }
            }
        }
    }

private fun DiagnosticsSessionRowUiModel.toLastScanSummary(): String =
    listOf(
        metrics.firstOrNull { it.label.contains("confidence", ignoreCase = true) }?.value ?: status,
        startedAtLabel,
    ).joinToString(" · ")

private fun DnsIntegrityResult.toUiModel(): DiagnosticsDnsIntegrityToolUiModel {
    val flagged = domains.count { result -> result.verdict != DnsIntegrityVerdict.DNS_OK }
    val checked = domains.size
    val flaggedTone = countTone(flagged)
    val dohBlockedTone = countTone(dohBlocked)
    return DiagnosticsDnsIntegrityToolUiModel(
        state = DiagnosticsDnsIntegrityState.Complete,
        summary =
            if (flagged == 0) {
                "No DNS substitution detected across $checked bundled domains."
            } else {
                "$flagged of $checked domains showed DNS integrity warnings."
            },
        metrics =
            listOf(
                DiagnosticsMetricUiModel("checked", checked.toString(), DiagnosticsTone.Info),
                DiagnosticsMetricUiModel(
                    "flagged",
                    flagged.toString(),
                    flaggedTone,
                ),
                DiagnosticsMetricUiModel("stub IPs", stubIps.size.toString(), DiagnosticsTone.Neutral),
                DiagnosticsMetricUiModel(
                    "DoH blocked",
                    dohBlocked.toString(),
                    dohBlockedTone,
                ),
            ).toPersistentList(),
        rows =
            domains
                .map { result ->
                    DiagnosticsDnsIntegrityDomainUiModel(
                        domain = result.domain,
                        verdict = result.verdict.displayLabel(),
                        udpAnswer = result.udpRecords.joinToString().ifBlank { "timeout" },
                        dohAnswer = result.dohIps.joinToString().ifBlank { "unavailable" },
                        tone = result.verdict.tone(),
                    )
                }.toPersistentList(),
    )
}

private fun DnsIntegrityVerdict.displayLabel(): String = name.lowercase(Locale.US).replace('_', ' ')

private fun countTone(count: Int): DiagnosticsTone =
    if (count == 0) {
        DiagnosticsTone.Positive
    } else {
        DiagnosticsTone.Warning
    }

private fun DnsIntegrityVerdict.tone(): DiagnosticsTone =
    when (this) {
        DnsIntegrityVerdict.DNS_OK -> DiagnosticsTone.Positive

        DnsIntegrityVerdict.DOH_BLOCKED,
        DnsIntegrityVerdict.DNS_SUBSTITUTION,
        DnsIntegrityVerdict.DNS_INTERCEPTION,
        DnsIntegrityVerdict.FAKE_NXDOMAIN,
        DnsIntegrityVerdict.FAKE_IP,
        -> DiagnosticsTone.Warning

        DnsIntegrityVerdict.UNKNOWN -> DiagnosticsTone.Neutral
    }
