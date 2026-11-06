package com.poyka.ripdpi.data.persistence

import android.content.Context
import android.content.SharedPreferences
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DefaultWarpProfileId
import com.poyka.ripdpi.data.GlobalWarpEndpointScopeKey
import com.poyka.ripdpi.data.KeystoreProxyGroupBlobStore
import com.poyka.ripdpi.data.KeystoreRelayCredentialStore
import com.poyka.ripdpi.data.KeystoreWarpCredentialStore
import com.poyka.ripdpi.data.LegacyProfileMutationJournal
import com.poyka.ripdpi.data.MeasuredActivationReservation
import com.poyka.ripdpi.data.MeasuredUtilitySelectionIntent
import com.poyka.ripdpi.data.PauseIntentAuthority
import com.poyka.ripdpi.data.ProfileActivationReceipt
import com.poyka.ripdpi.data.ProfileMutationJournalRecoveryReader
import com.poyka.ripdpi.data.ProfileMutationOutcome
import com.poyka.ripdpi.data.ProfileMutationRowWriter
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupCatalog
import com.poyka.ripdpi.data.RecoverablePendingMutation
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RuntimeActivationEnvelope
import com.poyka.ripdpi.data.RuntimeActivationPhase
import com.poyka.ripdpi.data.RuntimeCommandOrigin
import com.poyka.ripdpi.data.WarpCredentials
import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import com.poyka.ripdpi.data.WarpProfile
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.data.awg.KeystoreAwgCredentialStore
import com.poyka.ripdpi.data.ownsProviderSelection
import com.poyka.ripdpi.data.resolveAppSettingsStoreFile
import com.poyka.ripdpi.data.xray.KeystoreXrayProfileSecretStore
import com.poyka.ripdpi.data.xray.SharedPreferencesXrayProviderSelectionStore
import com.poyka.ripdpi.data.xray.XrayProfileMetadataRecord
import com.poyka.ripdpi.data.xray.XrayProfileSecretRecord
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.serialization.RipDpiJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import javax.inject.Inject
import javax.inject.Singleton

/** Reads old storage once; the caller commits this snapshot and its marker in the same Room transaction. */
@Singleton
internal class LegacyProfileImporter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        val authority: PauseIntentAuthority,
    ) {
        fun clearSource() = clearLegacyProfileSources(context)

        suspend fun read(): LegacyProfileSnapshot =
            withContext(Dispatchers.IO) {
                val settingsFile = resolveAppSettingsStoreFile(context)
                // The old DataStore corruption handler replaces the source. Import must fail without changing it.
                val settings =
                    if (settingsFile.exists()) {
                        settingsFile.inputStream().use { AppSettingsSerializer.readFrom(it) }
                    } else {
                        AppSettingsSerializer.defaultValue
                    }
                val groups = ProxyGroupCatalog(KeystoreProxyGroupBlobStore(context))
                val groupSnapshot = groups.snapshot()
                val selector = readSelector(context.legacyPreferences("selector_selection_store"), groupSnapshot)
                LegacyProfileSnapshot(
                    settings = settings,
                    relayProfiles =
                        context.readRecords(
                            "relay_profile_cache",
                            "relay-profile:",
                            RelayProfileRecord.serializer(),
                        ) { it.id },
                    relayCredentials = readRelayCredentials(),
                    warpProfiles = context.readRecords("warp_profiles", "profile:", WarpProfile.serializer()) { it.id },
                    warpCredentials = readWarpCredentials(),
                    warpEndpoints = readWarpEndpoints(context.legacyPreferences("warp_endpoint_cache")),
                    warpActiveProfileId =
                        context
                            .legacyPreferences(
                                "warp_profiles",
                            ).getString("active_profile_id", null),
                    xrayMetadata =
                        context.readRecords(
                            "xray_profile_metadata",
                            "xray-profile:",
                            XrayProfileMetadataRecord.serializer(),
                        ) {
                            it.profileId
                        },
                    xraySecrets = readXraySecrets(),
                    xraySelection = SharedPreferencesXrayProviderSelectionStore(context).current(),
                    awgSecrets = readAwgSecrets(),
                    awgActiveProfileId =
                        context
                            .createDeviceProtectedStorageContext()
                            .legacyPreferences("ripdpi_boot_session_state")
                            .getString("boot-session-active-awg-profile-id", null),
                    groups = groupSnapshot,
                    selector = selector,
                    pending =
                        ProfileMutationJournalRecoveryReader(
                            LegacyProfileMutationJournal(context),
                            groups,
                        ).read(),
                )
            }

        fun apply(
            rows: ProfileRows,
            snapshot: LegacyProfileSnapshot,
        ) {
            rows.settings.replace(snapshot.settings)
            snapshot.relayProfiles.forEach(rows.relayProfiles::save)
            snapshot.relayCredentials.forEach(rows.relayCredentials::save)
            snapshot.warpProfiles.forEach(rows.warpProfiles::save)
            snapshot.warpCredentials.forEach { rows.warpCredentials.save(it.profileId, it) }
            snapshot.warpEndpoints.forEach(rows.warpEndpoints::save)
            rows.warpProfiles.setActiveProfileId(snapshot.warpActiveProfileId)
            snapshot.xrayMetadata.forEach(rows.xrayMetadata::save)
            snapshot.xraySecrets.forEach(rows.xraySecrets::save)
            rows.selection.setXray(snapshot.xraySelection)
            snapshot.awgSecrets.forEach { (id, secrets) -> rows.awgCredentials.save(id, secrets) }
            rows.selection.setAwg(snapshot.awgActiveProfileId)
            rows.groups.replace(snapshot.groups)
            snapshot.selector.choices.forEach { (groupId, choice) ->
                rows.selection.setChoice(groupId, choice.profileId, choice.manual)
            }
            rows.selection.setActiveGroup(snapshot.selector.activeGroupId)
            snapshot.pending?.let { applyLegacyMutation(rows, it, authority) }
        }

        /** Runs after the marker commit, also when a previous process committed before initializing authority. */
        fun afterCommit() = authority.initializeAfterMigration()

        private suspend fun readRelayCredentials(): List<RelayCredentialRecord> {
            val store = KeystoreRelayCredentialStore(context)
            return context.legacyPreferences("relay_credentials_secure").recordIds("relay-credentials:").map { id ->
                checkNotNull(store.load(id)) { "Legacy relay credential is missing" }
                    .also { check(it.profileId == id) { "Legacy relay credential identity differs from its key" } }
            }
        }

        private suspend fun readWarpCredentials(): List<WarpCredentials> {
            val preferences = context.legacyPreferences("warp_credentials_secure")
            val ids = preferences.recordIds("warp_credentials:").toMutableSet()
            if (preferences.contains("warp_credentials")) ids += DefaultWarpProfileId
            val store = KeystoreWarpCredentialStore(context)
            return ids.sorted().map { id -> checkNotNull(store.load(id)) { "Legacy WARP credential is missing" } }
        }

        private suspend fun readXraySecrets(): List<XrayProfileSecretRecord> {
            val store = KeystoreXrayProfileSecretStore(context)
            return context.legacyPreferences("xray_profile_secrets_secure").recordIds("xray-secrets:").map { id ->
                checkNotNull(store.load(id)) { "Legacy Xray credential is missing" }
                    .also { check(it.profileId == id) { "Legacy Xray credential identity differs from its key" } }
            }
        }

        private suspend fun readAwgSecrets(): Map<String, AwgSecrets> {
            val store = KeystoreAwgCredentialStore(context)
            val ids = context.legacyPreferences("awg_credentials_secure").recordIds("awg_credentials:")
            return ids.associateWith { id ->
                checkNotNull(store.load(id)) { "Legacy AWG credential is unreadable" }
            }
        }
    }

internal data class LegacyProfileSnapshot(
    val settings: AppSettings,
    val relayProfiles: List<RelayProfileRecord>,
    val relayCredentials: List<RelayCredentialRecord>,
    val warpProfiles: List<WarpProfile>,
    val warpCredentials: List<WarpCredentials>,
    val warpEndpoints: List<WarpEndpointCacheEntry>,
    val warpActiveProfileId: String?,
    val xrayMetadata: List<XrayProfileMetadataRecord>,
    val xraySecrets: List<XrayProfileSecretRecord>,
    val xraySelection: XrayProviderSelectionRecord,
    val awgSecrets: Map<String, AwgSecrets>,
    val awgActiveProfileId: String?,
    val groups: List<ProxyGroup>,
    val selector: LegacySelectorSnapshot,
    val pending: RecoverablePendingMutation?,
)

internal data class LegacySelectorSnapshot(
    val activeGroupId: String?,
    val choices: Map<String, LegacySelectorChoice>,
)

internal data class LegacySelectorChoice(
    val profileId: String?,
    val manual: Boolean,
)

private fun Context.legacyPreferences(name: String): SharedPreferences =
    getSharedPreferences(name, Context.MODE_PRIVATE)

private fun SharedPreferences.recordIds(prefix: String): List<String> =
    all.keys
        .filter { it.startsWith(prefix) }
        .map { it.removePrefix(prefix) }
        .sorted()

private fun <T> Context.readRecords(
    name: String,
    prefix: String,
    serializer: KSerializer<T>,
    id: (T) -> String,
): List<T> =
    legacyPreferences(name).all.entries.filter { it.key.startsWith(prefix) }.map { (key, value) ->
        check(value is String) { "Legacy profile record has an invalid storage type" }
        RipDpiJson.decodeFromString(serializer, value).also {
            check(id(it) == key.removePrefix(prefix)) { "Legacy profile identity differs from its key" }
        }
    }

private fun readWarpEndpoints(preferences: SharedPreferences): List<WarpEndpointCacheEntry> {
    val endpoints =
        preferences.all.entries.filter { it.key.startsWith("endpoint:") }.map { (key, value) ->
            check(value is String) { "Legacy WARP endpoint has an invalid storage type" }
            val entry = RipDpiJson.decodeFromString(WarpEndpointCacheEntry.serializer(), value)
            val scope = entry.networkScopeKey.takeIf(String::isNotBlank) ?: GlobalWarpEndpointScopeKey
            val legacy = key == "endpoint:$scope"
            val profileId = if (legacy) DefaultWarpProfileId else entry.profileId.ifBlank { DefaultWarpProfileId }
            check(
                legacy || key == "endpoint:$profileId:$scope",
            ) { "Legacy WARP endpoint identity differs from its key" }
            legacy to entry.copy(profileId = profileId, networkScopeKey = scope)
        }
    // A current profile-scoped entry takes precedence over its old default-profile fallback.
    return endpoints
        .sortedBy { if (it.first) 0 else 1 }
        .associate { (_, entry) -> (entry.profileId to entry.networkScopeKey) to entry }
        .values
        .toList()
}

private fun readSelector(
    preferences: SharedPreferences,
    groups: List<ProxyGroup>,
): LegacySelectorSnapshot {
    val choices =
        preferences.recordIds("selected-profile-").associateWith { groupId ->
            val profileId = preferences.getString("selected-profile-$groupId", null)
            LegacySelectorChoice(
                profileId,
                profileId != null && preferences.getBoolean("manual-selection-$groupId", false),
            )
        }
    val activeGroupId =
        if (preferences.contains("active_group_id")) {
            preferences.getString("active_group_id", null)?.takeIf(String::isNotBlank)
        } else {
            groups
                .firstOrNull { group ->
                    group.isSelector &&
                        group.members.any { it.groupId == group.id && it.id == choices[group.id]?.profileId }
                }?.id
        }
    return LegacySelectorSnapshot(activeGroupId, choices)
}

private fun applyLegacyMutation(
    rows: ProfileRows,
    recovered: RecoverablePendingMutation,
    authority: PauseIntentAuthority,
) {
    val legacy = recovered.pending.schemaVersion == 1
    val outcome =
        if (legacy) {
            check(!authority.isInitialized()) { "Legacy profile journal exists after pause migration" }
            null
        } else {
            authority.initializeAfterMigration()
            if (recovered.intent is MeasuredUtilitySelectionIntent) {
                authority.retireMeasuredReservation(recovered.intent.reservation)
                ProfileMutationOutcome.NonSuperseding
            } else {
                authority.invalidateForMutation(
                    checkNotNull(recovered.pending.origin),
                    recovered.pending.mutationId,
                    checkNotNull(recovered.pending.expectedPauseAuthority),
                )
            }
        }
    val writer = ProfileMutationRowWriter(rows, authority)
    if (!legacy && recovered.intent is MeasuredUtilitySelectionIntent) {
        writer.applyMeasuredReconstructed(recovered.intent)
    } else if (!legacy && recovered.intent.ownsProviderSelection(checkNotNull(recovered.pending.origin))) {
        val receipt = (outcome as? ProfileMutationOutcome.Reserved)?.receipt as? ProfileActivationReceipt
        if (receipt != null) {
            authority.retireReconstructedActivation(receipt)
            writer.applyReconstructed(recovered.intent, receipt)
        }
    } else {
        writer.apply(recovered.intent)
    }
}

private fun PauseIntentAuthority.retireReconstructedActivation(receipt: ProfileActivationReceipt) {
    val command = snapshotAuthority().command ?: return
    if (!isCurrent(receipt)) return
    when (val phase = command.phase) {
        RuntimeActivationPhase.Unbound -> {
            cancelProfileActivation(receipt)
        }

        is RuntimeActivationPhase.Pending -> {
            val activation =
                activationReceiptFromEnvelope(
                    RuntimeActivationEnvelope(command.generation, command.commandId, command.origin, phase.mode),
                )
            if (activation != null) cancelPendingActivation(activation)
        }

        else -> {}
    }
}

private fun PauseIntentAuthority.retireMeasuredReservation(reservation: MeasuredActivationReservation) {
    val current =
        snapshotAuthority().command?.takeIf {
            it.generation == reservation.authority.generation && it.commandId == reservation.commandId
        } ?: return
    val origin = current.origin as? RuntimeCommandOrigin.MeasuredActivation ?: return
    check(origin.mutationId == reservation.commandId)
    when (current.phase) {
        RuntimeActivationPhase.Unbound -> {
            retireMeasuredActivation(reservation.authority, reservation.commandId)
        }

        is RuntimeActivationPhase.Pending -> {
            val receipt =
                activationReceiptFromEnvelope(
                    RuntimeActivationEnvelope(current.generation, current.commandId, current.origin, reservation.mode),
                )
            if (receipt != null) cancelPendingActivation(receipt)
        }

        else -> {}
    }
}
