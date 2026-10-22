package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.activities.FakeAppSettingsRepository
import com.poyka.ripdpi.activities.InMemoryRelayProfileStore
import com.poyka.ripdpi.data.PendingProfileMutation
import com.poyka.ripdpi.data.ProfileMutationGenerationPublisher
import com.poyka.ripdpi.data.ProfileMutationJournal
import com.poyka.ripdpi.data.ProfileMutationRecoveryCoordinator
import com.poyka.ripdpi.data.ProfileMutationStores
import com.poyka.ripdpi.data.ProxyGroupCatalog
import com.poyka.ripdpi.data.awg.AwgCredentialStore
import com.poyka.ripdpi.data.awg.AwgProfileDao
import com.poyka.ripdpi.data.selector.SelectorChoiceOrigin
import com.poyka.ripdpi.data.selector.SelectorChoicePersistence
import com.poyka.ripdpi.data.testPauseAuthority
import com.poyka.ripdpi.data.xray.XrayProfileMetadataRecord
import com.poyka.ripdpi.data.xray.XrayProfileMetadataStore
import java.lang.reflect.Proxy

/** Real recovery, journal replay and publication; only persistence ports are kept in memory. */
internal fun subscriptionMutationCoordinator(
    groups: ProxyGroupCatalog,
    profiles: AwgProfileDao,
    credentials: AwgCredentialStore,
): ProfileMutationRecoveryCoordinator =
    ProfileMutationRecoveryCoordinator(
        ProfileMutationStores(
            FakeAppSettingsRepository(),
            InMemoryRelayProfileStore(),
            unusedStore(),
            unusedStore(),
            unusedStore(),
            unusedStore(),
            SubscriptionXrayMetadata(),
            unusedStore(),
            unusedStore(),
            unusedStore(),
            groups,
            SubscriptionSelectorChoice(),
        ),
        profiles,
        credentials,
        SubscriptionJournal(),
        ProfileMutationGenerationPublisher(),
        testPauseAuthority(),
    )

private class SubscriptionJournal : ProfileMutationJournal {
    private var value: PendingProfileMutation? = null

    override suspend fun pending(): PendingProfileMutation? = value

    override suspend fun prepare(mutation: PendingProfileMutation) {
        check(value == null)
        value = mutation
    }

    override suspend fun replace(
        expectedMutationId: String,
        mutation: PendingProfileMutation,
    ) {
        check(value?.mutationId == expectedMutationId)
        value = mutation
    }

    override suspend fun complete(mutationId: String) {
        check(value?.mutationId == mutationId)
        value = null
    }

    override suspend fun clearForReset() {
        value = null
    }
}

private class SubscriptionXrayMetadata : XrayProfileMetadataStore {
    private val records = mutableMapOf<String, XrayProfileMetadataRecord>()

    override suspend fun load(profileId: String): XrayProfileMetadataRecord? = records[profileId]

    override suspend fun list(): List<XrayProfileMetadataRecord> = records.values.toList()

    override suspend fun save(record: XrayProfileMetadataRecord) {
        records[record.profileId] = record
    }

    override suspend fun clear(profileId: String) {
        records.remove(profileId)
    }
}

private class SubscriptionSelectorChoice : SelectorChoicePersistence {
    private var active: String? = null

    override fun commitMember(
        groupId: String,
        memberId: String,
        origin: SelectorChoiceOrigin,
    ) {
        active = groupId
    }

    override fun clearStandalone() {
        active = null
    }

    override fun snapshotActiveGroupId(): String? = active

    override fun restoreActiveGroup(groupId: String?) {
        active = groupId
    }

    override fun prune(groupIds: Set<String>) {
        if (active !in groupIds) active = null
    }
}

/** Unrelated WARP, Xray-secret and runtime ports must not participate in a subscription commit. */
private inline fun <reified T> unusedStore(): T =
    T::class.java.cast(
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            error("Unexpected subscription store operation: ${method.name}")
        },
    )
