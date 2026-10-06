package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.awg.AwgCredentialStore
import com.poyka.ripdpi.data.awg.AwgProfileDao
import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.data.backup.BackupPrivateDataV1
import com.poyka.ripdpi.data.xray.XrayProfileRecordPair
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.serialization.RipDpiContractJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Base64

/** Pure conversion of captured mutation input into the journal's immutable after-image. */
internal class ProfileMutationIntentProjection {
    private val json = RipDpiContractJson

    fun relayUpsertIntent(
        profile: RelayProfileRecord,
        credentials: RelayCredentialRecord,
        enabled: Boolean,
        select: Boolean,
        settingsAfterImage: AppSettings?,
        modeAfterImage: String?,
        xraySelectionAfterImage: XrayProviderSelectionRecord?,
        clearSelectorOwnership: Boolean,
    ) = RelayUpsertIntent(
        profile = profile,
        credentials = credentials,
        enabled = enabled,
        select = select,
        settingsAfterImageBase64 = settingsAfterImage?.toByteArray()?.let(Base64.getEncoder()::encodeToString),
        modeAfterImage = modeAfterImage,
        xraySelectionAfterImage = xraySelectionAfterImage,
        clearSelectorOwnership = clearSelectorOwnership,
    )

    fun measuredIntent(
        payload: ProfileUtilitySelectionPayload,
        mode: Mode,
    ): ProfileMutationIntent =
        when (payload) {
            is ProfileUtilitySelectionPayload.Native -> {
                RelayUpsertIntent(
                    payload.profile,
                    payload.credentials,
                    true,
                    true,
                    modeAfterImage = mode.preferenceValue,
                    xraySelectionAfterImage = XrayProviderSelectionRecord(),
                    clearSelectorOwnership = true,
                )
            }

            is ProfileUtilitySelectionPayload.Selector -> {
                SelectorSelectIntent(
                    payload.groupId,
                    payload.memberId,
                    mode.preferenceValue,
                )
            }

            is ProfileUtilitySelectionPayload.Xray -> {
                XrayProviderSelectIntent(
                    selection =
                        XrayProviderSelectionRecord(
                            XrayProviderSelectionRecord.ProviderKindXray,
                            payload.profileId,
                        ),
                    modeAfterImage = mode.preferenceValue,
                )
            }
        }

    fun pending(
        origin: ProfileMutationOrigin,
        expected: PauseAuthorityRef,
        intent: ProfileMutationIntent,
    ) = PendingProfileMutation(
        origin = origin,
        expectedPauseAuthority = expected,
        family = intent.family,
        payload = json.encodeToString(ProfileMutationIntent.serializer(), intent),
    )
}

internal class ProfileMutationReplayWriter(
    private val stores: ProfileMutationStores,
    private val awgProfiles: AwgProfileDao,
    private val awgCredentials: AwgCredentialStore,
    private val authority: PauseIntentAuthority,
) {
    suspend fun replay(intent: ProfileMutationIntent) {
        when (intent) {
            is AwgUpsertIntent -> {
                replayAwgUpsert(intent)
            }

            is AwgDeleteIntent -> {
                replayAwgDelete(intent)
            }

            is RelayUpsertIntent -> {
                replayRelayUpsert(intent, null)
            }

            is WarpUpsertIntent -> {
                replayWarpUpsert(intent)
            }

            is WarpDeleteIntent -> {
                replayWarpDelete(intent)
            }

            is WarpDeactivateIntent -> {
                replayWarpDeactivate(intent)
            }

            is PrivateBackupReplaceIntent -> {
                replayPrivateBackup(intent)
            }

            is XrayProviderSelectIntent -> {
                replayXrayProviderSelect(intent, null)
            }

            is MeasuredUtilitySelectionIntent -> {
                replay(intent.afterImage)
            }

            is StandaloneAwgSelectIntent -> {
                replayStandaloneAwg(intent, null)
            }

            is StandaloneAwgCompensateIntent -> {
                replayStandaloneCompensation(intent)
            }

            is StandaloneAwgClearIntent -> {
                authority.intentLinearizer.serialize {
                    if (matchesOwner(intent.authority, intent.commandId) &&
                        stores.bootSession.activeAwgProfileId() == intent.expectedProfileId
                    ) {
                        stores.bootSession.setActiveAwgProfileId(null)
                    }
                }
            }

            is SelectorSelectIntent -> {
                replaySelector(
                    intent,
                    com.poyka.ripdpi.data.selector.SelectorChoiceOrigin.Reconstruction,
                    null,
                )
            }
        }
    }

    suspend fun replayMeasured(
        intent: MeasuredUtilitySelectionIntent,
        receipt: ProfileActivationReceipt,
    ) {
        val owner = ProfileSelectionMetadataOwner(receipt.authority, receipt.commandId)
        when (val selected = intent.afterImage) {
            is SelectorSelectIntent -> {
                replaySelector(
                    selected,
                    com.poyka.ripdpi.data.selector.SelectorChoiceOrigin
                        .Manual(receipt),
                    owner,
                )
            }

            is RelayUpsertIntent -> {
                replayRelayUpsert(selected, owner)
            }

            is XrayProviderSelectIntent -> {
                replayXrayProviderSelect(selected, owner)
            }

            else -> {
                error("Measured activation requires its captured selection after-image")
            }
        }
    }

    suspend fun replayOwned(
        intent: ProfileMutationIntent,
        receipt: ProfileActivationReceipt,
    ) {
        when (intent) {
            is SelectorSelectIntent -> {
                replaySelector(
                    intent,
                    com.poyka.ripdpi.data.selector.SelectorChoiceOrigin
                        .Manual(receipt),
                    ProfileSelectionMetadataOwner(receipt.authority, receipt.commandId),
                )
            }

            is WarpUpsertIntent -> {
                replayWarpOwned(intent, receipt)
            }

            is StandaloneAwgSelectIntent -> {
                replayStandaloneAwg(intent, receipt)
            }

            else -> {
                replay(intent)
            }
        }
    }

    suspend fun replayMeasuredReconstructed(intent: MeasuredUtilitySelectionIntent) {
        val owner = ProfileSelectionMetadataOwner(intent.reservation.authority, intent.reservation.commandId)
        val current = authority.snapshotAuthority().command
        if (current?.origin !is RuntimeCommandOrigin.MeasuredActivation || !owner.matches(authority)) return
        when (val selected = intent.afterImage) {
            is SelectorSelectIntent -> {
                replaySelector(
                    selected,
                    com.poyka.ripdpi.data.selector.SelectorChoiceOrigin.Reconstruction,
                    owner,
                )
            }

            is RelayUpsertIntent -> {
                replayRelayUpsert(selected, owner)
            }

            is XrayProviderSelectIntent -> {
                replayXrayProviderSelect(selected, owner)
            }

            else -> {
                error("Measured recovery requires its captured selection after-image")
            }
        }
    }

    suspend fun replayReconstructed(
        intent: ProfileMutationIntent,
        receipt: ProfileActivationReceipt,
    ) {
        when (intent) {
            is SelectorSelectIntent -> {
                replaySelector(
                    intent,
                    com.poyka.ripdpi.data.selector.SelectorChoiceOrigin.Reconstruction,
                    ProfileSelectionMetadataOwner(receipt.authority, receipt.commandId),
                )
            }

            is StandaloneAwgSelectIntent -> {
                replayStandaloneAwg(intent, receipt)
            }

            is WarpUpsertIntent -> {
                replayWarpOwned(intent, receipt)
            }

            else -> {
                error("Provider recovery requires an explicit selection after-image")
            }
        }
    }

    private fun matchesOwner(
        reference: PauseAuthorityRef,
        commandId: String,
    ): Boolean = authority.reference() == reference && authority.snapshotAuthority().command?.commandId == commandId

    private suspend fun replayStandaloneCompensation(intent: StandaloneAwgCompensateIntent) {
        if (!matchesOwner(intent.authority, intent.commandId) ||
            stores.bootSession.activeAwgProfileId() != intent.expectedProfileId
        ) {
            return
        }
        if (!stores.warpProfiles.setActiveProfileIdOwned(
                intent.before.warpProfileId,
                authority,
                intent.authority,
                intent.commandId,
            )
        ) {
            return
        }
        stores.settings.update {
            authority.intentLinearizer.serialize {
                if (matchesOwner(intent.authority, intent.commandId)) setWarpEnabled(intent.before.warpEnabled)
            }
        }
        authority.intentLinearizer.serialize {
            if (matchesOwner(intent.authority, intent.commandId) &&
                stores.bootSession.activeAwgProfileId() == intent.expectedProfileId
            ) {
                stores.bootSession.setActiveAwgProfileId(intent.before.awgProfileId)
                stores.xraySelection.update(intent.before.provider)
                stores.selectorChoice.restoreActiveGroup(intent.before.selectorGroupId)
            }
        }
    }

    private suspend fun replayStandaloneAwg(
        intent: StandaloneAwgSelectIntent,
        receipt: ProfileActivationReceipt?,
    ) {
        if (receipt != null &&
            !stores.warpProfiles.setActiveProfileIdOwned(null, authority, receipt.authority, receipt.commandId)
        ) {
            return
        }
        if (receipt == null) stores.warpProfiles.setActiveProfileId(null)
        stores.settings.update {
            authority.intentLinearizer.serialize {
                if (receipt == null || authority.isCurrent(receipt)) setWarpEnabled(false)
            }
        }
        authority.intentLinearizer.serialize {
            if (receipt == null || authority.isCurrent(receipt)) {
                stores.selectorChoice.clearStandalone()
                stores.bootSession.setActiveAwgProfileId(intent.profileId)
                stores.xraySelection.update(XrayProviderSelectionRecord())
            }
        }
    }

    private suspend fun replayWarpOwned(
        intent: WarpUpsertIntent,
        receipt: ProfileActivationReceipt,
    ) {
        stores.warpProfiles.save(intent.profile)
        stores.warpCredentials.save(intent.profile.id, intent.credentials)
        stores.warpEndpoints.clearProfile(intent.profile.id)
        intent.endpoints.forEach { stores.warpEndpoints.save(it) }
        if (!authority.isCurrent(receipt)) return
        if (!stores.warpProfiles.setActiveProfileIdOwned(
                intent.profile.id,
                authority,
                receipt.authority,
                receipt.commandId,
            )
        ) {
            return
        }
        stores.settings.update {
            authority.intentLinearizer.serialize {
                if (authority.isCurrent(receipt)) applyWarpAfterImage(intent.profile, intent.scannerMode)
            }
        }
        authority.intentLinearizer.serialize {
            if (authority.isCurrent(receipt)) {
                stores.selectorChoice.clearStandalone()
                stores.bootSession.setActiveAwgProfileId(null)
                stores.xraySelection.update(XrayProviderSelectionRecord())
            }
        }
    }

    private suspend fun replaySelector(
        intent: SelectorSelectIntent,
        origin: com.poyka.ripdpi.data.selector.SelectorChoiceOrigin,
        owner: ProfileSelectionMetadataOwner?,
    ) {
        if (owner != null &&
            !stores.warpProfiles.setActiveProfileIdOwned(null, authority, owner.authority, owner.commandId)
        ) {
            return
        }
        if (owner == null) stores.warpProfiles.setActiveProfileId(null)
        stores.settings.update {
            authority.intentLinearizer.serialize {
                if (owner == null || owner.matches(authority)) {
                    setRipdpiMode(intent.mode)
                    setWarpEnabled(false)
                    setSimpleFailoverAwgProfileId("")
                }
            }
        }
        authority.intentLinearizer.serialize {
            if (owner == null || owner.matches(authority)) {
                stores.xraySelection.update(XrayProviderSelectionRecord())
                stores.bootSession.setActiveAwgProfileId(null)
                stores.selectorChoice.commitMember(intent.groupId, intent.memberId, origin)
            }
        }
    }

    private suspend fun replayAwgUpsert(intent: AwgUpsertIntent) {
        awgCredentials.save(intent.id, intent.secrets)
        awgProfiles.upsertProfile(intent.toEntity())
    }

    private suspend fun replayAwgDelete(intent: AwgDeleteIntent) {
        if (stores.bootSession.activeAwgProfileId() == intent.profileId) {
            stores.bootSession.setActiveAwgProfileId(null)
        }
        awgCredentials.clear(intent.profileId)
        awgProfiles.getProfile(intent.profileId)?.let { awgProfiles.deleteProfile(it) }
    }

    private suspend fun replayRelayUpsert(
        intent: RelayUpsertIntent,
        owner: ProfileSelectionMetadataOwner?,
    ) {
        if (owner == null && intent.clearSelectorOwnership) stores.selectorChoice.clearStandalone()
        stores.relayProfiles.save(intent.profile)
        stores.relayCredentials.save(intent.credentials)
        authority.intentLinearizer.serialize {
            if (owner == null || owner.matches(authority)) {
                if (owner != null && intent.clearSelectorOwnership) stores.selectorChoice.clearStandalone()
                intent.xraySelectionAfterImage?.let(stores.xraySelection::update)
            }
        }
        if (intent.settingsAfterImageBase64 != null) {
            stores.settings.replace(
                AppSettings.parseFrom(Base64.getDecoder().decode(intent.settingsAfterImageBase64)),
            )
        } else if (intent.select || intent.modeAfterImage != null) {
            stores.settings.update {
                authority.intentLinearizer.serialize {
                    if (owner == null || owner.matches(authority)) {
                        if (intent.select) applyRelayAfterImage(intent.profile, intent.enabled)
                        intent.modeAfterImage?.let(::setRipdpiMode)
                    }
                }
            }
        }
    }

    private suspend fun replayXrayProviderSelect(
        intent: XrayProviderSelectIntent,
        owner: ProfileSelectionMetadataOwner?,
    ) {
        intent.records?.let { records ->
            stores.xraySecrets.save(records.secret)
            stores.xrayMetadata.save(records.metadata)
        }
        authority.intentLinearizer.serialize {
            if (owner == null || owner.matches(authority)) {
                stores.selectorChoice.clearStandalone()
                stores.xraySelection.update(intent.selection)
            }
        }
        stores.settings.update {
            authority.intentLinearizer.serialize {
                if (owner == null || owner.matches(authority)) setRipdpiMode(intent.modeAfterImage)
            }
        }
    }

    private suspend fun replayPrivateBackup(intent: PrivateBackupReplaceIntent) {
        val data = intent.data
        stores.bootSession.setActiveAwgProfileId(null)
        stores.warpProfiles.setActiveProfileId(null)
        stores.xraySelection.update(XrayProviderSelectionRecord())

        stores.relayCredentials.clearAll()
        stores.relayProfiles.clearAll()
        data.relayCredentials.forEach { stores.relayCredentials.save(it) }
        data.relayProfiles.forEach { stores.relayProfiles.save(it) }

        stores.warpEndpoints.clearAll()
        stores.warpCredentials.clearAll()
        stores.warpProfiles.clearAll()
        data.warpCredentials.forEach { stores.warpCredentials.save(it.profileId, it) }
        data.warpProfiles.forEach { stores.warpProfiles.save(it) }
        stores.warpProfiles.setActiveProfileId(data.warpActiveProfileId)

        awgCredentials.clearAll()
        awgProfiles.deleteAll()
        data.awgProfiles.forEach { profile ->
            profile.secrets?.let { awgCredentials.save(profile.id, it) }
            awgProfiles.upsertProfile(profile.toEntity())
        }

        stores.xraySecrets.clearAll()
        stores.xrayMetadata.clearAll()
        data.xraySecrets.forEach { stores.xraySecrets.save(it) }
        data.xrayMetadata.forEach { stores.xrayMetadata.save(it) }
        stores.xraySelection.update(data.xraySelection)

        intent.activeAwgProfileId
            ?.takeIf { profileId -> data.awgProfiles.any { it.id == profileId } }
            ?.let(stores.bootSession::setActiveAwgProfileId)
    }

    private suspend fun replayWarpUpsert(intent: WarpUpsertIntent) {
        stores.warpProfiles.save(intent.profile)
        stores.warpCredentials.save(intent.profile.id, intent.credentials)
        stores.warpEndpoints.clearProfile(intent.profile.id)
        intent.endpoints.forEach { stores.warpEndpoints.save(it) }
        if (intent.activate) {
            stores.warpProfiles.setActiveProfileId(intent.profile.id)
            stores.settings.update { applyWarpAfterImage(intent.profile, intent.scannerMode) }
        }
    }

    private suspend fun replayWarpDelete(intent: WarpDeleteIntent) {
        if (intent.clearActive) {
            stores.warpProfiles.setActiveProfileId(null)
            stores.settings.update { clearWarpAfterImage() }
        }
        stores.warpEndpoints.clearProfile(intent.profileId)
        stores.warpCredentials.clear(intent.profileId)
        stores.warpProfiles.remove(intent.profileId)
    }

    private suspend fun replayWarpDeactivate(intent: WarpDeactivateIntent) {
        if (stores.warpProfiles.activeProfileId() == intent.profileId ||
            stores.settings.snapshot().warpProfileId == intent.profileId
        ) {
            stores.warpProfiles.setActiveProfileId(null)
            stores.settings.update { clearWarpAfterImage() }
        }
    }
}

internal const val ProfileMutationIntentSchemaVersion = 2

@Serializable
internal sealed interface ProfileMutationIntent {
    val family: ProfileMutationFamily
}

@Serializable
@SerialName("awg_upsert")
internal data class AwgUpsertIntent(
    val id: String,
    val name: String,
    val requestJson: String,
    val updatedAt: Long,
    val secrets: AwgSecrets,
) : ProfileMutationIntent {
    override val family: ProfileMutationFamily = ProfileMutationFamily.Awg

    fun toEntity() = AwgProfileEntity(id = id, name = name, requestJson = requestJson, updatedAt = updatedAt)
}

@Serializable
@SerialName("awg_delete")
internal data class AwgDeleteIntent(
    val profileId: String,
) : ProfileMutationIntent {
    override val family: ProfileMutationFamily = ProfileMutationFamily.Awg
}

@Serializable
@SerialName("relay_upsert")
internal data class RelayUpsertIntent(
    val profile: RelayProfileRecord,
    val credentials: RelayCredentialRecord,
    val enabled: Boolean,
    val select: Boolean,
    val settingsAfterImageBase64: String? = null,
    val modeAfterImage: String? = null,
    val xraySelectionAfterImage: XrayProviderSelectionRecord? = null,
    val clearSelectorOwnership: Boolean = false,
) : ProfileMutationIntent {
    override val family: ProfileMutationFamily = ProfileMutationFamily.Relay
}

@Serializable
@SerialName("xray_provider_select")
internal data class XrayProviderSelectIntent(
    val records: XrayProfileRecordPair? = null,
    val selection: XrayProviderSelectionRecord,
    val modeAfterImage: String,
) : ProfileMutationIntent {
    override val family: ProfileMutationFamily = ProfileMutationFamily.Xray
}

@Serializable
@SerialName("private_backup_replace")
internal data class PrivateBackupReplaceIntent(
    val data: BackupPrivateDataV1,
    val activeAwgProfileId: String? = null,
) : ProfileMutationIntent {
    override val family: ProfileMutationFamily = ProfileMutationFamily.Backup
}

@Serializable
@SerialName("warp_upsert")
internal data class WarpUpsertIntent(
    val profile: WarpProfile,
    val credentials: WarpCredentials,
    val endpoints: List<WarpEndpointCacheEntry>,
    val activate: Boolean,
    val scannerMode: String,
) : ProfileMutationIntent {
    override val family: ProfileMutationFamily = ProfileMutationFamily.Warp
}

@Serializable
@SerialName("warp_delete")
internal data class WarpDeleteIntent(
    val profileId: String,
    val clearActive: Boolean,
) : ProfileMutationIntent {
    override val family: ProfileMutationFamily = ProfileMutationFamily.Warp
}

@Serializable
@SerialName("warp_deactivate")
internal data class WarpDeactivateIntent(
    val profileId: String,
) : ProfileMutationIntent {
    override val family: ProfileMutationFamily = ProfileMutationFamily.Warp
}

private fun AppSettings.Builder.applyWarpAfterImage(
    profile: WarpProfile,
    scannerMode: String,
) {
    setWarpProfileId(profile.id)
    setWarpAccountKind(normalizeWarpAccountKind(profile.accountKind))
    setWarpZeroTrustOrg(profile.zeroTrustOrg)
    setWarpSetupState(normalizeWarpSetupState(profile.setupState))
    setWarpLastScannerMode(normalizeWarpScannerMode(scannerMode))
}

private fun AppSettings.Builder.clearWarpAfterImage() {
    setWarpProfileId(DefaultWarpProfileId)
    setWarpAccountKind(WarpAccountKindConsumerFree)
    setWarpZeroTrustOrg("")
    setWarpSetupState(WarpSetupStateNotConfigured)
    setWarpLastScannerMode(WarpScannerModeAutomatic)
}

/** Contains only the original command identity; transient network proof is never serialized. */
@Serializable
internal data class MeasuredActivationReservation(
    val authority: PauseAuthorityRef,
    val commandId: String,
    val mode: String,
) {
    init {
        require(commandId.isNotBlank() && Mode.fromString(mode).preferenceValue == mode)
    }
}

@Serializable
@SerialName("measured_utility_selection")
internal data class MeasuredUtilitySelectionIntent(
    val reservation: MeasuredActivationReservation,
    val afterImage: ProfileMutationIntent,
) : ProfileMutationIntent {
    override val family: ProfileMutationFamily get() = afterImage.family

    init {
        require(
            afterImage is RelayUpsertIntent || afterImage is XrayProviderSelectIntent ||
                afterImage is SelectorSelectIntent,
        )
    }
}

@Serializable
@SerialName("selector_select")
internal data class SelectorSelectIntent(
    val groupId: String,
    val memberId: String,
    val mode: String,
) : ProfileMutationIntent {
    override val family = ProfileMutationFamily.Relay
}

@Serializable
@SerialName("standalone_awg_select")
internal data class StandaloneAwgSelectIntent(
    val profileId: String,
) : ProfileMutationIntent {
    override val family = ProfileMutationFamily.Awg
}

@Serializable
internal data class StandaloneSelectionBeforeImage(
    val awgProfileId: String?,
    val provider: XrayProviderSelectionRecord,
    val selectorGroupId: String?,
    val warpProfileId: String?,
    val warpEnabled: Boolean,
)

@Serializable
@SerialName("standalone_awg_compensate")
internal data class StandaloneAwgCompensateIntent(
    val authority: PauseAuthorityRef,
    val commandId: String,
    val expectedProfileId: String,
    val before: StandaloneSelectionBeforeImage,
) : ProfileMutationIntent {
    override val family = ProfileMutationFamily.Awg
}

@Serializable
@SerialName("standalone_awg_clear")
internal data class StandaloneAwgClearIntent(
    val authority: PauseAuthorityRef,
    val commandId: String,
    val expectedProfileId: String,
) : ProfileMutationIntent {
    override val family = ProfileMutationFamily.Awg
}

/** Metadata-only journal identity; cannot bind, claim or dispatch a runtime. */
private data class ProfileSelectionMetadataOwner(
    val authority: PauseAuthorityRef,
    val commandId: String,
) {
    fun matches(current: PauseIntentAuthority): Boolean =
        current.reference() == authority && current.snapshotAuthority().command?.commandId == commandId
}
