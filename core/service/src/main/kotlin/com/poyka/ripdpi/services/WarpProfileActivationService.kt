package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DefaultWarpProfileId
import com.poyka.ripdpi.data.WarpAccountKindConsumerFree
import com.poyka.ripdpi.data.WarpProfile
import com.poyka.ripdpi.data.WarpProfileStore
import com.poyka.ripdpi.data.WarpScannerModeAutomatic
import com.poyka.ripdpi.data.WarpSetupStateNeedsAttention
import com.poyka.ripdpi.data.WarpSetupStateNotConfigured
import com.poyka.ripdpi.data.normalizeWarpAccountKind
import com.poyka.ripdpi.data.normalizeWarpScannerMode
import com.poyka.ripdpi.data.normalizeWarpSetupState
import com.poyka.ripdpi.data.rollbackStoreMutation
import com.poyka.ripdpi.proto.AppSettings
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
        private val appSettingsRepository: AppSettingsRepository,
        private val profileStore: WarpProfileStore,
        private val mutationLock: WarpStoreMutationLock,
    ) : WarpProfileActivationService {
        override suspend fun activateProfile(
            profile: WarpProfile,
            scannerMode: String,
        ) {
            updateActiveProfile(profile.id) {
                setWarpProfileId(profile.id)
                setWarpAccountKind(normalizeWarpAccountKind(profile.accountKind))
                setWarpZeroTrustOrg(profile.zeroTrustOrg)
                setWarpSetupState(normalizeWarpSetupState(profile.setupState))
                setWarpLastScannerMode(normalizeWarpScannerMode(scannerMode))
            }
        }

        override suspend fun markProfileNeedsAttention(profile: WarpProfile) =
            mutationLock.mutex.withLock {
                val previousProfile = profileStore.load(profile.id) ?: return@withLock
                val updatedProfile = previousProfile.copy(setupState = WarpSetupStateNeedsAttention)
                runCatching {
                    profileStore.save(updatedProfile)
                    if (profileStore.activeProfileId() == profile.id ||
                        appSettingsRepository.snapshot().warpProfileId == profile.id
                    ) {
                        activateProfile(
                            profile = updatedProfile,
                            scannerMode = updatedProfile.lastScannerModeOrAutomatic(),
                        )
                    }
                }.exceptionOrNull()
                    ?.rollbackStoreMutation({ profileStore.save(previousProfile) })
            }

        override suspend fun clearActiveProfile(profileId: String) {
            if (profileStore.activeProfileId() == profileId) {
                updateActiveProfile(null) {
                    setWarpProfileId(DefaultWarpProfileId)
                    setWarpAccountKind(WarpAccountKindConsumerFree)
                    setWarpZeroTrustOrg("")
                    setWarpSetupState(WarpSetupStateNotConfigured)
                    setWarpLastScannerMode(WarpScannerModeAutomatic)
                }
            }
        }

        private suspend fun updateActiveProfile(
            profileId: String?,
            updateSettings: AppSettings.Builder.() -> Unit,
        ) {
            val previousActiveProfileId = profileStore.activeProfileId()
            val previousSettings = appSettingsRepository.snapshot()
            runCatching {
                profileStore.setActiveProfileId(profileId)
                appSettingsRepository.update(updateSettings)
            }.exceptionOrNull()
                ?.rollbackStoreMutation(
                    { profileStore.setActiveProfileId(previousActiveProfileId) },
                    { appSettingsRepository.replace(previousSettings) },
                )
        }
    }
