package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.InMemoryStrategyPackStateStore
import com.poyka.ripdpi.data.StrategyPackRuntimeState
import com.poyka.ripdpi.security.PinVerifier
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
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
                loadInitialHostPackCatalog = { hostPackCatalogLoads += 1 },
                loadInitialStrategyPackCatalog = { strategyPackCatalogLoads += 1 },
                updateStrategyPackCatalogRuntimeState = { runtimeState ->
                    runtimeStates += runtimeState
                },
            )

            advanceUntilIdle()
            yield()
            runCurrent()

            val settings = settingsRepository.snapshot()

            assertEquals(1, hostPackCatalogLoads)
            assertEquals(1, strategyPackCatalogLoads)
            assertTrue(runtimeStates.isNotEmpty())
            assertEquals("", settings.backupPin)
            assertFalse(settings.biometricEnabled)
            assertTrue(settings.appLockResetNoticePending)

            assertEquals(strategyPackStateStore.state.value, runtimeStates.last())
        }

    @Test
    fun `initialize keeps the pin when the keystore cannot be read`() =
        runTest {
            val settingsRepository =
                FakeAppSettingsRepository(
                    AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setBackupPin("1234")
                        .setBiometricEnabled(true)
                        .build(),
                )

            SettingsViewModelBootstrapper(
                appSettingsRepository = settingsRepository,
                strategyPackStateStore = InMemoryStrategyPackStateStore(),
                pinVerifier =
                    object : PinVerifier {
                        override fun hashPin(pin: String): String = pin

                        override fun verify(
                            candidatePin: String,
                            storedHash: String,
                        ): Boolean = candidatePin == storedHash

                        // The keystore threw: whether the key exists is unknown.
                        override fun isKeyAvailable(): Boolean? = null
                    },
            ).initialize(
                scope = backgroundScope,
                loadInitialHostPackCatalog = {},
                loadInitialStrategyPackCatalog = {},
                updateStrategyPackCatalogRuntimeState = {},
            )
            advanceUntilIdle()

            val settings = settingsRepository.snapshot()
            assertEquals("1234", settings.backupPin)
            assertTrue(settings.biometricEnabled)
        }

    @Test
    fun `initialize keeps propagating later strategy pack runtime state updates`() =
        runTest {
            val settingsRepository = FakeAppSettingsRepository()
            val strategyPackStateStore = InMemoryStrategyPackStateStore()
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
                loadInitialHostPackCatalog = {},
                loadInitialStrategyPackCatalog = {},
                updateStrategyPackCatalogRuntimeState = { runtimeState ->
                    runtimeStates += runtimeState
                },
            )
            yield()
            runCurrent()

            val updatedState =
                StrategyPackRuntimeState(
                    selectedPackId = "pack-b",
                    selectedPackVersion = "2026.04.23",
                )
            strategyPackStateStore.update(updatedState)
            yield()
            runCurrent()

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
                loadInitialHostPackCatalog = {},
                loadInitialStrategyPackCatalog = {},
                updateStrategyPackCatalogRuntimeState = {},
            )

            advanceUntilIdle()

            val settings = settingsRepository.snapshot()
            assertEquals("1234", settings.backupPin)
            assertTrue(settings.biometricEnabled)
            assertFalse(settings.appLockResetNoticePending)
        }
}
