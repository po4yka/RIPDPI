package com.poyka.ripdpi.data.diagnostics

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DiagnosticsExportDao {
    @Query("SELECT * FROM export_records ORDER BY createdAt DESC LIMIT :limit")
    fun observeExportRecords(limit: Int = 50): Flow<List<ExportRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExportRecord(record: ExportRecordEntity)

    @Query("SELECT * FROM export_records ORDER BY createdAt DESC")
    suspend fun getExportRecords(): List<ExportRecordEntity>

    @Query("DELETE FROM export_records WHERE id IN (:recordIds)")
    suspend fun deleteExportRecords(recordIds: List<String>)

    @Query("DELETE FROM export_records")
    suspend fun deleteAllExportRecords()
}
