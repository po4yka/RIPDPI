package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.activities.FakeAppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsMutationRunnerTest {
    @Test
    fun `a failed settings write names the failure instead of escaping or staying silent`() =
        runTest {
            val uncaught = mutableListOf<Throwable>()
            val scope =
                CoroutineScope(
                    SupervisorJob() +
                        UnconfinedTestDispatcher(testScheduler) +
                        CoroutineExceptionHandler { _, error -> uncaught += error },
                )
            val effects = MutableSharedFlow<SettingsEffect>(replay = 4)
            val runner = SettingsMutationRunner(scope, FailingAppSettingsRepository(), effects)

            val dnsNotices = mutableListOf<SettingsEffect.Notice>()

            runner.updateSetting(key = "k", value = "v") { setBackupPin("") }
            runner.launch { updateDirect { setBiometricEnabled(false) } }
            runner.launch(onFailure = { dnsNotices += it }) { updateDirect { setBiometricEnabled(false) } }

            val storageFailure =
                SettingsEffect.Notice(
                    com.poyka.ripdpi.R.string.advanced_settings_unsaved_change,
                    com.poyka.ripdpi.R.string.settings_storage_failed,
                    SettingsNoticeTone.Error,
                )
            assertEquals(emptyList<Throwable>(), uncaught)
            assertEquals(listOf(storageFailure, storageFailure), effects.replayCache)
            assertEquals(listOf(storageFailure), dnsNotices)
        }

    private class FailingAppSettingsRepository : AppSettingsRepository {
        private val delegate = FakeAppSettingsRepository()

        override val settings = delegate.settings

        override suspend fun snapshot(): AppSettings = delegate.snapshot()

        override suspend fun update(transform: AppSettings.Builder.() -> Unit): Unit = throw IOException("disk full")

        override suspend fun replace(settings: AppSettings): Unit = throw IOException("disk full")
    }
}
