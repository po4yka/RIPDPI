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
                val xray = rows.xrayMetadata.list().map { ProfileUtilityReference.Xray(it.profileId) }
                val groups = rows.groups.list()
                val members =
                    groups.flatMap { group ->
                        group.members.map { ProfileUtilityReference.SelectorMember(group.id, it.id) }
                    }
                (native + xray + members).toSet()
            }
        authority.profileUtility.replaceCatalog(references)
    }
}
