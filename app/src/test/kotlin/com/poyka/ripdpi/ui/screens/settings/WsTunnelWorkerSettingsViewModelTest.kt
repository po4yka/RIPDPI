package com.poyka.ripdpi.ui.screens.settings

import app.cash.turbine.test
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsEffect
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.WsTunnelWorkerCredentialStore
import com.poyka.ripdpi.data.WsTunnelWorkerTransportProvisioner
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WsTunnelWorkerSettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `save failure emits save-specific notice`() =
        runTest {
            val viewModel = viewModel(repositoryFailure = IllegalStateException("disk full"))

            viewModel.effects.test {
                viewModel.save(WorkerUrl, CredentialRef, Bearer)
                runCurrent()

                val notice = awaitItem() as SettingsEffect.Notice
                assertEquals(resource(R.string.ws_tunnel_worker_save_failed_title), notice.title)
                assertEquals(resource(R.string.ws_tunnel_worker_save_failed_message), notice.message)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `clear failure emits disable-specific notice`() =
        runTest {
            val initial =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setWsTunnelWorkerUrl(WorkerUrl)
                    .setWsTunnelWorkerCredentialRef(CredentialRef)
                    .build()
            val viewModel = viewModel(initial = initial, clearFailure = IllegalStateException("keystore"))

            viewModel.effects.test {
                viewModel.clear()
                runCurrent()

                val notice = awaitItem() as SettingsEffect.Notice
                assertEquals(resource(R.string.ws_tunnel_worker_clear_failed_title), notice.title)
                assertEquals(resource(R.string.ws_tunnel_worker_clear_failed_message), notice.message)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `cancellation emits no failure notice`() =
        runTest {
            val viewModel = viewModel(repositoryFailure = CancellationException("cancelled"))

            viewModel.effects.test {
                viewModel.save(WorkerUrl, CredentialRef, Bearer)
                runCurrent()

                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun viewModel(
        initial: AppSettings = AppSettingsSerializer.defaultValue,
        repositoryFailure: Throwable? = null,
        clearFailure: Throwable? = null,
    ): WsTunnelWorkerSettingsViewModel {
        val repository = FakeSettingsRepository(initial, repositoryFailure)
        val store = FakeCredentialStore(clearFailure)
        return WsTunnelWorkerSettingsViewModel(
            WsTunnelWorkerTransportProvisioner(repository, store),
            TestStringResolver,
        )
    }

    private class FakeSettingsRepository(
        initial: AppSettings,
        private val failure: Throwable?,
    ) : AppSettingsRepository {
        private val state = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = state

        override suspend fun snapshot(): AppSettings = state.value

        override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
            failure?.let { throw it }
            state.value =
                state.value
                    .toBuilder()
                    .apply(transform)
                    .build()
        }

        override suspend fun replace(settings: AppSettings) {
            state.value = settings
        }
    }

    private class FakeCredentialStore(
        private val clearFailure: Throwable?,
    ) : WsTunnelWorkerCredentialStore {
        private val credentials = mutableMapOf<String, String>()

        override suspend fun load(credentialRef: String): String? = credentials[credentialRef]

        override suspend fun save(
            credentialRef: String,
            bearer: String,
        ) {
            credentials[credentialRef] = bearer
        }

        override suspend fun clear(credentialRef: String) {
            clearFailure?.let { throw it }
            credentials.remove(credentialRef)
        }

        override suspend fun clearAll() {
            credentials.clear()
        }
    }

    private object TestStringResolver : StringResolver {
        override fun getString(
            resId: Int,
            vararg formatArgs: Any,
        ): String = resource(resId)
    }

    private companion object {
        const val WorkerUrl = "https://edge.example/relay"
        const val CredentialRef = "worker"
        const val Bearer = "secret-token"

        fun resource(resId: Int): String = "res:$resId"
    }
}
