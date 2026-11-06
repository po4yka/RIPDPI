package com.poyka.ripdpi.data.persistence

import com.poyka.ripdpi.data.xray.XrayProfileMetadataRecord
import com.poyka.ripdpi.data.xray.XrayProfileSecretRecord
import com.poyka.ripdpi.serialization.RipDpiJson

internal class XrayMetadataRows(
    private val records: ProfileRecordAccess,
) {
    fun load(profileId: String): XrayProfileMetadataRecord? =
        records.read(Namespace, profileId)?.let {
            runCatching { RipDpiJson.decodeFromString(XrayProfileMetadataRecord.serializer(), it.decodeToString()) }
                .getOrNull()
                ?.takeIf { record -> record.profileId == profileId }
        }

    fun list(): List<XrayProfileMetadataRecord> =
        records
            .entries(Namespace)
            .mapNotNull { (profileId, payload) ->
                runCatching {
                    RipDpiJson.decodeFromString(
                        XrayProfileMetadataRecord.serializer(),
                        payload.decodeToString(),
                    )
                }.getOrNull()
                    ?.takeIf { it.profileId == profileId }
            }.sortedBy(XrayProfileMetadataRecord::profileId)

    fun save(record: XrayProfileMetadataRecord) =
        records.write(
            Namespace,
            record.profileId,
            RipDpiJson.encodeToString(XrayProfileMetadataRecord.serializer(), record).encodeToByteArray(),
        )

    fun clear(profileId: String) = records.delete(Namespace, profileId)

    fun clearAll() = records.clear(Namespace)

    companion object {
        const val Namespace = "xray_metadata"
    }
}

internal class XraySecretRows(
    private val records: ProfileRecordAccess,
) {
    fun load(profileId: String): XrayProfileSecretRecord? =
        records.readCredential(Namespace, profileId)?.let {
            RipDpiJson.decodeFromString(XrayProfileSecretRecord.serializer(), it.decodeToString())
        }

    fun save(record: XrayProfileSecretRecord) =
        records.write(
            Namespace,
            record.profileId,
            RipDpiJson.encodeToString(XrayProfileSecretRecord.serializer(), record).encodeToByteArray(),
        )

    fun clear(profileId: String) = records.delete(Namespace, profileId)

    fun clearAll() = records.clear(Namespace)

    companion object {
        const val Namespace = "xray_secrets"
    }
}
