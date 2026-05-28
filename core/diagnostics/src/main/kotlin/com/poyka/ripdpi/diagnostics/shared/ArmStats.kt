package com.poyka.ripdpi.diagnostics.shared

/**
 * Accumulates success/failure counts for a diagnostic arm regardless of
 * which transport mode (transparent or owned-stack) the arm belongs to.
 *
 * Consumed by both mode paths; lives here so neither mode arm needs to
 * depend on the other. Intentionally carries no platform HTTP or VpnService
 * references.
 */
data class ArmStats(
    val successCount: Int,
    val failureCount: Int,
) {
    val totalAttempts: Int get() = successCount + failureCount

    /**
     * Returns the fraction of successful attempts, or null when no attempt
     * has been recorded yet.
     */
    val successRate: Double?
        get() = if (totalAttempts == 0) null else successCount.toDouble() / totalAttempts
}
