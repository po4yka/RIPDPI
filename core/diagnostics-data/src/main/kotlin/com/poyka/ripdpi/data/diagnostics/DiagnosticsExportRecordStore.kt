package com.poyka.ripdpi.data.diagnostics

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

interface DiagnosticsExportRecordStore {
    suspend fun getExportRecords(): List<ExportRecordEntity>

    suspend fun insertExportRecord(record: ExportRecordEntity)

    suspend fun deleteExportRecords(recordIds: List<String>)
}

@Singleton
class RoomDiagnosticsExportRecordStore
    @Inject
    constructor(
        private val dao: DiagnosticsDao,
    ) : DiagnosticsExportRecordStore {
        override suspend fun getExportRecords(): List<ExportRecordEntity> = dao.getExportRecords()

        override suspend fun insertExportRecord(record: ExportRecordEntity) = dao.insertExportRecord(record)

        override suspend fun deleteExportRecords(recordIds: List<String>) {
            if (recordIds.isNotEmpty()) {
                dao.deleteExportRecords(recordIds)
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsExportRecordStoreModule {
    @Binds
    @Singleton
    abstract fun bindDiagnosticsExportRecordStore(
        store: RoomDiagnosticsExportRecordStore,
    ): DiagnosticsExportRecordStore
}
