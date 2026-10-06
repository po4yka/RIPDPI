package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.awg.AwgCredentialStore
import com.poyka.ripdpi.data.awg.AwgProfileDao
import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.data.backup.BackupPrivateDataV1
import com.poyka.ripdpi.data.boot.BootSessionStateStore
import com.poyka.ripdpi.data.xray.XrayProfile
import com.poyka.ripdpi.data.xray.XrayProfileMetadataStore
import com.poyka.ripdpi.data.xray.XrayProfileSecretStore
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import com.poyka.ripdpi.data.xray.XrayProviderSelectionStore
import com.poyka.ripdpi.data.xray.toXrayProfileRecordPair
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.serialization.RipDpiContractJson
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
        val bootSession: BootSessionStateStore,
        val groupBlob: ProxyGroupBlobStore,
        val selectorChoice: com.poyka.ripdpi.data.selector.SelectorChoicePersistence,
    )

data class ExpectedRelayProfileState(
    val profile: RelayProfileRecord?,
    val credentials: RelayCredentialRecord?,
)

interface ProfileMutationCoordinator :
    ProfileMutationRecoveryAccess,
    WarpRuntimeRevisionReader,
    StandaloneAwgMutationCoordinator {
    override suspend fun captureMutation(origin: ProfileMutationOrigin): ProfileMutationPreparation

    suspend fun upsertAwg(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profile: AwgProfileEntity,
        secrets: AwgSecrets,
    ): ProfileMutationOutcome

    suspend fun deleteAwg(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        profileId: String,
    ): ProfileMutationOutcome

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

    suspend fun replacePrivateBackup(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        data: BackupPrivateDataV1,
        rollbackData: BackupPrivateDataV1? = null,
    ): ProfileMutationOutcome
}

/** Replays encrypted after-images until every store in a profile mutation agrees. */
@Singleton
class ProfileMutationRecoveryCoordinator private constructor(
    private val runtime: ProfileMutationRuntime,
) : ProfileMutationCoordinator,
    XrayProviderMutationCoordinator,
    StandaloneAwgMutationCoordinator by runtime.standalone,
    WarpRuntimeRevisionReader by runtime.warpRevisions {
    @Inject
    constructor(
        stores: ProfileMutationStores,
        awgProfiles: AwgProfileDao,
        awgCredentials: AwgCredentialStore,
        journal: ProfileMutationJournal,
        mutationGeneration: ProfileMutationGenerationPublisher,
        pauseAuthority: PauseIntentAuthority,
    ) : this(ProfileMutationRuntime(stores, awgProfiles, awgCredentials, journal, mutationGeneration, pauseAuthority))

    private val stores get() = runtime.stores
    private val journal get() = runtime.journal
    private val mutationGeneration get() = runtime.mutationGeneration
    private val intentProjection get() = runtime.intentProjection
    private val replayWriter get() = runtime.replayWriter
    private val pauseAuthority get() = runtime.pauseAuthority
    private val warpRevisions get() = runtime.warpRevisions
    private val mutex get() = runtime.mutex
    private val catalog get() = runtime.catalog
    private val journalRecovery get() = runtime.journalRecovery
    private val afterImageWriter get() = runtime.afterImageWriter

    override suspend fun captureMutation(origin: ProfileMutationOrigin): ProfileMutationPreparation =
        mutex.withLock {
            journalRecovery.recover()
            ProfileMutationPreparation(origin, pauseAuthority.reference())
        }

    override suspend fun commitMutationIntent(preparation: ProfileMutationPreparation): ProfileMutationOutcome =
        mutex.withLock {
            journalRecovery.recover()
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
            journalRecovery.recover()
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
            journalRecovery.recover()
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
            val commandId =
                java.util.UUID
                    .randomUUID()
                    .toString()
            val outcome =
                pauseAuthority.intentLinearizer.serialize {
                    val utility = checkNotNull(pauseAuthority.states.value).profileUtility
                    val validCatalog =
                        utility.catalogReady && utility.catalogGeneration == lease.catalogGeneration &&
                            lease.reference in utility.catalog
                    if (!validCatalog || pauseAuthority.snapshotAuthority() != lease.expectedAuthority ||
                        !lease.environmentMatchesNow()
                    ) {
                        null
                    } else {
                        pauseAuthority.reserveMeasuredActivation(
                            lease.expectedAuthority,
                            lease.catalogGeneration,
                            lease.reference,
                            commandId,
                        )
                    }
                }
            val receipt = outcome ?: return@withLock ProfileUtilitySelectionResult.Superseded
            val measured =
                MeasuredUtilitySelectionIntent(
                    MeasuredActivationReservation(receipt.authority, receipt.commandId, mode.preferenceValue),
                    intent,
                )
            val pending =
                intentProjection
                    .pending(
                        ProfileMutationOrigin.ExplicitActivation,
                        receipt.authority,
                        measured,
                    ).copy(mutationId = receipt.commandId)
            runCatching {
                journal.prepare(pending)
                catalog.invalidate()
                replayWriter.replayMeasured(measured, receipt)
                catalog.publish()
                journal.complete(pending.mutationId)
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
                runCatching { pauseAuthority.cancelProfileActivation(receipt) }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
            }.getOrThrow()
        }

    override suspend fun recover() = mutex.withLock { journalRecovery.recover() }

    override suspend fun <T> readRecovered(block: suspend () -> T): T =
        mutex.withLock {
            journalRecovery.recover()
            block()
        }

    private val catalogTransactions =
        ProfileUtilityCatalogTransactions(
            mutex,
            pauseAuthority,
            catalog,
            mutationGeneration,
            journalRecovery::recover,
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
            journal.clearForReset()
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
    ): ProfileMutationOutcome {
        val intent =
            intentProjection.relayUpsertIntent(
                profile = profile,
                credentials = credentials,
                enabled = enabled,
                select = select,
                settingsAfterImage = settingsAfterImage,
                modeAfterImage = modeAfterImage,
                xraySelectionAfterImage = xraySelectionAfterImage,
                clearSelectorOwnership = preparation.origin == ProfileMutationOrigin.ExplicitActivation,
            )
        return if (expectedState == null) {
            afterImageWriter.execute(preparation, intent)
        } else {
            mutex.withLock {
                journalRecovery.recover()
                require(
                    stores.relayProfiles.load(profile.id) == expectedState.profile &&
                        stores.relayCredentials.load(profile.id) == expectedState.credentials,
                ) { "Relay profile changed since editing began" }
                afterImageWriter.executeRecovered(preparation, intent)
            }
        }
    }

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
            journalRecovery.recover()
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

    override suspend fun replacePrivateBackup(
        preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
        data: BackupPrivateDataV1,
        rollbackData: BackupPrivateDataV1?,
    ): ProfileMutationOutcome {
        val target = PrivateBackupReplaceIntent(data = data, activeAwgProfileId = null)
        return if (rollbackData == null) {
            afterImageWriter.execute(preparation, target)
        } else {
            val rollbackAwgProfileId =
                stores.bootSession
                    .activeAwgProfileId()
                    ?.takeIf { profileId -> rollbackData.awgProfiles.any { it.id == profileId } }
            afterImageWriter.executeWithCompensation(
                preparation,
                target,
                PrivateBackupReplaceIntent(
                    data = rollbackData,
                    activeAwgProfileId = rollbackAwgProfileId,
                ),
            )
        }
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
                beforeProfile?.zeroTrustOrg != intent.profile.zeroTrustOrg
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

        else -> {
            Unit
        }
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
            val groups =
                groupBlob
                    .read()
                    ?.let {
                        RipDpiContractJson.decodeFromString(
                            kotlinx.serialization.builtins.ListSerializer(ProxyGroup.serializer()),
                            it,
                        )
                    }.orEmpty()
            reference == ProfileUtilityReference.SelectorMember(payload.groupId, payload.memberId) &&
                groups
                    .singleOrNull { it.id == payload.groupId }
                    ?.members
                    ?.singleOrNull { it.id == payload.memberId } == payload.member
        }
    }
