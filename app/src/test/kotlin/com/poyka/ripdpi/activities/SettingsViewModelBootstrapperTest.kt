package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.InMemoryStrategyPackStateStore
import com.poyka.ripdpi.data.StrategyPackRuntimeState
import com.poyka.ripdpi.security.PinVerifier
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelBootstrapperTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `initialize loads catalogs wires initial runtime state and clears invalid pin state`() =
        runTest {
            val settingsRepository =
                FakeAppSettingsRepository(
                    AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setBackupPin("1234")
                        .setBiometricEnabled(true)
                        .build(),
                )
            val strategyPackStateStore = InMemoryStrategyPackStateStore()
            val effects = MutableSharedFlow<SettingsEffect>(extraBufferCapacity = 1)
            val noticeDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    effects.first() as SettingsEffect.Notice
                }
            val runtimeStates = mutableListOf<StrategyPackRuntimeState>()
            var hostPackCatalogLoads = 0
            var strategyPackCatalogLoads = 0

            SettingsViewModelBootstrapper(
                appSettingsRepository = settingsRepository,
                strategyPackStateStore = strategyPackStateStore,
                pinVerifier =
                    object : PinVerifier {
                        override fun hashPin(pin: String): String = pin

                        override fun verify(
                            candidatePin: String,
                            storedHash: String,
                        ): Boolean = candidatePin == storedHash

                        override fun isKeyAvailable(): Boolean = false
                    },
            ).initialize(
                scope = backgroundScope,
                stringResolver = FakeStringResolver(),
                effects = effects,
                loadInitialHostPackCatalog = { hostPackCatalogLoads += 1 },
                loadInitialStrategyPackCatalog = { strategyPackCatalogLoads += 1 },
                updateStrategyPackCatalogRuntimeState = { runtimeState ->
                    runtimeStates += runtimeState
                },
            )

            advanceUntilIdle()

            val notice = noticeDeferred.await()
            val settings = settingsRepository.snapshot()

            assertEquals(1, hostPackCatalogLoads)
            assertEquals(1, strategyPackCatalogLoads)
            assertTrue(runtimeStates.isNotEmpty())
            assertEquals(R.string.notice_pin_key_lost_title.toString(), notice.title)
            assertEquals(R.string.notice_pin_key_lost_message.toString(), notice.message)
            assertEquals(SettingsNoticeTone.Warning, notice.tone)
            assertEquals("", settings.backupPin)
            assertFalse(settings.biometricEnabled)

            assertEquals(strategyPackStateStore.state.value, runtimeStates.last())
        }

    @Test
    fun `initialize keeps propagating later strategy pack runtime state updates`() =
        runTest {
            val settingsRepository = FakeAppSettingsRepository()
            val strategyPackStateStore = InMemoryStrategyPackStateStore()
            val effects = MutableSharedFlow<SettingsEffect>(extraBufferCapacity = 1)
            val runtimeStates = mutableListOf<StrategyPackRuntimeState>()

            SettingsViewModelBootstrapper(
                appSettingsRepository = settingsRepository,
                strategyPackStateStore = strategyPackStateStore,
                pinVerifier =
                    object : PinVerifier {
                        override fun hashPin(pin: String): String = pin

                        override fun verify(
                            candidatePin: String,
                            storedHash: String,
                        ): Boolean = candidatePin == storedHash

                        override fun isKeyAvailable(): Boolean = true
                    },
            ).initialize(
                scope = backgroundScope,
                stringResolver = FakeStringResolver(),
                effects = effects,
                loadInitialHostPackCatalog = {},
                loadInitialStrategyPackCatalog = {},
                updateStrategyPackCatalogRuntimeState = { runtimeState ->
                    runtimeStates += runtimeState
                },
            )

            val updatedState =
                StrategyPackRuntimeState(
                    selectedPackId = "pack-b",
                    selectedPackVersion = "2026.04.23",
                )
            strategyPackStateStore.update(updatedState)
            advanceUntilIdle()

            assertEquals(updatedState, runtimeStates.last())
        }

    @Test
    fun `initialize leaves pin state unchanged when key is available`() =
        runTest {
            val initialSettings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setBackupPin("1234")
                    .setBiometricEnabled(true)
                    .build()
            val settingsRepository = FakeAppSettingsRepository(initialSettings)
            val strategyPackStateStore = InMemoryStrategyPackStateStore()
            val effects = MutableSharedFlow<SettingsEffect>(extraBufferCapacity = 1)
            val emittedEffects = mutableListOf<SettingsEffect>()
            val collector = backgroundScope.launch { effects.collect { emittedEffects += it } }

            SettingsViewModelBootstrapper(
                appSettingsRepository = settingsRepository,
                strategyPackStateStore = strategyPackStateStore,
                pinVerifier =
                    object : PinVerifier {
                        override fun hashPin(pin: String): String = pin

                        override fun verify(
                            candidatePin: String,
                            storedHash: String,
                        ): Boolean = candidatePin == storedHash

                        override fun isKeyAvailable(): Boolean = true
                    },
            ).initialize(
                scope = backgroundScope,
                stringResolver = FakeStringResolver(),
                effects = effects,
                loadInitialHostPackCatalog = {},
                loadInitialStrategyPackCatalog = {},
                updateStrategyPackCatalogRuntimeState = {},
            )

            advanceUntilIdle()
            collector.cancel()

            val settings = settingsRepository.snapshot()
            assertEquals("1234", settings.backupPin)
            assertTrue(settings.biometricEnabled)
            assertTrue(emittedEffects.isEmpty())
        }
}
