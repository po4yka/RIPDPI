package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.PauseIntentAuthority
import com.poyka.ripdpi.data.ProfileActivationReceipt
import com.poyka.ripdpi.data.ProfileMutationRecoveryAccess
import com.poyka.ripdpi.data.RuntimeActivationReceipt
import com.poyka.ripdpi.data.RuntimeCommandOrigin
import com.poyka.ripdpi.data.RuntimeConfigurationAttempt
import javax.inject.Inject
import javax.inject.Singleton

/** Process-local proof only; reconstruction cannot reuse an expired measurement to dispatch native startup. */
@Singleton
class MeasuredActivationRegistry
    @Inject
    internal constructor(
        private val authority: PauseIntentAuthority,
        private val recovery: ProfileMutationRecoveryAccess,
        private val requestedCapture: RequestedRuntimeConfigurationSource,
        private val settings: com.poyka.ripdpi.data.AppSettingsRepository,
    ) {
        private class Entry(
            val lease: MeasuredProfileSelectionLease,
            val catalogGeneration: Long,
        ) {
            var requested: RuntimeConfigurationIdentity? = null
        }

        private val entries = mutableMapOf<String, Entry>()

        fun register(
            receipt: ProfileActivationReceipt,
            lease: MeasuredProfileSelectionLease,
            catalogGeneration: Long,
        ) {
            val origin =
                receipt.origin as? RuntimeCommandOrigin.MeasuredActivation
                    ?: error("Measured activation origin is required")
            require(origin.reference == lease.reference)
            synchronized(entries) { entries[receipt.commandId] = Entry(lease, catalogGeneration) }
        }

        suspend fun bind(
            receipt: ProfileActivationReceipt,
            mode: com.poyka.ripdpi.data.Mode,
        ): RuntimeActivationReceipt? {
            val entry = synchronized(entries) { entries[receipt.commandId] }
            return if (entry == null || !entry.lease.refreshExternalEnvironment()) {
                null
            } else {
                bindCurrent(receipt, mode, entry)
            }
        }

        private suspend fun bindCurrent(
            receipt: ProfileActivationReceipt,
            mode: com.poyka.ripdpi.data.Mode,
            entry: Entry,
        ): RuntimeActivationReceipt? {
            val requested = requestedCapture.capture(mode, settings.snapshot(), null)
            return recovery.readRecovered {
                if (!entry.lease.payloadMatches()) return@readRecovered null
                authority.intentLinearizer.serialize {
                    if (!matches(receipt.commandId, entry)) {
                        null
                    } else {
                        val bound = authority.bindProfileActivation(receipt, mode)
                        if (bound != null) entry.requested = requested.identity
                        bound
                    }
                }
            }
        }

        internal fun allows(
            attempt: RuntimeConfigurationAttempt,
            requested: RuntimeConfigurationIdentity,
        ): Boolean {
            val original = attempt.originalIntent.receipt
            if (original.origin !is RuntimeCommandOrigin.MeasuredActivation ||
                attempt.originalIntent is com.poyka.ripdpi.data.RuntimeAppliedIntent.Continuation
            ) {
                return true
            }
            val entry = synchronized(entries) { entries[original.commandId] }
            return entry != null && matches(original.commandId, entry) &&
                attempt.catalogGeneration == entry.catalogGeneration && entry.requested?.matches(requested) == true
        }

        internal fun allowsConsumed(
            attempt: RuntimeConfigurationAttempt,
            consumed: CandidateConfigurationProof?,
        ): Boolean {
            val original = attempt.originalIntent.receipt
            if (original.origin !is RuntimeCommandOrigin.MeasuredActivation ||
                attempt.originalIntent is com.poyka.ripdpi.data.RuntimeAppliedIntent.Continuation
            ) {
                return true
            }
            val entry = synchronized(entries) { entries[original.commandId] }
            return entry != null && consumed != null && matches(original.commandId, entry) &&
                entry.lease.configurationProof.matches(consumed)
        }

        private fun matches(
            commandId: String,
            entry: Entry,
        ): Boolean {
            val stored = authority.states.value ?: return false
            return stored.command?.commandId == commandId && stored.profileUtility.catalogReady &&
                stored.profileUtility.catalogGeneration == entry.catalogGeneration &&
                entry.lease.externalEnvironmentMatchesNow()
        }

        fun release(commandId: String) {
            synchronized(entries) { entries.remove(commandId) }
        }

        internal fun consumed(attempt: RuntimeConfigurationAttempt) = release(attempt.originalIntent.receipt.commandId)
    }
