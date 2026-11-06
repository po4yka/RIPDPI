package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.data.persistence.ProfileDatabase
import com.poyka.ripdpi.data.persistence.ProfileRows
import com.poyka.ripdpi.data.selector.SelectorActiveGroupStore
import com.poyka.ripdpi.data.selector.SelectorChoiceOrigin
import com.poyka.ripdpi.data.xray.XrayProfile
import com.poyka.ripdpi.data.xray.XrayProfileMetadataStore
import com.poyka.ripdpi.data.xray.XrayProfileSecretStore
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import com.poyka.ripdpi.data.xray.XrayProviderSelectionStore
import com.poyka.ripdpi.data.xray.toXrayProfileRecordPair
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileMutationStores
    @Inject
    constructor(
        val settings: AppSettingsRepository,
        val relayProfiles: RelayProfileStore,
        val relayCredentials: RelayCredentialStore,
        val warpProfiles: WarpProfileStore,
        val warpCredentials: WarpCredentialStore,
        val warpEndpoints: WarpEndpointStore,
        val xrayMetadata: XrayProfileMetadataStore,
        val xraySecrets: XrayProfileSecretStore,
        val xraySelection: XrayProviderSelectionStore,
        val profileSelection: ProfileSelectionStore,
        val groupCatalog: ProxyGroupCatalog,
        val selectorChoice: com.poyka.ripdpi.data.selector.SelectorChoicePersistence,
    )

data class ExpectedRelayProfileState(
    val profile: RelayProfileRecord?,
    val credentials: RelayCredentialRecord?,
)

interface ProfileMutationCoordinator :
    ProfileMutationRecoveryAccess,
    ProfileBackupMutationCoordinator,
    RelayUpsertMutationCoordinator,
    WarpRuntimeRevisionReader,
    SubscriptionRefreshMutationCoordinator,
    StandaloneAwgMutationCoordinator {
    override suspend fun captureMutation(origin: ProfileMutationOrigin): ProfileMutationPreparation

    suspend fun prepareStandaloneSelectionForStart(
        guard: ProfileTransactionGuard,
        update: AppSettings.Builder.() -> Unit,
    ): Boolean

    suspend fun upsertAwg(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profile: AwgProfileEntity,
        secrets: AwgSecrets,
    ): ProfileMutationOutcome

    suspend fun deleteAwg(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profileId: String,
    ): ProfileMutationOutcome

    suspend fun upsertWarp(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profile: WarpProfile,
        credentials: WarpCredentials,
        endpoints: List<WarpEndpointCacheEntry>,
        activate: Boolean,
        scannerMode: String,
    ): ProfileMutationOutcome

    /** Runtime provisioning can commit only against its captured profile and credentials. */
    suspend fun upsertWarpForRuntimeProvisioning(
        profile: WarpProfile,
        credentials: WarpCredentials,
        endpoints: List<WarpEndpointCacheEntry>,
        activate: Boolean,
        scannerMode: String,
        expectedCredentials: WarpCredentials,
        expectedRevision: Long,
    ): Boolean

    suspend fun deleteWarp(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profileId: String,
        clearActive: Boolean,
    ): ProfileMutationOutcome

    suspend fun deactivateWarp(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profileId: String,
    ): ProfileMutationOutcome
}

/** Applies related profile data in one Room transaction and publishes committed runtime state. */
@Singleton
class ProfileMutationRecoveryCoordinator private constructor(
    private val runtime: ProfileMutationRuntime,
) : ProfileMutationCoordinator,
    XrayProviderMutationCoordinator,
    ProfileBackupMutationCoordinator by ProfileBackupMutations(runtime),
    StandaloneAwgMutationCoordinator by runtime.standalone,
    RelayUpsertMutationCoordinator by RelayUpsertWriter(runtime),
    SubscriptionRefreshMutationCoordinator by runtime.subscriptionRefresh,
    WarpRuntimeRevisionReader by runtime.warpRevisions {
    @Inject
    internal constructor(
        stores: ProfileMutationStores,
        database: ProfileDatabase,
        mutationGeneration: ProfileMutationGenerationPublisher,
        pauseAuthority: PauseIntentAuthority,
        activeGroup: SelectorActiveGroupStore,
    ) : this(ProfileMutationRuntime(stores, database, mutationGeneration, pauseAuthority, activeGroup))

    private val stores get() = runtime.stores
    private val mutationGeneration get() = runtime.mutationGeneration
    private val intentProjection get() = runtime.intentProjection
    private val pauseAuthority get() = runtime.pauseAuthority
    private val warpRevisions get() = runtime.warpRevisions
    private val mutex get() = runtime.mutex
    private val catalog get() = runtime.catalog
    private val afterImageWriter get() = runtime.afterImageWriter

    override suspend fun captureMutation(origin: ProfileMutationOrigin): ProfileMutationPreparation =
        mutex.withLock {
            runtime.recover()
            ProfileMutationPreparation(origin, pauseAuthority.reference())
        }

    override suspend fun commitMutationIntent(preparation: ProfileMutationPreparation): ProfileMutationOutcome =
        mutex.withLock {
            runtime.recover()
            pauseAuthority.invalidateForMutation(
                preparation.origin,
                java.util.UUID
                    .randomUUID()
                    .toString(),
                preparation.expectedPauseAuthority,
            )
        }

    override suspend fun activateSelector(
        preparation: ProfileMutationPreparation,
        groupId: String,
        memberId: String,
        choice: com.poyka.ripdpi.data.selector.SelectorChoicePersistence,
    ): ProfileMutationOutcome =
        mutex.withLock {
            runtime.recover()
            require(preparation.origin == ProfileMutationOrigin.ExplicitActivation)
            require(
                choice === stores.selectorChoice,
            ) { "Selector publication uses the catalog's single choice owner" }
            val mode = Mode.fromString(stores.settings.snapshot().ripdpiMode)
            afterImageWriter.executeRecovered(
                preparation,
                SelectorSelectIntent(groupId, memberId, mode.preferenceValue),
            )
        }

    /** The original measurement lease and catalog are consumed in the same reservation transaction. */
    suspend fun selectMeasuredProfile(
        lease: ProfileUtilitySelectionLease,
        mode: Mode,
    ): ProfileUtilitySelectionResult =
        mutex.withLock {
            runtime.recover()
            if (!lease.refreshEnvironment() ||
                !lease.payloadMatches()
            ) {
                return@withLock ProfileUtilitySelectionResult.Superseded
            }
            val payload = lease.payload
            if (!stores.measuredPayloadMatches(
                    lease.reference,
                    payload,
                )
            ) {
                return@withLock ProfileUtilitySelectionResult.Superseded
            }
            val intent = intentProjection.measuredIntent(payload, mode)
            var reserved: ProfileActivationReceipt? = null
            runCatching {
                val receipt =
                    runtime.database.withAuthority(
                        pauseAuthority,
                        afterCommit = { receipt ->
                            if (receipt != null && payload is ProfileUtilitySelectionPayload.Selector) {
                                runtime.activeGroup.publishCommittedChoice(
                                    payload.groupId,
                                    SelectorChoiceOrigin.Manual(receipt),
                                )
                            }
                        },
                    ) { rows ->
                        if (!measuredLeaseMatches(lease, payload, rows)) return@withAuthority null
                        val selected =
                            pauseAuthority.reserveMeasuredActivation(
                                lease.expectedAuthority,
                                lease.catalogGeneration,
                                lease.reference,
                                java.util.UUID
                                    .randomUUID()
                                    .toString(),
                            ) ?: return@withAuthority null
                        reserved = selected
                        val measured =
                            MeasuredUtilitySelectionIntent(
                                MeasuredActivationReservation(
                                    selected.authority,
                                    selected.commandId,
                                    mode.preferenceValue,
                                ),
                                intent,
                            )
                        ProfileMutationRowWriter(rows, pauseAuthority).applyMeasured(measured, selected)
                        selected
                    } ?: return@runCatching ProfileUtilitySelectionResult.Superseded
                catalog.publish()
                mutationGeneration.completed()
                if (pauseAuthority.isCurrent(receipt)) {
                    ProfileUtilitySelectionResult.Selected(
                        receipt,
                        checkNotNull(pauseAuthority.states.value).profileUtility.catalogGeneration,
                    )
                } else {
                    ProfileUtilitySelectionResult.Superseded
                }
            }.onFailure { failure ->
                reserved?.let { receipt ->
                    runCatching { pauseAuthority.cancelProfileActivation(receipt) }
                        .exceptionOrNull()
                        ?.let(failure::addSuppressed)
                }
            }.getOrThrow()
        }

    private fun measuredLeaseMatches(
        lease: ProfileUtilitySelectionLease,
        payload: ProfileUtilitySelectionPayload,
        rows: ProfileRows,
    ): Boolean {
        val utility = checkNotNull(pauseAuthority.states.value).profileUtility
        if (!utility.catalogReady || utility.catalogGeneration != lease.catalogGeneration ||
            lease.reference !in utility.catalog
        ) {
            return false
        }
        return pauseAuthority.snapshotAuthority() == lease.expectedAuthority &&
            lease.environmentMatchesNow() && rows.measuredPayloadMatches(lease.reference, payload)
    }

    override suspend fun recover() = mutex.withLock { runtime.recover() }

    override suspend fun <T> readRecovered(block: suspend () -> T): T =
        mutex.withLock {
            runtime.recover()
            block()
        }

    private val catalogTransactions =
        ProfileUtilityCatalogTransactions(
            mutex,
            pauseAuthority,
            catalog,
            mutationGeneration,
            runtime::recover,
        )

    override suspend fun <T> mutateCatalog(
        preparation: ProfileMutationPreparation,
        block: suspend () -> T,
    ): T = catalogTransactions.mutate(preparation, block)

    override suspend fun <T> mutateReservedCatalog(
        receipt: DurableCommandReceipt,
        block: suspend () -> T,
    ): T = catalogTransactions.replace(receipt, block)

    override suspend fun runReset(block: suspend (DurableCommandReceipt) -> Unit) =
        mutex.withLock {
            // Explicit destructive Reset is the only migration exception:
            // establish a checked stop before discarding the marker.
            val receipt = pauseAuthority.reserveResetStop()
            runtime.database.resetProfiles(pauseAuthority)
            catalog.invalidate()
            catalogTransactions.withinReset(receipt) { block(receipt) }
            catalog.publish()
            warpRevisions.invalidateAll()
            mutationGeneration.completed()
            receipt
        }

    override suspend fun upsertAwg(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profile: AwgProfileEntity,
        secrets: AwgSecrets,
    ) = afterImageWriter.execute(
        preparation,
        AwgUpsertIntent(
            id = profile.id,
            name = profile.name,
            requestJson = profile.requestJson,
            updatedAt = profile.updatedAt,
            secrets = secrets,
        ),
    )

    override suspend fun deleteAwg(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profileId: String,
    ) = afterImageWriter.execute(preparation, AwgDeleteIntent(profileId))

    override suspend fun upsertXrayProvider(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profileId: String,
        profile: XrayProfile,
        selection: XrayProviderSelectionRecord,
        modeAfterImage: String,
    ) = afterImageWriter.execute(
        preparation,
        XrayProviderSelectIntent(
            records = profile.toXrayProfileRecordPair(profileId),
            selection = selection,
            modeAfterImage = modeAfterImage,
        ),
    )

    override suspend fun selectNativeProvider(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        selection: XrayProviderSelectionRecord,
        modeAfterImage: String,
    ) = afterImageWriter.execute(
        preparation,
        XrayProviderSelectIntent(
            records = null,
            selection = selection,
            modeAfterImage = modeAfterImage,
        ),
    )

    override suspend fun upsertWarp(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profile: WarpProfile,
        credentials: WarpCredentials,
        endpoints: List<WarpEndpointCacheEntry>,
        activate: Boolean,
        scannerMode: String,
    ) = afterImageWriter.execute(
        preparation,
        WarpUpsertIntent(profile, credentials, endpoints, activate, scannerMode),
    )

    override suspend fun upsertWarpForRuntimeProvisioning(
        profile: WarpProfile,
        credentials: WarpCredentials,
        endpoints: List<WarpEndpointCacheEntry>,
        activate: Boolean,
        scannerMode: String,
        expectedCredentials: WarpCredentials,
        expectedRevision: Long,
    ): Boolean =
        mutex.withLock {
            runtime.recover()
            if (warpRuntimeRevision(profile.id) != expectedRevision ||
                !stores.warpCredentials.load(profile.id).sameRuntimeProvisioningMaterial(expectedCredentials) ||
                stores.settings.snapshot().warpProfileId != profile.id
            ) {
                return@withLock false
            }
            val currentProfile = stores.warpProfiles.load(profile.id) ?: return@withLock false
            val currentCredentials = stores.warpCredentials.load(profile.id) ?: return@withLock false
            val mergedProfile =
                currentProfile.copy(
                    lastProvisionedAtEpochMillis = profile.lastProvisionedAtEpochMillis,
                )
            val mergedCredentials =
                credentials.copy(
                    displayName = currentCredentials.displayName,
                    license = currentCredentials.license,
                    accountKind = currentCredentials.accountKind,
                    zeroTrustOrg = currentCredentials.zeroTrustOrg,
                )
            afterImageWriter.executeRecovered(
                ProfileMutationPreparation(ProfileMutationOrigin.InternalReconcile, pauseAuthority.reference()),
                WarpUpsertIntent(mergedProfile, mergedCredentials, endpoints, false, scannerMode),
            )
            true
        }

    override suspend fun deleteWarp(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profileId: String,
        clearActive: Boolean,
    ) = afterImageWriter.execute(preparation, WarpDeleteIntent(profileId, clearActive))

    override suspend fun deactivateWarp(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profileId: String,
    ) = afterImageWriter.execute(preparation, WarpDeactivateIntent(profileId))

    override suspend fun prepareStandaloneSelectionForStart(
        guard: ProfileTransactionGuard,
        update: AppSettings.Builder.() -> Unit,
    ): Boolean =
        runtime.database.guardedWrite(guard, pauseAuthority) { rows ->
            rows.selection.setAwg(null)
            rows.settings.update(update)
        }
}

internal fun publishCommittedWarpRevision(
    warpRevisions: WarpRuntimeMutationRevisions,
    intent: ProfileMutationIntent,
    before: WarpCredentials?,
    beforeProfile: WarpProfile?,
) {
    when (intent) {
        is WarpUpsertIntent -> {
            if (!before.sameRuntimeProvisioningMaterial(intent.credentials) ||
                beforeProfile?.accountKind != intent.profile.accountKind ||
                beforeProfile.zeroTrustOrg != intent.profile.zeroTrustOrg
            ) {
                warpRevisions.changed(intent.profile.id)
            }
        }

        is WarpDeleteIntent -> {
            warpRevisions.changed(intent.profileId)
        }

        is WarpDeactivateIntent -> {
            warpRevisions.changed(intent.profileId)
        }

        is PrivateBackupReplaceIntent -> {
            warpRevisions.invalidateAll()
        }

        else -> {}
    }
}

private suspend fun ProfileMutationStores.measuredPayloadMatches(
    reference: ProfileUtilityReference,
    payload: ProfileUtilitySelectionPayload,
): Boolean =
    when (payload) {
        is ProfileUtilitySelectionPayload.Native -> {
            reference == ProfileUtilityReference.NativeRelay(payload.profile.id) &&
                payload.credentials.profileId == payload.profile.id &&
                relayProfiles.load(payload.profile.id) == payload.profile &&
                relayCredentials.load(payload.profile.id) == payload.credentials
        }

        is ProfileUtilitySelectionPayload.Xray -> {
            reference == ProfileUtilityReference.Xray(payload.profileId) &&
                xrayMetadata.load(payload.profileId) == payload.records.metadata &&
                xraySecrets.load(payload.profileId) == payload.records.secret
        }

        is ProfileUtilitySelectionPayload.Selector -> {
            val groups = groupCatalog.snapshot()
            reference == ProfileUtilityReference.SelectorMember(payload.groupId, payload.memberId) &&
                groups
                    .singleOrNull { it.id == payload.groupId }
                    ?.members
                    ?.singleOrNull { it.id == payload.memberId } == payload.member
        }
    }

private fun ProfileRows.measuredPayloadMatches(
    reference: ProfileUtilityReference,
    payload: ProfileUtilitySelectionPayload,
): Boolean =
    when (payload) {
        is ProfileUtilitySelectionPayload.Native -> {
            reference == ProfileUtilityReference.NativeRelay(payload.profile.id) &&
                payload.credentials.profileId == payload.profile.id &&
                relayProfiles.load(payload.profile.id) == payload.profile &&
                relayCredentials.load(payload.profile.id) == payload.credentials
        }

        is ProfileUtilitySelectionPayload.Xray -> {
            reference == ProfileUtilityReference.Xray(payload.profileId) &&
                xrayMetadata.load(payload.profileId) == payload.records.metadata &&
                xraySecrets.load(payload.profileId) == payload.records.secret
        }

        is ProfileUtilitySelectionPayload.Selector -> {
            reference == ProfileUtilityReference.SelectorMember(payload.groupId, payload.memberId) &&
                groups
                    .list()
                    .singleOrNull { it.id == payload.groupId }
                    ?.members
                    ?.singleOrNull { it.id == payload.memberId } ==
                payload.member
        }
    }

/** Shared write path for relay upserts; the expected-state check runs under the recovery mutex. */
private suspend fun ProfileMutationRuntime.writeRelayUpsert(
    preparation: ProfileMutationPreparation,
    expectedState: ExpectedRelayProfileState?,
    intent: RelayUpsertIntent,
): ProfileMutationOutcome {
    val profile = intent.profile
    return if (expectedState == null) {
        afterImageWriter.execute(preparation, intent)
    } else {
        mutex.withLock {
            recover()
            afterImageWriter.executeRecovered(preparation, intent) { rows ->
                require(
                    rows.relayProfiles.load(profile.id) == expectedState.profile &&
                        loadReplaceableCredential { rows.relayCredentials.load(profile.id) } ==
                        expectedState.credentials,
                ) { "Relay profile changed since editing began" }
            }
        }
    }
}

/** Relay profile writes; the editor commit variant merges its settings after-image. */
interface RelayUpsertMutationCoordinator {
    suspend fun upsertRelay(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profile: RelayProfileRecord,
        credentials: RelayCredentialRecord,
        enabled: Boolean,
        select: Boolean,
        settingsAfterImage: AppSettings? = null,
        modeAfterImage: String? = null,
        xraySelectionAfterImage: XrayProviderSelectionRecord? = null,
        expectedState: ExpectedRelayProfileState? = null,
    ): ProfileMutationOutcome

    /**
     * Saves a relay editor commit whose [settingsAfterImage] was built from [settingsBaseImage].
     * Only the settings fields the edit changed are applied, so a concurrent settings write survives.
     */
    suspend fun upsertRelayEdit(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profile: RelayProfileRecord,
        credentials: RelayCredentialRecord,
        enabled: Boolean,
        settingsBaseImage: AppSettings,
        settingsAfterImage: AppSettings,
        expectedState: ExpectedRelayProfileState,
    ): ProfileMutationOutcome
}

private class RelayUpsertWriter(
    private val runtime: ProfileMutationRuntime,
) : RelayUpsertMutationCoordinator {
    private val intentProjection get() = runtime.intentProjection

    override suspend fun upsertRelay(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profile: RelayProfileRecord,
        credentials: RelayCredentialRecord,
        enabled: Boolean,
        select: Boolean,
        settingsAfterImage: AppSettings?,
        modeAfterImage: String?,
        xraySelectionAfterImage: XrayProviderSelectionRecord?,
        expectedState: ExpectedRelayProfileState?,
    ): ProfileMutationOutcome =
        runtime.writeRelayUpsert(
            preparation,
            expectedState,
            intentProjection.relayUpsertIntent(
                profile = profile,
                credentials = credentials,
                enabled = enabled,
                select = select,
                settingsAfterImage = settingsAfterImage,
                modeAfterImage = modeAfterImage,
                xraySelectionAfterImage = xraySelectionAfterImage,
                clearSelectorOwnership = preparation.origin == ProfileMutationOrigin.ExplicitActivation,
            ),
        )

    override suspend fun upsertRelayEdit(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profile: RelayProfileRecord,
        credentials: RelayCredentialRecord,
        enabled: Boolean,
        settingsBaseImage: AppSettings,
        settingsAfterImage: AppSettings,
        expectedState: ExpectedRelayProfileState,
    ): ProfileMutationOutcome =
        runtime.writeRelayUpsert(
            preparation,
            expectedState,
            intentProjection.relayUpsertIntent(
                profile = profile,
                credentials = credentials,
                enabled = enabled,
                select = true,
                settingsAfterImage = settingsAfterImage,
                modeAfterImage = null,
                xraySelectionAfterImage = null,
                clearSelectorOwnership = preparation.origin == ProfileMutationOrigin.ExplicitActivation,
                settingsBaseImage = settingsBaseImage,
            ),
        )
}
