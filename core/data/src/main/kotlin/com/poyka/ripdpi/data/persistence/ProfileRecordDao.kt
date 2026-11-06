package com.poyka.ripdpi.data.persistence

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileRecordDao {
    @Query("SELECT * FROM profile_records WHERE namespace = :namespace AND id = :id")
    fun get(
        namespace: String,
        id: String,
    ): ProfileRecordEntity?

    @Query("SELECT * FROM profile_records WHERE namespace = :namespace ORDER BY id")
    fun list(namespace: String): List<ProfileRecordEntity>

    @Query("SELECT id FROM profile_records WHERE namespace = :namespace ORDER BY id")
    fun keys(namespace: String): List<String>

    @Query("SELECT * FROM profile_records WHERE namespace = :namespace ORDER BY id")
    fun observeNamespace(namespace: String): Flow<List<ProfileRecordEntity>>

    @Upsert
    fun upsert(record: ProfileRecordEntity)

    @Query("DELETE FROM profile_records WHERE namespace = :namespace AND id = :id")
    fun delete(
        namespace: String,
        id: String,
    )

    @Query("DELETE FROM profile_records WHERE namespace = :namespace")
    fun deleteNamespace(namespace: String)

    @Query("DELETE FROM profile_records")
    fun deleteAll()
}
