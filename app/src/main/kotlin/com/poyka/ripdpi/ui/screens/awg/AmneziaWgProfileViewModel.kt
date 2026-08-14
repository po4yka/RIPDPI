package com.poyka.ripdpi.ui.screens.awg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgCohortCatalogData
import com.poyka.ripdpi.data.awg.AwgProfileForm
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.services.StandaloneAmneziaWgActivator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One row of the cohort-preset picker.
 *
 * [id] is the catalog preset id (or [AwgProfileForm.CUSTOM_COHORT_ID] for the free-form
 * sentinel). [displayNameKey] is a `strings.xml` resource key — the picker never carries
 * literal localized text — and is empty for the `Custom` sentinel, which the screen
 * labels from a fixed resource.
 */
data class AwgCohortOption(
    val id: String,
    val displayNameKey: String,
)

/**
 * Lifecycle of a Connect tap, surfaced so the screen can give feedback.
 *
 * [Idle] is the resting state (no attempt, or a prior attempt cleared by an edit).
 * [Connecting] spans the in-flight activation. [Failed] means the service layer could
 * not bring the tunnel to readiness — the screen shows an error caption. There is no
 * `Connected` state: a running tunnel is owned by the foreground service and observed
 * via the runtime telemetry channel, not this editor's transient state.
 */
enum class AwgActivationStatus { Idle, Connecting, Failed }

/**
 * UI state for the AmneziaWG profile editor.
 *
 * [editor] is the immutable field/validation snapshot. [cohortOptions] is the picker
 * content (every catalog preset plus the `Custom` sentinel). [privateKeyRevealed] /
 * [presharedKeyRevealed] gate the secret fields behind the biometric-reveal pattern used
 * by the other profile editors; both default to hidden.
 */
data class AmneziaWgProfileUiState(
    val editor: AmneziaWgEditorState,
    val cohortOptions: ImmutableList<AwgCohortOption>,
    val privateKeyRevealed: Boolean = false,
    val presharedKeyRevealed: Boolean = false,
    /**
     * `true` exactly when the editor carries the identity fields required to
     * open a tunnel. Drives the Connect action's enabled state.
     */
    val canActivate: Boolean = false,
    /**
     * A one-shot activation request produced by [AmneziaWgProfileViewModel.onConnect].
     * Non-null means the editor was projected into an [AwgActivationRequest] the
     * service layer should hand to the AmneziaWG runtime; the screen clears it
     * via [AmneziaWgProfileViewModel.onActivationConsumed] after dispatching.
     */
    val pendingActivation: AwgActivationRequest? = null,
    /**
     * Connect-attempt lifecycle for screen feedback. [AwgActivationStatus.Failed] is set
     * when the service layer cannot reach readiness; the next edit or Connect tap clears it.
     */
    val activationStatus: AwgActivationStatus = AwgActivationStatus.Idle,
)

/**
 * Backing [ViewModel] for the AmneziaWG profile editor screen.
 *
 * The editor exposes every standard WireGuard field plus all 16 AmneziaWG obfuscation
 * fields inline (`Jc`, `Jmin`, `Jmax`, `S1`-`S4`, `H1`-`H4`, `I1`-`I5`) — they are not
 * hidden behind an "Advanced" toggle because they are server-coordinated and pasted
 * verbatim. Picking a cohort preset fills and locks the obfuscation group; the `Custom`
 * sentinel frees it again. Secret fields (private key, preshared key) stay hidden until a
 * biometric reveal is authorized.
 */
@HiltViewModel
class AmneziaWgProfileViewModel
    @Inject
    constructor(
        private val catalogProvider: AwgCohortCatalogProvider,
        private val amneziaWgActivator: StandaloneAmneziaWgActivator,
        private val profileRepository: AwgProfileRepository,
    ) : ViewModel() {
        private val catalog: AwgCohortCatalogData = catalogProvider.catalog()

        /**
         * The stable id of the durably-persisted profile this editor is bound to,
         * or `null` until the first successful save. On the first Connect tap the
         * profile is persisted and the repository mints an opaque `"awg-<UUID>"` id;
         * every later Connect re-uses that same id (both as the persisted row key and
         * as [AwgActivationRequest.profileId]), closing the per-activation fresh-UUID
         * deferral. Accessed only from [onConnect] on the main dispatcher, so a plain
         * field is sufficient.
         */
        private var savedProfileId: String? = null

        private val _uiState =
            MutableStateFlow(
                AmneziaWgProfileUiState(
                    editor = AmneziaWgEditorState.initial(),
                    cohortOptions = buildCohortOptions(catalog).toImmutableList(),
                    canActivate = AmneziaWgEditorState.initial().isActivatable(),
                ),
            )
        val uiState: StateFlow<AmneziaWgProfileUiState> = _uiState.asStateFlow()

        /**
         * Replaces the editor, recomputes the derived [AmneziaWgProfileUiState.canActivate],
         * and clears a stale [AwgActivationStatus.Failed] (the user is changing input, so the
         * previous failure no longer describes the current form).
         */
        private inline fun mutateEditor(crossinline transform: (AmneziaWgEditorState) -> AmneziaWgEditorState) {
            _uiState.update {
                val nextEditor = transform(it.editor)
                it.copy(
                    editor = nextEditor,
                    canActivate = nextEditor.isActivatable(),
                    activationStatus =
                        if (it.activationStatus == AwgActivationStatus.Failed) {
                            AwgActivationStatus.Idle
                        } else {
                            it.activationStatus
                        },
                )
            }
        }

        /**
         * Applies a user edit of [field] to [raw]. Edits to an obfuscation field while a
         * cohort preset is locked are ignored (the preset owns those values).
         */
        fun onFieldChanged(
            field: AwgEditorField,
            raw: String,
        ) {
            mutateEditor { it.updateField(field, raw) }
        }

        /**
         * Selects a cohort by [cohortId]. The [AwgProfileForm.CUSTOM_COHORT_ID] sentinel
         * frees the obfuscation fields; any other id fills and locks them from the catalog.
         */
        fun onCohortSelected(cohortId: String) {
            if (cohortId == AwgProfileForm.CUSTOM_COHORT_ID) {
                mutateEditor { it.selectCustom() }
                return
            }
            val preset = catalog.find(cohortId) ?: return
            mutateEditor { it.selectCohort(preset) }
        }

        /**
         * Selects the transport carrier ([AwgProfileForm.carrier]) by [carrier] token
         * ([AwgActivationRequest.CARRIER_UDP] or [AwgActivationRequest.CARRIER_WS]).
         */
        fun onCarrierSelected(carrier: String) {
            mutateEditor { it.selectCarrier(carrier) }
        }

        /**
         * Replaces the editor state from a pasted AmneziaWG `.conf`. Malformed input or a
         * vanilla WireGuard config leaves the editor unchanged.
         */
        fun onConfPasted(conf: String) {
            mutateEditor { it.populateFromConf(conf, catalog) }
        }

        /**
         * Projects the current editor into an [AwgActivationRequest], durably persists
         * it, and activates it through the `:core:service` [StandaloneAmneziaWgActivator]
         * (the WARP-engine-derived AmneziaWG runtime path). A no-op when the editor is not
         * yet [AmneziaWgEditorState.isActivatable].
         *
         * **Stable id (A3 closure):** the first Connect saves the profile via
         * [AwgProfileRepository], which mints an opaque `"awg-<UUID>"` id; every later
         * Connect re-saves under that same id and re-uses it as
         * [AwgActivationRequest.profileId]. The persisted id -- never the endpoint -- is
         * what flows into native runtime telemetry, honoring network-fingerprint-privacy.
         *
         * The request carries the full PSK + persistent-keepalive plumbing and is surfaced
         * on [AmneziaWgProfileUiState.pendingActivation] so the screen can react to the
         * dispatch; the screen clears it via [onActivationConsumed].
         *
         * The save + activation run on [viewModelScope]; the call is wrapped so a persist
         * or startup failure surfaces as [AwgActivationStatus.Failed] instead of being
         * silently dropped.
         */
        @Suppress("TooGenericExceptionCaught")
        fun onConnect() {
            val editor = _uiState.value.editor
            if (!editor.isActivatable()) return
            _uiState.update { it.copy(activationStatus = AwgActivationStatus.Connecting) }
            viewModelScope.launch {
                try {
                    // Persist first so the stable row id is the profileId the runtime sees.
                    // The blob's profileId is blanked by the repository; the saved id is
                    // authoritative and re-used on every later Connect.
                    val draft = editor.toActivationRequest(profileId = "")
                    val name = profileName(editor)
                    val stableId = profileRepository.save(name, draft, existingId = savedProfileId)
                    savedProfileId = stableId
                    val request = draft.copy(profileId = stableId)
                    _uiState.update { it.copy(pendingActivation = request) }
                    amneziaWgActivator.activate(request)
                    _uiState.update { it.copy(activationStatus = AwgActivationStatus.Idle) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (ignored: Exception) {
                    // The service signals startup failure with SupervisorStartupFailureException,
                    // which is `internal` to :core:service and so cannot be named from :app; the
                    // resolver can also fail closed with IllegalArgumentException, and the persist
                    // step may throw. Catch broadly to convert ANY failure into the user-visible
                    // Failed state, rethrowing CancellationException above to preserve structured
                    // concurrency. The exception detail is intentionally not propagated or logged --
                    // it may carry endpoint or config material that must not reach logs
                    // (network-fingerprint-privacy.md); the Failed status is the user-facing feedback.
                    _uiState.update { it.copy(activationStatus = AwgActivationStatus.Failed) }
                }
            }
        }

        /** Clears the one-shot [AmneziaWgProfileUiState.pendingActivation] after dispatch. */
        fun onActivationConsumed() {
            _uiState.update { it.copy(pendingActivation = null) }
        }

        /**
         * A non-sensitive display label for the saved profile, derived from the cohort id
         * (server-coordinated, not user-private) rather than the endpoint host. The
         * endpoint host is intentionally NOT used as the name so it never surfaces in a
         * profile-list UI label; it lives only inside the serialized blob as user config.
         */
        private fun profileName(editor: AmneziaWgEditorState): String {
            val cohort = editor.form.cohortId
            return if (cohort == AwgProfileForm.CUSTOM_COHORT_ID) "AmneziaWG" else "AmneziaWG ($cohort)"
        }

        /** Reveals the private-key field after the biometric gate authorizes it. */
        fun onPrivateKeyRevealAuthorized() {
            _uiState.update { it.copy(privateKeyRevealed = true) }
        }

        /** Reveals the preshared-key field after the biometric gate authorizes it. */
        fun onPresharedKeyRevealAuthorized() {
            _uiState.update { it.copy(presharedKeyRevealed = true) }
        }

        /** Re-hides both secret fields (e.g. when the screen is left). */
        fun onSecretsRelocked() {
            _uiState.update { it.copy(privateKeyRevealed = false, presharedKeyRevealed = false) }
        }
    }

private fun buildCohortOptions(catalog: AwgCohortCatalogData): List<AwgCohortOption> =
    catalog.presets.map { AwgCohortOption(id = it.id, displayNameKey = it.displayNameKey) } +
        AwgCohortOption(id = AwgProfileForm.CUSTOM_COHORT_ID, displayNameKey = "")
