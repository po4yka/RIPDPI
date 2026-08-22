package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusValidated
import com.poyka.ripdpi.data.diagnostics.DefaultRememberedNetworkPolicyStore
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
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
    fun `scheduler records cooldown when a probe launches so sibling deliveries stay suppressed`() =
        runTest {
            val now = System.currentTimeMillis()
            val env =
                newEnv(
                    telemetrySamples =
                        listOf(
                            telemetrySample(
                                createdAt = now - 500L,
                                failureClass = "dns_tampering",
                            ),
                        ),
                    now = now,
                    launchOutcome = AutomaticProbeLaunchOutcome.LAUNCHED,
                    activeProbeSafetyPolicy =
                        ActiveProbeSafetyPolicy(
                            automaticHandoverProbeDelayMs = 100L,
                            automaticHandoverProbeCooldownMs = Long.MAX_VALUE,
                        ),
                )

            env.scheduler.schedule(handoverEvent())
            advanceTimeBy(100L)
            runCurrent()

            // A new delivery for the same fingerprint must not launch while the
            // freshly started probe owns the cooldown.
            env.scheduler.schedule(handoverEvent(classification = "link_refresh"))
            advanceTimeBy(300L)
            runCurrent()

            assertEquals(
                listOf("delivery-transport_switch"),
                env.launcher.events
                    .map { it.deliveryId }
                    .distinct(),
            )
            assertTrue(env.handoverStore.acknowledged.isEmpty())
        }

    @Test
    fun `scheduler launches eligible transport switch probe after configured delay`() =
        runTest {
            val now = System.currentTimeMillis()
            val env =
                newEnv(
                    telemetrySamples =
                        listOf(
                            telemetrySample(
                                createdAt = now - 1_000L,
                                failureClass = "dns_tampering",
                            ),
                        ),
                    now = now,
                    activeProbeSafetyPolicy =
                        ActiveProbeSafetyPolicy(
                            automaticHandoverProbeDelayMs = 1_000L,
                            automaticHandoverProbeCooldownMs = Long.MAX_VALUE,
                        ),
                )

            env.scheduler.schedule(handoverEvent())
            advanceTimeBy(999L)
            runCurrent()
            assertTrue(env.launcher.events.isEmpty())

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(1, env.launcher.events.size)
            assertEquals(
                "fingerprint-b",
                env.launcher.events
                    .single()
                    .currentFingerprintHash,
            )
            assertEquals(listOf("delivery-transport_switch"), env.handoverStore.acknowledged)
        }

    @Test
    fun `scheduler launches eligible link refresh probes`() =
        runTest {
            val now = System.currentTimeMillis()
            val env =
                newEnv(
                    telemetrySamples =
                        listOf(
                            telemetrySample(
                                createdAt = now - 500L,
                                lastFailureClass = "timeout",
                            ),
                        ),
                    now = now,
                )

            env.scheduler.schedule(handoverEvent(classification = "link_refresh"))
            advanceTimeBy(100L)
            runCurrent()

            assertEquals(1, env.launcher.events.size)
            assertEquals(
                "link_refresh",
                env.launcher.events
                    .single()
                    .classification,
            )
        }

    @Test
    fun `scheduler suppresses repeated launches within cooldown`() =
        runTest {
            val now = System.currentTimeMillis()
            val env =
                newEnv(
                    telemetrySamples =
                        listOf(
                            telemetrySample(
                                createdAt = now - 500L,
                                failureClass = "dns_tampering",
                            ),
                        ),
                    now = now,
                    activeProbeSafetyPolicy =
                        ActiveProbeSafetyPolicy(
                            automaticHandoverProbeDelayMs = 100L,
                            automaticHandoverProbeCooldownMs = Long.MAX_VALUE,
                        ),
                )

            env.scheduler.schedule(handoverEvent())
            advanceTimeBy(100L)
            runCurrent()
            env.scheduler.schedule(handoverEvent())
            advanceTimeBy(100L)
            runCurrent()

            assertEquals(1, env.launcher.events.size)
            assertEquals(listOf("delivery-transport_switch"), env.handoverStore.acknowledged)
        }

    @Test
    fun `scheduler deduplicates a pending delivery`() =
        runTest {
            val now = System.currentTimeMillis()
            val env =
                newEnv(
                    telemetrySamples =
                        listOf(
                            telemetrySample(
                                fingerprintHash = "fingerprint-b",
                                createdAt = now - 500L,
                                failureClass = "dns_tampering",
                            ),
                        ),
                    now = now,
                    activeProbeSafetyPolicy =
                        ActiveProbeSafetyPolicy(
                            automaticHandoverProbeDelayMs = 1_000L,
                        ),
                )

            val event = handoverEvent()
            env.scheduler.schedule(event)
            advanceTimeBy(500L)
            env.scheduler.schedule(event)
            advanceTimeBy(499L)
            runCurrent()
            assertTrue(env.launcher.events.isEmpty())

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(listOf("fingerprint-b"), env.launcher.events.map { it.currentFingerprintHash })
            assertEquals(listOf(event.deliveryId), env.handoverStore.acknowledged)
        }

    @Test
    fun `scheduler skips launches while another scan is already active`() =
        runTest {
            val now = System.currentTimeMillis()
            val env =
                newEnv(
                    hasActiveScan = true,
                    telemetrySamples =
                        listOf(
                            telemetrySample(
                                createdAt = now - 500L,
                                failureClass = "dns_tampering",
                            ),
                        ),
                    now = now,
                )

            env.scheduler.schedule(handoverEvent())
            advanceTimeBy(100L)
            runCurrent()

            assertTrue(env.launcher.events.isEmpty())
            assertTrue(env.handoverStore.acknowledged.isEmpty())
        }

    @Test
    fun `scheduler retains delivery across active scan until terminal replay can acknowledge`() =
        runTest {
            val now = System.currentTimeMillis()
            val env =
                newEnv(
                    hasActiveScan = true,
                    telemetrySamples =
                        listOf(
                            telemetrySample(
                                createdAt = now - 500L,
                                failureClass = "dns_tampering",
                            ),
                        ),
                    now = now,
                )
            val event = handoverEvent()

            env.scheduler.schedule(event)
            advanceTimeBy(100L)
            runCurrent()
            assertTrue(env.handoverStore.acknowledged.isEmpty())

            env.launcher.hasActiveScan = false
            env.scheduler.schedule(event)
            advanceTimeBy(100L)
            runCurrent()

            assertEquals(listOf(event), env.launcher.events)
            assertEquals(listOf(event.deliveryId), env.handoverStore.acknowledged)
        }

    @Test
    fun `scheduler skips launches when settings make probing ineligible`() =
        runTest {
            val now = System.currentTimeMillis()
            val env =
                newEnv(
                    settings = defaultDiagnosticsAppSettings(),
                    telemetrySamples =
                        listOf(
                            telemetrySample(
                                createdAt = now - 500L,
                                failureClass = "dns_tampering",
                            ),
                        ),
                    now = now,
                )

            env.scheduler.schedule(handoverEvent())
            advanceTimeBy(100L)
            runCurrent()

            assertTrue(env.launcher.events.isEmpty())
        }

    @Test
    fun `scheduler rejects unsupported handover classifications`() =
        runTest {
            val now = System.currentTimeMillis()
            val env =
                newEnv(
                    telemetrySamples =
                        listOf(
                            telemetrySample(
                                createdAt = now - 500L,
                                failureClass = "dns_tampering",
                            ),
                        ),
                    now = now,
                )

            env.scheduler.schedule(handoverEvent(classification = "wifi_reconnect"))
            advanceTimeBy(100L)
            runCurrent()

            assertTrue(env.launcher.events.isEmpty())
        }

    @Test
    fun `scheduler skips launch when validated remembered policy already exists`() =
        runTest {
            val now = System.currentTimeMillis()
            val env =
                newEnv(
                    telemetrySamples =
                        listOf(
                            telemetrySample(
                                createdAt = now - 500L,
                                failureClass = "dns_tampering",
                            ),
                        ),
                    rememberedPolicies =
                        listOf(
                            rememberedPolicy(fingerprintHash = "fingerprint-b"),
                        ),
                    now = now,
                )

            env.scheduler.schedule(handoverEvent())
            advanceTimeBy(100L)
            runCurrent()

            assertTrue(env.launcher.events.isEmpty())
        }

    @Test
    fun `scheduler rejects captive networks`() =
        runTest {
            val now = System.currentTimeMillis()
            val env =
                newEnv(
                    telemetrySamples =
                        listOf(
                            telemetrySample(
                                createdAt = now - 500L,
                                failureClass = "dns_tampering",
                            ),
                        ),
                    now = now,
                )

            env.scheduler.schedule(handoverEvent(currentCaptivePortalDetected = true))
            advanceTimeBy(100L)
            runCurrent()

            assertTrue(env.launcher.events.isEmpty())
        }

    @Test
    fun `scheduler rejects unvalidated networks`() =
        runTest {
            val now = System.currentTimeMillis()
            val env =
                newEnv(
                    telemetrySamples =
                        listOf(
                            telemetrySample(
                                createdAt = now - 500L,
                                failureClass = "dns_tampering",
                            ),
                        ),
                    now = now,
                )

            env.scheduler.schedule(handoverEvent(currentNetworkValidated = false))
            advanceTimeBy(100L)
            runCurrent()

            assertTrue(env.launcher.events.isEmpty())
        }

    @Test
    fun `scheduler rejects launch when no recent matching telemetry exists`() =
        runTest {
            val env = newEnv()

            env.scheduler.schedule(handoverEvent())
            advanceTimeBy(100L)
            runCurrent()

            assertTrue(env.launcher.events.isEmpty())
            assertTrue(env.handoverStore.acknowledged.isEmpty())
        }

    @Test
    fun `scheduler acknowledges missing evidence delivery when retry window expires`() =
        runTest {
            val event = handoverEvent()
            val env =
                newEnv(
                    activeProbeSafetyPolicy =
                        ActiveProbeSafetyPolicy(
                            automaticHandoverProbeDelayMs = AutomaticProbeCoordinator.recentFailureLookbackMs(),
                            automaticHandoverProbeCooldownMs = 0L,
                            automaticStrategyFailureProbeCooldownMs = 0L,
                        ),
                )

            env.scheduler.schedule(event)
            advanceTimeBy(AutomaticProbeCoordinator.recentFailureLookbackMs())
            runCurrent()

            assertTrue(env.launcher.events.isEmpty())
            assertEquals(listOf(event.deliveryId), env.handoverStore.acknowledged)
        }

    @Test
    fun `scheduler acknowledges permanently ineligible delivery`() =
        runTest {
            val env = newEnv(settings = defaultDiagnosticsAppSettings())
            val event = handoverEvent()

            env.scheduler.schedule(event)
            advanceTimeBy(100L)
            runCurrent()

            assertTrue(env.launcher.events.isEmpty())
            assertEquals(listOf(event.deliveryId), env.handoverStore.acknowledged)
        }

    @Test
    fun `scheduler rejects stale telemetry`() =
        runTest {
            val now = System.currentTimeMillis()
            val env =
                newEnv(
                    telemetrySamples =
                        listOf(
                            telemetrySample(
                                createdAt = now - AutomaticProbeCoordinator.recentFailureLookbackMs() - 1_000L,
                                failureClass = "dns_tampering",
                            ),
                        ),
                    now = now,
                )

            env.scheduler.schedule(handoverEvent())
            advanceTimeBy(100L)
            runCurrent()

            assertTrue(env.launcher.events.isEmpty())
        }

    @Test
    fun `scheduler rejects pure network handover telemetry failures`() =
        runTest {
            val now = System.currentTimeMillis()
            val env =
                newEnv(
                    telemetrySamples =
                        listOf(
                            telemetrySample(
                                createdAt = now - 500L,
                                failureClass = "network_handover",
                                lastFailureClass = "network_handover",
                            ),
                        ),
                    now = now,
                )

            env.scheduler.schedule(handoverEvent())
            advanceTimeBy(100L)
            runCurrent()

            assertTrue(env.launcher.events.isEmpty())
        }

    @Test
    fun `scheduler launches when recent telemetry has failed connection state`() =
        runTest {
            val now = System.currentTimeMillis()
            val env =
                newEnv(
                    telemetrySamples =
                        listOf(
                            telemetrySample(
                                createdAt = now - 500L,
                                connectionState = "Failed",
                            ),
                        ),
                    now = now,
                )

            env.scheduler.schedule(handoverEvent())
            advanceTimeBy(100L)
            runCurrent()

            assertEquals(1, env.launcher.events.size)
        }

    @Test
    fun `scheduler retains delivery when eligible launch is not registered`() =
        runTest {
            val now = System.currentTimeMillis()
            val env =
                newEnv(
                    telemetrySamples =
                        listOf(
                            telemetrySample(
                                createdAt = now - 500L,
                                failureClass = "dns_tampering",
                            ),
                        ),
                    now = now,
                    launchOutcome = AutomaticProbeLaunchOutcome.RETRY,
                )

            env.scheduler.schedule(handoverEvent())
            advanceTimeBy(100L)
            runCurrent()

            assertEquals(1, env.launcher.events.size)
            assertTrue(env.handoverStore.acknowledged.isEmpty())
        }

    @Test
    fun `scheduler retries until the launched scan has a terminal row`() =
        runTest {
            val now = System.currentTimeMillis()
            val env =
                newEnv(
                    telemetrySamples =
                        listOf(
                            telemetrySample(
                                createdAt = now - 500L,
                                failureClass = "dns_tampering",
                            ),
                        ),
                    now = now,
                    launchOutcome = AutomaticProbeLaunchOutcome.RETRY,
                )
            val event = handoverEvent()

            env.scheduler.schedule(event)
            advanceTimeBy(100L)
            runCurrent()
            assertEquals(listOf(event), env.launcher.events)
            assertTrue(env.handoverStore.acknowledged.isEmpty())

            env.launcher.launchOutcome = AutomaticProbeLaunchOutcome.SETTLED
            advanceTimeBy(100L)
            runCurrent()

            assertEquals(listOf(event, event), env.launcher.events)
            assertEquals(listOf(event.deliveryId), env.handoverStore.acknowledged)
        }

    @Test
    fun `scheduler retries a transient launcher exception`() =
        runTest {
            val now = System.currentTimeMillis()
            val env =
                newEnv(
                    telemetrySamples =
                        listOf(telemetrySample(createdAt = now - 500L, failureClass = "dns_tampering")),
                    now = now,
                )
            val event = handoverEvent()
            env.launcher.nextFailure = IllegalStateException("transient startup failure")

            env.scheduler.schedule(event)
            advanceTimeBy(100L)
            runCurrent()
            assertTrue(env.handoverStore.acknowledged.isEmpty())

            advanceTimeBy(100L)
            runCurrent()

            assertEquals(listOf(event, event), env.launcher.events)
            assertEquals(listOf(event.deliveryId), env.handoverStore.acknowledged)
        }

    @Test
    fun `scheduler stops retrying after durable delivery eviction`() =
        runTest {
            val now = System.currentTimeMillis()
            val env =
                newEnv(
                    telemetrySamples =
                        listOf(telemetrySample(createdAt = now - 500L, failureClass = "dns_tampering")),
                    now = now,
                    launchOutcome = AutomaticProbeLaunchOutcome.RETRY,
                )
            val event = handoverEvent()
            env.launcher.afterLaunch = {
                env.handoverStore.pendingOverride = false
            }

            env.scheduler.schedule(event)
            advanceTimeBy(100L)
            runCurrent()
            assertEquals(listOf(event), env.launcher.events)

            assertTrue(!env.handoverStore.isPending(event.deliveryId))
            env.launcher.launchOutcome = AutomaticProbeLaunchOutcome.SETTLED
            advanceTimeBy(100L)
            runCurrent()

            assertEquals(listOf(event), env.launcher.events)
            assertTrue(env.handoverStore.acknowledged.isEmpty())
        }

    private fun TestScope.newEnv(
        settings: com.poyka.ripdpi.proto.AppSettings =
            defaultDiagnosticsAppSettings()
                .toBuilder()
                .setNetworkStrategyMemoryEnabled(true)
                .build(),
        telemetrySamples: List<TelemetrySampleEntity> = emptyList(),
        rememberedPolicies: List<RememberedNetworkPolicyEntity> = emptyList(),
        hasActiveScan: Boolean = false,
        launchOutcome: AutomaticProbeLaunchOutcome = AutomaticProbeLaunchOutcome.SETTLED,
        now: Long = System.currentTimeMillis(),
        activeProbeSafetyPolicy: ActiveProbeSafetyPolicy =
            ActiveProbeSafetyPolicy(
                automaticHandoverProbeDelayMs = 100L,
                automaticHandoverProbeCooldownMs = 0L,
                automaticStrategyFailureProbeCooldownMs = 0L,
            ),
    ): SchedulerEnv {
        val stores =
            FakeDiagnosticsHistoryStores().also {
                it.telemetryState.value = telemetrySamples
                it.rememberedPoliciesState.value = rememberedPolicies
                it.currentTime = now
            }
        val launcher =
            RecordingAutomaticProbeLauncher(
                hasActiveScan = hasActiveScan,
                launchOutcome = launchOutcome,
            )
        val handoverStore = FakePolicyHandoverEventStore()
        val scheduler =
            AutomaticProbeScheduler(
                appSettingsRepository = FakeAppSettingsRepository(settings),
                rememberedNetworkPolicyStore =
                    DefaultRememberedNetworkPolicyStore(
                        stores,
                        TestDiagnosticsHistoryClock(now),
                    ),
                diagnosticsArtifactReadStore = stores,
                policyHandoverEventStore = handoverStore,
                launcherProvider = constantProvider(launcher),
                activeProbeSafetyPolicy = activeProbeSafetyPolicy,
                scope = backgroundScope,
            )
        return SchedulerEnv(
            launcher = launcher,
            scheduler = scheduler,
            handoverStore = handoverStore,
        )
    }
}

private data class SchedulerEnv(
    val launcher: RecordingAutomaticProbeLauncher,
    val scheduler: AutomaticProbeScheduler,
    val handoverStore: FakePolicyHandoverEventStore,
)

private class RecordingAutomaticProbeLauncher(
    hasActiveScan: Boolean = false,
    var launchOutcome: AutomaticProbeLaunchOutcome = AutomaticProbeLaunchOutcome.SETTLED,
) : AutomaticProbeLauncher {
    val events = mutableListOf<PolicyHandoverEvent>()
    var hasActiveScan = hasActiveScan
    var nextFailure: Throwable? = null
    var afterLaunch: (() -> Unit)? = null

    override fun hasActiveScan(): Boolean = hasActiveScan

    override suspend fun launchAutomaticProbe(
        settings: com.poyka.ripdpi.proto.AppSettings,
        event: PolicyHandoverEvent,
    ): AutomaticProbeLaunchOutcome {
        events += event
        afterLaunch?.invoke()
        nextFailure?.let { failure ->
            nextFailure = null
            throw failure
        }
        return launchOutcome
    }
}

private fun handoverEvent(
    currentFingerprintHash: String = "fingerprint-b",
    classification: String = "transport_switch",
    currentNetworkValidated: Boolean = true,
    currentCaptivePortalDetected: Boolean = false,
) = PolicyHandoverEvent(
    deliveryId = "delivery-$classification",
    mode = Mode.VPN,
    previousFingerprintHash = "fingerprint-a",
    currentFingerprintHash = currentFingerprintHash,
    classification = classification,
    currentNetworkValidated = currentNetworkValidated,
    currentCaptivePortalDetected = currentCaptivePortalDetected,
    usedRememberedPolicy = false,
    occurredAt = 100L,
)

private fun telemetrySample(
    id: String = "telemetry-1",
    activeMode: String = Mode.VPN.name,
    fingerprintHash: String = "fingerprint-b",
    connectionState: String = "Running",
    failureClass: String? = null,
    lastFailureClass: String? = null,
    createdAt: Long,
) = TelemetrySampleEntity(
    id = id,
    sessionId = null,
    connectionSessionId = "conn-1",
    activeMode = activeMode,
    connectionState = connectionState,
    networkType = "wifi",
    publicIp = null,
    failureClass = failureClass,
    telemetryNetworkFingerprintHash = fingerprintHash,
    lastFailureClass = lastFailureClass,
    txPackets = 0L,
    txBytes = 0L,
    rxPackets = 0L,
    rxBytes = 0L,
    createdAt = createdAt,
)

private fun rememberedPolicy(
    fingerprintHash: String,
    mode: Mode = Mode.VPN,
) = RememberedNetworkPolicyEntity(
    fingerprintHash = fingerprintHash,
    mode = mode.preferenceValue,
    summaryJson = "{}",
    proxyConfigJson = "{}",
    source = RememberedNetworkPolicySource.MANUAL_SESSION.encodeStorageValue(),
    status = RememberedNetworkPolicyStatusValidated,
    firstObservedAt = 1L,
    lastValidatedAt = 1L,
    updatedAt = 1L,
)

private fun constantProvider(launcher: AutomaticProbeLauncher): Provider<AutomaticProbeLauncher> =
    object : Provider<AutomaticProbeLauncher> {
        override fun get(): AutomaticProbeLauncher = launcher
    }
