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

/** Ensures a killed Relay write is replayed before runtime reads either store. */
@Singleton
internal class RelayRuntimeProfileReader private constructor(
    private val profileStore: RelayProfileStore,
    private val credentialStore: RelayCredentialStore,
    private val recoverPending: suspend () -> Unit,
) {
    @Inject
    constructor(
        profileStore: RelayProfileStore,
        credentialStore: RelayCredentialStore,
        profileMutations: ProfileMutationCoordinator,
    ) : this(profileStore, credentialStore, profileMutations::recover)

    internal constructor(
        profileStore: RelayProfileStore,
        credentialStore: RelayCredentialStore,
    ) : this(profileStore, credentialStore, {})

    suspend fun read(profileId: String): RelayRuntimeProfile {
        recoverPending()
        return RelayRuntimeProfile(
            profile = profileStore.load(profileId),
            credentials = credentialStore.load(profileId),
        )
    }
}
