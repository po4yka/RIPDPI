package com.poyka.ripdpi.ui.screens.relayeditor

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.proxyimport.RelayProfileActivator
import com.poyka.ripdpi.ui.screens.connection.ConnectionDraft
import com.poyka.ripdpi.ui.screens.connection.ConnectionRelayArtifactRepository
import com.poyka.ripdpi.ui.screens.connection.RelayProfileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * The save lifecycle every relay editor ViewModel shares: run one save at a time, report success or failure, and emit
 * [savedEvents] after a success so the screen closes. A failure never emits, so the user keeps the form.
 */
internal class RelayEditorSaveFlow(
    private val scope: CoroutineScope,
) {
    private val savedEventChannel = Channel<Unit>(capacity = Channel.BUFFERED)
    val savedEvents: Flow<Unit> = savedEventChannel.receiveAsFlow()
    private var inFlight = false

    /** `true` once a save succeeded. Callers read it to skip their own pre-save UI work, such as the `saving` flag. */
    var completed: Boolean = false
        private set

    /**
     * Runs [block] unless a save is running or already succeeded. A thrown error other than cancellation counts as a
     * failed save. [onResult] sees the outcome.
     */
    fun save(
        block: suspend () -> Boolean,
        onResult: (activated: Boolean) -> Unit,
    ) {
        if (completed || inFlight) return
        inFlight = true
        scope.launch {
            val activated =
                try {
                    runCatching { block() }.getOrElse { error ->
                        if (error is CancellationException) throw error
                        false
                    }
                } finally {
                    inFlight = false
                }
            onResult(activated)
            if (activated) {
                completed = true
                savedEventChannel.send(Unit)
            }
        }
    }
}

/** Applies [profile] as the active native relay, the same path the import confirmation uses. */
internal suspend fun RelayProfileActivator.activateExplicitly(profile: ProxyProfile): Boolean =
    activate(captureMutation(ProfileMutationOrigin.ExplicitActivation), profile)

/**
 * Loads the saved profile [profileId] for editing. Returns `null` after calling [onFailure] when the profile is
 * missing (`notFound`), is another relay kind, or fails to load (a corrupt record keeps the load error message).
 */
internal suspend fun ConnectionRelayArtifactRepository.loadRelayDraft(
    profileId: String,
    kind: String,
    onFailure: (
        notFound: Boolean,
        errorMessage: Int?,
    ) -> Unit,
): ConnectionDraft? {
    val draft =
        runCatching { hydrateProfile(profileId) }.getOrElse { error ->
            if (error is CancellationException) throw error
            val missing = error is RelayProfileNotFoundException
            onFailure(missing, R.string.relay_editor_activation_failed.takeUnless { missing })
            null
        }
    val matching = draft?.takeIf { it.relayKind == kind }
    if (draft != null && matching == null) onFailure(true, null)
    return matching
}
