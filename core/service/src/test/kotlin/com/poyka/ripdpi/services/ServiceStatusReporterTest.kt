package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DefaultStartupJournal
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RuntimeTelemetryStatus
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.service.telemetry.RuntimeTelemetryStatuses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStatusReporterTest {
    @Test
    fun vpnStartupIsRecordedBeforeDiagnosticsCanReadTheJournal() {
        val startupJournal = DefaultStartupJournal()
        val reporter =
            ServiceStatusReporter(
                mode = Mode.VPN,
                sender = Sender.VPN,
                serviceStateStore = TestServiceStateStore(),
                networkFingerprintProvider = TestNetworkFingerprintProvider(),
                telemetryFingerprintHasher = TestTelemetryFingerprintHasher(),
                runtimeExperimentSelectionProvider =
                    object : RuntimeExperimentSelectionProvider {
                        override fun current(): RuntimeExperimentSelection = RuntimeExperimentSelection()
                    },
                startupJournal = startupJournal,
                clock = TestServiceClock(now = 42L),
            )

        reporter.reportStatus(
            newStatus = ServiceStatus.Connected,
            activePolicy = null,
            consumePendingNetworkHandoverClass = { null },
            currentNetworkHandoverState = { null },
            tunnelRecoveryRetryCount = 0L,
        )

        assertTrue(startupJournal.snapshot().content.contains("42 service_started mode=vpn"))
    }

    @Test
    fun failedStartupIsRecordedWithoutSensitiveFailureText() {
        val startupJournal = DefaultStartupJournal()
        val reporter =
            ServiceStatusReporter(
                mode = Mode.VPN,
                sender = Sender.VPN,
                serviceStateStore = TestServiceStateStore(),
                networkFingerprintProvider = TestNetworkFingerprintProvider(),
                telemetryFingerprintHasher = TestTelemetryFingerprintHasher(),
                runtimeExperimentSelectionProvider =
                    object : RuntimeExperimentSelectionProvider {
                        override fun current(): RuntimeExperimentSelection = RuntimeExperimentSelection()
                    },
                startupJournal = startupJournal,
                clock = TestServiceClock(now = 43L),
            )

        reporter.reportStatus(
            newStatus = ServiceStatus.Failed,
            activePolicy = null,
            consumePendingNetworkHandoverClass = { null },
            currentNetworkHandoverState = { null },
            tunnelRecoveryRetryCount = 0L,
            failureReason = FailureReason.InitialTransportSelectionFailed("vless://credential@private.example"),
        )

        val content = startupJournal.snapshot().content
        assertTrue(
            content.contains("43 service_status mode=vpn status=failed failure=initial_transport_selection_failed") &&
                !content.contains("credential") &&
                !content.contains("private.example"),
        )
    }

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
    fun connectedStatusPreservesPublishedReadyAutolearnSnapshotAndStatus() {
        val store = TestServiceStateStore()
        val reporter = testReporter(store = store, mode = Mode.Proxy, sender = Sender.Proxy, now = 42L)
        val readySnapshot =
            NativeRuntimeSnapshot(
                source = "proxy",
                state = "running",
                autolearnEnabled = true,
                learnedHostCount = 3,
                capturedAt = 41L,
            )

        reporter.reportRuntimeStartTelemetry(
            activePolicy = null,
            currentNetworkHandoverState = { null },
            proxyTelemetry = readySnapshot,
            tunnelRecoveryRetryCount = 0L,
        )

        assertEquals(com.poyka.ripdpi.data.AppStatus.Halted, store.telemetry.value.status)
        assertPublishedReadyAutolearnSnapshot(store.telemetry.value.proxyTelemetry, readySnapshot)
        assertEquals(
            com.poyka.ripdpi.data.RuntimeTelemetryState.Snapshot,
            store.telemetry.value.proxyTelemetryStatus.state,
        )

        reporter.reportStatus(
            newStatus = ServiceStatus.Connected,
            activePolicy = null,
            consumePendingNetworkHandoverClass = { null },
            currentNetworkHandoverState = { null },
            tunnelRecoveryRetryCount = 0L,
        )

        assertPublishedReadyAutolearnSnapshot(store.telemetry.value.proxyTelemetry, readySnapshot)
        assertEquals(
            com.poyka.ripdpi.data.RuntimeTelemetryState.Snapshot,
            store.telemetry.value.proxyTelemetryStatus.state,
        )
    }

    private fun assertPublishedReadyAutolearnSnapshot(
        actual: NativeRuntimeSnapshot,
        expected: NativeRuntimeSnapshot,
    ) {
        assertEquals(expected.source, actual.source)
        assertEquals(expected.state, actual.state)
        assertEquals(expected.autolearnEnabled, actual.autolearnEnabled)
        assertEquals(expected.learnedHostCount, actual.learnedHostCount)
        assertEquals(expected.capturedAt, actual.capturedAt)
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
                                nativeEvents = dataPlaneEvents(20) + staleNativeEvents(),
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
        assertEquals(16, store.telemetry.value.proxyTelemetry.nativeEvents.size)
        assertTrue(
            store.telemetry.value.proxyTelemetry.nativeEvents.all { event ->
                event.subsystem == "data_plane" && event.level == "info"
            },
        )
        assertEquals(
            "event-4",
            store.telemetry.value.proxyTelemetry.nativeEvents
                .first()
                .message,
        )
        assertEquals(
            "event-19",
            store.telemetry.value.proxyTelemetry.nativeEvents
                .last()
                .message,
        )
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
            awgTelemetry = NativeRuntimeSnapshot.idle(source = "awg"),
            tunnelTelemetry = tunnelTelemetry,
            telemetryStatuses =
                RuntimeTelemetryStatuses(
                    proxy = RuntimeTelemetryStatus.NoData,
                    relay = RuntimeTelemetryStatus.NoData,
                    warp = RuntimeTelemetryStatus.NoData,
                    awg = RuntimeTelemetryStatus.NoData,
                    tunnel = RuntimeTelemetryStatus.NoData,
                ),
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

    /**
     * Test #5: reportStatus reads the telemetry snapshot AFTER applyStatus, not before.
     *
     * applyStatus calls setStatus(Running) which increments restartCount inside the store.
     * The projection then receives currentTelemetry captured post-applyStatus, so
     * updateTelemetry sees restartCount=1 in the snapshot passed to it. Because
     * updateTelemetry uses maxOf(snapshot.restartCount, currentTelemetry.restartCount),
     * the final telemetry must reflect restartCount >= 1.
     *
     * With the old ordering (read before applyStatus), currentTelemetry.restartCount was
     * 0; the projection snapshot also had restartCount=0; maxOf(0,0)=0 — the increment
     * from setStatus was silently discarded.
     */
    @Test
    fun `telemetry projection uses post-applyStatus snapshot so restartCount is not lost`() {
        val store = TestServiceStateStore()
        val reporter = testReporter(store = store, mode = Mode.VPN, sender = Sender.VPN, now = 1L)

        // First call transitions Halted → Running; setStatus increments restartCount to 1.
        reporter.reportStatus(
            newStatus = ServiceStatus.Connected,
            activePolicy = null,
            consumePendingNetworkHandoverClass = { null },
            currentNetworkHandoverState = { null },
            tunnelRecoveryRetryCount = 0L,
        )

        // restartCount must be 1 — the post-applyStatus snapshot carried it into the projection.
        assert(store.telemetry.value.restartCount >= 1) {
            "Expected restartCount >= 1 after first Connected transition, " +
                "got ${store.telemetry.value.restartCount}. " +
                "The projection must read telemetry AFTER applyStatus sets it."
        }
    }

    /**
     * Regression: a STATUS-ONLY update (reportStatus with no snapshot arg) must
     * NOT blank an Xray provider snapshot already present in the telemetry store.
     *
     * The additive xrayProviderSnapshot is populated by the live-telemetry path
     * and persists for the session. A status-only update (the default-null
     * xrayProviderSnapshot parameter) previously overwrote it with null, making
     * the Home banner / Settings row / Diagnostics card flicker to "no provider"
     * mid-session. The projection now preserves the prior snapshot
     * (xrayProviderSnapshot ?: currentTelemetry.xrayProviderSnapshot).
     */
    @Test
    fun statusOnlyUpdatePreservesExistingXrayProviderSnapshot() {
        val existingSnapshot =
            com.poyka.ripdpi.data.xray.XrayProviderSnapshot(
                xrayVersion = "25.1.0",
                profileName = "edge-relay",
                outboundProtocol = "vless",
                outboundSecurity = "reality",
            )
        val store =
            TestServiceStateStore().apply {
                updateTelemetry(
                    ServiceTelemetrySnapshot(xrayProviderSnapshot = existingSnapshot),
                )
            }
        val reporter = testReporter(store = store, mode = Mode.VPN, sender = Sender.VPN, now = 55L)

        // Status-only update: reportStatus is called WITHOUT a snapshot arg, so the
        // projection receives the default-null xrayProviderSnapshot.
        reporter.reportStatus(
            newStatus = ServiceStatus.Connected,
            activePolicy = null,
            consumePendingNetworkHandoverClass = { null },
            currentNetworkHandoverState = { null },
            tunnelRecoveryRetryCount = 0L,
        )

        assertEquals(existingSnapshot, store.telemetry.value.xrayProviderSnapshot)
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

    private fun dataPlaneEvents(count: Int): List<NativeRuntimeEvent> =
        List(count) { index ->
            NativeRuntimeEvent(
                source = "service",
                level = "info",
                message = "event-$index",
                createdAt = index.toLong(),
                kind = "data_plane_correlation",
                subsystem = "data_plane",
            )
        }

    private fun staleNativeEvents(): List<NativeRuntimeEvent> =
        listOf(
            NativeRuntimeEvent(
                source = "proxy",
                level = "error",
                message = "stale native error",
                createdAt = 100L,
                kind = "native_error",
                subsystem = "proxy",
            ),
            NativeRuntimeEvent(
                source = "proxy",
                level = "warn",
                message = "stale native warning",
                createdAt = 101L,
                kind = "native_warning",
            ),
        )
}
