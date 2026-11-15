package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.ExpectedRelayProfileState
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.ProfileMutationOutcome
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.mapRelayProfile
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists a parsed or edited [ProxyProfile] as the active native relay.
 *
 * Writes the non-secret [RelayProfileRecord], the secure [RelayCredentialRecord],
 * and the AppSettings relay fields the runtime resolver
 * (`UpstreamRelayRuntimeConfigResolver`) reads to build the native wire config.
 * Dedicated profile editors and the import confirmation flow share the same mapping.
 *
 * [activate] returns `true` when [profile] is a relay-activatable kind and was
 * applied, `false` (a no-op) for kinds that are not relay outbounds. The optional
 * `profileId` defaults to [DefaultRelayProfileId] (the single active-relay slot);
 * callers that must keep several relay profiles side by side in the store — e.g.
 * the simple-flavor seeder building a multi-transport failover set — pass a
 * distinct stable id per profile so they do not overwrite one another.
 */
@Singleton
class RelayProfileActivator
    @Inject
    constructor(
        private val profileMutations: ProfileMutationCoordinator,
    ) {
        suspend fun captureMutation(origin: com.poyka.ripdpi.data.ProfileMutationOrigin) =
            profileMutations.captureMutation(origin)

        /** Saves a new share-link import without changing runtime selection. */
        suspend fun saveImport(profile: ProxyProfile): Boolean {
            val group = profile.importedGroup(UUID.randomUUID().toString())
            val member = group.members.single()
            val mapped = mapRelayProfile(member) ?: return false
            val preparation = profileMutations.captureMutation(ProfileMutationOrigin.ImportOnly)
            val outcome =
                profileMutations.upsertRelay(
                    preparation,
                    profile = mapped.profile.copy(operatorName = profile.displayName),
                    credentials = mapped.credentials.copy(updatedAtEpochMillis = System.currentTimeMillis()),
                    enabled = false,
                    select = false,
                    expectedState = ExpectedRelayProfileState(profile = null, credentials = null),
                    importedGroup = group,
                )
            return outcome !is ProfileMutationOutcome.Superseded
        }

        suspend fun activate(
            preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
            profile: ProxyProfile,
            profileId: String = DefaultRelayProfileId,
            tlsFingerprintOverride: String? = null,
            modeAfterImage: String? = null,
            xraySelectionAfterImage: XrayProviderSelectionRecord? = null,
        ): Boolean {
            val mapped = mapRelayProfile(profile, profileId, tlsFingerprintOverride) ?: return false
            val outcome =
                profileMutations.upsertRelay(
                    preparation,
                    profile = mapped.profile,
                    credentials = mapped.credentials.copy(updatedAtEpochMillis = System.currentTimeMillis()),
                    enabled = true,
                    select = true,
                    modeAfterImage = modeAfterImage,
                    xraySelectionAfterImage = xraySelectionAfterImage ?: XrayProviderSelectionRecord(),
                )
            return outcome !is ProfileMutationOutcome.Superseded
        }
    }
