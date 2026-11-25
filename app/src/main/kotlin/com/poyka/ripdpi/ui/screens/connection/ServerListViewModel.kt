package com.poyka.ripdpi.ui.screens.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppliedRuntimeConfigurationSource
import com.poyka.ripdpi.data.ExpectedRelayProfileState
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.RelayCatalogMutationCoordinator
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialRepository
import com.poyka.ripdpi.data.RelayProfileIdTakenException
import com.poyka.ripdpi.data.RelayProfileInUseException
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.loadReplaceableCredential
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.ui.screens.awg.runsAwgProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ServerKey {
    val id: String

    data class Relay(
        override val id: String,
    ) : ServerKey

    data class Awg(
        override val id: String,
    ) : ServerKey
}

internal fun uniqueServerId(
    base: String,
    taken: Set<String>,
): String = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { it !in taken }

@HiltViewModel
internal class ServerListViewModel
    @Inject
    constructor(
        private val relayCatalog: RelayCatalogMutationCoordinator,
        private val profileMutations: ProfileMutationCoordinator,
        private val relayProfileStore: RelayProfileStore,
        private val relayCredentialStore: RelayCredentialRepository,
        private val awgRepository: AwgProfileRepository,
        private val applied: AppliedRuntimeConfigurationSource,
        private val stringResolver: StringResolver,
    ) : ViewModel() {
        private val _message = MutableStateFlow<String?>(null)
        val message: StateFlow<String?> = _message.asStateFlow()

        fun dismissMessage() {
            _message.value = null
        }

        fun rename(
            server: ServerKey,
            name: String,
            onChanged: () -> Unit,
        ) {
            val trimmed = name.trim()
            if (trimmed.isEmpty() || trimmed == server.id) return
            launchAction(onChanged) {
                when (server) {
                    is ServerKey.Relay -> {
                        relayCatalog.renameRelay(
                            profileMutations.captureMutation(ProfileMutationOrigin.SavedEdit),
                            server.id,
                            trimmed,
                        )
                    }

                    is ServerKey.Awg -> {
                        val saved = requireNotNull(awgRepository.load(server.id))
                        awgRepository.save(trimmed, saved.request, existingId = server.id)
                    }
                }
            }
        }

        fun duplicate(
            server: ServerKey,
            onChanged: () -> Unit,
        ) = launchAction(onChanged) {
            when (server) {
                is ServerKey.Relay -> {
                    duplicateRelay(server.id)
                }

                is ServerKey.Awg -> {
                    val saved = requireNotNull(awgRepository.load(server.id))
                    val copyName = stringResolver.getString(R.string.server_copy_name, saved.name)
                    awgRepository.save(copyName, saved.request, existingId = null)
                }
            }
        }

        fun delete(
            server: ServerKey,
            onChanged: () -> Unit,
        ) = launchAction(onChanged) {
            when (server) {
                is ServerKey.Relay -> {
                    relayCatalog.deleteRelay(
                        profileMutations.captureMutation(ProfileMutationOrigin.ExplicitDeletion),
                        server.id,
                    )
                }

                is ServerKey.Awg -> {
                    val saved = awgRepository.load(server.id)
                    if (saved?.subscriptionOwned == true || applied.applications.value.runsAwgProfile(server.id)) {
                        throw RelayProfileInUseException()
                    }
                    awgRepository.delete(server.id)
                }
            }
        }

        private suspend fun duplicateRelay(profileId: String) {
            val preparation = profileMutations.captureMutation(ProfileMutationOrigin.SavedEdit)
            val (profile, credentials, copyId) =
                profileMutations.readRecovered {
                    val profile = requireNotNull(relayProfileStore.load(profileId))
                    val taken = relayProfileStore.list().map { it.id }.toSet()
                    Triple(
                        profile,
                        loadReplaceableCredential { relayCredentialStore.load(profileId) },
                        uniqueServerId("$profileId-copy", taken),
                    )
                }
            profileMutations.upsertRelay(
                preparation,
                profile.copy(id = copyId),
                credentials?.copy(profileId = copyId) ?: RelayCredentialRecord(copyId),
                enabled = false,
                select = false,
                expectedState = ExpectedRelayProfileState(null, null),
            )
        }

        private fun launchAction(
            onChanged: () -> Unit,
            block: suspend () -> Unit,
        ) {
            viewModelScope.launch {
                val failure = runCatching { block() }.exceptionOrNull()
                if (failure is CancellationException) throw failure
                _message.value =
                    when (failure) {
                        null -> null
                        is RelayProfileInUseException -> stringResolver.getString(R.string.server_action_in_use)
                        is RelayProfileIdTakenException -> stringResolver.getString(R.string.server_action_name_taken)
                        else -> stringResolver.getString(R.string.server_action_failed)
                    }
                onChanged()
            }
        }
    }

internal fun ConnectionProfileActions.withServerActions(
    servers: ServerListViewModel,
    connection: ConnectionViewModel,
    onOpenRoute: (com.poyka.ripdpi.ui.navigation.Route) -> Unit,
): ConnectionProfileActions {
    val changed = connection::refreshPersistedSnapshot
    return copy(
        openAwg = { id, needsPrivateKey ->
            onOpenRoute(
                com.poyka.ripdpi.ui.navigation.Route
                    .AmneziaWgProfile(profileId = id, keyOnly = needsPrivateKey),
            )
        },
        rename = { server, name -> servers.rename(server, name, changed) },
        duplicate = { server -> servers.duplicate(server, changed) },
        delete = { server -> servers.delete(server, changed) },
        dismissMessage = servers::dismissMessage,
    )
}
