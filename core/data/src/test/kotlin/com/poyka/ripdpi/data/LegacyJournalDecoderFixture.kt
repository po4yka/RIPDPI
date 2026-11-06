package com.poyka.ripdpi.data

/** Only tests pure legacy decoding; importer tests cover actual preference and Room persistence. */
internal class LegacyJournalDecoderFixture {
    private var source: PendingProfileMutation? = null
    val journal = ProfileMutationJournal { source }
    val reader = ProfileMutationJournalRecoveryReader(journal, ProxyGroupCatalog(EmptyLegacyGroupBlob()))

    suspend fun seed(payload: String): PendingProfileMutation {
        val pending =
            PendingProfileMutation(
                mutationId = "legacy-decoder",
                family = ProfileMutationFamily.Subscription,
                payload = payload,
                origin = ProfileMutationOrigin.SubscriptionRefresh,
                expectedPauseAuthority = PauseAuthorityRef(0),
            )
        check(source == null)
        source = pending
        return pending
    }
}

private class EmptyLegacyGroupBlob : ProxyGroupBlobStore {
    override suspend fun read(): String? = null

    override suspend fun write(json: String) = error("Unexpected legacy group write")

    override suspend fun clear() = error("Unexpected legacy group clear")

    override suspend fun update(transform: (String?) -> String?): String? = error("Unexpected legacy group update")

    override fun observe() = kotlinx.coroutines.flow.flowOf(null)
}
