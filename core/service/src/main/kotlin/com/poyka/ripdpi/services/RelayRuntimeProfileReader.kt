package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import javax.inject.Inject
import javax.inject.Singleton

internal data class RelayRuntimeProfile(
    val profile: RelayProfileRecord?,
    val credentials: RelayCredentialRecord?,
)

/** Keeps profile and credential reads in one recovered mutation scope. */
@Singleton
internal class RelayRuntimeProfileReader private constructor(
    private val profileStore: RelayProfileStore,
    private val credentialStore: RelayCredentialStore,
    private val readRecovered: suspend (suspend () -> RelayRuntimeProfile) -> RelayRuntimeProfile,
) {
    @Inject
    constructor(
        profileStore: RelayProfileStore,
        credentialStore: RelayCredentialStore,
        profileMutations: ProfileMutationCoordinator,
    ) : this(profileStore, credentialStore, { read -> profileMutations.readRecovered(read) })

    internal constructor(
        profileStore: RelayProfileStore,
        credentialStore: RelayCredentialStore,
    ) : this(profileStore, credentialStore, { read -> read() })

    suspend fun read(profileId: String): RelayRuntimeProfile =
        readRecovered {
            RelayRuntimeProfile(
                profile = profileStore.load(profileId),
                credentials = credentialStore.load(profileId),
            )
        }
}
