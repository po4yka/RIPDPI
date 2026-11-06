package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.data.backup.BackupPrivateDataV1
import com.poyka.ripdpi.data.xray.XrayProfileRecordPair
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Base64

/** Pure conversion of captured mutation input into one transaction's immutable values. */
internal class ProfileMutationIntentProjection {
    fun relayUpsertIntent(
        profile: RelayProfileRecord,
        credentials: RelayCredentialRecord,
        enabled: Boolean,
        select: Boolean,
        settingsAfterImage: AppSettings?,
        modeAfterImage: String?,
        xraySelectionAfterImage: XrayProviderSelectionRecord?,
        clearSelectorOwnership: Boolean,
        settingsBaseImage: AppSettings? = null,
    ) = RelayUpsertIntent(
        profile = profile,
        credentials = credentials,
        enabled = enabled,
        select = select,
        settingsAfterImageBase64 = settingsAfterImage?.toByteArray()?.let(Base64.getEncoder()::encodeToString),
        settingsBaseImageBase64 = settingsBaseImage?.toByteArray()?.let(Base64.getEncoder()::encodeToString),
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
}

internal class ProfileMutationRowWriter(
    private val rows: com.poyka.ripdpi.data.persistence.ProfileRows,
    private val authority: PauseIntentAuthority,
) {
    fun apply(intent: ProfileMutationIntent) {
        when (intent) {
            is AwgUpsertIntent -> {
                saveAwg(intent)
            }

            is AwgDeleteIntent -> {
                if (rows.selection.awg() == intent.profileId) rows.selection.setAwg(null)
                rows.awgCredentials.clear(intent.profileId)
                rows.awgProfiles.getProfile(intent.profileId)?.let(rows.awgProfiles::deleteProfile)
            }

            is RelayUpsertIntent -> {
                saveRelay(intent)
            }

            is WarpUpsertIntent -> {
                saveWarp(intent)
            }

            is WarpDeleteIntent -> {
                if (intent.clearActive) clearWarp()
                rows.warpEndpoints.clearProfile(intent.profileId)
                rows.warpCredentials.clear(intent.profileId)
                rows.warpProfiles.remove(intent.profileId)
            }

            is WarpDeactivateIntent -> {
                if (rows.warpProfiles.activeProfileId() == intent.profileId ||
                    rows.settings.snapshot().warpProfileId == intent.profileId
                ) {
                    clearWarp()
                }
            }

            is PrivateBackupReplaceIntent -> {
                replacePrivate(intent)
            }

            is XrayProviderSelectIntent -> {
                selectXray(intent)
            }

            is MeasuredUtilitySelectionIntent -> {
                apply(intent.afterImage)
            }

            is StandaloneAwgSelectIntent -> {
                selectStandaloneAwg(intent)
            }

            is StandaloneAwgCompensateIntent -> {
                compensateStandaloneAwg(intent)
            }

            is StandaloneAwgClearIntent -> {
                clearStandaloneAwg(intent)
            }

            is SelectorSelectIntent -> {
                selectSelector(intent)
            }

            is SubscriptionRefreshIntent -> {
                rows.groups.list()
                intent.awgProfiles.forEach(::saveAwg)
                rows.groups.replace(intent.groups)
            }
        }
    }

    private fun selectXray(intent: XrayProviderSelectIntent) {
        intent.records?.let {
            rows.xrayMetadata.save(it.metadata)
            rows.xraySecrets.save(it.secret)
        }
        rows.selection.setActiveGroup(null)
        rows.selection.setXray(intent.selection)
        rows.settings.update { setRipdpiMode(intent.modeAfterImage) }
    }

    private fun selectStandaloneAwg(intent: StandaloneAwgSelectIntent) {
        rows.warpProfiles.setActiveProfileId(null)
        rows.settings.update { setWarpEnabled(false) }
        rows.selection.setActiveGroup(null)
        rows.selection.setAwg(intent.profileId)
        rows.selection.setXray(XrayProviderSelectionRecord())
    }

    private fun compensateStandaloneAwg(intent: StandaloneAwgCompensateIntent) {
        if (matchesOwner(intent.authority, intent.commandId) &&
            rows.selection.awg() == intent.expectedProfileId
        ) {
            rows.warpProfiles.setActiveProfileId(intent.before.warpProfileId)
            rows.settings.update { setWarpEnabled(intent.before.warpEnabled) }
            rows.selection.setAwg(intent.before.awgProfileId)
            rows.selection.setXray(intent.before.provider)
            rows.selection.setActiveGroup(intent.before.selectorGroupId)
        }
    }

    private fun clearStandaloneAwg(intent: StandaloneAwgClearIntent) {
        if (matchesOwner(intent.authority, intent.commandId) &&
            rows.selection.awg() == intent.expectedProfileId
        ) {
            rows.selection.setAwg(null)
        }
    }

    private fun selectSelector(intent: SelectorSelectIntent) {
        rows.warpProfiles.setActiveProfileId(null)
        rows.settings.update {
            setRipdpiMode(intent.mode)
            setWarpEnabled(false)
            setSimpleFailoverAwgProfileId("")
        }
        rows.selection.setXray(XrayProviderSelectionRecord())
        rows.selection.setAwg(null)
        rows.selection.setChoice(intent.groupId, intent.memberId, true)
        rows.selection.setActiveGroup(intent.groupId)
    }

    fun applyOwned(
        intent: ProfileMutationIntent,
        receipt: ProfileActivationReceipt,
    ) {
        if (!authority.isCurrent(receipt)) return
        apply(intent)
        if (intent is WarpUpsertIntent) {
            rows.selection.setActiveGroup(null)
            rows.selection.setAwg(null)
            rows.selection.setXray(XrayProviderSelectionRecord())
        }
    }

    fun applyMeasured(
        intent: MeasuredUtilitySelectionIntent,
        receipt: ProfileActivationReceipt,
    ) {
        if (authority.isCurrent(receipt)) apply(intent.afterImage)
    }

    fun applyMeasuredReconstructed(intent: MeasuredUtilitySelectionIntent) {
        if (authority.snapshotAuthority().command?.origin is RuntimeCommandOrigin.MeasuredActivation &&
            matchesOwner(intent.reservation.authority, intent.reservation.commandId)
        ) {
            apply(intent.afterImage)
        }
    }

    fun applyReconstructed(
        intent: ProfileMutationIntent,
        receipt: ProfileActivationReceipt,
    ) = applyOwned(intent, receipt)

    private fun matchesOwner(
        reference: PauseAuthorityRef,
        commandId: String,
    ) = authority.reference() == reference && authority.snapshotAuthority().command?.commandId == commandId

    private fun saveAwg(intent: AwgUpsertIntent) {
        rows.awgCredentials.save(intent.id, intent.secrets)
        rows.awgProfiles.upsertProfile(intent.toEntity())
    }

    private fun saveRelay(intent: RelayUpsertIntent) {
        rows.relayProfiles.save(intent.profile)
        rows.relayCredentials.save(intent.credentials)
        if (intent.clearSelectorOwnership) rows.selection.setActiveGroup(null)
        intent.xraySelectionAfterImage?.let(rows.selection::setXray)
        if (intent.settingsAfterImageBase64 != null) {
            val after = AppSettings.parseFrom(Base64.getDecoder().decode(intent.settingsAfterImageBase64))
            val base = intent.settingsBaseImageBase64?.let { AppSettings.parseFrom(Base64.getDecoder().decode(it)) }
            rows.settings.replace(
                if (base ==
                    null
                ) {
                    after
                } else {
                    mergeChangedSettingsFields(base, after, rows.settings.snapshot())
                },
            )
        } else if (intent.select || intent.modeAfterImage != null) {
            rows.settings.update {
                if (intent.select) applyRelayAfterImage(intent.profile, intent.enabled)
                intent.modeAfterImage?.let(::setRipdpiMode)
            }
        }
    }

    private fun saveWarp(intent: WarpUpsertIntent) {
        rows.warpProfiles.save(intent.profile)
        rows.warpCredentials.save(intent.profile.id, intent.credentials)
        rows.warpEndpoints.clearProfile(intent.profile.id)
        intent.endpoints.forEach(rows.warpEndpoints::save)
        if (intent.activate) {
            rows.warpProfiles.setActiveProfileId(intent.profile.id)
            rows.settings.update { applyWarpAfterImage(intent.profile, intent.scannerMode) }
        }
    }

    private fun clearWarp() {
        rows.warpProfiles.setActiveProfileId(null)
        rows.settings.update { clearWarpAfterImage() }
    }

    private fun replacePrivate(intent: PrivateBackupReplaceIntent) {
        val data = intent.data
        rows.selection.setAwg(null)
        rows.warpProfiles.setActiveProfileId(null)
        rows.selection.setXray(XrayProviderSelectionRecord())
        rows.relayCredentials.clearAll()
        rows.relayProfiles.clearAll()
        data.relayCredentials.forEach(rows.relayCredentials::save)
        data.relayProfiles.forEach(rows.relayProfiles::save)
        rows.warpEndpoints.clearAll()
        rows.warpCredentials.clearAll()
        rows.warpProfiles.clearAll()
        data.warpCredentials.forEach { rows.warpCredentials.save(it.profileId, it) }
        data.warpProfiles.forEach(rows.warpProfiles::save)
        rows.warpProfiles.setActiveProfileId(data.warpActiveProfileId)
        rows.awgCredentials.clearAll()
        rows.awgProfiles.deleteAll()
        data.awgProfiles.forEach {
            it.secrets?.let { secrets -> rows.awgCredentials.save(it.id, secrets) }
            rows.awgProfiles.upsertProfile(it.toEntity())
        }
        rows.xraySecrets.clearAll()
        rows.xrayMetadata.clearAll()
        data.xraySecrets.forEach(rows.xraySecrets::save)
        data.xrayMetadata.forEach(rows.xrayMetadata::save)
        rows.selection.setXray(data.xraySelection)
        rows.selection.setAwg(intent.activeAwgProfileId?.takeIf { id -> data.awgProfiles.any { it.id == id } })
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
    /** Snapshot the after-image was built from; when present, replay merges instead of overwriting. */
    val settingsBaseImageBase64: String? = null,
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
