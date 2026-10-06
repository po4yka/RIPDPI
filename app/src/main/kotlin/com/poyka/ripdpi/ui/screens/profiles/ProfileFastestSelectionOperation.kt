package com.poyka.ripdpi.ui.screens.profiles

import com.poyka.ripdpi.data.ProfileUtilityReference
import com.poyka.ripdpi.services.MeasuredProfileActivationCoordinator
import com.poyka.ripdpi.services.MeasuredProfileActivationFailure
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Ranks only successful HTTP observations made by this one user operation. */
internal class ProfileFastestSelectionOperation(
    private val measurement: ProfileUtilityMeasurementCoordinator,
    private val selection: MeasuredProfileActivationCoordinator,
) {
    suspend fun run(
        references: List<ProfileUtilityReference>,
        url: String,
        isCurrent: suspend () -> Boolean,
        publish: (ProfileUtilityReference, ProfileMeasurementUiState) -> Unit,
    ): ProfileUtilityFailure? {
        var winner: ProfileUtilityCheckResult.Measured? = null
        var lastFailure = ProfileUtilityFailure.Unsupported
        var aborted: ProfileUtilityFailure? = null
        for (reference in references) {
            val checked = checkCurrent(reference, url, isCurrent, publish)
            when (checked) {
                is ProfileUtilityCheckResult.Failed -> {
                    lastFailure = checked.reason
                    publish(reference, ProfileMeasurementUiState.Failed(checked.reason))
                    if (checked.reason in
                        setOf(
                            ProfileUtilityFailure.CleanupPending,
                            ProfileUtilityFailure.Busy,
                            ProfileUtilityFailure.EnvironmentChanged,
                        )
                    ) {
                        aborted = checked.reason
                    }
                }

                is ProfileUtilityCheckResult.Measured -> {
                    publish(
                        reference,
                        ProfileMeasurementUiState.Measured(checked.latencyMillis, checked.observedAtMillis),
                    )
                    if (winner == null || checked.latencyMillis < winner.latencyMillis) winner = checked
                }
            }
            if (aborted != null) break
        }
        currentCoroutineContext().ensureActive()
        return when {
            aborted != null -> aborted
            !isCurrent() -> ProfileUtilityFailure.EnvironmentChanged
            winner == null -> lastFailure
            else -> selection.select(winner.lease)?.profileFailure()
        }
    }

    private suspend fun checkCurrent(
        reference: ProfileUtilityReference,
        url: String,
        isCurrent: suspend () -> Boolean,
        publish: (ProfileUtilityReference, ProfileMeasurementUiState) -> Unit,
    ): ProfileUtilityCheckResult {
        currentCoroutineContext().ensureActive()
        return if (!isCurrent()) {
            ProfileUtilityCheckResult.Failed(ProfileUtilityFailure.EnvironmentChanged)
        } else {
            publish(reference, ProfileMeasurementUiState.Checking)
            val result = measurement.check(reference, url)
            currentCoroutineContext().ensureActive()
            if (isCurrent()) result else ProfileUtilityCheckResult.Failed(ProfileUtilityFailure.EnvironmentChanged)
        }
    }
}

internal fun MeasuredProfileActivationFailure.profileFailure(): ProfileUtilityFailure =
    when (this) {
        MeasuredProfileActivationFailure.EnvironmentChanged -> {
            ProfileUtilityFailure.EnvironmentChanged
        }

        MeasuredProfileActivationFailure.SelectionFailed -> {
            ProfileUtilityFailure.SelectionFailed
        }
    }
