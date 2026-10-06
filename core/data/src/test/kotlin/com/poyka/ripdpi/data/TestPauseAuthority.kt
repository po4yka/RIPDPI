package com.poyka.ripdpi.data

fun testPauseAuthority(
    persistence: PauseAuthorityPersistence = TestPauseAuthorityPersistence(),
    initialize: Boolean = true,
): PauseIntentAuthority =
    PauseIntentAuthority(
        persistence,
        object : PauseClock {
            override fun read() = PauseClockReading(1_800_000_000_000L, 10_000L, 7)
        },
        RuntimeIntentLinearizer(),
    ).apply { if (initialize) initializeAfterMigration() }

internal class TestPauseAuthorityPersistence(
    initial: PauseAuthorityState? = null,
) : PauseAuthorityPersistence {
    private var state = initial
    var commitCount = 0
        private set

    override fun read() = state

    override fun commit(state: PauseAuthorityState) {
        this.state = state
        commitCount += 1
    }
}

internal class TestSelectorChoicePersistence : com.poyka.ripdpi.data.selector.SelectorChoicePersistence {
    var activeGroupId: String? = null
        private set
    val members = mutableMapOf<String, String>()
    val manualReceipts = mutableMapOf<String, ProfileActivationReceipt>()
    var writeCount = 0
        private set

    override fun commitMember(
        groupId: String,
        memberId: String,
        origin: com.poyka.ripdpi.data.selector.SelectorChoiceOrigin,
    ) {
        members[groupId] = memberId
        activeGroupId = groupId
        when (origin) {
            is com.poyka.ripdpi.data.selector.SelectorChoiceOrigin.Manual -> manualReceipts[groupId] = origin.receipt
            com.poyka.ripdpi.data.selector.SelectorChoiceOrigin.Reconstruction -> manualReceipts.remove(groupId)
        }
        writeCount += 1
    }

    override fun snapshotActiveGroupId() = activeGroupId

    override fun restoreActiveGroup(groupId: String?) {
        manualReceipts.clear()
        activeGroupId = groupId
    }

    override fun clearStandalone() {
        activeGroupId = null
        writeCount += 1
    }

    override fun prune(groupIds: Set<String>) {
        if (activeGroupId != null && activeGroupId !in groupIds) clearStandalone()
    }
}

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

fun testMutationPreparationSource(): TestMutationPreparationSource = TestMutationPreparationSource(testPauseAuthority())

class TestMutationPreparationSource(
    val authority: PauseIntentAuthority,
) : PauseMutationPreparationSource {
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
