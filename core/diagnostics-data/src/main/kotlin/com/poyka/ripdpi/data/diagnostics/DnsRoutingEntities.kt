package com.poyka.ripdpi.data.diagnostics

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(
    tableName = "network_dns_path_preferences",
    indices = [
        Index(
            name = "index_network_dns_path_preferences_fingerprintHash",
            value = ["fingerprintHash"],
            unique = true,
        ),
        Index(
            name = "index_network_dns_path_preferences_updatedAt",
            value = ["updatedAt"],
        ),
    ],
)
@Serializable
data class NetworkDnsPathPreferenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val fingerprintHash: String,
    val summaryJson: String,
    val pathJson: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "network_dns_blocked_paths",
    indices = [
        Index(
            name = "index_network_dns_blocked_paths_lookup",
            value = ["fingerprintHash", "pathKey"],
            unique = true,
        ),
        Index(
            name = "index_network_dns_blocked_paths_updatedAt",
            value = ["updatedAt"],
        ),
    ],
)
data class NetworkDnsBlockedPathEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val fingerprintHash: String,
    val pathKey: String,
    val blockReason: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "network_edge_preferences",
    indices = [
        Index(
            name = "index_network_edge_preferences_lookup",
            value = ["fingerprintHash", "host", "transportKind"],
            unique = true,
        ),
        Index(
            name = "index_network_edge_preferences_updatedAt",
            value = ["updatedAt"],
        ),
    ],
)
@Serializable
data class NetworkEdgePreferenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val fingerprintHash: String,
    val host: String,
    val transportKind: String,
    val summaryJson: String,
    val edgesJson: String,
    val updatedAt: Long,
)
