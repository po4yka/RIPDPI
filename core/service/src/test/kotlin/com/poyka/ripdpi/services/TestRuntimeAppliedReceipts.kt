package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DurableCommandReceipt
import com.poyka.ripdpi.data.PauseIntentAuthority
import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.ProfileMutationPreparation
import com.poyka.ripdpi.data.ProfileMutationRecoveryAccess

/** Same authority for every mandatory constructor dependency; all receipts come from checked real operations. */
internal fun testRuntimeAppliedReceiptConsumer(authority: PauseIntentAuthority): RuntimeAppliedReceiptConsumer =
    RuntimeAppliedReceiptConsumer(
        authority,
        MeasuredActivationRegistry(
            authority,
            object : ProfileMutationRecoveryAccess {
                override suspend fun recover() = authority.initializeAfterMigration()

                override suspend fun <T> readRecovered(block: suspend () -> T): T {
                    recover()
                    return block()
                }

                override suspend fun captureMutation(origin: ProfileMutationOrigin): ProfileMutationPreparation {
                    recover()
                    return ProfileMutationPreparation(origin, authority.reference())
                }

                override suspend fun activateSelector(
                    preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
                    groupId: String,
                    memberId: String,
                    choice: com.poyka.ripdpi.data.selector.SelectorChoicePersistence,
                ): com.poyka.ripdpi.data.ProfileMutationOutcome {
                    val outcome = commitMutationIntent(preparation)
                    val receipt =
                        (outcome as? com.poyka.ripdpi.data.ProfileMutationOutcome.Reserved)?.receipt
                            as? com.poyka.ripdpi.data.ProfileActivationReceipt
                    if (receipt != null) {
                        choice.commitMember(
                            groupId,
                            memberId,
                            com.poyka.ripdpi.data.selector.SelectorChoiceOrigin
                                .Manual(receipt),
                        )
                    }
                    return outcome
                }

                override suspend fun commitMutationIntent(preparation: ProfileMutationPreparation) =
                    authority.invalidateForMutation(
                        preparation.origin,
                        java.util.UUID
                            .randomUUID()
                            .toString(),
                        preparation.expectedPauseAuthority,
                    )

                override suspend fun <T> mutateCatalog(
                    preparation: ProfileMutationPreparation,
                    block: suspend () -> T,
                ): T {
                    commitMutationIntent(preparation)
                    return block()
                }

                override suspend fun <T> mutateReservedCatalog(
                    receipt: DurableCommandReceipt,
                    block: suspend () -> T,
                ): T {
                    check(authority.isCurrent(receipt))
                    return block()
                }

                override suspend fun runReset(block: suspend (DurableCommandReceipt) -> Unit): DurableCommandReceipt {
                    val receipt = authority.reserveResetStop()
                    block(receipt)
                    return receipt
                }
            },
            RequestedRuntimeConfigurationSource { mode, settings, _ ->
                sampleResolution(mode, settings = settings).requestedConfiguration
            },
            TestAppSettingsRepository(),
        ),
    )
