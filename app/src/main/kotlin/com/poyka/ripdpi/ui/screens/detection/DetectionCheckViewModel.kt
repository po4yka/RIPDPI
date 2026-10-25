package com.poyka.ripdpi.ui.screens.detection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.DetectionCheckRunner
import com.poyka.ripdpi.core.detection.DetectionHistoryEntry
import com.poyka.ripdpi.core.detection.DetectionHistoryRepository
import com.poyka.ripdpi.core.detection.DetectionPermissionPlanner
import com.poyka.ripdpi.core.detection.DetectionProgress
import com.poyka.ripdpi.core.detection.Recommendation
import com.poyka.ripdpi.core.detection.VerdictNarrative
import com.poyka.ripdpi.core.detection.community.CommunityStats
import com.poyka.ripdpi.core.detection.community.CommunityStatsRepository
import com.poyka.ripdpi.core.detection.ui.DetectionColorVisionMode
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.services.RoutingProtectionCatalogService
import com.poyka.ripdpi.ui.components.bufferForUiLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class DetectionCheckUiState(
    val isRunning: Boolean = false,
    val progress: DetectionProgress? = null,
    val result: DetectionCheckResult? = null,
    val narrative: VerdictNarrative? = null,
    val stealthScore: Int? = null,
    val stealthLabel: String? = null,
    val recommendations: ImmutableList<Recommendation> = persistentListOf(),
    val reportText: String? = null,
    val debugReportText: String? = null,
    val error: String? = null,
    val showOnboarding: Boolean = false,
    val permissionAction: DetectionPermissionPlanner.Action = DetectionPermissionPlanner.Action.NONE,
    val missingPermissions: ImmutableList<String> = persistentListOf(),
    val history: ImmutableList<DetectionHistoryEntry> = persistentListOf(),
    val communityStats: CommunityStats? = null,
    val communityStatsLoading: Boolean = false,
    val communityStatsError: String? = null,
    val privacyModeEnabled: Boolean = false,
    val cdnPullingEnabled: Boolean = false,
    val debugModeEnabled: Boolean = false,
    val tlsKeylogPath: String = "",
    val colorVisionMode: DetectionColorVisionMode = DetectionColorVisionMode.OFF,
    val redGreenAltEnabled: Boolean = false,
) {
    val tlsKeylogWarningPath: String?
        get() = tlsKeylogPath.takeUnless { !debugModeEnabled || privacyModeEnabled || it.isBlank() }
}

@HiltViewModel
class DetectionCheckViewModel
    @Inject
    constructor(
        appSettingsRepository: AppSettingsRepository,
        networkFingerprintProvider: com.poyka.ripdpi.data.NetworkFingerprintProvider,
        routingProtectionCatalogService: RoutingProtectionCatalogService,
        detectionCheckRunner: DetectionCheckRunner,
        historyStore: DetectionHistoryRepository,
        communityStatsRepository: CommunityStatsRepository,
        detectionCheckPreferences: DetectionCheckPreferences,
        detectionCheckPlatform: DetectionCheckPlatform,
        stringResolver: StringResolver,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(DetectionCheckUiState())
        val uiState: StateFlow<DetectionCheckUiState> = _uiState.asStateFlow()

        private val _effects = MutableSharedFlow<DetectionCheckEffect>()
        val effects = _effects.bufferForUiLifecycle(viewModelScope)

        private val reducer = DetectionCheckStateReducer(_uiState)

        private val auxStateOwner =
            DetectionAuxStateOwner(
                scope = viewModelScope,
                reducer = reducer,
                appSettingsRepository = appSettingsRepository,
                networkFingerprintProvider = networkFingerprintProvider,
                historyStore = historyStore,
                communityStatsRepository = communityStatsRepository,
                detectionCheckPreferences = detectionCheckPreferences,
            )

        private val permissionStateOwner =
            DetectionPermissionStateOwner(
                reducer = reducer,
                permissionChecker = detectionCheckPlatform,
                preferences = detectionCheckPreferences,
            )

        private val runCoordinator =
            DetectionRunCoordinator(
                scope = viewModelScope,
                reducer = reducer,
                appSettingsRepository = appSettingsRepository,
                detectionCheckRunner = detectionCheckRunner,
                probeRunner = detectionCheckPlatform,
                routingProtectionCatalogService = routingProtectionCatalogService,
                stringResolver = stringResolver,
                onSaveHistory = { result, score -> auxStateOwner.saveToHistory(result, score) },
                onRefreshCommunity = { auxStateOwner.refreshCommunityStats() },
                onTerminalResult = { _effects.emit(it) },
            )

        private var startAfterPermissionResult = false

        init {
            permissionStateOwner.refreshPermissionState()
            auxStateOwner.observeDetectionSettings()
            auxStateOwner.loadHistory()
            auxStateOwner.loadCommunityState()
        }

        fun startCheck() {
            if (permissionStateOwner.showRationaleBeforeCheck()) return
            runCoordinator.startCheck()
        }

        fun stopCheck() = runCoordinator.stopCheck()

        fun reloadCommunityStats() = auxStateOwner.reloadCommunityStats()

        fun dismissOnboarding() = auxStateOwner.dismissOnboarding()

        fun skipOnboarding() {
            auxStateOwner.dismissOnboarding()
            runCoordinator.startCheck()
        }

        /** The screen requests the permissions next; the check starts once Android answers. */
        fun acceptOnboarding() {
            auxStateOwner.dismissOnboarding()
            startAfterPermissionResult = true
        }

        fun onPermissionsResult() {
            permissionStateOwner.onPermissionsResult()
            if (startAfterPermissionResult) {
                startAfterPermissionResult = false
                runCoordinator.startCheck()
            }
        }

        fun refreshPermissionState() = permissionStateOwner.refreshPermissionState()

        val unlockProtanopiaVariant: () -> Unit = auxStateOwner.unlockProtanopiaVariant

        companion object {
            fun requiredPermissions(): Array<String> = DetectionPermissionStateOwner.requiredPermissions()
        }
    }
