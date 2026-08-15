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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
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
    val suggestedFixes: ImmutableList<DetectionSuggestedFix> = persistentListOf(),
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
            )

        private val onboardingShown = detectionCheckPreferences.wasOnboardingShown()

        init {
            if (!onboardingShown) {
                reducer.mutate { it.copy(showOnboarding = true) }
            }
            permissionStateOwner.refreshPermissionState()
            auxStateOwner.observeDetectionSettings()
            auxStateOwner.loadHistory()
            auxStateOwner.loadCommunityState()
        }

        fun startCheck() = runCoordinator.startCheck()

        fun stopCheck() = runCoordinator.stopCheck()

        fun reloadCommunityStats() = auxStateOwner.reloadCommunityStats()

        fun dismissOnboarding() = auxStateOwner.dismissOnboarding()

        fun applyAllFixes() = auxStateOwner.applyAllFixes()

        fun onPermissionsResult() = permissionStateOwner.onPermissionsResult()

        fun refreshPermissionState() = permissionStateOwner.refreshPermissionState()

        val setPrivacyModeEnabled: (Boolean) -> Unit = auxStateOwner.setPrivacyModeEnabled

        val setCdnPullingEnabled: (Boolean) -> Unit = auxStateOwner.setCdnPullingEnabled

        val setDebugModeEnabled: (Boolean) -> Unit = auxStateOwner.setDebugModeEnabled

        val setColorVisionMode: (DetectionColorVisionMode) -> Unit = auxStateOwner.setColorVisionMode

        val unlockProtanopiaVariant: () -> Unit = auxStateOwner.unlockProtanopiaVariant

        companion object {
            fun requiredPermissions(): Array<String> = DetectionPermissionStateOwner.requiredPermissions()
        }
    }
