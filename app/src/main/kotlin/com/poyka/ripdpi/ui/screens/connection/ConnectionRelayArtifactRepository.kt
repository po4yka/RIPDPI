package com.poyka.ripdpi.ui.screens.connection

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.ExpectedRelayProfileState
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.RelayCredentialRepository
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.applyRelayAfterImage
import com.poyka.ripdpi.data.loadReplaceableCredential
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/** The relay profile to edit is not in the store; other load failures keep their own exception type. */
class RelayProfileNotFoundException : NoSuchElementException("Relay profile no longer exists")

class ConnectionRelayArtifactRepository
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val relayProfileStore: RelayProfileStore,
        private val relayCredentialStore: RelayCredentialRepository,
        private val profileMutations: ProfileMutationCoordinator,
    ) {
        private val mutationMutex = Mutex()

        suspend fun prepareForPersistence(draft: ConnectionDraft): ConnectionDraft {
            requireWritableProfileId(draft)
            return prepareRelayDraftForPersistence(
                draft = draft,
                profileMutations = profileMutations,
            )
        }

        suspend fun hydrate(draft: ConnectionDraft): ConnectionDraft =
            readRecovered {
                val profileId = draft.relayProfileId.ifBlank { DefaultRelayProfileId }
                draft.withRelayArtifacts(relayProfileStore.load(profileId), loadEditableCredentials(profileId))
            }

        suspend fun hydrateProfile(profileId: String): ConnectionDraft =
            readRecovered {
                val profile = relayProfileStore.load(profileId) ?: throw RelayProfileNotFoundException()
                val draft =
                    appSettingsRepository
                        .snapshot()
                        .toBuilder()
                        .apply { applyRelayAfterImage(profile, enabled = true) }
                        .build()
                        .toConfigDraft()
                draft.withRelayArtifacts(profile, loadEditableCredentials(profileId))
            }

        suspend fun selectProfile(profileId: String): ConnectionDraft {
            val preparation =
                profileMutations.captureMutation(
                    com.poyka.ripdpi.data.ProfileMutationOrigin.ExplicitActivation,
                )
            val captured =
                readRecovered {
                    val profile = requireNotNull(relayProfileStore.load(profileId)) { "Relay profile no longer exists" }
                    val credentials = loadEditableCredentials(profileId)
                    profile to credentials
                }
            profileMutations.upsertRelay(
                preparation,
                captured.first,
                captured.second ?: com.poyka.ripdpi.data
                    .RelayCredentialRecord(profileId),
                enabled = true,
                select = true,
                expectedState = ExpectedRelayProfileState(captured.first, captured.second),
            )
            return appSettingsRepository.snapshot().toConfigDraft()
        }

        /** Turns the active relay off and keeps its profile selected, so Relay can reactivate it. */
        suspend fun deactivateActiveProfile(): ConnectionDraft {
            val preparation =
                profileMutations.captureMutation(
                    com.poyka.ripdpi.data.ProfileMutationOrigin.ExplicitActivation,
                )
            val profileId =
                appSettingsRepository
                    .snapshot()
                    .relayProfileId
                    .ifBlank { DefaultRelayProfileId }
            val (profile, credentials) =
                readRecovered { relayProfileStore.load(profileId) to loadEditableCredentials(profileId) }
            if (profile == null) {
                appSettingsRepository.update { setRelayEnabled(false) }
            } else {
                profileMutations.upsertRelay(
                    preparation,
                    profile,
                    credentials ?: com.poyka.ripdpi.data
                        .RelayCredentialRecord(profileId),
                    enabled = false,
                    select = true,
                    expectedState = ExpectedRelayProfileState(profile, credentials),
                )
            }
            return appSettingsRepository.snapshot().toConfigDraft()
        }

        suspend fun listProfiles(): List<RelayProfileRecord> = readRecovered { relayProfileStore.list() }

        suspend fun persist(draft: ConnectionDraft): ConnectionDraft =
            mutationMutex.withLock {
                requireWritableProfileId(draft)
                val profileId = draft.relayProfileId.ifBlank { DefaultRelayProfileId }
                val profile = draft.toRelayProfileRecord(profileId)
                val credentials = draft.toRelayCredentialRecord(profileId)
                val savedDraft = draft.withSavedRelayIdentity(profile, credentials)
                run {
                    val settingsBaseImage = appSettingsRepository.snapshot()
                    val settingsAfterImage = settingsBaseImage.toBuilder().apply { applyConfigDraft(draft) }.build()
                    profileMutations.upsertRelayEdit(
                        profileMutations.captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.SavedEdit),
                        profile = profile,
                        credentials = credentials,
                        enabled = draft.relayEnabled,
                        settingsBaseImage = settingsBaseImage,
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

        private suspend fun requireWritableProfileId(draft: ConnectionDraft) {
            readRecovered {
                val profileId = draft.relayProfileId.ifBlank { DefaultRelayProfileId }
                val profile = relayProfileStore.load(profileId)
                val credentials = loadEditableCredentials(profileId)
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

        // An unreadable secret opens as empty so the user can re-enter it; a save replaces it.
        private suspend fun loadEditableCredentials(profileId: String) =
            loadReplaceableCredential { relayCredentialStore.load(profileId) }

        private suspend fun <T> readRecovered(block: suspend () -> T): T = profileMutations.readRecovered(block)
    }
