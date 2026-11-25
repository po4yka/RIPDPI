package com.poyka.ripdpi.ui.screens.awg

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.WarpKeyPairGenerator
import com.poyka.ripdpi.data.AppliedRuntimeConfigurationSource
import com.poyka.ripdpi.data.LogTags
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgCohortCatalogData
import com.poyka.ripdpi.data.awg.AwgProfileForm
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.awg.requireRuntimeReady
import com.poyka.ripdpi.platform.PermissionPlatformBridge
import com.poyka.ripdpi.proxyimport.ClipboardReader
import com.poyka.ripdpi.proxyimport.PendingProxyImportStore
import com.poyka.ripdpi.services.StandaloneAmneziaWgActivator
import com.poyka.ripdpi.ui.screens.connection.awgProfileId
import com.poyka.ripdpi.ui.screens.connection.runningSelections
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
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

/** A system consent request; it never contains profile credentials. */
class AwgVpnConsentRequest(
    val id: String,
    val intent: Intent,
)

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
    /** The profile name the lists show; blank saves the cohort-derived default. */
    val name: String = "",
    /** The public half of the key pair generated in this editor; it goes when the private key changes. */
    val generatedPublicKey: String? = null,
    /** The last key generation failed; the next edit clears it. */
    val keyGenerationFailed: Boolean = false,
    /** A Save without Connect runs. */
    val saving: Boolean = false,
    /** The last Save stored the form; the next edit clears it. */
    val saved: Boolean = false,
    /** The last Save failed; the next edit clears it. */
    val saveFailed: Boolean = false,
    val privateKeyRevealed: Boolean = false,
    val presharedKeyRevealed: Boolean = false,
    val headerProtectionKeyRevealed: Boolean = false,
    val pendingCohortId: String? = null,
    /**
     * `true` when the projected request passes the runtime's local input checks.
     * Drives the Connect action's enabled state.
     */
    val canActivate: Boolean = false,
    /**
     * Connect-attempt lifecycle for screen feedback. [AwgActivationStatus.Failed] is set
     * when the service layer cannot reach readiness; the next edit or Connect tap clears it.
     */
    val activationStatus: AwgActivationStatus = AwgActivationStatus.Idle,
    /** Why the last pasted, picked, scanned, or shared `.conf` was not used; the next edit clears it. */
    val confImportFailure: AwgConfImport.Failed? = null,
    /** `[Peer]` sections the last imported `.conf` had beyond the first one, which the editor ignores. */
    val ignoredPeerCount: Int = 0,
    val ignoredConfOptions: ImmutableList<String> = persistentListOf(),
    /** Connect refused to save another server under a subscription profile; the next edit clears it. */
    val subscriptionServerChanged: Boolean = false,
    /** A saved profile is loading; the form stays hidden so nothing typed can be lost or merged. */
    val loadingProfile: Boolean = false,
    /** The editor is bound to a stored profile that is not a subscription's; that subscription's delete removes it. */
    val deletable: Boolean = false,
    /** A running session carries traffic through the bound profile, so it cannot be deleted. */
    val inUse: Boolean = false,
    /** A delete runs; Connect and Delete wait for it, and a successful one closes the editor. */
    val deleting: Boolean = false,
    /** The last delete failed; the next edit clears it. */
    val deleteFailed: Boolean = false,
)

/**
 * Backing [ViewModel] for the AmneziaWG profile editor screen.
 *
 * The editor exposes every standard WireGuard field plus all 16 AmneziaWG obfuscation
 * fields (`Jc`, `Jmin`, `Jmax`, `S1`-`S4`, `H1`-`H4`, `I1`-`I5`); all but the key, server,
 * address and peer key sit in the collapsed Advanced group, which opens while one is invalid.
 * Picking a cohort preset fills and locks the obfuscation group; the `Custom` sentinel frees it
 * again. Secret fields (private key, preshared key) stay hidden until a
 * biometric reveal is authorized.
 */
@HiltViewModel
class AmneziaWgProfileViewModel
    @Inject
    constructor(
        private val catalogProvider: AwgCohortCatalogProvider,
        private val amneziaWgActivator: StandaloneAmneziaWgActivator,
        private val profileRepository: AwgProfileRepository,
        private val permissionPlatformBridge: PermissionPlatformBridge,
        private val clipboardReader: ClipboardReader,
        private val applied: AppliedRuntimeConfigurationSource,
        private val keyPairGenerator: WarpKeyPairGenerator,
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

        /** The subscription profile this editor updates; a pasted `.conf` must keep its server. */
        private var subscriptionServer: AwgServerIdentity? = null
        private var requestedProfileId: String? = null
        private var claimedImportToken: String? = null
        private var pendingConsent: Pair<String, AmneziaWgEditorState>? = null
        private val consentRequests = Channel<AwgVpnConsentRequest>(Channel.BUFFERED)
        val vpnConsentRequests = consentRequests.receiveAsFlow()
        private val deletions = Channel<Unit>(Channel.BUFFERED)

        /** Emits once the bound profile is deleted, so the screen can close. */
        val profileDeleted = deletions.receiveAsFlow()

        private val _uiState =
            MutableStateFlow(
                AmneziaWgProfileUiState(
                    editor = AmneziaWgEditorState.initial(),
                    cohortOptions = buildCohortOptions(catalog).toImmutableList(),
                    canActivate = AmneziaWgEditorState.initial().isActivatable(),
                ),
            )
        val uiState: StateFlow<AmneziaWgProfileUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                applied.applications.collect { applications ->
                    _uiState.update { it.copy(inUse = applications.runsAwgProfile(savedProfileId)) }
                }
            }
        }

        /**
         * Replaces the editor, recomputes the derived [AmneziaWgProfileUiState.canActivate],
         * and clears a stale [AwgActivationStatus.Failed] (the user is changing input, so the
         * previous failure no longer describes the current form).
         */
        private inline fun mutateEditor(crossinline transform: (AmneziaWgEditorState) -> AmneziaWgEditorState) {
            _uiState.update { if (it.loadingProfile) it else it.withEditor(transform(it.editor)) }
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

        fun onNameChanged(name: String) {
            _uiState.update { if (it.loadingProfile) it else it.copy(name = name, saved = false, saveFailed = false) }
        }

        /**
         * Fills an empty private key from a fresh native X25519 pair and keeps its public half for copying.
         * A key typed or pasted meanwhile wins: generation never replaces an existing key.
         */
        @Suppress("TooGenericExceptionCaught")
        fun onGenerateKeyPair() {
            if (!_uiState.value.canGenerateKey()) return
            viewModelScope.launch {
                val pair =
                    try {
                        keyPairGenerator.generate()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (expectedFailure: Exception) {
                        Logger.w(
                            tag = LogTags.UI,
                        ) { "AmneziaWG key generation failed: ${expectedFailure::class.simpleName}" }
                        _uiState.update { it.copy(keyGenerationFailed = true) }
                        return@launch
                    }
                _uiState.update { state ->
                    if (!state.canGenerateKey()) return@update state
                    state
                        .withEditor(state.editor.updateField(AwgEditorField.INTERFACE_PRIVATE_KEY, pair.privateKey))
                        .copy(generatedPublicKey = pair.publicKey)
                }
            }
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
            _uiState.update { state ->
                if (state.loadingProfile) {
                    state
                } else if (state.editor.hasV3Edits()) {
                    state.copy(pendingCohortId = cohortId)
                } else {
                    state.withEditor(state.editor.selectCohort(preset))
                }
            }
        }

        fun onCohortReplacementConfirmed() {
            _uiState.update { state ->
                val preset = state.pendingCohortId?.let(catalog::find) ?: return@update state
                state.withEditor(state.editor.selectCohort(preset)).copy(headerProtectionKeyRevealed = false)
            }
        }

        fun onCohortReplacementCancelled() {
            _uiState.update { it.copy(pendingCohortId = null) }
        }

        /**
         * Selects the transport carrier ([AwgProfileForm.carrier]) by [carrier] token
         * ([AwgActivationRequest.CARRIER_UDP] or [AwgActivationRequest.CARRIER_WS]).
         */
        fun onCarrierSelected(carrier: String) {
            mutateEditor { it.selectCarrier(carrier) }
        }

        /**
         * Replaces the editor state from a WireGuard or AmneziaWG `.conf`. Input that cannot
         * be used leaves the editor unchanged and sets [AmneziaWgProfileUiState.confImportFailure].
         */
        fun onConfPasted(conf: String) {
            _uiState.update { state ->
                if (state.loadingProfile) return@update state
                when (val result = state.editor.importConf(conf, catalog)) {
                    is AwgConfImport.Failed -> {
                        state.copy(confImportFailure = result)
                    }

                    is AwgConfImport.Imported -> {
                        if (subscriptionServer.isChangedBy(result.editor)) {
                            return@update state.copy(
                                confImportFailure =
                                    AwgConfImport.Failed(
                                        AwgConfImportFailure.SUBSCRIPTION_SERVER_MISMATCH,
                                    ),
                            )
                        }
                        // A subscription refresh owns everything but the user's fields, so only those are taken.
                        val pasted =
                            if (state.editor.subscriptionOwned) {
                                state.editor.withUserFieldsFrom(result.editor)
                            } else {
                                result.editor
                            }
                        // A conf without a key value only refreshes the other fields.
                        val currentKey = state.editor.form.interfacePrivateKey
                        val imported =
                            if (pasted.form.interfacePrivateKey.isBlank() && currentKey.isNotBlank()) {
                                pasted.updateField(AwgEditorField.INTERFACE_PRIVATE_KEY, currentKey)
                            } else {
                                pasted
                            }
                        state.withEditor(imported).copy(
                            ignoredPeerCount = result.ignoredPeerCount,
                            ignoredConfOptions = result.ignoredOptions.toImmutableList(),
                            privateKeyRevealed = false,
                            presharedKeyRevealed = false,
                            headerProtectionKeyRevealed = false,
                        )
                    }
                }
            }
        }

        /**
         * Prefills the editor from a `.conf` staged by the QR scanner or the file picker.
         * The token is claimed once; a repeat call for the same token (screen recreation) is
         * a no-op. A token lost with the process leaves the editor as is: there is nothing to
         * report about the file itself.
         */
        internal fun onImportToken(
            importToken: String,
            pendingImports: PendingProxyImportStore = PendingProxyImportStore.process,
        ) {
            if (importToken == claimedImportToken) return
            claimedImportToken = importToken
            pendingImports.claimAmneziaWgConf(importToken)?.let(::onConfPasted)
        }

        /**
         * Opens the saved profile [profileId]. Save then updates that row, so a subscription profile keeps its
         * id and subscription link. Edits wait until the load completes. A cancelled or failed load can run
         * again; a row that cannot be loaded shows the connect error, and Connect never creates a new row.
         */
        @Suppress("TooGenericExceptionCaught")
        suspend fun onLoadProfile(profileId: String) {
            if (profileId == savedProfileId) return
            requestedProfileId = profileId
            _uiState.update { it.copy(loadingProfile = true) }
            val saved =
                try {
                    profileRepository.load(profileId)
                } catch (cancellation: CancellationException) {
                    _uiState.update { it.copy(loadingProfile = false) }
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
            if (saved == null) {
                _uiState.update { it.copy(loadingProfile = false, activationStatus = AwgActivationStatus.Failed) }
                return
            }
            savedProfileId = saved.id
            subscriptionServer = saved.request.takeIf { saved.subscriptionOwned }?.serverIdentity()
            val editor = saved.request.toEditorState().copy(subscriptionOwned = saved.subscriptionOwned)
            _uiState.update {
                it.withEditor(editor).copy(
                    name = saved.name,
                    loadingProfile = false,
                    deletable = !saved.subscriptionOwned,
                    inUse = applied.applications.value.runsAwgProfile(saved.id),
                )
            }
        }

        /**
         * Deletes the bound saved profile. It runs once: a repeat tap, a Connect in progress, or a profile the running
         * session uses is ignored. A failure keeps the editor open and reports it; success closes the editor once.
         */
        fun onDelete() {
            val id = savedProfileId ?: return
            val state = _uiState.value
            val busy =
                state.loadingProfile || state.deleting || state.saving ||
                    state.activationStatus == AwgActivationStatus.Connecting
            if (busy || !state.deletable || applied.applications.value.runsAwgProfile(id)) return
            _uiState.update { it.copy(deleting = true, deleteFailed = false) }
            viewModelScope.launch {
                try {
                    profileRepository.delete(id)
                } catch (cancellation: CancellationException) {
                    _uiState.update { it.copy(deleting = false) }
                    throw cancellation
                } catch (expectedFailure: Exception) {
                    Logger.w(
                        tag = LogTags.UI,
                    ) { "AmneziaWG profile delete failed: ${expectedFailure::class.simpleName}" }
                    _uiState.update { it.copy(deleting = false, deleteFailed = true) }
                    return@launch
                }
                savedProfileId = null
                _uiState.update { it.copy(deletable = false) }
                deletions.send(Unit)
            }
        }

        /** Reads the clipboard only in response to the editor's explicit Paste action. */
        fun onPasteConf() {
            clipboardReader.readPrimaryClipText()?.let(::onConfPasted)
        }

        /** Requests system VPN consent before saving or activating the selected profile. */
        fun onConnect() {
            val state = _uiState.value
            val editor = state.editor
            if (state.busy() || !editor.isActivatable()) return
            // Refuse before the consent dialog: the save it would authorize is refused anyway.
            if (subscriptionServer.isChangedBy(editor)) {
                _uiState.update { it.copy(subscriptionServerChanged = true) }
                return
            }
            _uiState.update { it.copy(activationStatus = AwgActivationStatus.Connecting) }
            launchConnectAction {
                val intent = permissionPlatformBridge.prepareVpnPermissionIntent()
                if (intent == null) {
                    persistAndActivate(editor)
                } else {
                    val id = UUID.randomUUID().toString()
                    pendingConsent = id to editor
                    consentRequests.send(AwgVpnConsentRequest(id, intent))
                }
            }
        }

        /** Stores the form as the bound profile without VPN consent or activation. */
        @Suppress("TooGenericExceptionCaught")
        fun onSave() {
            val state = _uiState.value
            if (state.busy() || !state.editor.isActivatable()) return
            _uiState.update { it.copy(saving = true, saved = false, saveFailed = false) }
            viewModelScope.launch {
                val stored =
                    try {
                        persist(state.editor) != null
                    } catch (cancellation: CancellationException) {
                        _uiState.update { it.copy(saving = false) }
                        throw cancellation
                    } catch (ignored: Exception) {
                        // Config exceptions can contain secrets; expose only the status.
                        _uiState.update { it.copy(saving = false, saveFailed = true) }
                        return@launch
                    }
                _uiState.update { it.copy(saving = false, saved = stored) }
            }
        }

        /** Ignores stale callbacks and rechecks the platform grant before any activation. */
        fun onVpnConsentResult(
            requestId: String,
            granted: Boolean,
        ) {
            val pending = pendingConsent?.takeIf { it.first == requestId } ?: return
            pendingConsent = null
            if (!granted) {
                _uiState.update { it.copy(activationStatus = AwgActivationStatus.Idle) }
                return
            }
            launchConnectAction {
                if (permissionPlatformBridge.prepareVpnPermissionIntent() == null) {
                    persistAndActivate(pending.second)
                } else {
                    _uiState.update { it.copy(activationStatus = AwgActivationStatus.Idle) }
                }
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private fun launchConnectAction(action: suspend () -> Unit) {
            viewModelScope.launch {
                try {
                    action()
                } catch (cancellation: CancellationException) {
                    pendingConsent = null
                    _uiState.update { it.copy(activationStatus = AwgActivationStatus.Idle) }
                    throw cancellation
                } catch (ignored: Exception) {
                    // Config and platform exceptions can contain secrets; expose only the status.
                    pendingConsent = null
                    _uiState.update { it.copy(activationStatus = AwgActivationStatus.Failed) }
                }
            }
        }

        private suspend fun persistAndActivate(editor: AmneziaWgEditorState) {
            val request = persist(editor)
            if (request != null) amneziaWgActivator.activate(request)
            _uiState.update { it.copy(activationStatus = AwgActivationStatus.Idle) }
        }

        /**
         * Saves [editor] as the bound row and returns its request with the stable id. Returns `null` without saving
         * when the form changed since the tap or the edit would move a subscription profile to another server.
         */
        private suspend fun persist(editor: AmneziaWgEditorState): AwgActivationRequest? {
            val state = _uiState.value
            if (state.editor !== editor || state.pendingCohortId != null) return null
            // An editor opened for a saved row never saves a new, unlinked row.
            check(requestedProfileId == null || savedProfileId != null) { "Saved AmneziaWG profile is not loaded" }
            if (subscriptionServer.isChangedBy(editor)) {
                _uiState.update { it.copy(subscriptionServerChanged = true) }
                return null
            }
            val draft = editor.toActivationRequest(profileId = "")
            draft.requireRuntimeReady()
            val stableId = profileRepository.save(profileName(state.name, editor), draft, existingId = savedProfileId)
            savedProfileId = stableId
            _uiState.update { it.copy(deletable = !editor.subscriptionOwned) }
            return draft.copy(profileId = stableId)
        }

        /** Reveals the private-key field after the biometric gate authorizes it. */
        fun onPrivateKeyRevealAuthorized() {
            _uiState.update { it.copy(privateKeyRevealed = true) }
        }

        /** Reveals the preshared-key field after the biometric gate authorizes it. */
        fun onPresharedKeyRevealAuthorized() {
            _uiState.update { it.copy(presharedKeyRevealed = true) }
        }

        fun onHeaderProtectionKeyRevealAuthorized() {
            _uiState.update { it.copy(headerProtectionKeyRevealed = true) }
        }

        /** Re-hides all secret fields when the screen is left. */
        fun onSecretsRelocked() {
            _uiState.update {
                it.copy(privateKeyRevealed = false, presharedKeyRevealed = false, headerProtectionKeyRevealed = false)
            }
        }
    }

/**
 * The typed [name], or a default derived from the cohort id (server-coordinated, not user-private) rather than the
 * endpoint host, so the host never surfaces in a profile-list label unless the user types it.
 */
private fun profileName(
    name: String,
    editor: AmneziaWgEditorState,
): String {
    name.trim().takeIf { it.isNotEmpty() }?.let { return it }
    val cohort = editor.form.cohortId
    return if (cohort == AwgProfileForm.CUSTOM_COHORT_ID) "AmneziaWG" else "AmneziaWG ($cohort)"
}

private fun Map<Mode, RuntimeConfigurationApplication>.runsAwgProfile(profileId: String?): Boolean =
    profileId != null && runningSelections().any { it.awgProfileId() == profileId }

private fun AmneziaWgProfileUiState.busy(): Boolean =
    pendingCohortId != null || loadingProfile || deleting || saving ||
        activationStatus == AwgActivationStatus.Connecting

private fun AmneziaWgProfileUiState.canGenerateKey(): Boolean =
    !loadingProfile && editor.form.interfacePrivateKey.isBlank()

/** Replaces the editor and clears the stale flags that described the previous input. */
private fun AmneziaWgProfileUiState.withEditor(nextEditor: AmneziaWgEditorState): AmneziaWgProfileUiState =
    copy(
        editor = nextEditor,
        generatedPublicKey =
            generatedPublicKey.takeIf { nextEditor.form.interfacePrivateKey == editor.form.interfacePrivateKey },
        keyGenerationFailed = false,
        saved = false,
        saveFailed = false,
        pendingCohortId = null,
        canActivate = nextEditor.isActivatable(),
        confImportFailure = null,
        subscriptionServerChanged = false,
        deleteFailed = false,
        activationStatus =
            if (activationStatus == AwgActivationStatus.Failed) AwgActivationStatus.Idle else activationStatus,
    )

private fun buildCohortOptions(catalog: AwgCohortCatalogData): List<AwgCohortOption> =
    catalog.presets.map { AwgCohortOption(id = it.id, displayNameKey = it.displayNameKey) } +
        AwgCohortOption(id = AwgProfileForm.CUSTOM_COHORT_ID, displayNameKey = "")

/** The one server check for paste and save: a subscription row keeps the server its bundle names. */
private fun AwgServerIdentity?.isChangedBy(editor: AmneziaWgEditorState): Boolean =
    this != null && this != editor.serverIdentity()
