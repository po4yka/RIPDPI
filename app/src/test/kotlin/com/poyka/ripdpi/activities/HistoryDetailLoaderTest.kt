package com.poyka.ripdpi.activities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoryDetailLoaderTest {
    @Test
    fun `missing connection session returns null`() =
        kotlinx.coroutines.test.runTest {
            val source = FakeDiagnosticsHistorySource()
            val loader = DefaultHistoryDetailLoader(source, FakeHistoryDiagnosticsDetailLoader())

            assertNull(loader.loadConnectionDetail("missing"))
        }

    @Test
    fun `connection detail loader preserves repository slices`() =
        kotlinx.coroutines.test.runTest {
            val source =
                FakeDiagnosticsHistorySource().apply {
                    connectionDetails["connection-1"] = historyConnectionDetail()
                }
            val loader = DefaultHistoryDetailLoader(source, FakeHistoryDiagnosticsDetailLoader())

            val detail = loader.loadConnectionDetail("connection-1")

            assertNotNull(detail)
            assertSame(source.connectionDetails["connection-1"], detail)
            assertEquals("connection-1", detail?.session?.id)
            assertEquals(1, detail?.snapshots?.size)
            assertEquals(1, detail?.events?.size)
        }

    @Test
    fun `diagnostics detail loader delegates raw manager data`() =
        kotlinx.coroutines.test.runTest {
            val detailLoader =
                FakeHistoryDiagnosticsDetailLoader().apply {
                    nextDetail = historyDiagnosticsDetail("scan-1")
                }
            val loader = DefaultHistoryDetailLoader(FakeDiagnosticsHistorySource(), detailLoader)

            val detail = loader.loadDiagnosticsDetail("scan-1")

            assertSame(detailLoader.nextDetail, detail)
            assertEquals(1, detailLoader.sessionDetailLoads)
        }
}
