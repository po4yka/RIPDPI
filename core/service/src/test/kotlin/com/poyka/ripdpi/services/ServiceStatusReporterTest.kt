package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStatusReporterTest {
    @Test
    fun connectedStatusPublishesRunningSnapshotWithIdleTelemetry() {
        val store = TestServiceStateStore()
        val reporter =
            ServiceStatusReporter(
                mode = Mode.Proxy,
                sender = Sender.Proxy,
                serviceStateStore = store,
                networkFingerprintProvider = TestNetworkFingerprintProvider(sampleFingerprint()),
                telemetryFingerprintHasher = TestTelemetryFingerprintHasher(hashValue = "fp-hash"),
                runtimeExperimentSelectionProvider =
                    object : RuntimeExperimentSelectionProvider {
                        override fun current(): RuntimeExperimentSelection = RuntimeExperimentSelection()
                    },
                clock = TestServiceClock(now = 42L),
            )

        reporter.reportStatus(
            newStatus = ServiceStatus.Connected,
            activePolicy = null,
            consumePendingNetworkHandoverClass = { null },
            currentNetworkHandoverState = { null },
            tunnelRecoveryRetryCount = 0L,
        )

        assertEquals(Mode.Proxy, store.telemetry.value.mode)
        assertEquals("idle", store.telemetry.value.proxyTelemetry.state)
        assertEquals("fp-hash", store.telemetry.value.runtimeFieldTelemetry.telemetryNetworkFingerprintHash)
        assertEquals(42L, store.telemetry.value.updatedAt)
    }

    @Test
    fun failedStatusEmitsFailureEvent() {
        val store = TestServiceStateStore()
        val reporter =
            ServiceStatusReporter(
                mode = Mode.VPN,
                sender = Sender.VPN,
                serviceStateStore = store,
                networkFingerprintProvider = TestNetworkFingerprintProvider(),
                telemetryFingerprintHasher = TestTelemetryFingerprintHasher(),
                runtimeExperimentSelectionProvider =
                    object : RuntimeExperimentSelectionProvider {
                        override fun current(): RuntimeExperimentSelection = RuntimeExperimentSelection()
                    },
                clock = TestServiceClock(now = 99L),
            )
        val reason = FailureReason.NativeError("boom")

        reporter.reportStatus(
            newStatus = ServiceStatus.Failed,
            activePolicy = null,
            consumePendingNetworkHandoverClass = { null },
            currentNetworkHandoverState = { com.poyka.ripdpi.data.NetworkHandoverStates.Failed },
            tunnelRecoveryRetryCount = 0L,
            failureReason = reason,
        )

        assertTrue(store.eventHistory.single() is com.poyka.ripdpi.data.ServiceEvent.Failed)
        assertEquals(Sender.VPN, store.telemetry.value.lastFailureSender)
    }

    @Test
    fun disconnectedStatusPublishesIdleSnapshotsAndDropsStaleNativeErrors() {
        val store =
            TestServiceStateStore().apply {
                updateTelemetry(
                    ServiceTelemetrySnapshot(
                        proxyTelemetry =
                            NativeRuntimeSnapshot(
                                source = "proxy",
                                state = "running",
                                health = "degraded",
                                totalErrors = 1,
                                lastError = "no supported socks auth method",
                                lastFailureClass = "native_io",
                            ),
                        tunnelTelemetry =
                            NativeRuntimeSnapshot(
                                source = "tunnel",
                                state = "running",
                                health = "degraded",
                                totalErrors = 1,
                                lastError = "no supported socks auth method",
                                lastFailureClass = "native_io",
                            ),
                        relayTelemetry =
                            NativeRuntimeSnapshot(
                                source = "relay",
                                state = "running",
                                health = "healthy",
                                activeSessions = 2,
                            ),
                        warpTelemetry =
                            NativeRuntimeSnapshot(
                                source = "warp",
                                state = "running",
                                health = "healthy",
                                activeSessions = 1,
                            ),
                    ),
                )
            }
        val reporter = testReporter(store = store, mode = Mode.VPN, sender = Sender.VPN, now = 77L)

        reporter.reportStatus(
            newStatus = ServiceStatus.Disconnected,
            activePolicy = null,
            consumePendingNetworkHandoverClass = { null },
            currentNetworkHandoverState = { null },
            tunnelRecoveryRetryCount = 0L,
        )

        assertEquals("idle", store.telemetry.value.proxyTelemetry.state)
        assertEquals("idle", store.telemetry.value.tunnelTelemetry.state)
        assertEquals("idle", store.telemetry.value.relayTelemetry.state)
        assertEquals("idle", store.telemetry.value.warpTelemetry.state)
        assertNull(store.telemetry.value.proxyTelemetry.lastError)
        assertNull(store.telemetry.value.tunnelTelemetry.lastError)
        assertNull(store.telemetry.value.proxyTelemetry.lastFailureClass)
        assertNull(store.telemetry.value.tunnelTelemetry.lastFailureClass)
    }

    @Test
    fun failedStatusPreservesRuntimeSnapshotsAndNativeErrors() {
        val store =
            TestServiceStateStore().apply {
                updateTelemetry(
                    ServiceTelemetrySnapshot(
                        proxyTelemetry =
                            NativeRuntimeSnapshot(
                                source = "proxy",
                                state = "running",
                                health = "degraded",
                                totalErrors = 1,
                                lastError = "no supported socks auth method",
                                lastFailureClass = "native_io",
                            ),
                        tunnelTelemetry =
                            NativeRuntimeSnapshot(
                                source = "tunnel",
                                state = "running",
                                health = "degraded",
                                totalErrors = 1,
                                lastError = "no supported socks auth method",
                                lastFailureClass = "native_io",
                            ),
                    ),
                )
            }
        val reporter = testReporter(store = store, mode = Mode.VPN, sender = Sender.VPN, now = 99L)
        val reason = FailureReason.NativeError("boom")

        reporter.reportStatus(
            newStatus = ServiceStatus.Failed,
            activePolicy = null,
            consumePendingNetworkHandoverClass = { null },
            currentNetworkHandoverState = { com.poyka.ripdpi.data.NetworkHandoverStates.Failed },
            tunnelRecoveryRetryCount = 0L,
            failureReason = reason,
        )

        assertEquals("running", store.telemetry.value.proxyTelemetry.state)
        assertEquals("running", store.telemetry.value.tunnelTelemetry.state)
        assertEquals("no supported socks auth method", store.telemetry.value.proxyTelemetry.lastError)
        assertEquals("no supported socks auth method", store.telemetry.value.tunnelTelemetry.lastError)
        assertEquals(Sender.VPN, store.telemetry.value.lastFailureSender)
        assertTrue(store.eventHistory.single() is com.poyka.ripdpi.data.ServiceEvent.Failed)
    }

    @Test
    fun telemetryReportAppliesPendingHandoverClassAndWinningFamilies() {
        val store = TestServiceStateStore()
        val reporter = testReporter(store = store, mode = Mode.VPN, sender = Sender.VPN, now = 123L)
        val policy =
            com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy(
                mode = Mode.VPN,
                policy = sampleRememberedPolicyJson(Mode.VPN),
                matchedPolicy = null,
                usedRememberedPolicy = false,
                fingerprintHash = "fingerprint",
                policySignature = "policy-signature",
                appliedAt = 1L,
                restartReason = "test",
                handoverClassification = null,
            )
        val proxyTelemetry =
            NativeRuntimeSnapshot(
                source = "proxy",
                state = "running",
                health = "healthy",
                activeSessions = 3,
            )
        val tunnelTelemetry =
            NativeRuntimeSnapshot(
                source = "tunnel",
                state = "running",
                health = "healthy",
            )

        reporter.reportTelemetry(
            activePolicy = policy,
            consumePendingNetworkHandoverClass = { "transport_switch" },
            currentNetworkHandoverState = { com.poyka.ripdpi.data.NetworkHandoverStates.Revalidated },
            proxyTelemetry = proxyTelemetry,
            relayTelemetry = NativeRuntimeSnapshot.idle(source = "relay"),
            warpTelemetry = NativeRuntimeSnapshot.idle(source = "warp"),
            tunnelTelemetry = tunnelTelemetry,
            tunnelRecoveryRetryCount = 4L,
        )

        assertEquals("transport_switch", store.telemetry.value.tunnelTelemetry.networkHandoverClass)
        assertEquals(
            com.poyka.ripdpi.data.NetworkHandoverStates.Revalidated,
            store.telemetry.value.networkHandoverState,
        )
        assertEquals("tcp-family", store.telemetry.value.runtimeFieldTelemetry.winningTcpStrategyFamily)
        assertEquals(4L, store.telemetry.value.runtimeFieldTelemetry.tunnelRecoveryRetryCount)
    }

    private fun testReporter(
        store: TestServiceStateStore,
        mode: Mode,
        sender: Sender,
        now: Long,
    ): ServiceStatusReporter =
        ServiceStatusReporter(
            mode = mode,
            sender = sender,
            serviceStateStore = store,
            networkFingerprintProvider = TestNetworkFingerprintProvider(sampleFingerprint()),
            telemetryFingerprintHasher = TestTelemetryFingerprintHasher(hashValue = "fp-hash"),
            runtimeExperimentSelectionProvider =
                object : RuntimeExperimentSelectionProvider {
                    override fun current(): RuntimeExperimentSelection = RuntimeExperimentSelection()
                },
            clock = TestServiceClock(now = now),
        )
}
