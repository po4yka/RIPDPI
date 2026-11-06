package com.poyka.ripdpi.data.persistence

import androidx.room.Entity

@Entity(tableName = "profile_records", primaryKeys = ["namespace", "id"])
class ProfileRecordEntity(
    val namespace: String,
    val id: String,
    val payload: ByteArray,
)
