package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.PauseIntent
import com.poyka.ripdpi.data.PauseIntentAuthority
import com.poyka.ripdpi.data.PausePhase
import com.poyka.ripdpi.data.RuntimeConfigurationAttempt
import javax.inject.Inject
import javax.inject.Singleton

/** Captures resume provenance; later revisions continue only an acknowledged lease of the same session. */
@Singleton
internal class PauseAppliedReceiptConsumer
    @Inject
    constructor(
        private val authority: PauseIntentAuthority,
    ) {
        private enum class Phase { PendingResume, ConsumedResume }

        private data class Lease(
            val intent: PauseIntent,
            var phase: Phase = Phase.PendingResume,
        )

        private val leases = mutableMapOf<RuntimeConfigurationAttempt, Lease>()

        fun bind(
            attempt: RuntimeConfigurationAttempt,
            intent: PauseIntent,
        ) {
            check(leases[attempt] == null) { "Resume attempt was already bound" }
            val previous = leases.entries.singleOrNull { it.key.mode == attempt.mode }
            val continuation =
                previous?.let { (priorAttempt, lease) ->
                    priorAttempt.runtimeId == attempt.runtimeId && priorAttempt.revision < attempt.revision &&
                        lease.intent == intent && lease.phase == Phase.ConsumedResume
                } == true
            check(
                if (continuation) {
                    authority.allowsRecovery(intent.reference, attempt.mode)
                } else {
                    authority.isCurrent(intent) && authority.snapshot()?.phase == PausePhase.Resuming
                },
            ) {
                "Resume intent superseded before configuration begin"
            }
            leases.keys.removeAll { it.mode == attempt.mode }
            leases[attempt] = Lease(intent, if (continuation) Phase.ConsumedResume else Phase.PendingResume)
        }

        fun acknowledge(attempt: RuntimeConfigurationAttempt): Boolean {
            val lease = leases[attempt] ?: return true
            return if (lease.phase ==
                Phase.ConsumedResume
            ) {
                authority.allowsRecovery(lease.intent.reference, attempt.mode)
            } else if (authority.acknowledgeResume(lease.intent, attempt.mode)) {
                lease.phase = Phase.ConsumedResume
                true
            } else {
                false
            }
        }

        fun stopped(runtimeId: String) {
            leases.entries.removeAll { it.key.runtimeId == runtimeId && it.value.phase == Phase.PendingResume }
        }
    }
