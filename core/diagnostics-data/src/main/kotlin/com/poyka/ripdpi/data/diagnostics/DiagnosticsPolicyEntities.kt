package com.poyka.ripdpi.data.diagnostics

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "bypass_usage_sessions")
@Serializable
data class BypassUsageSessionEntity(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val updatedAt: Long = 0L,
    val serviceMode: String,
    val connectionState: String = "Running",
    val health: String = "idle",
    val approachProfileId: String?,
    val approachProfileName: String?,
    val strategyId: String,
    val strategyLabel: String,
    val strategyJson: String,
    val networkType: String,
    val publicIp: String? = null,
    val failureClass: String? = null,
    val telemetryNetworkFingerprintHash: String? = null,
    val winningTcpStrategyFamily: String? = null,
    val winningQuicStrategyFamily: String? = null,
    val proxyRttBand: String = "unknown",
    val resolverRttBand: String = "unknown",
    val proxyRouteRetryCount: Long = 0,
    val tunnelRecoveryRetryCount: Long = 0,
    val txBytes: Long,
    val rxBytes: Long,
    val totalErrors: Long,
    val routeChanges: Long,
    val restartCount: Int,
    val endedReason: String?,
    val failureMessage: String? = null,
    val rememberedPolicyMatchedFingerprintHash: String? = null,
    val rememberedPolicySource: String? = null,
    val rememberedPolicyAppliedByExactMatch: Boolean? = null,
    val rememberedPolicyPreviousSuccessCount: Int? = null,
    val rememberedPolicyPreviousFailureCount: Int? = null,
    val rememberedPolicyPreviousConsecutiveFailureCount: Int? = null,
)

@Entity(
    tableName = "remembered_network_policies",
    indices = [
        Index(
            name = "index_remembered_network_policies_fingerprintHash_mode",
            value = ["fingerprintHash", "mode"],
            unique = true,
        ),
        Index(
            name = "index_remembered_network_policies_updatedAt",
            value = ["updatedAt"],
        ),
        Index(
            name = "index_remembered_network_policies_mode_status_suppressedUntil",
            value = ["mode", "status", "suppressedUntil"],
        ),
    ],
)
@Serializable
data class RememberedNetworkPolicyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val fingerprintHash: String,
    val mode: String,
    val summaryJson: String,
    val proxyConfigJson: String,
    val vpnDnsPolicyJson: String? = null,
    val strategySignatureJson: String? = null,
    val winningTcpStrategyFamily: String? = null,
    val winningQuicStrategyFamily: String? = null,
    val connectionConcurrencyPolicyJson: String? = null,
    val source: String,
    val status: String,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val consecutiveFailureCount: Int = 0,
    val firstObservedAt: Long,
    val lastValidatedAt: Long? = null,
    val lastAppliedAt: Long? = null,
    val suppressedUntil: Long? = null,
    val updatedAt: Long,
)
