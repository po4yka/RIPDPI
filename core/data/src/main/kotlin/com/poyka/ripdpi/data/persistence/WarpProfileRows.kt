package com.poyka.ripdpi.data.persistence

import com.poyka.ripdpi.data.DefaultWarpProfileId
import com.poyka.ripdpi.data.GlobalWarpEndpointScopeKey
import com.poyka.ripdpi.data.UnreadableCredentialException
import com.poyka.ripdpi.data.WarpCredentials
import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import com.poyka.ripdpi.data.WarpProfile
import com.poyka.ripdpi.data.logUnreadableCredential
import com.poyka.ripdpi.data.normalizeWarpAccountKind
import com.poyka.ripdpi.data.normalizeWarpSetupState
import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

internal class WarpProfileRows(
    private val records: ProfileRecordAccess,
) {
    fun load(profileId: String): WarpProfile? =
        records.read(Namespace, profileId)?.let {
            RipDpiJson.decodeFromString(WarpProfile.serializer(), it.decodeToString())
        }

    fun loadAll(): List<WarpProfile> =
        records
            .entries(Namespace)
            .values
            .mapNotNull {
                runCatching {
                    RipDpiJson.decodeFromString(
                        WarpProfile.serializer(),
                        it.decodeToString(),
                    )
                }.getOrNull()
            }.sortedBy(WarpProfile::id)

    fun save(profile: WarpProfile) {
        val normalized =
            profile.copy(
                id = profile.id.ifBlank { DefaultWarpProfileId },
                accountKind = normalizeWarpAccountKind(profile.accountKind),
                setupState = normalizeWarpSetupState(profile.setupState),
            )
        records.write(
            Namespace,
            normalized.id,
            RipDpiJson.encodeToString(WarpProfile.serializer(), normalized).encodeToByteArray(),
        )
    }

    fun remove(profileId: String) = records.delete(Namespace, profileId)

    fun activeProfileId(): String? =
        records.read(SelectionNamespace, ActiveProfileId)?.let {
            RipDpiJson.decodeFromString(String.serializer(), it.decodeToString()).takeIf(String::isNotBlank)
        }

    fun setActiveProfileId(profileId: String?) {
        if (profileId.isNullOrBlank()) {
            records.delete(SelectionNamespace, ActiveProfileId)
        } else {
            records.write(
                SelectionNamespace,
                ActiveProfileId,
                RipDpiJson.encodeToString(String.serializer(), profileId).encodeToByteArray(),
            )
        }
    }

    fun clearAll() {
        records.clear(Namespace)
        setActiveProfileId(null)
    }

    companion object {
        const val Namespace = "warp_profiles"
        const val SelectionNamespace = "profile_selection"
        const val ActiveProfileId = "warp_active"
    }
}

internal class WarpCredentialRows(
    private val records: ProfileRecordAccess,
) {
    fun load(profileId: String): WarpCredentials? =
        records.readCredential(Namespace, profileId)?.let {
            RipDpiJson.decodeFromString(WarpCredentials.serializer(), it.decodeToString()).copy(profileId = profileId)
        }

    fun loadAll(): List<WarpCredentials> =
        records
            .keys(Namespace)
            .mapNotNull { profileId ->
                val payload =
                    try {
                        records.readCredential(Namespace, profileId)
                    } catch (error: UnreadableCredentialException) {
                        logUnreadableCredential("skipped in a WARP list", error)
                        null
                    }
                payload?.let {
                    runCatching {
                        RipDpiJson
                            .decodeFromString(
                                WarpCredentials.serializer(),
                                it.decodeToString(),
                            ).copy(profileId = profileId)
                    }.getOrNull()
                }
            }.sortedBy(WarpCredentials::profileId)

    fun save(
        profileId: String,
        credentials: WarpCredentials,
    ) {
        val normalized =
            credentials.copy(
                profileId = profileId,
                accountKind = normalizeWarpAccountKind(credentials.accountKind),
            )
        records.write(
            Namespace,
            profileId,
            RipDpiJson.encodeToString(WarpCredentials.serializer(), normalized).encodeToByteArray(),
        )
    }

    fun clear(profileId: String) = records.delete(Namespace, profileId)

    fun clearAll() = records.clear(Namespace)

    companion object {
        const val Namespace = "warp_credentials"
    }
}

internal class WarpEndpointRows(
    private val records: ProfileRecordAccess,
) {
    fun load(
        profileId: String,
        networkScopeKey: String,
    ): WarpEndpointCacheEntry? =
        records.read(Namespace, key(profileId, networkScopeKey))?.let {
            RipDpiJson.decodeFromString(WarpEndpointCacheEntry.serializer(), it.decodeToString())
        }

    fun loadAll(profileId: String): List<WarpEndpointCacheEntry> =
        records
            .keys(Namespace)
            .filter { it.startsWith(profilePrefix(profileId)) }
            .mapNotNull { id ->
                records.read(Namespace, id)?.let {
                    runCatching {
                        RipDpiJson.decodeFromString(
                            WarpEndpointCacheEntry.serializer(),
                            it.decodeToString(),
                        )
                    }.getOrNull()
                }
            }.sortedBy(WarpEndpointCacheEntry::networkScopeKey)

    fun save(entry: WarpEndpointCacheEntry) {
        val normalized =
            entry.copy(
                profileId = entry.profileId.ifBlank { DefaultWarpProfileId },
                networkScopeKey = normalizeScope(entry.networkScopeKey),
            )
        records.write(
            Namespace,
            key(normalized.profileId, normalized.networkScopeKey),
            RipDpiJson.encodeToString(WarpEndpointCacheEntry.serializer(), normalized).encodeToByteArray(),
        )
    }

    fun clear(
        profileId: String,
        networkScopeKey: String,
    ) = records.delete(Namespace, key(profileId, networkScopeKey))

    fun clearProfile(profileId: String) {
        records
            .keys(
                Namespace,
            ).filter { it.startsWith(profilePrefix(profileId)) }
            .forEach { records.delete(Namespace, it) }
    }

    fun clearAll() = records.clear(Namespace)

    companion object {
        const val Namespace = "warp_endpoints"

        fun key(
            profileId: String,
            networkScopeKey: String,
        ): String =
            RipDpiJson.encodeToString(
                ListSerializer(String.serializer()),
                listOf(profileId, normalizeScope(networkScopeKey)),
            )

        private fun profilePrefix(profileId: String): String =
            "[${RipDpiJson.encodeToString(String.serializer(), profileId)},"

        private fun normalizeScope(networkScopeKey: String): String =
            networkScopeKey.takeIf(String::isNotBlank) ?: GlobalWarpEndpointScopeKey
    }
}
