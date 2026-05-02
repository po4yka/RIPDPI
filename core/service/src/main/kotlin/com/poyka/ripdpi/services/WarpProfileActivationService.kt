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
    ) : WarpProfileActivationService {
        override suspend fun activateProfile(
            profile: WarpProfile,
            scannerMode: String,
        ) {
            profileStore.setActiveProfileId(profile.id)
            appSettingsRepository.update {
                setWarpProfileId(profile.id)
                setWarpAccountKind(normalizeWarpAccountKind(profile.accountKind))
                setWarpZeroTrustOrg(profile.zeroTrustOrg)
                setWarpSetupState(normalizeWarpSetupState(profile.setupState))
                setWarpLastScannerMode(normalizeWarpScannerMode(scannerMode))
            }
        }

        override suspend fun markProfileNeedsAttention(profile: WarpProfile) {
            val updatedProfile = profile.copy(setupState = WarpSetupStateNeedsAttention)
            profileStore.save(updatedProfile)
            if (profileStore.activeProfileId() == profile.id ||
                appSettingsRepository.snapshot().warpProfileId == profile.id
            ) {
                activateProfile(profile = updatedProfile, scannerMode = updatedProfile.lastScannerModeOrAutomatic())
            }
        }

        override suspend fun clearActiveProfile(profileId: String) {
            if (profileStore.activeProfileId() == profileId) {
                profileStore.setActiveProfileId(null)
                appSettingsRepository.update {
                    setWarpProfileId(DefaultWarpProfileId)
                    setWarpAccountKind(WarpAccountKindConsumerFree)
                    setWarpZeroTrustOrg("")
                    setWarpSetupState(WarpSetupStateNotConfigured)
                    setWarpLastScannerMode(WarpScannerModeAutomatic)
                }
            }
        }
    }
