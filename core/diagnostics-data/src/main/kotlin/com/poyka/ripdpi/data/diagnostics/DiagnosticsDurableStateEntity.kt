package com.poyka.ripdpi.data.diagnostics

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "diagnostics_durable_state")
@Serializable
data class DiagnosticsDurableStateEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long,
)
