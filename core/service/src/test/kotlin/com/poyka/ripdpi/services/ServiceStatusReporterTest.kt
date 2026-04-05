package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStatus
import org.junit.Assert.assertEquals
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
                clock = TestServiceClock(now = 42L),
            )

        reporter.reportStatus(
            newStatus = ServiceStatus.Connected,
            activePolicy = null,
            consumePendingNetworkHandoverClass = { null },
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
                clock = TestServiceClock(now = 99L),
            )
        val reason = FailureReason.NativeError("boom")

        reporter.reportStatus(
            newStatus = ServiceStatus.Failed,
            activePolicy = null,
            consumePendingNetworkHandoverClass = { null },
            tunnelRecoveryRetryCount = 0L,
            failureReason = reason,
        )

        assertTrue(store.eventHistory.single() is com.poyka.ripdpi.data.ServiceEvent.Failed)
        assertEquals(Sender.VPN, store.telemetry.value.lastFailureSender)
    }

    @Test
    fun telemetryReportAppliesPendingHandoverClassAndWinningFamilies() {
        val store = TestServiceStateStore()
        val reporter =
            ServiceStatusReporter(
                mode = Mode.VPN,
                sender = Sender.VPN,
                serviceStateStore = store,
                networkFingerprintProvider = TestNetworkFingerprintProvider(sampleFingerprint()),
                telemetryFingerprintHasher = TestTelemetryFingerprintHasher(hashValue = "fp-hash"),
                clock = TestServiceClock(now = 123L),
            )
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
            proxyTelemetry = proxyTelemetry,
            relayTelemetry = NativeRuntimeSnapshot.idle(source = "relay"),
            warpTelemetry = NativeRuntimeSnapshot.idle(source = "warp"),
            tunnelTelemetry = tunnelTelemetry,
            tunnelRecoveryRetryCount = 4L,
        )

        assertEquals("transport_switch", store.telemetry.value.tunnelTelemetry.networkHandoverClass)
        assertEquals("tcp-family", store.telemetry.value.runtimeFieldTelemetry.winningTcpStrategyFamily)
        assertEquals(4L, store.telemetry.value.runtimeFieldTelemetry.tunnelRecoveryRetryCount)
    }
}
