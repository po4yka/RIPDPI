package com.poyka.ripdpi.data.diagnostics

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "scan_sessions")
@Serializable
data class ScanSessionEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val approachProfileId: String? = null,
    val approachProfileName: String? = null,
    val strategyId: String? = null,
    val strategyLabel: String? = null,
    val strategyJson: String? = null,
    val pathMode: String,
    val serviceMode: String?,
    val status: String,
    val summary: String,
    val reportJson: String?,
    val reportCompletionKind: String? = null,
    val reportTerminationReason: String? = null,
    val startedAt: Long,
    val finishedAt: Long?,
    val launchOrigin: String? = null,
    val triggerType: String? = null,
    val triggerClassification: String? = null,
    val triggerOccurredAt: Long? = null,
    val triggerPreviousFingerprintHash: String? = null,
    val triggerCurrentFingerprintHash: String? = null,
)

@Entity(tableName = "probe_results")
@Serializable
data class ProbeResultEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val probeType: String,
    val target: String,
    val outcome: String,
    val detailJson: String,
    val createdAt: Long,
)
