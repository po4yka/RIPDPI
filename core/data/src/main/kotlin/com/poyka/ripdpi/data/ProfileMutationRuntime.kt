package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.persistence.ProfileDatabase
import com.poyka.ripdpi.data.selector.SelectorActiveGroupStore
import kotlinx.coroutines.sync.Mutex

internal class ProfileMutationRuntime(
    val stores: ProfileMutationStores,
    val database: ProfileDatabase,
    val mutationGeneration: ProfileMutationGenerationPublisher,
    val pauseAuthority: PauseIntentAuthority,
    val activeGroup: SelectorActiveGroupStore,
) {
    val warpRevisions = WarpRuntimeMutationRevisions()
    val mutex = Mutex()
    val intentProjection = ProfileMutationIntentProjection()
    val catalog = ProfileUtilityCatalogPublisher(database, pauseAuthority)
    val afterImageWriter = ProfileMutationAfterImageWriter(this)
    val standalone: StandaloneAwgMutationCoordinator = StandaloneAwgMutations(this)
    val subscriptionRefresh: SubscriptionRefreshMutationCoordinator = SubscriptionRefreshMutations(this)

    suspend fun recover() {
        database.initialize()
        pauseAuthority.initializeAfterMigration()
        catalog.publish()
    }
}
