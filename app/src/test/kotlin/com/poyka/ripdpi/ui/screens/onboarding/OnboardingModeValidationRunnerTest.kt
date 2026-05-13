package com.poyka.ripdpi.ui.screens.onboarding

import com.poyka.ripdpi.activities.FakeAppSettingsRepository
import com.poyka.ripdpi.activities.FakeServiceStateStore
import com.poyka.ripdpi.activities.FakeStringResolver
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.services.OwnedTlsClientFactory
import com.poyka.ripdpi.services.OwnedTlsFingerprintSelection
import com.poyka.ripdpi.services.ServiceController
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.TlsVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OnboardingModeValidationRunnerTest {
    @Test
    fun `connectivity probe runs on injected io dispatcher`() =
        runTest {
            val ioExecutor =
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "onboarding-io-test")
                }
            val ioDispatcher = ioExecutor.asCoroutineDispatcher()
            val serviceStateStore = FakeServiceStateStore()
            val serviceController = RunningServiceController(serviceStateStore)
            val tlsClientFactory = RecordingOwnedTlsClientFactory()
            val runner =
                DefaultOnboardingModeValidationRunner(
                    appSettingsRepository = FakeAppSettingsRepository(),
                    serviceController = serviceController,
                    serviceStateStore = serviceStateStore,
                    tlsClientFactory = tlsClientFactory,
                    stringResolver = FakeStringResolver(),
                    dispatchers =
                        AppCoroutineDispatchers(
                            default = UnconfinedTestDispatcher(testScheduler),
                            io = ioDispatcher,
                            main = UnconfinedTestDispatcher(testScheduler),
                        ),
                )

            try {
                val result = runner.validate(Mode.VPN) { }

                assertTrue(result is OnboardingValidationResult.Success)
                assertEquals(listOf(Mode.VPN), serviceController.startedModes)
                assertEquals("onboarding-io-test", tlsClientFactory.callThreadName)
            } finally {
                ioDispatcher.close()
                ioExecutor.shutdownNow()
            }
        }

    private class RunningServiceController(
        private val serviceStateStore: FakeServiceStateStore,
    ) : ServiceController {
        val startedModes = mutableListOf<Mode>()

        override fun start(mode: Mode) {
            startedModes += mode
            serviceStateStore.setStatus(AppStatus.Running, mode)
        }

        override fun stop() {
            serviceStateStore.setStatus(AppStatus.Halted, serviceStateStore.status.value.second)
        }
    }

    private class RecordingOwnedTlsClientFactory : OwnedTlsClientFactory {
        var callThreadName: String? = null

        override fun currentProfile(): String = "test"

        override fun selectionForAuthority(authority: String?): OwnedTlsFingerprintSelection =
            OwnedTlsFingerprintSelection(profileId = "test")

        override fun create(
            forcedTlsVersions: List<TlsVersion>?,
            configure: OkHttpClient.Builder.() -> Unit,
        ): OkHttpClient =
            OkHttpClient
                .Builder()
                .addInterceptor(RecordingInterceptor())
                .apply(configure)
                .build()

        override fun createForAuthority(
            authority: String?,
            forcedTlsVersions: List<TlsVersion>?,
            configure: OkHttpClient.Builder.() -> Unit,
        ): OkHttpClient = create(forcedTlsVersions, configure)

        private inner class RecordingInterceptor : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                callThreadName = Thread.currentThread().name
                return Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(204)
                    .message("No Content")
                    .body("".toResponseBody(null))
                    .build()
            }
        }
    }
}
