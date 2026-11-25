package com.poyka.ripdpi.data.diagnostics

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsStrategyProbeSessionRoomTest : DiagnosticsRoomStoreTestBase() {
    @Test
    fun `latest strategy probe session is found behind any number of other scans`() =
        runTest {
            suspend fun scan(
                id: String,
                startedAt: Long,
                reportJson: String?,
            ) = dao.upsertScanSession(
                ScanSessionEntity(
                    id = id,
                    profileId = "profile",
                    pathMode = "RAW_PATH",
                    serviceMode = null,
                    status = "completed",
                    summary = "",
                    reportJson = reportJson,
                    startedAt = startedAt,
                    finishedAt = startedAt,
                    networkScopeKey = "scope-$id",
                ),
            )
            scan("compact-test", startedAt = 1, reportJson = """{"strategyProbeReport":{"suiteId":"quick_v1"}}""")
            scan(
                "pretty-test",
                startedAt = 2,
                reportJson = "{\n  \"strategyProbeReport\": {\n    \"suiteId\": \"quick_v1\"\n  }\n}",
            )
            scan("no-key", startedAt = 3, reportJson = """{"summary":"ok"}""")
            scan("compact-null", startedAt = 4, reportJson = """{"strategyProbeReport":null}""")
            scan("pretty-null", startedAt = 5, reportJson = "{\n  \"strategyProbeReport\": null\n}")
            scan("as-text", startedAt = 6, reportJson = """{"summary":"\\"strategyProbeReport\\":{"}""")
            scan(
                "oversized-test",
                startedAt = 7,
                reportJson = """{"strategyProbeReport":{"suiteId":"${"x".repeat(1_500_001)}"}}""",
            )
            repeat(60) { index -> scan("running-$index", startedAt = 10L + index, reportJson = null) }

            val latest = dao.observeLatestStrategyProbeSession().first()

            assertEquals("pretty-test", latest?.id)
            assertEquals("scope-pretty-test", latest?.networkScopeKey)
        }
}
