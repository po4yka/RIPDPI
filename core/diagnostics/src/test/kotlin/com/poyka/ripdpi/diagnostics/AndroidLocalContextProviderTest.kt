package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DefaultServiceStateStore
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidLocalContextProviderTest {
    @Test
    fun `provider captures local evidence without an active mobile network`() =
        runTest {
            val settings =
                object : AppSettingsRepository {
                    override val settings = MutableStateFlow(AppSettings.getDefaultInstance())

                    override suspend fun snapshot(): AppSettings = settings.value

                    override suspend fun update(transform: AppSettings.Builder.() -> Unit) = Unit

                    override suspend fun replace(settings: AppSettings) = Unit
                }
            val provider =
                AndroidDiagnosticsContextProvider(
                    RuntimeEnvironment.getApplication(),
                    settings,
                    FakeDiagnosticsHistoryStores(),
                    DefaultServiceStateStore(),
                )
            assertNotNull(provider.captureContext().localNetwork)
        }
}
