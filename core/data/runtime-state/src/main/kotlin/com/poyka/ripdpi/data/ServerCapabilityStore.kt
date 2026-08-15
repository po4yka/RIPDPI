package com.poyka.ripdpi.data

import android.content.Context
import com.poyka.ripdpi.serialization.RipDpiContractJson
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.Serializable
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
    val transportPolicy: TransportPolicy? = null,
    val ipSetDigest: String? = null,
    val dnsClassification: DirectDnsClassification? = null,
    val transportClass: DirectTransportClass? = null,
    val reasonCode: DirectModeReasonCode? = null,
    val cooldownUntil: Long? = null,
    val policyConfirmedAt: Long? = null,
    val policyFailureCount: Int? = null,
    /** Autonomous System Number the policy was confirmed under, for ASN-change invalidation. */
    val observedAsn: Int? = null,
    /** ECH capability observed when the policy was confirmed, for ECH-change invalidation. */
    val echCapable: Boolean? = null,
    /** Absolute epoch-ms at which the backing HTTPS/SVCB record expires (TTL boundary). */
    val httpsRrExpiresAt: Long? = null,
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
    val transportPolicyEnvelope: TransportPolicyEnvelope? = null,
    val policyConfirmedAt: Long? = null,
    val policyFailureCount: Int = 0,
    /** Autonomous System Number the policy was confirmed under, for ASN-change invalidation. */
    val observedAsn: Int? = null,
    /** ECH capability observed when the policy was confirmed, for ECH-change invalidation. */
    val echCapable: Boolean? = null,
    /** Absolute epoch-ms at which the backing HTTPS/SVCB record expires (TTL boundary). */
    val httpsRrExpiresAt: Long? = null,
    val source: String = "runtime",
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * The current network environment a cached direct-mode policy is revalidated
 * against.  All fields are optional: a `null` field means "current value
 * unknown", and an invalidation trigger only fires when BOTH the stored and the
 * current value are known and differ.  An empty environment is therefore a
 * safe no-op that never invalidates.
 */
data class DirectPolicyEnvironment(
    val currentAsn: Int? = null,
    val echCapable: Boolean? = null,
)

fun mergeCapabilityRecord(
    existing: ServerCapabilityRecord?,
    scope: ServerCapabilityScope,
    fingerprintHash: String,
    authority: String,
    relayProfileId: String?,
    observation: ServerCapabilityObservation,
    source: String,
    recordedAt: Long,
): ServerCapabilityRecord {
    val environmentFields = resolveEnvironmentFields(existing, observation)
    return mergeTransportPolicyEnvelope(existing, observation).let { policyEnvelope ->
        ServerCapabilityRecord(
            scope = scope.wireValue,
            fingerprintHash = fingerprintHash,
            authority = authority.normalizeCapabilityAuthority(),
            relayProfileId = relayProfileId?.trim()?.takeIf { it.isNotEmpty() } ?: existing?.relayProfileId,
            quicUsable = observation.quicUsable ?: policyEnvelope?.derivedQuicUsable() ?: existing?.quicUsable,
            udpUsable = observation.udpUsable ?: policyEnvelope?.derivedUdpUsable() ?: existing?.udpUsable,
            authModeAccepted = observation.authModeAccepted ?: existing?.authModeAccepted,
            multiplexReusable = observation.multiplexReusable ?: existing?.multiplexReusable,
            shadowTlsCamouflageAccepted =
                observation.shadowTlsCamouflageAccepted ?: existing?.shadowTlsCamouflageAccepted,
            naiveHttpsProxyAccepted = observation.naiveHttpsProxyAccepted ?: existing?.naiveHttpsProxyAccepted,
            fallbackRequired =
                observation.fallbackRequired ?: policyEnvelope?.derivedFallbackRequired() ?: existing?.fallbackRequired,
            repeatedHandshakeFailureClass =
                observation.repeatedHandshakeFailureClass?.trim()?.takeIf { it.isNotEmpty() }
                    ?: policyEnvelope?.derivedHandshakeFailureClass()
                    ?: existing?.repeatedHandshakeFailureClass,
            transportPolicyEnvelope = policyEnvelope,
            policyConfirmedAt = observation.policyConfirmedAt?.takeIf { it > 0L } ?: existing?.policyConfirmedAt,
            policyFailureCount = observation.policyFailureCount?.coerceAtLeast(0) ?: existing?.policyFailureCount ?: 0,
            observedAsn = environmentFields.observedAsn,
            echCapable = environmentFields.echCapable,
            httpsRrExpiresAt = environmentFields.httpsRrExpiresAt,
            source = source.trim().ifBlank { existing?.source ?: "runtime" },
            updatedAt = recordedAt,
        )
    }
}

private data class DirectPolicyEnvironmentFields(
    val observedAsn: Int?,
    val echCapable: Boolean?,
    val httpsRrExpiresAt: Long?,
)

private fun resolveEnvironmentFields(
    existing: ServerCapabilityRecord?,
    observation: ServerCapabilityObservation,
): DirectPolicyEnvironmentFields =
    DirectPolicyEnvironmentFields(
        observedAsn = observation.observedAsn ?: existing?.observedAsn,
        echCapable = observation.echCapable ?: existing?.echCapable,
        httpsRrExpiresAt = observation.httpsRrExpiresAt?.takeIf { it > 0L } ?: existing?.httpsRrExpiresAt,
    )

fun ServerCapabilityRecord.hasConfirmedDirectPolicy(): Boolean = policyConfirmedAt?.let { it > 0L } == true

fun ServerCapabilityRecord.hasExceededDirectPolicyFailureBudget(): Boolean =
    policyFailureCount >= DirectModePolicyRevalidationFailureThreshold

/**
 * Epoch-ms the TTL window is measured from.  Anchored on [policyConfirmedAt] so
 * that a Phase 6 variant rotation (which rewrites the winning family and bumps
 * [updatedAt] but preserves the original confirmation) does NOT extend the TTL —
 * the policy still ages out 7 days after it was first confirmed.  Falls back to
 * [updatedAt] for records that were never confirmed.
 */
private fun ServerCapabilityRecord.directPolicyTtlAnchor(): Long = policyConfirmedAt?.takeIf { it > 0L } ?: updatedAt

fun ServerCapabilityRecord.isFreshDirectPolicy(nowMillis: Long): Boolean {
    val anchor = directPolicyTtlAnchor()
    val withinTtl = anchor > 0L && nowMillis - anchor <= DirectModePolicyTtlMs
    val httpsRrLive = httpsRrExpiresAt == null || httpsRrExpiresAt <= 0L || nowMillis <= httpsRrExpiresAt
    if (!withinTtl || !httpsRrLive) return false
    val envelope = effectiveTransportPolicyEnvelope()
    return when (envelope.policy.outcome) {
        DirectModeOutcome.NO_DIRECT_SOLUTION -> envelope.isCooldownActive(nowMillis)

        DirectModeOutcome.OWNED_STACK_ONLY,
        DirectModeOutcome.TRANSPARENT_OK,
        -> true
    }
}

/**
 * True when an environmental change since the policy was confirmed invalidates
 * it: the ASN changed, or the ECH capability flipped.  A trigger fires only
 * when both the stored and the current value are known, so an unknown current
 * signal never wrongly drops a valid policy.
 */
fun ServerCapabilityRecord.isInvalidatedByEnvironment(
    environment: DirectPolicyEnvironment,
    @Suppress("UNUSED_PARAMETER") nowMillis: Long,
): Boolean {
    val asnChanged =
        observedAsn != null && environment.currentAsn != null && observedAsn != environment.currentAsn
    val echChanged =
        echCapable != null && environment.echCapable != null && echCapable != environment.echCapable
    return asnChanged || echChanged
}

fun ServerCapabilityRecord.isRuntimeUsableDirectPolicy(nowMillis: Long): Boolean =
    hasConfirmedDirectPolicy() && !hasExceededDirectPolicyFailureBudget() && isFreshDirectPolicy(nowMillis)

/**
 * Environment-aware usability gate: the policy must be runtime-usable AND not
 * invalidated by an ASN / ECH change in [environment].
 */
fun ServerCapabilityRecord.isRuntimeUsableDirectPolicy(
    nowMillis: Long,
    environment: DirectPolicyEnvironment,
): Boolean = isRuntimeUsableDirectPolicy(nowMillis) && !isInvalidatedByEnvironment(environment, nowMillis)

fun ServerCapabilityRecord.effectiveTransportPolicyEnvelope(): TransportPolicyEnvelope {
    val existingEnvelope = transportPolicyEnvelope
    if (existingEnvelope != null) {
        return existingEnvelope.copy(
            version = existingEnvelope.version.coerceAtLeast(CurrentTransportPolicyEnvelopeVersion),
            ipSetDigest = existingEnvelope.ipSetDigest.trim(),
            dnsClassification = existingEnvelope.dnsClassification,
            cooldownUntil = existingEnvelope.cooldownUntil?.takeIf { it > 0L },
        )
    }
    val hasTlsFallbackEvidence = fallbackRequired == true || !repeatedHandshakeFailureClass.isNullOrBlank()
    val policy =
        when {
            hasTlsFallbackEvidence -> {
                TransportPolicy(
                    quicMode = QuicMode.HARD_DISABLE,
                    preferredStack = PreferredStack.H2,
                    dnsMode = DnsMode.SYSTEM,
                    tcpFamily = normalizeStrategyFamilyToTcpFamily("tlsrec"),
                    outcome = DirectModeOutcome.TRANSPARENT_OK,
                )
            }

            quicUsable == false || udpUsable == false -> {
                TransportPolicy(
                    quicMode = QuicMode.SOFT_DISABLE,
                    preferredStack = PreferredStack.H2,
                    dnsMode = DnsMode.SYSTEM,
                    tcpFamily = TcpFamily.NONE,
                    outcome = DirectModeOutcome.TRANSPARENT_OK,
                )
            }

            else -> {
                TransportPolicy()
            }
        }
    return TransportPolicyEnvelope(
        version = CurrentTransportPolicyEnvelopeVersion,
        policy = policy,
        ipSetDigest = "",
        dnsClassification = null,
        transportClass =
            when {
                hasTlsFallbackEvidence -> DirectTransportClass.SNI_TLS_SUSPECT
                quicUsable == false || udpUsable == false -> DirectTransportClass.QUIC_BLOCK_SUSPECT
                else -> null
            },
        reasonCode =
            when {
                hasTlsFallbackEvidence -> DirectModeReasonCode.TCP_POST_CLIENT_HELLO_FAILURE
                quicUsable == false || udpUsable == false -> DirectModeReasonCode.QUIC_BLOCKED
                else -> null
            },
        cooldownUntil = null,
    )
}

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
            RipDpiContractJson

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
            val key =
                capabilityPrefKey(
                    prefix = prefix,
                    fingerprintHash = fingerprintHash,
                    authority = normalizedAuthority,
                    relayProfileId = relayProfileId,
                    ipSetDigest = observation.ipSetDigest,
                )
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
            // commit() (not apply()) writes the backing XML atomically AND
            // synchronously, so a confirmed direct-mode policy survives an LMK
            // SIGKILL immediately after a state transition (see
            // android-vpn-lifecycle: persist on every transition, no torn file).
            preferences
                .edit()
                .putString(key, json.encodeToString(ServerCapabilityRecord.serializer(), merged))
                .commit()
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
            ipSetDigest: String? = null,
        ): String =
            buildString {
                append(prefix)
                append(fingerprintHash)
                append(':')
                append(authority)
                ipSetDigest?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    append(':')
                    append(it.lowercase(Locale.US))
                }
                relayProfileId?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    append(':')
                    append(it.lowercase(Locale.US))
                }
            }
    }

private fun String.normalizeCapabilityAuthority(): String = trim().lowercase(Locale.US)

@Suppress("ReturnCount")
private fun mergeTransportPolicyEnvelope(
    existing: ServerCapabilityRecord?,
    observation: ServerCapabilityObservation,
): TransportPolicyEnvelope? {
    val existingEnvelope = existing?.effectiveTransportPolicyEnvelope()
    val hasObservationPolicyData =
        observation.transportPolicy != null ||
            observation.ipSetDigest != null ||
            observation.dnsClassification != null ||
            observation.transportClass != null ||
            observation.reasonCode != null ||
            observation.cooldownUntil != null ||
            observation.policyConfirmedAt != null ||
            observation.policyFailureCount != null
    if (!hasObservationPolicyData) {
        return existingEnvelope
    }
    if (observation.transportPolicy != null) {
        return TransportPolicyEnvelope(
            version = CurrentTransportPolicyEnvelopeVersion,
            policy = observation.transportPolicy,
            ipSetDigest = observation.ipSetDigest?.trim().orEmpty(),
            dnsClassification = observation.dnsClassification ?: existingEnvelope?.dnsClassification,
            transportClass = observation.transportClass,
            reasonCode = observation.reasonCode,
            cooldownUntil = observation.cooldownUntil?.takeIf { it > 0L },
        )
    }
    return TransportPolicyEnvelope(
        version = CurrentTransportPolicyEnvelopeVersion,
        policy = existingEnvelope?.policy ?: TransportPolicy(),
        ipSetDigest =
            observation.ipSetDigest
                ?.trim()
                .orEmpty()
                .ifEmpty { existingEnvelope?.ipSetDigest.orEmpty() },
        dnsClassification = observation.dnsClassification ?: existingEnvelope?.dnsClassification,
        transportClass = observation.transportClass ?: existingEnvelope?.transportClass,
        reasonCode = observation.reasonCode ?: existingEnvelope?.reasonCode,
        cooldownUntil = observation.cooldownUntil?.takeIf { it > 0L } ?: existingEnvelope?.cooldownUntil,
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ServerCapabilityStoreModule {
    @Binds
    @Singleton
    abstract fun bindServerCapabilityStore(store: SharedPreferencesServerCapabilityStore): ServerCapabilityStore
}
