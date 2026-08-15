package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.WarpCredentialStore
import com.poyka.ripdpi.data.WarpEndpointStore
import com.poyka.ripdpi.data.WarpProfile
import com.poyka.ripdpi.data.WarpProfileStore
import com.poyka.ripdpi.data.WarpSetupStateNeedsAttention
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

interface WarpProfileActivationService {
    suspend fun activateProfile(
        profile: WarpProfile,
        scannerMode: String,
    )

    suspend fun markProfileNeedsAttention(profile: WarpProfile)

    suspend fun clearActiveProfile(profileId: String)
}

@Singleton
class DefaultWarpProfileActivationService
    @Inject
    constructor(
        private val profileStore: WarpProfileStore,
        private val credentialStore: WarpCredentialStore,
        private val endpointStore: WarpEndpointStore,
        private val profileMutations: ProfileMutationCoordinator,
        private val mutationLock: WarpStoreMutationLock,
    ) : WarpProfileActivationService {
        override suspend fun activateProfile(
            profile: WarpProfile,
            scannerMode: String,
        ) {
            persistProfile(profile, scannerMode, activate = true)
        }

        override suspend fun markProfileNeedsAttention(profile: WarpProfile) =
            mutationLock.mutex.withLock {
                profileMutations.recover()
                val previousProfile = profileStore.load(profile.id) ?: return@withLock
                val updatedProfile = previousProfile.copy(setupState = WarpSetupStateNeedsAttention)
                persistProfile(
                    profile = updatedProfile,
                    scannerMode = updatedProfile.lastScannerModeOrAutomatic(),
                    activate = profileStore.activeProfileId() == profile.id,
                )
            }

        override suspend fun clearActiveProfile(profileId: String) {
            profileMutations.deactivateWarp(profileId)
        }

        private suspend fun persistProfile(
            profile: WarpProfile,
            scannerMode: String,
            activate: Boolean,
        ) {
            profileMutations.recover()
            val credentials = credentialStore.load(profile.id) ?: error("No WARP credentials found for ${profile.id}")
            profileMutations.upsertWarp(
                profile = profile,
                credentials = credentials,
                endpoints = endpointStore.loadAll(profile.id),
                activate = activate,
                scannerMode = scannerMode,
            )
        }
    }
