package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PolicyHandoverEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.inject.Provider

@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticProbeSchedulerTest {
    @Test
    fun `scheduler launches eligible probe after configured delay`() =
        runTest {
            val launcher = RecordingAutomaticProbeLauncher()
            val scheduler =
                AutomaticProbeScheduler(
                    appSettingsRepository =
                        FakeAppSettingsRepository(
                            defaultDiagnosticsAppSettings()
                                .toBuilder()
                                .setNetworkStrategyMemoryEnabled(true)
                                .build(),
                        ),
                    launcherProvider = constantProvider(launcher),
                    automaticHandoverProbeDelayMs = 1_000L,
                    automaticHandoverProbeCooldownMs = Long.MAX_VALUE,
                    scope = backgroundScope,
                )

            scheduler.schedule(handoverEvent())
            advanceTimeBy(999L)
            runCurrent()
            assertTrue(launcher.events.isEmpty())

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(1, launcher.events.size)
            assertEquals("fingerprint-b", launcher.events.single().currentFingerprintHash)
        }

    @Test
    fun `scheduler suppresses repeated launches within cooldown`() =
        runTest {
            val launcher = RecordingAutomaticProbeLauncher()
            val scheduler =
                AutomaticProbeScheduler(
                    appSettingsRepository =
                        FakeAppSettingsRepository(
                            defaultDiagnosticsAppSettings()
                                .toBuilder()
                                .setNetworkStrategyMemoryEnabled(true)
                                .build(),
                        ),
                    launcherProvider = constantProvider(launcher),
                    automaticHandoverProbeDelayMs = 100L,
                    automaticHandoverProbeCooldownMs = Long.MAX_VALUE,
                    scope = backgroundScope,
                )

            scheduler.schedule(handoverEvent())
            advanceTimeBy(100L)
            runCurrent()
            scheduler.schedule(handoverEvent())
            advanceTimeBy(100L)
            runCurrent()

            assertEquals(1, launcher.events.size)
        }

    @Test
    fun `scheduler replaces pending job for the same mode`() =
        runTest {
            val launcher = RecordingAutomaticProbeLauncher()
            val scheduler =
                AutomaticProbeScheduler(
                    appSettingsRepository =
                        FakeAppSettingsRepository(
                            defaultDiagnosticsAppSettings()
                                .toBuilder()
                                .setNetworkStrategyMemoryEnabled(true)
                                .build(),
                        ),
                    launcherProvider = constantProvider(launcher),
                    automaticHandoverProbeDelayMs = 1_000L,
                    automaticHandoverProbeCooldownMs = 0L,
                    scope = backgroundScope,
                )

            scheduler.schedule(handoverEvent(currentFingerprintHash = "fingerprint-a"))
            advanceTimeBy(500L)
            scheduler.schedule(handoverEvent(currentFingerprintHash = "fingerprint-b"))
            advanceTimeBy(999L)
            runCurrent()
            assertTrue(launcher.events.isEmpty())

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(listOf("fingerprint-b"), launcher.events.map { it.currentFingerprintHash })
        }

    @Test
    fun `scheduler skips launches while another scan is already active`() =
        runTest {
            val launcher = RecordingAutomaticProbeLauncher(hasActiveScan = true)
            val scheduler =
                AutomaticProbeScheduler(
                    appSettingsRepository =
                        FakeAppSettingsRepository(
                            defaultDiagnosticsAppSettings()
                                .toBuilder()
                                .setNetworkStrategyMemoryEnabled(true)
                                .build(),
                        ),
                    launcherProvider = constantProvider(launcher),
                    automaticHandoverProbeDelayMs = 100L,
                    automaticHandoverProbeCooldownMs = 0L,
                    scope = backgroundScope,
                )

            scheduler.schedule(handoverEvent())
            advanceTimeBy(100L)
            runCurrent()

            assertTrue(launcher.events.isEmpty())
        }

    @Test
    fun `scheduler skips launches when settings make probing ineligible`() =
        runTest {
            val launcher = RecordingAutomaticProbeLauncher()
            val scheduler =
                AutomaticProbeScheduler(
                    appSettingsRepository = FakeAppSettingsRepository(defaultDiagnosticsAppSettings()),
                    launcherProvider = constantProvider(launcher),
                    automaticHandoverProbeDelayMs = 100L,
                    automaticHandoverProbeCooldownMs = 0L,
                    scope = backgroundScope,
                )

            scheduler.schedule(handoverEvent())
            advanceTimeBy(100L)
            runCurrent()

            assertTrue(launcher.events.isEmpty())
        }
}

private class RecordingAutomaticProbeLauncher(
    private val hasActiveScan: Boolean = false,
) : AutomaticProbeLauncher {
    val events = mutableListOf<PolicyHandoverEvent>()

    override fun hasActiveScan(): Boolean = hasActiveScan

    override suspend fun launchAutomaticProbe(
        settings: com.poyka.ripdpi.proto.AppSettings,
        event: PolicyHandoverEvent,
    ): Boolean {
        events += event
        return true
    }
}

private fun handoverEvent(currentFingerprintHash: String = "fingerprint-b") =
    PolicyHandoverEvent(
        mode = Mode.VPN,
        previousFingerprintHash = "fingerprint-a",
        currentFingerprintHash = currentFingerprintHash,
        classification = "transport_switch",
        usedRememberedPolicy = false,
        policySignature = "policy-1",
        occurredAt = 100L,
    )

private fun constantProvider(launcher: AutomaticProbeLauncher): Provider<AutomaticProbeLauncher> =
    object : Provider<AutomaticProbeLauncher> {
        override fun get(): AutomaticProbeLauncher = launcher
    }
