package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.PauseIntentAuthority
import com.poyka.ripdpi.data.ProfileUtilityReference
import com.poyka.ripdpi.data.RuntimeAppliedIntent
import com.poyka.ripdpi.data.RuntimeAppliedUseIdentity
import com.poyka.ripdpi.data.RuntimeAppliedUseReceipt
import com.poyka.ripdpi.data.RuntimeConfigurationApplyReason
import com.poyka.ripdpi.data.RuntimeConfigurationAttempt
import com.poyka.ripdpi.data.RuntimeConfigurationSelection
import javax.inject.Inject
import javax.inject.Singleton

/** Every positive native ACK commits its original authority and profile utility in the same durable state. */
@Singleton
internal class RuntimeAppliedReceiptConsumer
    @Inject
    constructor(
        private val authority: PauseIntentAuthority,
        private val measured: MeasuredActivationRegistry,
    ) {
        private data class Binding(
            val original: RuntimeAppliedIntent,
            var acknowledged: Boolean,
            val requested: RuntimeConfigurationIdentity,
            val durableDuplicate: Boolean,
        ) {
            var failed = false
        }

        private val bindings = mutableMapOf<RuntimeConfigurationAttempt, Binding>()

        fun bind(
            attempt: RuntimeConfigurationAttempt,
            requested: RuntimeConfigurationIdentity,
        ) {
            check(bindings[attempt] == null) { "Applied attempt was already bound" }
            val identity = RuntimeAppliedUseIdentity(attempt.runtimeId, attempt.revision, attempt.mode.preferenceValue)
            val original = attempt.originalIntent
            val alreadyApplied = authority.acknowledgedAttempt(original, identity) != null
            check(
                alreadyApplied || measured.allows(attempt, requested),
            ) { "Measurement scope changed before native claim" }
            val claimed =
                alreadyApplied ||
                    when (original) {
                        is RuntimeAppliedIntent.Continuation -> {
                            authority.claimContinuation(
                                original.receipt,
                                original.lastPositiveIdentity,
                                identity,
                            )
                        }

                        else -> {
                            authority.claimActivation(original.receipt, identity)
                        }
                    }
            check(claimed) { "Runtime command claim rejected" }
            bindings.keys.filter { it.mode == attempt.mode }.forEach {
                measured.release(it.originalIntent.receipt.commandId)
                bindings.remove(it)
            }
            bindings[attempt] = Binding(original, false, requested, alreadyApplied)
        }

        fun acknowledge(
            attempt: RuntimeConfigurationAttempt,
            configuration: AppliedRuntimeConfiguration,
            consumed: CandidateConfigurationProof?,
        ): Boolean {
            val binding = bindings[attempt] ?: return false
            return if (binding.failed || measurementRejected(attempt, binding, consumed)) {
                false
            } else {
                acknowledgeBound(attempt, configuration, binding)
            }
        }

        private fun measurementRejected(
            attempt: RuntimeConfigurationAttempt,
            binding: Binding,
            consumed: CandidateConfigurationProof?,
        ): Boolean =
            !binding.acknowledged && !binding.durableDuplicate && !measurementMatches(attempt, binding, consumed)

        private fun measurementMatches(
            attempt: RuntimeConfigurationAttempt,
            binding: Binding,
            consumed: CandidateConfigurationProof?,
        ): Boolean = measured.allows(attempt, binding.requested) && measured.allowsConsumed(attempt, consumed)

        private fun acknowledgeBound(
            attempt: RuntimeConfigurationAttempt,
            configuration: AppliedRuntimeConfiguration,
            binding: Binding,
        ): Boolean {
            val original = binding.original
            val receipt =
                RuntimeAppliedUseReceipt(
                    RuntimeAppliedUseIdentity(attempt.runtimeId, attempt.revision, attempt.mode.preferenceValue),
                    configuration.effectiveSelection.profileUtilityReferences(),
                    configuration.appliedAt,
                    attempt.catalogGeneration,
                    attempt.originalIntent !is RuntimeAppliedIntent.Continuation &&
                        attempt.reason.recordsSuccessfulUse(),
                    configuration.payloadFingerprint(),
                )
            val accepted = authority.acknowledgeApplied(original, receipt)
            if (accepted) {
                binding.acknowledged = true
                measured.consumed(attempt)
            }
            return accepted
        }

        fun publishIfCurrent(
            attempt: RuntimeConfigurationAttempt,
            publish: () -> Unit,
        ): Boolean {
            val binding = bindings[attempt] ?: return false
            return if (!binding.acknowledged || binding.failed) {
                false
            } else {
                publishAcknowledged(attempt, binding, publish)
            }
        }

        private fun publishAcknowledged(
            attempt: RuntimeConfigurationAttempt,
            binding: Binding,
            publish: () -> Unit,
        ): Boolean {
            val permit =
                authority.publicationPermit(
                    binding.original.receipt,
                    RuntimeAppliedUseIdentity(attempt.runtimeId, attempt.revision, attempt.mode.preferenceValue),
                )
                    ?: return false
            return authority.intentLinearizer.publishIf({ authority.allowsPublication(permit) }, publish)
        }

        fun failed(attempt: RuntimeConfigurationAttempt) {
            val binding = bindings[attempt] ?: return
            binding.failed = true
            try {
                authority.terminateActivation(
                    binding.original.receipt,
                    RuntimeAppliedUseIdentity(attempt.runtimeId, attempt.revision, attempt.mode.preferenceValue),
                )
            } finally {
                measured.release(binding.original.receipt.commandId)
            }
        }

        fun stopped(runtimeId: String) {
            bindings.keys.filter { it.runtimeId == runtimeId }.forEach {
                measured.release(it.originalIntent.receipt.commandId)
            }
            bindings.keys.removeAll { it.runtimeId == runtimeId }
        }
    }

private fun RuntimeConfigurationApplyReason.recordsSuccessfulUse(): Boolean =
    this == RuntimeConfigurationApplyReason.InitialStart || this == RuntimeConfigurationApplyReason.UserReconnect ||
        this == RuntimeConfigurationApplyReason.SelectorReload

private fun RuntimeConfigurationSelection.profileUtilityReferences(): List<ProfileUtilityReference> {
    val group = selectorGroupId
    val member = selectorMemberId
    val profile = profileId
    return when {
        !group.isNullOrBlank() && !member.isNullOrBlank() -> {
            listOf(
                ProfileUtilityReference.SelectorMember(group, member),
            )
        }

        !profile.isNullOrBlank() &&
            provider.equals(
                "xray",
                ignoreCase = true,
            )
        -> {
            listOf(ProfileUtilityReference.Xray(profile))
        }

        !profile.isNullOrBlank() && relayKind != null && relayKind !in setOf("warp", "amneziawg", "off") -> {
            listOf(ProfileUtilityReference.NativeRelay(profile))
        }

        else -> {
            emptyList()
        }
    }
}

private fun AppliedRuntimeConfiguration.payloadFingerprint(): String {
    val payload =
        com.poyka.ripdpi.data.RuntimeAppliedPayload(
            requestedSelection,
            effectiveSelection,
            dns,
            strategy,
            reason.name,
        )
    val encoded =
        com.poyka.ripdpi.serialization.RipDpiContractJson
            .encodeToString(payload)
    return java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(encoded.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
