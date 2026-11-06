package com.poyka.ripdpi.data.persistence

import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.serialization.RipDpiContractJson

internal class AwgCredentialRows(
    private val records: ProfileRecordAccess,
) {
    fun load(profileId: String): AwgSecrets? =
        records.readCredential(Namespace, profileId)?.let { payload ->
            runCatching {
                RipDpiContractJson.decodeFromString(
                    AwgSecrets.serializer(),
                    payload.decodeToString(),
                )
            }.getOrNull()
        }

    fun save(
        profileId: String,
        secrets: AwgSecrets,
    ) = records.write(
        Namespace,
        profileId,
        RipDpiContractJson.encodeToString(AwgSecrets.serializer(), secrets).encodeToByteArray(),
    )

    fun clear(profileId: String) = records.delete(Namespace, profileId)

    fun clearAll() = records.clear(Namespace)

    companion object {
        const val Namespace = "awg_credentials"
    }
}
