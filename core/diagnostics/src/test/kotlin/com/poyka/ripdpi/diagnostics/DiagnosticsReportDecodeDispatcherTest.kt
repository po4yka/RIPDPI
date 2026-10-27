package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors

class DiagnosticsReportDecodeDispatcherTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `stored reports are decoded off the collecting dispatcher`() =
        runBlocking {
            val uiThread = Executors.newSingleThreadExecutor { task -> Thread(task, UiThreadName) }
            val uiDispatcher = uiThread.asCoroutineDispatcher()
            val stores = FakeDiagnosticsHistoryStores()
            val readThreads = Collections.synchronizedList(mutableListOf<String>())
            val recordingStore =
                object : DiagnosticsScanRecordStore by stores {
                    override fun observeRecentScanSessions(limit: Int): Flow<List<ScanSessionEntity>> =
                        stores.observeRecentScanSessions(limit).onEach { readThreads += Thread.currentThread().name }
                }
            val scope = CoroutineScope(SupervisorJob() + uiDispatcher)
            val timeline =
                DefaultDiagnosticsTimelineSource(
                    profileCatalog = stores,
                    scanRecordStore = recordingStore,
                    artifactReadStore = stores,
                    bypassUsageHistoryStore = stores,
                    mapper = DiagnosticsBoundaryMapper(json),
                    scope = scope,
                    json = json,
                )
            val history =
                DefaultDiagnosticsHistorySource(
                    bypassUsageHistoryStore = stores,
                    scanRecordStore = recordingStore,
                    artifactReadStore = stores,
                    mapper = DiagnosticsBoundaryMapper(json),
                )

            withContext(uiDispatcher) {
                timeline.approachStats.first()
                history.observeDiagnosticsSessions(limit = 10).first()
            }

            assertTrue(
                readThreads.toString(),
                readThreads.size == 2 && readThreads.none { it.startsWith(UiThreadName) },
            )
            scope.cancel()
            uiThread.shutdown()
        }

    private companion object {
        const val UiThreadName = "fake-main"
    }
}
