package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PauseIntentAuthority
import com.poyka.ripdpi.data.ProfileMutationRecoveryCoordinator
import com.poyka.ripdpi.data.ProfileUtilityReference
import com.poyka.ripdpi.data.ProfileUtilitySelectionResult
import com.poyka.ripdpi.services.MeasuredActivationRegistry
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceStartPreflightResult
import com.poyka.ripdpi.services.ServiceStartResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeasuredProfileActivationCoordinator
    @Inject
    constructor(
        private val mutations: ProfileMutationRecoveryCoordinator,
        private val settings: AppSettingsRepository,
        private val authority: PauseIntentAuthority,
        private val controller: ServiceController,
        private val measured: MeasuredActivationRegistry,
        private val runtimes: ServiceRuntimeRegistry,
    ) {
        suspend fun select(lease: MeasuredProfileSelectionLease): MeasuredProfileActivationFailure? {
            val mode =
                if (lease.reference is ProfileUtilityReference.Xray) {
                    Mode.VPN
                } else {
                    Mode.fromString(settings.snapshot().ripdpiMode)
                }
            return if (controller.preflight(mode) is ServiceStartPreflightResult.Rejected) {
                MeasuredProfileActivationFailure.SelectionFailed
            } else {
                when (val selected = mutations.selectMeasuredProfile(lease, mode)) {
                    is ProfileUtilitySelectionResult.Selected -> activateSelected(selected, lease, mode)
                    else -> MeasuredProfileActivationFailure.EnvironmentChanged
                }
            }
        }

        private suspend fun activateSelected(
            selected: ProfileUtilitySelectionResult.Selected,
            lease: MeasuredProfileSelectionLease,
            mode: Mode,
        ): MeasuredProfileActivationFailure? {
            var bound: com.poyka.ripdpi.data.RuntimeActivationReceipt? = null
            val outcome =
                runCatching {
                    measured.register(selected.receipt, lease, selected.catalogGeneration)
                    bound = measured.bind(selected.receipt, mode)
                    val captured = bound
                    if (captured == null) {
                        MeasuredProfileActivationFailure.EnvironmentChanged
                    } else {
                        dispatchBound(captured, lease, mode)
                    }
                }
            if (outcome.isFailure || outcome.getOrNull() != null) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    val cleanupFailure = runCatching { cancelOwned(selected.receipt, bound) }.exceptionOrNull()
                    if (cleanupFailure != null) {
                        val original = outcome.exceptionOrNull()
                        if (original != null) original.addSuppressed(cleanupFailure) else throw cleanupFailure
                    }
                }
            }
            return outcome.getOrThrow()
        }

        private suspend fun dispatchBound(
            bound: com.poyka.ripdpi.data.RuntimeActivationReceipt,
            lease: MeasuredProfileSelectionLease,
            mode: Mode,
        ): MeasuredProfileActivationFailure? {
            val runtime = runtimes.current(mode)
            val accepted =
                if (runtime != null) {
                    runtime.reloadConnectionPolicy(RuntimePolicyReloadIntent.Explicit(bound)) {
                        authority.isCurrent(bound) && lease.externalEnvironmentMatchesNow()
                    }
                } else {
                    controller.startPrepared(mode, bound) is ServiceStartResult.Accepted
                }
            return if (accepted) null else MeasuredProfileActivationFailure.SelectionFailed
        }

        private fun cancelOwned(
            selected: com.poyka.ripdpi.data.ProfileActivationReceipt,
            bound: com.poyka.ripdpi.data.RuntimeActivationReceipt?,
        ) {
            try {
                if (bound == null) {
                    authority.cancelProfileActivation(selected)
                } else {
                    authority.cancelPendingActivation(bound)
                }
            } finally {
                measured.release(selected.commandId)
            }
        }
    }

enum class MeasuredProfileActivationFailure { EnvironmentChanged, SelectionFailed }
