@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.Serializable

@Serializable
enum class ConnectionConcurrencyCellStatus {
    HEALTHY,
    BLOCKED,
    MIXED,
    CONTAMINATED,
    SKIPPED,
}

@Serializable
data class ConnectionConcurrencyObservationFact(
    val cohortId: String,
    val tlsProfileId: String,
    val requestedParallelism: Int,
    val observedPeakParallelism: Int,
    val launchSpreadMs: Int,
    val burstWindowMs: Int,
    val successes: Int,
    val failures: Int,
    val blockSignals: List<String> = emptyList(),
    val status: ConnectionConcurrencyCellStatus,
    val contaminated: Boolean = false,
    val skipReason: String? = null,
)

@Serializable
enum class ConnectionConcurrencyVerdict {
    NO_INTERACTION,
    FINGERPRINT_ONLY,
    CONCURRENCY_ONLY,
    CONJUNCTION_SUSPECTED,
    CONJUNCTION_CONFIRMED,
    INCONCLUSIVE,
}

@Serializable
data class ConnectionConcurrencyAssessment(
    val classifierVersion: String = "connection_concurrency_v1",
    val verdict: ConnectionConcurrencyVerdict,
    val selectedProfileId: String? = null,
    val safeCap: Int? = null,
    val plannedCells: Int,
    val cleanCells: Int,
    val affectedTargets: Int,
    val healthyCapsByProfile: Map<String, Int> = emptyMap(),
    val warnings: List<String> = emptyList(),
)
