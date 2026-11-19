package com.poyka.ripdpi.services.fieldreport

import android.app.Application
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.IOException

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
            FieldReportConsentFollower(application, failing, ledger()).follow()
        }
}
