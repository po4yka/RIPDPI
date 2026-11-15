package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.persistence.ProfileDatabase

internal class ProfileUtilityCatalogPublisher(
    private val database: ProfileDatabase,
    private val authority: PauseIntentAuthority,
) {
    fun invalidate() = authority.profileUtility.invalidateCatalog()

    suspend fun publish() {
        val references =
            database.read { rows ->
                val native = rows.relayProfiles.list().map { ProfileUtilityReference.NativeRelay(it.id) }
                val nativeIds = native.map { it.profileId }.toSet()
                val xray = rows.xrayMetadata.list().map { ProfileUtilityReference.Xray(it.profileId) }
                val groups = rows.groups.list()
                val members =
                    groups.flatMap { group ->
                        group.members
                            .filterNot { group.type == ProxyGroupType.BASIC && !group.isSelector && it.id in nativeIds }
                            .map { ProfileUtilityReference.SelectorMember(group.id, it.id) }
                    }
                (native + xray + members).toSet()
            }
        authority.profileUtility.replaceCatalog(references)
    }
}
