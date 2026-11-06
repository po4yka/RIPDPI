package com.poyka.ripdpi.data.persistence

import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.UnreadableCredentialException
import com.poyka.ripdpi.data.migrateRelayProfileRecord
import com.poyka.ripdpi.serialization.RipDpiJson
import java.security.GeneralSecurityException

internal class RelayProfileRows(
    private val records: ProfileRecordAccess,
) {
    fun load(profileId: String): RelayProfileRecord? =
        records.read(Namespace, profileId)?.let {
            migrateRelayProfileRecord(
                RipDpiJson.decodeFromString(RelayProfileRecord.serializer(), it.decodeToString()),
            ).record
        }

    fun list(): List<RelayProfileRecord> =
        records
            .entries(Namespace)
            .values
            .map {
                migrateRelayProfileRecord(
                    RipDpiJson.decodeFromString(RelayProfileRecord.serializer(), it.decodeToString()),
                ).record
            }.sortedBy(RelayProfileRecord::id)

    fun save(profile: RelayProfileRecord) {
        val migrated = migrateRelayProfileRecord(profile).record
        records.write(
            Namespace,
            migrated.id,
            RipDpiJson.encodeToString(RelayProfileRecord.serializer(), migrated).encodeToByteArray(),
        )
    }

    fun clear(profileId: String) = records.delete(Namespace, profileId)

    fun clearAll() = records.clear(Namespace)

    companion object {
        const val Namespace = "relay_profiles"
    }
}

internal class RelayCredentialRows(
    private val records: ProfileRecordAccess,
) {
    fun load(profileId: String): RelayCredentialRecord? =
        records.readCredential(Namespace, profileId)?.let {
            RipDpiJson.decodeFromString(RelayCredentialRecord.serializer(), it.decodeToString())
        }

    fun save(credentials: RelayCredentialRecord) =
        records.write(
            Namespace,
            credentials.profileId,
            RipDpiJson.encodeToString(RelayCredentialRecord.serializer(), credentials).encodeToByteArray(),
        )

    fun clear(profileId: String) = records.delete(Namespace, profileId)

    fun clearAll() = records.clear(Namespace)

    companion object {
        const val Namespace = "relay_credentials"
    }
}

/** Preserve the distinction between a missing credential and a sealed value that cannot be opened. */
internal fun ProfileRecordAccess.readCredential(
    namespace: String,
    id: String,
): ByteArray? =
    try {
        read(namespace, id)
    } catch (error: GeneralSecurityException) {
        throw UnreadableCredentialException(error)
    } catch (error: IllegalArgumentException) {
        throw UnreadableCredentialException(error)
    }
