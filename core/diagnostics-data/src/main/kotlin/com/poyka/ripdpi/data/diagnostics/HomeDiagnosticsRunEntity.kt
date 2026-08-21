package com.poyka.ripdpi.data.diagnostics

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "home_diagnostics_runs",
    indices = [
        Index(
            name = "index_home_diagnostics_runs_updatedAt",
            value = ["updatedAt"],
        ),
    ],
)
data class HomeDiagnosticsRunEntity(
    @PrimaryKey val runId: String,
    val origin: String,
    val status: String,
    val payloadVersion: Int,
    val payloadJson: String,
    val createdAt: Long,
    val updatedAt: Long,
)
