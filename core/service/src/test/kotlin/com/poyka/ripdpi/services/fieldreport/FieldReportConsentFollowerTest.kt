package com.poyka.ripdpi.services.fieldreport

import android.app.Application
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.subscription.RipdpiFieldReport
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.IOException
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class FieldReportConsentFollowerTest {
    private val application: Application = RuntimeEnvironment.getApplication()

    private class Settings(
        override val settings: Flow<AppSettings>,
    ) : AppSettingsRepository {
        override suspend fun snapshot(): AppSettings = AppSettings.getDefaultInstance()

        override suspend fun update(transform: AppSettings.Builder.() -> Unit) = Unit

        override suspend fun replace(settings: AppSettings) = Unit
    }

    private fun ledger() =
        FieldReportLedger(
            File.createTempFile("field-report", ".json").apply { delete() },
            { false },
            { 0L },
            { FieldReportNetwork("none", "other") },
        )

    @Test
    fun `a failing settings flow stops the follower without escaping`() =
        runTest {
            val failing = Settings(flow { throw IOException("datastore unreadable") })

            // Completing normally is the assertion: an escaped exception would fail the test.
            FieldReportConsentFollower(application, failing, ledger(), networkContext()).follow()
        }

    @Test
    fun `revoking consent drops the cached network hint`() =
        runTest {
            val context = networkContext()
            context.refreshHint(listOf(target))
            assertEquals("server_hint", context.current().source)
            val revoked = Settings(flowOf(AppSettings.newBuilder().setFieldReportEnabled(false).build()))

            FieldReportConsentFollower(application, revoked, ledger(), context).follow()

            assertEquals(FieldReportNetwork("none", "wifi"), context.current())
        }

    private val target =
        RipdpiFieldReport(
            "https://reports.example.test/v1/field-report",
            "cohort-a",
            4,
            networkHintEndpoint = "https://reports.example.test/v1/network-hint",
        )

    private fun networkContext() =
        FieldReportNetworkContext(
            consent = { true },
            defaultNetwork = { DefaultNetwork(handle = 101, networkType = "wifi", vpn = false) },
            operatorCode = { null },
            fetcher = { _, _, _ -> contractFile("client-network-hint.golden.json").readText() },
            clock = { epoch("2026-11-19T10:30:00Z") },
            random = Random(7),
            pause = {},
        )
}
