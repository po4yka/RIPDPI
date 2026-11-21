package com.poyka.ripdpi.data

import android.content.Context
import com.poyka.ripdpi.serialization.RipDpiStrictJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

interface ProfileQualityBudgetPersistence {
    fun read(): ProfileQualityBudgetRecords

    fun commit(value: ProfileQualityBudgetRecords)
}

data class ProfileQualityBudgetRecords(
    val ledger: String? = null,
    val recovery: String? = null,
)

@Singleton
class CheckedProfileQualityBudgetPersistence
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : ProfileQualityBudgetPersistence {
        private val preferences = context.getSharedPreferences("profile_quality_budget", Context.MODE_PRIVATE)

        override fun read(): ProfileQualityBudgetRecords {
            val snapshot = preferences.all
            return ProfileQualityBudgetRecords(snapshot["ledger"] as String?, snapshot["recovery"] as String?)
        }

        override fun commit(value: ProfileQualityBudgetRecords) {
            check(
                preferences
                    .edit()
                    .putString("ledger", value.ledger)
                    .putString("recovery", value.recovery)
                    .commit(),
            ) {
                "Quality budget persistence failed"
            }
        }
    }

enum class PayloadProbePurpose { Scheduled, Recovery }

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
        purpose: PayloadProbePurpose = PayloadProbePurpose.Scheduled,
    ): ProfileQualityBudget {
        val window = policy.quality?.windowSeconds ?: DefaultWindowSeconds
        val cap = policy.quality?.maxWindowBytes ?: DefaultWindowBytes
        return try {
            val charge =
                QualityCharge(
                    0L,
                    policy.reservedQualityPassBytes(),
                    window,
                    cap,
                    purpose == PayloadProbePurpose.Recovery,
                )
            reserveChecked(groupId, charge)
        } catch (_: RuntimeException) {
            ProfileQualityBudget(groupId, 0L, 0L, cap, null, ProfileQualityReason.BudgetUnavailable)
        }
    }

    private fun reserveChecked(
        groupId: String,
        requested: QualityCharge,
    ): ProfileQualityBudget {
        val reading = clock.read()
        val boot = requireNotNull(reading.bootCount)
        val now = reading.elapsedMillis
        require(now >= 0 && boot >= 0)
        val records = persistence.read()
        val stored =
            records.ledger?.let {
                require(it.length <= MaxLedgerCharacters)
                RipDpiStrictJson.decodeFromString<QualityLedger>(it)
            }
        stored?.validate()
        val recoveryIndices = records.recoveryIndices(stored?.entries?.size ?: 0)
        val entries =
            stored
                ?.entries
                .orEmpty()
                .mapIndexed { index, charge ->
                    val entry = charge.copy(recovery = index in recoveryIndices)
                    if (stored?.bootCount == boot) {
                        require(entry.elapsedMillis <= now)
                        entry
                    } else {
                        entry.copy(elapsedMillis = now)
                    }
                }.filter { now - it.elapsedMillis < RetentionMillis }
        val constraints =
            listOf(requested.windowSeconds to requested.maxWindowBytes) +
                entries
                    .filter { now - it.elapsedMillis < it.windowSeconds * MillisPerSecond }
                    .map { it.windowSeconds to it.maxWindowBytes }
                    .distinct()
        val blockedUntil =
            constraints
                .mapNotNull { (window, cap) ->
                    val active = entries.filter { now - it.elapsedMillis < window * MillisPerSecond }
                    if (active.sumOf { it.bytes } + requested.bytes + recoveryHeadroom(active, requested, cap) > cap) {
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
                entries + requested.copy(elapsedMillis = now)
            } else {
                entries
            }
        persistence.commit(QualityLedger(version = 1, bootCount = boot, entries = updated).records())
        return ProfileQualityBudget(
            groupId = groupId,
            reservedPassBytes = if (admitted) requested.bytes else 0,
            consumedWindowBytes =
                updated
                    .filter { now - it.elapsedMillis < requested.windowSeconds * MillisPerSecond }
                    .sumOf { it.bytes },
            maxWindowBytes = requested.maxWindowBytes,
            nextProbeAtElapsedMillis = next,
            reason = if (admitted) ProfileQualityReason.Verified else ProfileQualityReason.BudgetExhausted,
        )
    }

    private fun recoveryHeadroom(
        active: List<QualityCharge>,
        requested: QualityCharge,
        cap: Long,
    ): Long {
        val recoveryPass = maxOf(requested.bytes, active.maxOfOrNull { it.bytes } ?: 0L)
        // Preserve one scheduled slot for policies that cannot fit two passes.
        return if (requested.recovery || requested.bytes + recoveryPass > cap) {
            0L
        } else {
            (recoveryPass - active.filter { it.recovery }.sumOf { it.bytes }).coerceAtLeast(0L)
        }
    }
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
    @Transient val recovery: Boolean = false,
) {
    fun validate() {
        require(elapsedMillis >= 0 && bytes in 1..MaximumWindowBytes)
        require(windowSeconds in MinimumWindowSeconds..MaxQualityWindowSeconds)
        require(maxWindowBytes in bytes..MaximumWindowBytes)
    }
}

// Keep the v1 ledger readable on downgrade. Only use recovery tags matched to the exact byte ledger.
@Serializable
private data class QualityRecoveryTags(
    val ledgerSha256: String,
    val indices: List<Int>,
)

private fun QualityLedger.records(): ProfileQualityBudgetRecords {
    val ledger = RipDpiStrictJson.encodeToString(this)
    val indices = entries.indices.filter { entries[it].recovery }
    val recovery =
        indices.takeIf { it.isNotEmpty() }?.let {
            RipDpiStrictJson.encodeToString(QualityRecoveryTags(ledger.sha256(), it))
        }
    return ProfileQualityBudgetRecords(ledger, recovery)
}

private fun ProfileQualityBudgetRecords.recoveryIndices(size: Int): Set<Int> {
    val raw = recovery?.takeIf { it.length <= MaxLedgerCharacters } ?: return emptySet()
    return try {
        val tags = RipDpiStrictJson.decodeFromString<QualityRecoveryTags>(raw)
        if (tags.ledgerSha256 == ledger?.sha256() && tags.indices.size <= size) {
            tags.indices.filter { it in 0 until size }.toSet()
        } else {
            emptySet()
        }
    } catch (_: IllegalArgumentException) {
        emptySet()
    }
}

private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(toByteArray()).joinToString("") {
        "%02x".format(it)
    }

private const val MillisPerSecond = 1_000L
private const val RetentionMillis = MaxQualityWindowSeconds * MillisPerSecond
private const val MaxLedgerEntries = 4_096
private const val MaximumWindowBytes = 268_435_456L
private const val MinimumWindowSeconds = 60
private const val DefaultWindowSeconds = 60

// Two passes, two members and two controls, with the maximum payload plus its overrun byte.
private const val DefaultWindowBytes = 8L * (1_048_576L + 1L)

private const val MaxLedgerCharacters = 1_048_576
