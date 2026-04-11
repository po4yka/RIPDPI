package com.poyka.ripdpi.data

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val ServerCapabilityPrefsName = "server_capability_cache"
private const val RelayCapabilityPrefix = "relay:"
private const val DirectPathCapabilityPrefix = "direct:"

enum class ServerCapabilityScope(
    val wireValue: String,
) {
    Relay("relay"),
    DirectPath("direct_path"),
}

@Serializable
data class ServerCapabilityObservation(
    val quicUsable: Boolean? = null,
    val udpUsable: Boolean? = null,
    val authModeAccepted: Boolean? = null,
    val multiplexReusable: Boolean? = null,
    val shadowTlsCamouflageAccepted: Boolean? = null,
    val naiveHttpsProxyAccepted: Boolean? = null,
    val fallbackRequired: Boolean? = null,
    val repeatedHandshakeFailureClass: String? = null,
)

@Serializable
data class ServerCapabilityRecord(
    val scope: String,
    val fingerprintHash: String,
    val authority: String,
    val relayProfileId: String? = null,
    val quicUsable: Boolean? = null,
    val udpUsable: Boolean? = null,
    val authModeAccepted: Boolean? = null,
    val multiplexReusable: Boolean? = null,
    val shadowTlsCamouflageAccepted: Boolean? = null,
    val naiveHttpsProxyAccepted: Boolean? = null,
    val fallbackRequired: Boolean? = null,
    val repeatedHandshakeFailureClass: String? = null,
    val source: String = "runtime",
    val updatedAt: Long = System.currentTimeMillis(),
)

internal fun mergeCapabilityRecord(
    existing: ServerCapabilityRecord?,
    scope: ServerCapabilityScope,
    fingerprintHash: String,
    authority: String,
    relayProfileId: String?,
    observation: ServerCapabilityObservation,
    source: String,
    recordedAt: Long,
): ServerCapabilityRecord =
    ServerCapabilityRecord(
        scope = scope.wireValue,
        fingerprintHash = fingerprintHash,
        authority = authority.normalizeCapabilityAuthority(),
        relayProfileId = relayProfileId?.trim()?.takeIf { it.isNotEmpty() } ?: existing?.relayProfileId,
        quicUsable = observation.quicUsable ?: existing?.quicUsable,
        udpUsable = observation.udpUsable ?: existing?.udpUsable,
        authModeAccepted = observation.authModeAccepted ?: existing?.authModeAccepted,
        multiplexReusable = observation.multiplexReusable ?: existing?.multiplexReusable,
        shadowTlsCamouflageAccepted =
            observation.shadowTlsCamouflageAccepted ?: existing?.shadowTlsCamouflageAccepted,
        naiveHttpsProxyAccepted = observation.naiveHttpsProxyAccepted ?: existing?.naiveHttpsProxyAccepted,
        fallbackRequired = observation.fallbackRequired ?: existing?.fallbackRequired,
        repeatedHandshakeFailureClass =
            observation.repeatedHandshakeFailureClass?.trim()?.takeIf { it.isNotEmpty() }
                ?: existing?.repeatedHandshakeFailureClass,
        source = source.trim().ifBlank { existing?.source ?: "runtime" },
        updatedAt = recordedAt,
    )

interface ServerCapabilityStore {
    suspend fun relayCapabilitiesForFingerprint(fingerprintHash: String): List<ServerCapabilityRecord>

    suspend fun directPathCapabilitiesForFingerprint(fingerprintHash: String): List<ServerCapabilityRecord>

    suspend fun rememberRelayObservation(
        fingerprint: NetworkFingerprint,
        authority: String,
        relayProfileId: String? = null,
        observation: ServerCapabilityObservation,
        source: String = "runtime",
        recordedAt: Long? = null,
    ): ServerCapabilityRecord

    suspend fun rememberDirectPathObservation(
        fingerprint: NetworkFingerprint,
        authority: String,
        observation: ServerCapabilityObservation,
        source: String = "runtime",
        recordedAt: Long? = null,
    ): ServerCapabilityRecord

    suspend fun clearAll()
}

@Singleton
class SharedPreferencesServerCapabilityStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : ServerCapabilityStore {
        private val preferences = context.getSharedPreferences(ServerCapabilityPrefsName, Context.MODE_PRIVATE)
        private val json =
            Json {
                encodeDefaults = true
                explicitNulls = false
                ignoreUnknownKeys = true
            }

        override suspend fun relayCapabilitiesForFingerprint(fingerprintHash: String): List<ServerCapabilityRecord> =
            capabilitiesForPrefix(
                prefix = RelayCapabilityPrefix,
                fingerprintHash = fingerprintHash,
            )

        override suspend fun directPathCapabilitiesForFingerprint(
            fingerprintHash: String,
        ): List<ServerCapabilityRecord> =
            capabilitiesForPrefix(
                prefix = DirectPathCapabilityPrefix,
                fingerprintHash = fingerprintHash,
            )

        override suspend fun rememberRelayObservation(
            fingerprint: NetworkFingerprint,
            authority: String,
            relayProfileId: String?,
            observation: ServerCapabilityObservation,
            source: String,
            recordedAt: Long?,
        ): ServerCapabilityRecord =
            rememberObservation(
                prefix = RelayCapabilityPrefix,
                scope = ServerCapabilityScope.Relay,
                fingerprintHash = fingerprint.scopeKey(),
                authority = authority,
                relayProfileId = relayProfileId,
                observation = observation,
                source = source,
                recordedAt = recordedAt ?: System.currentTimeMillis(),
            )

        override suspend fun rememberDirectPathObservation(
            fingerprint: NetworkFingerprint,
            authority: String,
            observation: ServerCapabilityObservation,
            source: String,
            recordedAt: Long?,
        ): ServerCapabilityRecord =
            rememberObservation(
                prefix = DirectPathCapabilityPrefix,
                scope = ServerCapabilityScope.DirectPath,
                fingerprintHash = fingerprint.scopeKey(),
                authority = authority,
                relayProfileId = null,
                observation = observation,
                source = source,
                recordedAt = recordedAt ?: System.currentTimeMillis(),
            )

        override suspend fun clearAll() {
            preferences.edit().clear().apply()
        }

        private fun capabilitiesForPrefix(
            prefix: String,
            fingerprintHash: String,
        ): List<ServerCapabilityRecord> =
            preferences
                .all
                .entries
                .asSequence()
                .filter { (key, _) -> key.startsWith(prefix) }
                .mapNotNull { (_, value) ->
                    (value as? String)?.let(::decodeRecord)
                }.filter { record -> record.fingerprintHash == fingerprintHash }
                .sortedByDescending(ServerCapabilityRecord::updatedAt)
                .toList()

        private fun rememberObservation(
            prefix: String,
            scope: ServerCapabilityScope,
            fingerprintHash: String,
            authority: String,
            relayProfileId: String?,
            observation: ServerCapabilityObservation,
            source: String,
            recordedAt: Long,
        ): ServerCapabilityRecord {
            val normalizedAuthority = authority.normalizeCapabilityAuthority()
            require(normalizedAuthority.isNotEmpty()) { "Capability authority must not be blank" }
            val key = capabilityPrefKey(prefix, fingerprintHash, normalizedAuthority, relayProfileId)
            val existing = preferences.getString(key, null)?.let(::decodeRecord)
            val merged =
                mergeCapabilityRecord(
                    existing = existing,
                    scope = scope,
                    fingerprintHash = fingerprintHash,
                    authority = normalizedAuthority,
                    relayProfileId = relayProfileId,
                    observation = observation,
                    source = source,
                    recordedAt = recordedAt,
                )
            preferences.edit().putString(key, json.encodeToString(ServerCapabilityRecord.serializer(), merged)).apply()
            return merged
        }

        private fun decodeRecord(payload: String): ServerCapabilityRecord? =
            runCatching {
                json.decodeFromString(ServerCapabilityRecord.serializer(), payload)
            }.getOrNull()

        private fun capabilityPrefKey(
            prefix: String,
            fingerprintHash: String,
            authority: String,
            relayProfileId: String?,
        ): String =
            buildString {
                append(prefix)
                append(fingerprintHash)
                append(':')
                append(authority)
                relayProfileId?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    append(':')
                    append(it.lowercase(Locale.US))
                }
            }
    }

private fun String.normalizeCapabilityAuthority(): String = trim().lowercase(Locale.US)

@Module
@InstallIn(SingletonComponent::class)
abstract class ServerCapabilityStoreModule {
    @Binds
    @Singleton
    abstract fun bindServerCapabilityStore(store: SharedPreferencesServerCapabilityStore): ServerCapabilityStore
}
