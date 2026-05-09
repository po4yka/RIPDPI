package com.poyka.ripdpi.strategy

internal data class StrategyPackSchedulePlan(
    val delayMs: Long,
    val reason: StrategyPackRefreshScheduleReason,
)

internal enum class StrategyPackRefreshScheduleReason(
    val logLabel: String,
) {
    Startup("startup"),
    RelevantSettingsChanged("relevant-settings-changed"),
    TtlDue("ttl-due"),
    RetryBackoff("retry-backoff"),
    ManualRefreshReseed("manual-refresh-reseed"),
}

internal class StrategyPackRefreshBackoffPolicy(
    private val clock: StrategyPackClock,
    private val refreshSuccessTtlMs: Long,
    private val initialFailureBackoffMs: Long,
    private val maxFailureBackoffMs: Long,
) {
    fun nextDelay(
        reason: StrategyPackRefreshScheduleReason,
        lastSuccessfulFetchAtEpochMillis: Long?,
        consecutiveAutomaticFailures: Int,
    ): Long =
        when (reason) {
            StrategyPackRefreshScheduleReason.Startup -> {
                val lastSuccessfulFetchAt = lastSuccessfulFetchAtEpochMillis ?: return 0L
                val dueAt = lastSuccessfulFetchAt + refreshSuccessTtlMs
                (dueAt - clock.nowEpochMillis()).coerceAtLeast(0L)
            }

            StrategyPackRefreshScheduleReason.RelevantSettingsChanged -> {
                0L
            }

            StrategyPackRefreshScheduleReason.TtlDue -> {
                refreshSuccessTtlMs
            }

            StrategyPackRefreshScheduleReason.RetryBackoff -> {
                nextFailureBackoff(consecutiveAutomaticFailures)
            }

            StrategyPackRefreshScheduleReason.ManualRefreshReseed -> {
                refreshSuccessTtlMs
            }
        }

    fun nextFailureBackoff(consecutiveAutomaticFailures: Int): Long {
        val exponent = (consecutiveAutomaticFailures - 1).coerceAtLeast(0).coerceAtMost(maxBackoffExponent)
        return (initialFailureBackoffMs * (1L shl exponent)).coerceAtMost(maxFailureBackoffMs)
    }
}

private const val maxBackoffExponent = 5
