package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.ExpectedRelayProfileState
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.RelayCredentialRepository
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.applyRelayAfterImage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

class ConfigRelayArtifactRepository
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val relayProfileStore: RelayProfileStore,
        private val relayCredentialStore: RelayCredentialRepository,
        private val profileMutations: ProfileMutationCoordinator,
    ) {
        private val mutationMutex = Mutex()

        suspend fun prepareForPersistence(draft: ConfigDraft): ConfigDraft {
            requireWritableProfileId(draft)
            return prepareRelayDraftForPersistence(
                draft = draft,
                relayProfileStore = relayProfileStore,
                relayCredentialStore = relayCredentialStore,
                profileMutations = profileMutations,
            )
        }

        suspend fun hydrate(draft: ConfigDraft): ConfigDraft =
            readRecovered {
                val profileId = draft.relayProfileId.ifBlank { DefaultRelayProfileId }
                draft.withRelayArtifacts(relayProfileStore.load(profileId), relayCredentialStore.load(profileId))
            }

        suspend fun hydrateProfile(profileId: String): ConfigDraft =
            readRecovered {
                val profile = requireNotNull(relayProfileStore.load(profileId)) { "Relay profile no longer exists" }
                val draft =
                    appSettingsRepository
                        .snapshot()
                        .toBuilder()
                        .apply { applyRelayAfterImage(profile, enabled = true) }
                        .build()
                        .toConfigDraft()
                draft.withRelayArtifacts(profile, relayCredentialStore.load(profileId))
            }

        suspend fun selectProfile(profileId: String): ConfigDraft {
            val preparation =
                profileMutations.captureMutation(
                    com.poyka.ripdpi.data.ProfileMutationOrigin.ExplicitActivation,
                )
            val captured =
                readRecovered {
                    val profile = requireNotNull(relayProfileStore.load(profileId)) { "Relay profile no longer exists" }
                    val credentials = relayCredentialStore.load(profileId)
                    profile to credentials
                }
            profileMutations.upsertRelay(
                preparation,
                captured.first,
                captured.second ?: com.poyka.ripdpi.data
                    .RelayCredentialRecord(profileId),
                enabled = true,
                select = true,
                modeAfterImage = Mode.VPN.preferenceValue,
                expectedState = ExpectedRelayProfileState(captured.first, captured.second),
            )
            return appSettingsRepository.snapshot().toConfigDraft()
        }

        suspend fun listProfiles(): List<RelayProfileRecord> = readRecovered { relayProfileStore.list() }

        suspend fun persist(draft: ConfigDraft): ConfigDraft =
            mutationMutex.withLock {
                requireWritableProfileId(draft)
                val profileId = draft.relayProfileId.ifBlank { DefaultRelayProfileId }
                val profile = draft.toRelayProfileRecord(profileId)
                val credentials = draft.toRelayCredentialRecord(profileId)
                val savedDraft = draft.withSavedRelayIdentity(profile, credentials)
                run {
                    val settingsAfterImage =
                        appSettingsRepository
                            .snapshot()
                            .toBuilder()
                            .apply { applyConfigDraft(draft) }
                            .build()
                    profileMutations.upsertRelay(
                        profileMutations.captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.SavedEdit),
                        profile = profile,
                        credentials = credentials,
                        enabled = draft.relayEnabled,
                        select = true,
                        settingsAfterImage = settingsAfterImage,
                        expectedState =
                            ExpectedRelayProfileState(
                                draft.editingRelayProfileAtOpen,
                                draft.editingRelayCredentialsAtOpen,
                            ),
                    )
                    return@withLock savedDraft
                }
            }

        private suspend fun requireWritableProfileId(draft: ConfigDraft) {
            readRecovered {
                val profileId = draft.relayProfileId.ifBlank { DefaultRelayProfileId }
                val profile = relayProfileStore.load(profileId)
                val credentials = relayCredentialStore.load(profileId)
                require(draft.editingRelayProfileId.isBlank() || draft.editingRelayProfileId == profileId) {
                    "A saved relay profile ID cannot be changed"
                }
                require(
                    draft.editingRelayProfileId.isBlank() ||
                        draft.editingRelayProfileAtOpen?.kind == draft.relayKind,
                ) { "A saved relay profile kind cannot be changed" }
                require(
                    if (draft.editingRelayProfileId.isBlank()) {
                        profile == null && credentials == null
                    } else {
                        profile == draft.editingRelayProfileAtOpen &&
                            credentials == draft.editingRelayCredentialsAtOpen
                    },
                ) {
                    "Relay profile changed since editing began"
                }
            }
        }

        private suspend fun <T> readRecovered(block: suspend () -> T): T = profileMutations.readRecovered(block)
    }
