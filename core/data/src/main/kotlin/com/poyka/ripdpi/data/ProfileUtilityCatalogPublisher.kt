package com.poyka.ripdpi.data

/** Called only inside the profile recovery mutex; authority never reads a store or acquires this mutex. */
internal class ProfileUtilityCatalogPublisher(
    private val stores: ProfileMutationStores,
    private val authority: PauseIntentAuthority,
) {
    fun invalidate() = authority.profileUtility.invalidateCatalog()

    suspend fun publish() {
        val native = stores.relayProfiles.list().map { ProfileUtilityReference.NativeRelay(it.id) }
        val xray = stores.xrayMetadata.list().map { ProfileUtilityReference.Xray(it.profileId) }
        val groups = stores.groupCatalog.snapshot()
        stores.selectorChoice.prune(groups.map { it.id }.toSet())
        val members =
            groups.flatMap { group ->
                group.members.map { ProfileUtilityReference.SelectorMember(group.id, it.id) }
            }
        authority.profileUtility.replaceCatalog((native + xray + members).toSet())
    }
}
