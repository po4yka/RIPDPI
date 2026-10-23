package com.poyka.ripdpi.data

import android.content.Context
import com.poyka.ripdpi.serialization.RipDpiStrictJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import javax.inject.Inject
import javax.inject.Singleton

interface ProfileQualityBudgetPersistence {
    fun read(): String?

    fun commit(value: String)
}

@Singleton
class CheckedProfileQualityBudgetPersistence
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : ProfileQualityBudgetPersistence {
        private val preferences = context.getSharedPreferences("profile_quality_budget", Context.MODE_PRIVATE)

        override fun read(): String? = preferences.getString("ledger", null)

        override fun commit(value: String) {
            check(preferences.edit().putString("ledger", value).commit()) { "Quality budget persistence failed" }
        }
    }

/** No identifiers or wall-clock credit. Admission is durable before the caller may open a socket. */
@Singleton
class ProfileQualityBudgetLedger internal constructor(
    private val persistence: ProfileQualityBudgetPersistence,
    private val clock: PauseClock,
) {
    @Inject
    constructor(persistence: CheckedProfileQualityBudgetPersistence, clock: PauseClock) :
        this(persistence as ProfileQualityBudgetPersistence, clock)

    @Synchronized
    fun reserve(
        groupId: String,
        policy: PayloadFailoverPolicy,
    ): ProfileQualityBudget {
        val quality = requireNotNull(policy.quality)
        return try {
            reserveChecked(groupId, policy, quality)
        } catch (_: RuntimeException) {
            unavailable(groupId, quality)
        }
    }

    private fun reserveChecked(
        groupId: String,
        policy: PayloadFailoverPolicy,
        quality: ProfileQualityPolicy,
    ): ProfileQualityBudget {
        val reading = clock.read()
        val boot = requireNotNull(reading.bootCount)
        val now = reading.elapsedMillis
        require(now >= 0 && boot >= 0)
        val stored =
            persistence.read()?.let {
                require(it.length <= MaxLedgerCharacters)
                RipDpiStrictJson.decodeFromString<QualityLedger>(it)
            }
        stored?.validate()
        val entries =
            stored
                ?.entries
                .orEmpty()
                .map {
                    if (stored?.bootCount == boot) {
                        require(it.elapsedMillis <= now)
                        it
                    } else {
                        it.copy(elapsedMillis = now)
                    }
                }.filter { now - it.elapsedMillis < RetentionMillis }
        val constraints =
            listOf(quality.windowSeconds to quality.maxWindowBytes) +
                entries
                    .filter { now - it.elapsedMillis < it.windowSeconds * MillisPerSecond }
                    .map { it.windowSeconds to it.maxWindowBytes }
                    .distinct()
        val charge = policy.reservedQualityPassBytes()
        val blockedUntil =
            constraints
                .mapNotNull { (window, cap) ->
                    val active = entries.filter { now - it.elapsedMillis < window * MillisPerSecond }
                    if (active.sumOf { it.bytes } + charge > cap) {
                        active.maxOfOrNull { it.elapsedMillis + window * MillisPerSecond }
                    } else {
                        null
                    }
                }.maxOrNull()
        val full = entries.size >= MaxLedgerEntries
        val next = blockedUntil ?: if (full) entries.minOf { it.elapsedMillis + RetentionMillis } else null
        val admitted = next == null
        val updated =
            if (admitted) {
                entries + QualityCharge(now, charge, quality.windowSeconds, quality.maxWindowBytes)
            } else {
                entries
            }
        persistence.commit(
            RipDpiStrictJson.encodeToString(QualityLedger(version = 1, bootCount = boot, entries = updated)),
        )
        return ProfileQualityBudget(
            groupId = groupId,
            reservedPassBytes = if (admitted) charge else 0,
            consumedWindowBytes =
                updated
                    .filter { now - it.elapsedMillis < quality.windowSeconds * MillisPerSecond }
                    .sumOf { it.bytes },
            maxWindowBytes = quality.maxWindowBytes,
            nextProbeAtElapsedMillis = next,
            reason = if (admitted) ProfileQualityReason.Verified else ProfileQualityReason.BudgetExhausted,
        )
    }

    private fun unavailable(
        groupId: String,
        policy: ProfileQualityPolicy,
    ) = ProfileQualityBudget(
        groupId,
        0,
        0,
        policy.maxWindowBytes,
        null,
        ProfileQualityReason.BudgetUnavailable,
    )
}

@Serializable
private data class QualityLedger(
    val version: Int,
    val bootCount: Int,
    val entries: List<QualityCharge>,
) {
    fun validate() {
        require(version == 1 && bootCount >= 0 && entries.size <= MaxLedgerEntries)
        entries.forEach { it.validate() }
    }
}

@Serializable
private data class QualityCharge(
    val elapsedMillis: Long,
    val bytes: Long,
    val windowSeconds: Int,
    val maxWindowBytes: Long,
) {
    fun validate() {
        require(elapsedMillis >= 0 && bytes in 1..MaximumWindowBytes)
        require(windowSeconds in MinimumWindowSeconds..MaxQualityWindowSeconds)
        require(maxWindowBytes in bytes..MaximumWindowBytes)
    }
}

private const val MillisPerSecond = 1_000L
private const val RetentionMillis = MaxQualityWindowSeconds * MillisPerSecond
private const val MaxLedgerEntries = 4_096
private const val MaximumWindowBytes = 268_435_456L
private const val MinimumWindowSeconds = 60

private const val MaxLedgerCharacters = 1_048_576
