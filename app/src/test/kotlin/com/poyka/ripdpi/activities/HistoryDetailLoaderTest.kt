package com.poyka.ripdpi.activities

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoryDetailLoaderTest {
    private val connectionDetailUiFactory =
        HistoryConnectionDetailUiFactory(
            context = ApplicationProvider.getApplicationContext(),
            coreSupport = DiagnosticsUiCoreSupport(),
        )

    @Test
    fun `missing connection session returns null`() =
        kotlinx.coroutines.test.runTest {
            val source = FakeDiagnosticsHistorySource()
            val detailLoader = FakeHistoryDiagnosticsDetailLoader()
            val mapper = FakeDiagnosticsSessionDetailUiMapper()
            val loader =
                DefaultHistoryDetailLoader(
                    diagnosticsHistorySource = source,
                    diagnosticsDetailLoader = detailLoader,
                    connectionDetailUiFactory = connectionDetailUiFactory,
                    diagnosticsSessionDetailUiMapper = mapper,
                )

            assertNull(loader.loadConnectionDetail("missing"))
        }

    @Test
    fun `connection detail loader composes repository slices`() =
        kotlinx.coroutines.test.runTest {
            val source =
                FakeDiagnosticsHistorySource().apply {
                    connectionDetails["connection-1"] =
                        com.poyka.ripdpi.diagnostics.DiagnosticConnectionDetail(
                            session = historyConnectionSession(),
                            snapshots = listOf(historySnapshot(connectionSessionId = "connection-1")),
                            contexts = listOf(historyContext(connectionSessionId = "connection-1")),
                            telemetry = listOf(historyTelemetry(connectionSessionId = "connection-1")),
                            events = listOf(historyEvent(connectionSessionId = "connection-1")),
                        )
                }
            val loader =
                DefaultHistoryDetailLoader(
                    diagnosticsHistorySource = source,
                    diagnosticsDetailLoader = FakeHistoryDiagnosticsDetailLoader(),
                    connectionDetailUiFactory = connectionDetailUiFactory,
                    diagnosticsSessionDetailUiMapper = FakeDiagnosticsSessionDetailUiMapper(),
                )

            val detail = loader.loadConnectionDetail("connection-1")

            assertNotNull(detail)
            assertEquals("connection-1", detail?.session?.id)
            assertEquals(1, detail?.snapshots?.size)
            assertEquals(1, detail?.events?.size)
        }

    @Test
    fun `diagnostics detail loader delegates to manager and mapper`() =
        kotlinx.coroutines.test.runTest {
            val detailLoader =
                FakeHistoryDiagnosticsDetailLoader().apply {
                    nextDetail = historyDiagnosticsDetail("scan-1")
                }
            val mapper =
                FakeDiagnosticsSessionDetailUiMapper().apply {
                    nextResult = historyDiagnosticsDetailUi("scan-1")
                }
            val loader =
                DefaultHistoryDetailLoader(
                    diagnosticsHistorySource = FakeDiagnosticsHistorySource(),
                    diagnosticsDetailLoader = detailLoader,
                    connectionDetailUiFactory = connectionDetailUiFactory,
                    diagnosticsSessionDetailUiMapper = mapper,
                )

            val detail = loader.loadDiagnosticsDetail("scan-1")

            assertSame(detailLoader.nextDetail, mapper.lastDetail)
            assertEquals(false, mapper.lastSensitiveDetailsFlag)
            assertSame(mapper.nextResult, detail)
        }
}
