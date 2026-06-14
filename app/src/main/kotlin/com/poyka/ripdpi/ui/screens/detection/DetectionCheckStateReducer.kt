package com.poyka.ripdpi.ui.screens.detection

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Shared write seam over the single [MutableStateFlow] that backs the detection screen.
 *
 * Every collaborator (run coordinator, auxiliary-state owner, permission owner) mutates the
 * **same** flow through this reducer so the StateFlow-only effect mechanism is preserved: writes
 * happen via `state.value = reducer(state.value)` (never `update {}`), on `viewModelScope` only.
 */
internal class DetectionCheckStateReducer(
    private val state: MutableStateFlow<DetectionCheckUiState>,
) {
    val value: DetectionCheckUiState
        get() = state.value

    fun mutate(reducer: (DetectionCheckUiState) -> DetectionCheckUiState) {
        state.value = reducer(state.value)
    }
}

/**
 * Narrow seam over [DetectionCheckPlatform]'s permission probe so the permission owner is unit
 * testable without Robolectric. [DetectionCheckPlatform] implements this in addition to its
 * existing surface — purely additive, no behavior change.
 */
internal interface DetectionPermissionChecker {
    fun isPermissionGranted(permission: String): Boolean
}

/**
 * Narrow seam over [DetectionCheckPlatform]'s probe surface so the run coordinator is unit testable
 * without a real Android [android.content.Context]. [DetectionCheckPlatform] implements this in
 * addition to its existing surface — purely additive, no behavior change.
 */
internal interface DetectionProbeRunner {
    val packageName: String

    suspend fun runDetectionCheck(
        runner: com.poyka.ripdpi.core.detection.DetectionCheckRunner,
        config: com.poyka.ripdpi.core.detection.DetectionRunnerConfig,
        onProgress: (com.poyka.ripdpi.core.detection.DetectionProgress) -> Unit,
    ): com.poyka.ripdpi.core.detection.DetectionCheckResult
}

/**
 * Narrow seam over [DetectionCheckPreferences] for the same reason. [DetectionCheckPreferences]
 * implements this in addition to its existing surface — purely additive.
 */
internal interface DetectionCheckPreferenceStore {
    fun wasOnboardingShown(): Boolean

    fun markOnboardingShown()

    fun wasPermissionRequested(permission: String): Boolean

    fun markPermissionsRequested(permissions: Array<String>)
}
