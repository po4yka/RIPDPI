package com.poyka.ripdpi.data

fun testPauseAuthority(): PauseIntentAuthority =
    PauseIntentAuthority(
        object : PauseAuthorityPersistence {
            private var state: PauseAuthorityState? = null

            override fun read() = state

            override fun commit(state: PauseAuthorityState) {
                this.state = state
            }
        },
        object : PauseClock {
            override fun read() = PauseClockReading(1_800_000_000_000L, 10_000L, 7)
        },
        com.poyka.ripdpi.data
            .RuntimeIntentLinearizer(),
    ).apply { initializeAfterMigration() }

fun testMutationOutcome(origin: ProfileMutationOrigin): ProfileMutationOutcome {
    val authority = testPauseAuthority()
    return authority.invalidateForMutation(
        origin,
        java.util.UUID
            .randomUUID()
            .toString(),
        authority.reference(),
    )
}

fun testMutationPreparationSource(): PauseMutationPreparationSource =
    object : PauseMutationPreparationSource {
        private val authority = testPauseAuthority()

        override suspend fun captureMutation(origin: ProfileMutationOrigin) =
            ProfileMutationPreparation(origin, authority.reference())

        override suspend fun <T> mutateCatalog(
            preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
            block: suspend () -> T,
        ): T {
            check(commitMutationIntent(preparation) != com.poyka.ripdpi.data.ProfileMutationOutcome.Superseded)
            return block()
        }

        override suspend fun <T> mutateReservedCatalog(
            receipt: com.poyka.ripdpi.data.DurableCommandReceipt,
            block: suspend () -> T,
        ): T = block()

        override suspend fun activateSelector(
            preparation: ProfileMutationPreparation,
            groupId: String,
            memberId: String,
            choice: com.poyka.ripdpi.data.selector.SelectorChoicePersistence,
        ): ProfileMutationOutcome {
            val outcome = commitMutationIntent(preparation)
            val receipt = (outcome as? ProfileMutationOutcome.Reserved)?.receipt as? ProfileActivationReceipt
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
    }

fun testProfileRecovery(): ProfileMutationRecoveryAccess =
    object : ProfileMutationRecoveryAccess {
        private val authority = testPauseAuthority()

        override suspend fun recover() = Unit

        override suspend fun <T> readRecovered(block: suspend () -> T): T = block()

        override suspend fun captureMutation(origin: ProfileMutationOrigin) =
            ProfileMutationPreparation(origin, authority.reference())

        override suspend fun <T> mutateCatalog(
            preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
            block: suspend () -> T,
        ): T {
            check(commitMutationIntent(preparation) != com.poyka.ripdpi.data.ProfileMutationOutcome.Superseded)
            return block()
        }

        override suspend fun <T> mutateReservedCatalog(
            receipt: com.poyka.ripdpi.data.DurableCommandReceipt,
            block: suspend () -> T,
        ): T = block()

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

        override suspend fun runReset(block: suspend (DurableCommandReceipt) -> Unit): DurableCommandReceipt {
            val receipt = authority.reserveStop()
            block(receipt)
            return receipt
        }
    }
