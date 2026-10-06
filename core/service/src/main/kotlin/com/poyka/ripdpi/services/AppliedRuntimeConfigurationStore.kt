package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.AppliedRuntimeConfigurationSource
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.RuntimeConfigurationApplyFailure
import com.poyka.ripdpi.data.RuntimeConfigurationAttempt
import com.poyka.ripdpi.data.RuntimeConfigurationPendingStatus
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class AppliedRuntimeConfigurationStore
    @Inject
    constructor(
        private val pauseReceipts: RuntimeAppliedReceiptConsumer,
    ) : AppliedRuntimeConfigurationSource {
        private val lock = Any()
        private val applicationState =
            MutableStateFlow<Map<Mode, RuntimeConfigurationApplication>>(
                Mode.entries.associateWith { RuntimeConfigurationApplication.Unknown },
            )
        private val pendingState =
            MutableStateFlow(Mode.entries.associateWith { RuntimeConfigurationPendingStatus.Unknown })
        private val attempts = mutableMapOf<Mode, RuntimeConfigurationAttempt>()
        private val lastAcknowledged = mutableMapOf<Mode, AppliedRuntimeConfiguration>()
        private val requestedIdentities = mutableMapOf<Mode, RuntimeConfigurationIdentity>()
        private val acknowledgedIdentities = mutableMapOf<Mode, RuntimeConfigurationIdentity>()
        private val currentSavedIdentities = mutableMapOf<Mode, RuntimeConfigurationIdentity>()
        private val provisioning = mutableMapOf<Mode, ProvisioningAcknowledgment>()
        private val revokedRuntimes = mutableSetOf<String>()

        override val applications = applicationState.asStateFlow()
        override val pendingChanges = pendingState.asStateFlow()

        @Inject
        fun observeSavedConfiguration(observer: SavedRuntimeConfigurationObserver) {
            observer.observe(this)
        }

        fun begin(
            attempt: RuntimeConfigurationAttempt,
            requested: RuntimeConfigurationIdentity,
        ): Boolean =
            synchronized(lock) {
                val current = attempts[attempt.mode]
                if (attempt.runtimeId in revokedRuntimes ||
                    (current?.runtimeId == attempt.runtimeId && current.revision >= attempt.revision)
                ) {
                    false
                } else {
                    current?.runtimeId?.takeIf { it != attempt.runtimeId }?.let { revokedRuntimes += it }
                    pauseReceipts.bind(attempt, requested)
                    provisioning.remove(attempt.mode)
                    attempts[attempt.mode] = attempt
                    requestedIdentities[attempt.mode] = requested
                    applicationState.value = applicationState.value +
                        (
                            attempt.mode to
                                RuntimeConfigurationApplication.Applying(attempt, lastAcknowledged[attempt.mode])
                        )
                    refreshPending(attempt.mode)
                    true
                }
            }

        /** Synchronous publication makes confirmed state atomic with its captured requested reference. */
        fun acknowledge(
            attempt: RuntimeConfigurationAttempt,
            configuration: AppliedRuntimeConfiguration,
            consumed: CandidateConfigurationProof?,
        ): Boolean =
            synchronized(lock) {
                val current = applicationState.value[attempt.mode]
                when {
                    attempts[attempt.mode] != attempt || !configuration.matchesAttempt(attempt) -> {
                        false
                    }

                    current is RuntimeConfigurationApplication.Applied -> {
                        current.configuration == configuration &&
                            pauseReceipts.acknowledge(attempt, configuration, consumed)
                    }

                    current !is RuntimeConfigurationApplication.Applying -> {
                        false
                    }

                    !pauseReceipts.acknowledge(attempt, configuration, consumed) -> {
                        false
                    }

                    else -> {
                        lastAcknowledged[attempt.mode] = configuration
                        acknowledgedIdentities[attempt.mode] = checkNotNull(requestedIdentities[attempt.mode])
                        applicationState.value =
                            applicationState.value +
                            (attempt.mode to RuntimeConfigurationApplication.Applied(configuration))
                        refreshPending(attempt.mode)
                        true
                    }
                }
            }

        /** Applies only a committed automatic transform of the captured request at its positive ACK. */
        fun acknowledgeProvisioned(
            attempt: RuntimeConfigurationAttempt,
            configuration: AppliedRuntimeConfiguration,
            captured: RuntimeConfigurationIdentity,
            provisioned: RuntimeConfigurationIdentity,
            consumed: CandidateConfigurationProof?,
        ): Boolean =
            synchronized(lock) {
                val current = applicationState.value[attempt.mode]
                when {
                    attempts[attempt.mode] != attempt || !configuration.matchesAttempt(attempt) -> {
                        false
                    }

                    current is RuntimeConfigurationApplication.Applied -> {
                        val previous = provisioning[attempt.mode]
                        current.configuration == configuration && previous != null &&
                            previous.captured.matches(captured) && previous.provisioned.matches(provisioned) &&
                            pauseReceipts.acknowledge(attempt, configuration, consumed)
                    }

                    current is RuntimeConfigurationApplication.Applying &&
                        requestedIdentities[attempt.mode]?.matches(captured) == true -> {
                        if (pauseReceipts.acknowledge(attempt, configuration, consumed)) {
                            provisioning[attempt.mode] = ProvisioningAcknowledgment(captured, provisioned)
                            requestedIdentities[attempt.mode] = provisioned
                            lastAcknowledged[attempt.mode] = configuration
                            acknowledgedIdentities[attempt.mode] = provisioned
                            applicationState.value = applicationState.value +
                                (attempt.mode to RuntimeConfigurationApplication.Applied(configuration))
                            refreshPending(attempt.mode)
                            true
                        } else {
                            false
                        }
                    }

                    else -> {
                        false
                    }
                }
            }

        fun publishIfCurrent(
            attempt: RuntimeConfigurationAttempt,
            publish: () -> Unit,
        ): Boolean =
            synchronized(lock) {
                val applied = applicationState.value[attempt.mode] as? RuntimeConfigurationApplication.Applied
                if (attempts[attempt.mode] != attempt || applied?.configuration?.matchesAttempt(attempt) != true) {
                    false
                } else {
                    pauseReceipts.publishIfCurrent(attempt, publish)
                }
            }

        fun fail(
            attempt: RuntimeConfigurationAttempt,
            failure: RuntimeConfigurationApplyFailure,
        ): Boolean =
            synchronized(lock) {
                if (attempts[attempt.mode] != attempt || attempt.runtimeId in revokedRuntimes) {
                    false
                } else {
                    pauseReceipts.failed(attempt)
                    applicationState.value = applicationState.value +
                        (
                            attempt.mode to
                                RuntimeConfigurationApplication.Failed(attempt, lastAcknowledged[attempt.mode], failure)
                        )
                    refreshPending(attempt.mode)
                    true
                }
            }

        fun stopped(
            mode: Mode,
            runtimeId: String,
        ) = synchronized(lock) {
            pauseReceipts.stopped(runtimeId)
            revokedRuntimes += runtimeId
            if (attempts[mode]?.runtimeId == runtimeId) {
                if (applicationState.value[mode] !is RuntimeConfigurationApplication.Failed) {
                    applicationState.value = applicationState.value + (mode to RuntimeConfigurationApplication.Unknown)
                }
                pendingState.value = pendingState.value + (mode to RuntimeConfigurationPendingStatus.Unknown)
            }
        }

        fun observeSaved(
            mode: Mode,
            requested: RuntimeConfigurationIdentity?,
        ) = synchronized(lock) {
            if (requested == null) currentSavedIdentities.remove(mode) else currentSavedIdentities[mode] = requested
            refreshPending(mode)
        }

        /** Captures only the requested DNS facet; a concurrent saved transport edit remains pending. */
        fun beginDns(
            attempt: RuntimeConfigurationAttempt,
            capturedRequested: RuntimeConfigurationIdentity?,
        ): Boolean =
            synchronized(lock) {
                val acknowledged = acknowledgedIdentities[attempt.mode]
                if (acknowledged == null) {
                    false
                } else {
                    begin(
                        attempt,
                        if (capturedRequested ==
                            null
                        ) {
                            acknowledged
                        } else {
                            acknowledged.withDns(capturedRequested)
                        },
                    )
                }
            }

        fun lastConfirmed(mode: Mode): AppliedRuntimeConfiguration? = synchronized(lock) { lastAcknowledged[mode] }

        private class ProvisioningAcknowledgment(
            val captured: RuntimeConfigurationIdentity,
            val provisioned: RuntimeConfigurationIdentity,
        )

        private fun refreshPending(mode: Mode) {
            val saved = currentSavedIdentities[mode]
            val acknowledged = acknowledgedIdentities[mode]
            val status =
                when {
                    applicationState.value[mode] is RuntimeConfigurationApplication.Unknown || saved == null ||
                        acknowledged == null -> {
                        RuntimeConfigurationPendingStatus.Unknown
                    }

                    saved.matches(acknowledged) -> {
                        RuntimeConfigurationPendingStatus.InSync
                    }

                    else -> {
                        RuntimeConfigurationPendingStatus.SavedChangesPending
                    }
                }
            pendingState.value = pendingState.value + (mode to status)
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AppliedRuntimeConfigurationModule {
    @Binds
    abstract fun bindConfigurationSource(store: AppliedRuntimeConfigurationStore): AppliedRuntimeConfigurationSource
}

private fun AppliedRuntimeConfiguration.matchesAttempt(attempt: RuntimeConfigurationAttempt): Boolean =
    runtimeId == attempt.runtimeId && revision == attempt.revision && mode == attempt.mode
