package com.poyka.ripdpi.failover

import android.content.Context
import com.poyka.ripdpi.services.EgressRequirements
import com.poyka.ripdpi.services.InitialRelayCandidate
import com.poyka.ripdpi.services.RelayHealthScope
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

internal enum class EgressProof(
    val wireValue: String,
) {
    TcpOnly("tcp_only"),
    TcpUdp("tcp_udp"),
    ;

    companion object {
        fun from(requirements: EgressRequirements): EgressProof = if (requirements.udpAssociate) TcpUdp else TcpOnly

        fun fromWireValue(value: String): EgressProof? = entries.firstOrNull { it.wireValue == value }
    }
}

internal interface SimpleEgressHealthMemory {
    fun isCoolingDown(
        scope: RelayHealthScope,
        proof: EgressProof,
        candidate: InitialRelayCandidate,
    ): Boolean

    fun readWinner(
        networkScopeKey: String?,
        signature: String,
        proof: EgressProof,
    ): String?

    fun writeWinner(
        networkScopeKey: String?,
        signature: String,
        proof: EgressProof,
        profileId: String,
    )

    fun recordConfirmedFailure(
        scope: RelayHealthScope,
        proof: EgressProof,
        relayKind: String,
        profileId: String,
    )

    fun clearConfirmedFailure(
        scope: RelayHealthScope,
        proof: EgressProof,
        relayKind: String,
        profileId: String,
    )

    fun clearSession(sessionGeneration: Long)
}

/**
 * Network- and capability-scoped memory for Simple transport selection.
 *
 * Preference keys contain only hashes. Values retain the local profile id needed to restore a
 * winner, but never an endpoint or credential. A confirmed negative result is deliberately short
 * lived so a recovered transport is retried without user intervention.
 */
@Singleton
internal class SimpleEgressHealthCache
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val clock: FailoverClock,
    ) : SimpleEgressHealthMemory {
        private val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        private val sessionFailures = mutableMapOf<SessionNegativeKey, Long>()

        override fun isCoolingDown(
            scope: RelayHealthScope,
            proof: EgressProof,
            candidate: InitialRelayCandidate,
        ): Boolean {
            val persistentScope = scope.persistentNetworkHash?.takeIf(String::isNotBlank)
            if (persistentScope == null) {
                val key = SessionNegativeKey(scope.sessionGeneration, proof, candidate.relayKind, candidate.profileId)
                val failedAt = synchronized(sessionFailures) { sessionFailures[key] } ?: return false
                if (clock.nowMillis() - failedAt in 0 until NegativeCooldownMillis) return true
                synchronized(sessionFailures) { sessionFailures.remove(key) }
                return false
            }
            val key = negativeKey(persistentScope, proof, candidate.relayKind, candidate.profileId)
            val failedAt = preferences.getLong(key, MissingTimestamp)
            if (failedAt == MissingTimestamp) return false
            if (clock.nowMillis() - failedAt in 0 until NegativeCooldownMillis) return true
            preferences.edit().remove(key).apply()
            return false
        }

        override fun readWinner(
            networkScopeKey: String?,
            signature: String,
            proof: EgressProof,
        ): String? {
            val scope = networkScopeKey?.takeIf(String::isNotBlank) ?: return null
            val key = winnerKey(scope, signature)
            val record = preferences.getString(key, null)?.toWinnerRecord() ?: return null
            if (record.proof != proof || clock.nowMillis() - record.confirmedAt !in 0..WinnerTtlMillis) {
                preferences.edit().remove(key).apply()
                return null
            }
            return record.profileId
        }

        override fun writeWinner(
            networkScopeKey: String?,
            signature: String,
            proof: EgressProof,
            profileId: String,
        ) {
            val scope = networkScopeKey?.takeIf(String::isNotBlank) ?: return
            preferences
                .edit()
                .putString(
                    winnerKey(scope, signature),
                    "${clock.nowMillis()}|${proof.wireValue}|$profileId",
                ).apply()
        }

        override fun recordConfirmedFailure(
            scope: RelayHealthScope,
            proof: EgressProof,
            relayKind: String,
            profileId: String,
        ) {
            val persistentScope = scope.persistentNetworkHash?.takeIf(String::isNotBlank)
            if (persistentScope == null) {
                val key = SessionNegativeKey(scope.sessionGeneration, proof, relayKind, profileId)
                synchronized(sessionFailures) { sessionFailures.putIfAbsent(key, clock.nowMillis()) }
                return
            }
            val negativeKey = negativeKey(persistentScope, proof, relayKind, profileId)
            val existing = preferences.getLong(negativeKey, MissingTimestamp)
            if (existing == MissingTimestamp || clock.nowMillis() - existing !in 0 until NegativeCooldownMillis) {
                preferences.edit().putLong(negativeKey, clock.nowMillis()).apply()
            }

            val winnerPrefix = "winner.${sha256(persistentScope)}."
            val staleWinnerKeys =
                preferences.all
                    .filterKeys { it.startsWith(winnerPrefix) }
                    .filterValues { value ->
                        (value as? String)?.toWinnerRecord()?.let { record ->
                            record.proof == proof && record.profileId == profileId
                        } == true
                    }.keys
            if (staleWinnerKeys.isNotEmpty()) {
                preferences.edit().also { editor -> staleWinnerKeys.forEach(editor::remove) }.apply()
            }
        }

        override fun clearConfirmedFailure(
            scope: RelayHealthScope,
            proof: EgressProof,
            relayKind: String,
            profileId: String,
        ) {
            val persistentScope = scope.persistentNetworkHash?.takeIf(String::isNotBlank)
            if (persistentScope == null) {
                synchronized(sessionFailures) {
                    sessionFailures.remove(SessionNegativeKey(scope.sessionGeneration, proof, relayKind, profileId))
                }
            } else {
                preferences.edit().remove(negativeKey(persistentScope, proof, relayKind, profileId)).apply()
            }
        }

        override fun clearSession(sessionGeneration: Long) {
            synchronized(sessionFailures) {
                sessionFailures.keys.removeAll { it.sessionGeneration == sessionGeneration }
            }
        }

        private fun winnerKey(
            networkScopeKey: String,
            signature: String,
        ): String = "winner.${sha256(networkScopeKey)}.$signature"

        private fun negativeKey(
            networkScopeKey: String,
            proof: EgressProof,
            relayKind: String,
            profileId: String,
        ): String =
            "negative.${sha256(networkScopeKey)}.${proof.wireValue}." +
                sha256("$relayKind|$profileId")

        private fun String.toWinnerRecord(): WinnerRecord? {
            val parts = split('|', limit = 3)
            if (parts.size != 3) return null
            return WinnerRecord(
                confirmedAt = parts[0].toLongOrNull() ?: return null,
                proof = EgressProof.fromWireValue(parts[1]) ?: return null,
                profileId = parts[2].takeIf(String::isNotBlank) ?: return null,
            )
        }

        private data class WinnerRecord(
            val confirmedAt: Long,
            val proof: EgressProof,
            val profileId: String,
        )

        private data class SessionNegativeKey(
            val sessionGeneration: Long,
            val proof: EgressProof,
            val relayKind: String,
            val profileId: String,
        )

        internal companion object {
            const val PreferencesName = "simple_initial_relay_race"
            const val WinnerTtlMillis = 24L * 60L * 60L * 1_000L
            const val NegativeCooldownMillis = 15L * 60L * 1_000L
            private const val MissingTimestamp = Long.MIN_VALUE
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SimpleEgressHealthCacheModule {
    @Binds
    abstract fun bindSimpleEgressHealthMemory(cache: SimpleEgressHealthCache): SimpleEgressHealthMemory
}

private fun sha256(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
