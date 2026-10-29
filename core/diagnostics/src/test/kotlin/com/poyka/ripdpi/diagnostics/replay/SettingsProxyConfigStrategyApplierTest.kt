package com.poyka.ripdpi.diagnostics.replay

import com.poyka.ripdpi.core.RipDpiFakePacketConfig
import com.poyka.ripdpi.core.RipDpiListenConfig
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsProxyConfigStrategyApplierTest {
    private val repository =
        InMemoryAppSettingsRepository(
            AppSettings
                .newBuilder()
                .setFakeTtl(3)
                .setProxyPort(1080)
                .setNoDomain(false)
                .build(),
        )
    private val applier = SettingsProxyConfigStrategyApplier(repository)
    private val preferences =
        RipDpiProxyUIPreferences(
            fakePackets = RipDpiFakePacketConfig(fakeTtl = 7),
            listen = RipDpiListenConfig(port = 2222),
        )

    @Test
    fun `apply writes strategy fields only and restore puts them back`() =
        runTest {
            val snapshot = applier.apply(preferences)

            assertEquals(7, repository.snapshot().fakeTtl)
            assertEquals("listen port is not a strategy field", 1080, repository.snapshot().proxyPort)

            applier.restore(snapshot)

            assertEquals(3, repository.snapshot().fakeTtl)
            assertEquals(1080, repository.snapshot().proxyPort)
        }

    @Test
    fun `restore keeps an unrelated edit made during the replay`() =
        runTest {
            val snapshot = applier.apply(preferences)
            repository.update { setNoDomain(true) }

            applier.restore(snapshot)

            assertEquals(3, repository.snapshot().fakeTtl)
            assertEquals(true, repository.snapshot().noDomain)
        }

    @Test
    fun `restore keeps a strategy field the user changed during the replay`() =
        runTest {
            val snapshot = applier.apply(preferences)
            repository.update { setFakeTtl(9) }

            applier.restore(snapshot)

            assertEquals(9, repository.snapshot().fakeTtl)
        }
}

private class InMemoryAppSettingsRepository(
    initial: AppSettings,
) : AppSettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state

    override suspend fun snapshot(): AppSettings = state.value

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
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
