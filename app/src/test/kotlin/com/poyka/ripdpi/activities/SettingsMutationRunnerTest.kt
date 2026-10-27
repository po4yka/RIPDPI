package com.poyka.ripdpi.activities

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
    fun `a failed settings write does not escape as an uncaught exception`() =
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

            runner.updateSetting(key = "k", value = "v") { setBackupPin("") }
            runner.launch { updateDirect { setBiometricEnabled(false) } }

            assertEquals(emptyList<Throwable>(), uncaught)
            assertEquals(emptyList<SettingsEffect>(), effects.replayCache)
        }

    private class FailingAppSettingsRepository : AppSettingsRepository {
        private val delegate = FakeAppSettingsRepository()

        override val settings = delegate.settings

        override suspend fun snapshot(): AppSettings = delegate.snapshot()

        override suspend fun update(transform: AppSettings.Builder.() -> Unit): Unit = throw IOException("disk full")

        override suspend fun replace(settings: AppSettings): Unit = throw IOException("disk full")
    }
}
