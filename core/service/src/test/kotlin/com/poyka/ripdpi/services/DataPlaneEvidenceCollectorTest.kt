package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ProxyForwardingEvidence
import com.poyka.ripdpi.core.TunForwardingEvidence
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.RuntimeTelemetryStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

class DataPlaneEvidenceCollectorTest {
    @Test
    fun unavailablePollDoesNotDiscardCurrentGenerationEvidence() {
        val tracker = DataPlaneCorrelationTracker(Mode.VPN, TestServiceClock(now = 42L))

        tracker.observe(
            proxyEvidence = ProxyForwardingEvidence(upstreamApplicationBytes = 100),
            tunEvidence = TunForwardingEvidence(),
            tunSupport = TunEvidenceSupport.Supported,
        )
        tracker.observe(
            proxyEvidence =
                ProxyForwardingEvidence(
                    upstreamApplicationBytes = 200,
                    firstUpstreamApplicationForwardedAt = 1_000,
                    lastUpstreamApplicationForwardedAt = 2_000,
                ),
            tunEvidence = TunForwardingEvidence(),
            tunSupport = TunEvidenceSupport.Supported,
        )
        tracker.finalEvent()
        val finalBeforeZero = tracker.events().last()

        tracker.observe(proxyEvidence = null, tunEvidence = null, tunSupport = TunEvidenceSupport.Unavailable)
        tracker.finalEvent()

        assertTrue(finalBeforeZero.message.contains("final=true"))
        assertTrue(finalBeforeZero.message.contains("proxy_application_bytes=200"))
        assertTrue(finalBeforeZero.message.contains("last_proxy_application_at=2000"))
        assertFalse(tracker.events().any { it.kind == "data_plane_counter_reset" })
        assertEquals(finalBeforeZero, tracker.events().last())
    }

    @Test
    fun allZeroProxyCountersStartNewGenerationBeforeFinalSeal() {
        val tracker = DataPlaneCorrelationTracker(Mode.Proxy, TestServiceClock(now = 42L))

        tracker.observe(
            proxyEvidence = ProxyForwardingEvidence(upstreamApplicationBytes = 200),
            tunEvidence = null,
            tunSupport = TunEvidenceSupport.Unsupported,
        )
        tracker.observe(
            proxyEvidence = ProxyForwardingEvidence.Empty,
            tunEvidence = null,
            tunSupport = TunEvidenceSupport.Unsupported,
            proxyGenerationAuthoritative = true,
        )
        tracker.finalEvent()

        val events = tracker.events()
        assertTrue(events.any { it.kind == "data_plane_counter_reset" && it.message.contains("generation=2") })
        val finalEvent = events.last()
        assertEquals("data_plane_final", finalEvent.kind)
        assertTrue(finalEvent.message.contains("state=no_flow"))
        assertTrue(finalEvent.message.contains("generation=2"))
        assertTrue(finalEvent.message.contains("proxy_application_bytes=0"))
        assertFalse(finalEvent.message.contains("proxy_application_bytes=200"))
    }

    @Test
    fun allZeroTunCountersStartNewGenerationBeforeFinalSeal() {
        val tracker = DataPlaneCorrelationTracker(Mode.VPN, TestServiceClock(now = 42L))

        tracker.observe(
            proxyEvidence = ProxyForwardingEvidence(upstreamApplicationBytes = 200),
            tunEvidence = TunForwardingEvidence(tunWriteBytes = 100),
            tunSupport = TunEvidenceSupport.Supported,
        )
        tracker.observe(
            proxyEvidence = ProxyForwardingEvidence(upstreamApplicationBytes = 200),
            tunEvidence = TunForwardingEvidence(),
            tunSupport = TunEvidenceSupport.Supported,
            tunGenerationAuthoritative = true,
        )
        tracker.finalEvent()

        val events = tracker.events()
        assertTrue(
            events.any {
                it.kind == "data_plane_counter_reset" &&
                    it.message.contains("generation=2") &&
                    it.message.contains("layers=tun")
            },
        )
        val finalEvent = events.last()
        assertEquals("data_plane_final", finalEvent.kind)
        assertTrue(finalEvent.message.contains("state=evidence_unavailable_partial"))
        assertTrue(finalEvent.message.contains("generation=2"))
        assertTrue(finalEvent.message.contains("tun_write_bytes=0"))
        assertFalse(finalEvent.message.contains("state=cross_layer_return_observed"))
        assertFalse(finalEvent.message.contains("tun_write_bytes=100"))
    }

    @Test
    fun transientZeroPayloadWithoutRunningTelemetryKeepsCurrentGeneration() =
        runTest {
            var polls = 0
            val collector =
                DataPlaneEvidenceCollector(
                    mode = Mode.Proxy,
                    proxyEvidenceProvider = {
                        polls += 1
                        if (polls == 1) {
                            ProxyForwardingEvidence(upstreamApplicationBytes = 200)
                        } else {
                            ProxyForwardingEvidence.Empty
                        }
                    },
                )

            collector.enrich(runningTelemetrySnapshot())
            val finalized = collector.finalizeAndEnrich(emptyTelemetrySnapshot())

            assertFalse(finalized.proxyTelemetry.nativeEvents.any { it.kind == "data_plane_counter_reset" })
            val finalEvent = finalized.proxyTelemetry.nativeEvents.last()
            assertTrue(finalEvent.message.contains("generation=1"))
            assertTrue(finalEvent.message.contains("proxy_application_bytes=200"))
        }

    @Test
    fun authoritativeRunningZeroPayloadStartsNewCollectorGeneration() =
        runTest {
            var polls = 0
            val collector =
                DataPlaneEvidenceCollector(
                    mode = Mode.Proxy,
                    proxyEvidenceProvider = {
                        polls += 1
                        if (polls == 1) {
                            ProxyForwardingEvidence(upstreamApplicationBytes = 200)
                        } else {
                            ProxyForwardingEvidence.Empty
                        }
                    },
                )

            collector.enrich(runningTelemetrySnapshot())
            val finalized = collector.finalizeAndEnrich(runningTelemetrySnapshot())

            assertTrue(finalized.proxyTelemetry.nativeEvents.any { it.kind == "data_plane_counter_reset" })
            val finalEvent = finalized.proxyTelemetry.nativeEvents.last()
            assertTrue(finalEvent.message.contains("generation=2"))
            assertTrue(finalEvent.message.contains("proxy_application_bytes=0"))
            assertFalse(finalEvent.message.contains("proxy_application_bytes=200"))
        }

    @Test
    fun proxyModeMarksTunUnsupportedAndInboundUnavailable() =
        runTest {
            val collector =
                DataPlaneEvidenceCollector(
                    mode = Mode.Proxy,
                    proxyEvidenceProvider = {
                        ProxyForwardingEvidence(upstreamApplicationBytes = 64)
                    },
                    clock = TestServiceClock(now = 7L),
                )

            val enriched = collector.enrich(emptyTelemetrySnapshot())
            val event = enriched.proxyTelemetry.nativeEvents.single()

            assertEquals("data_plane_correlation", event.kind)
            assertEquals("proxy", event.mode)
            assertTrue(event.message.contains("state=proxy_outbound_observed"))
            assertTrue(event.message.contains("tun_support=unsupported"))
            assertTrue(event.message.contains("proxy_inbound=unavailable"))
            assertTrue(event.message.contains("tun_read_packets=none"))
            assertTrue(event.message.contains("tun_write_bytes=none"))
            assertFalse(event.message.contains("bidirectional"))
            assertTrue(enriched.tunnelTelemetry.nativeEvents.isEmpty())
        }

    @Test
    fun rapidTransitionsAreReplayedAndHardCapped() {
        val tracker = DataPlaneCorrelationTracker(Mode.VPN, TestServiceClock())

        fun observe(
            proxy: ProxyForwardingEvidence?,
            tun: TunForwardingEvidence?,
            support: TunEvidenceSupport,
        ) {
            tracker.observe(proxy, tun, support)
        }

        observe(null, null, TunEvidenceSupport.Unavailable)
        observe(ProxyForwardingEvidence.Empty, TunForwardingEvidence(), TunEvidenceSupport.Supported)
        observe(ProxyForwardingEvidence.Empty, TunForwardingEvidence(tunReadBytes = 1), TunEvidenceSupport.Supported)
        observe(
            ProxyForwardingEvidence(upstreamOpened = 1),
            TunForwardingEvidence(tunReadBytes = 1),
            TunEvidenceSupport.Supported,
        )
        observe(
            ProxyForwardingEvidence(upstreamOpened = 1, upstreamApplicationBytes = 1),
            TunForwardingEvidence(tunReadBytes = 1),
            TunEvidenceSupport.Supported,
        )
        observe(
            ProxyForwardingEvidence(upstreamOpened = 1, upstreamApplicationBytes = 1),
            TunForwardingEvidence(tunReadBytes = 1, tunWriteBytes = 1),
            TunEvidenceSupport.Supported,
        )
        repeat(32) {
            observe(ProxyForwardingEvidence.Empty, TunForwardingEvidence(), TunEvidenceSupport.Unavailable)
        }

        val events = tracker.events().map { it.message }
        assertTrue(events.size <= 16)
        val supportedZero = events.first { it.contains("state=no_flow") }
        assertTrue(supportedZero.contains("tun_read_packets=0"))
        assertTrue(supportedZero.contains("tun_write_bytes=0"))
    }

    @Test
    fun pollFailureDoesNotCancelTelemetryCollection() =
        runTest {
            val original =
                emptyTelemetrySnapshot().copy(
                    proxyTelemetry =
                        NativeRuntimeSnapshot(
                            source = "proxy",
                            totalSessions = 9,
                        ),
                )
            val collector =
                DataPlaneEvidenceCollector(
                    mode = Mode.VPN,
                    proxyEvidenceProvider = { throw IOException("private endpoint text") },
                    tunEvidenceProvider = { throw IllegalStateException("fd=123") },
                    clock = TestServiceClock(now = 8L),
                )

            val enriched = collector.enrich(original)
            val event = enriched.proxyTelemetry.nativeEvents.single()

            assertEquals(9L, enriched.proxyTelemetry.totalSessions)
            assertTrue(event.message.contains("state=evidence_unavailable"))
            assertFalse(event.message.contains("private endpoint text"))
            assertFalse(event.message.contains("fd=123"))
        }

    @Test
    fun eventDoesNotCopyHostileRuntimeIdentifiersOrErrors() =
        runTest {
            val hostile =
                emptyTelemetrySnapshot().copy(
                    proxyTelemetry =
                        NativeRuntimeSnapshot(
                            source = "proxy",
                            listenerAddress = "198.51.100.7:6543",
                            upstreamAddress = "secret.example:443",
                            profileId = "dad-phone",
                            lastError = "fd=88 key=private",
                        ),
                )
            val collector =
                DataPlaneEvidenceCollector(
                    mode = Mode.Proxy,
                    proxyEvidenceProvider = {
                        ProxyForwardingEvidence(
                            proxyClientSocketsAccepted = 1,
                            upstreamOpened = 1,
                            upstreamOpenFailures = 2,
                        )
                    },
                    clock = TestServiceClock(now = 11L),
                )

            val message =
                collector
                    .enrich(hostile)
                    .proxyTelemetry.nativeEvents
                    .single()
                    .message

            assertTrue(message.contains("proxy_clients_accepted=1"))
            assertTrue(message.contains("upstream_opened=1"))
            assertTrue(message.contains("upstream_open_failures=2"))
            listOf("198.51.100.7", "secret.example", "dad-phone", "fd=88", "private")
                .forEach { secret -> assertFalse(message.contains(secret)) }
        }

    @Test
    fun collectorReplaysTransitionEventsOnEverySnapshot() =
        runTest {
            var proxyPolls = 0
            var tunPolls = 0
            val collector =
                DataPlaneEvidenceCollector(
                    mode = Mode.VPN,
                    proxyEvidenceProvider = {
                        proxyPolls += 1
                        ProxyForwardingEvidence(upstreamApplicationBytes = 2)
                    },
                    tunEvidenceProvider = {
                        tunPolls += 1
                        TunForwardingEvidence(tunWriteBytes = 3)
                    },
                    clock = TestServiceClock(now = 9L),
                )
            val snapshot = emptyTelemetrySnapshot()

            val first = collector.enrich(snapshot)
            val second = collector.enrich(snapshot)

            assertEquals(2, proxyPolls)
            assertEquals(2, tunPolls)
            assertEquals(1, first.proxyTelemetry.nativeEvents.size)
            assertEquals(first.proxyTelemetry.nativeEvents, second.proxyTelemetry.nativeEvents)
        }

    @Test
    fun finalCaptureEmitsCanonicalFinalEventWhenSummaryIsUnchanged() =
        runTest {
            val collector =
                DataPlaneEvidenceCollector(
                    mode = Mode.Proxy,
                    proxyEvidenceProvider = { ProxyForwardingEvidence(upstreamApplicationBytes = 64) },
                    clock = TestServiceClock(now = 9L),
                )

            val first = collector.enrich(emptyTelemetrySnapshot())
            val finalized = collector.finalizeAndEnrich(emptyTelemetrySnapshot())
            val finalEvents = finalized.proxyTelemetry.nativeEvents.filter { event -> event.kind == "data_plane_final" }
            val finalEvent = finalEvents.single()

            assertEquals(
                "data_plane_correlation",
                first.proxyTelemetry.nativeEvents
                    .single()
                    .kind,
            )
            assertEquals("service", finalEvent.source)
            assertEquals("info", finalEvent.level)
            assertEquals("data_plane", finalEvent.subsystem)
            assertEquals("proxy", finalEvent.mode)
            assertTrue(finalEvent.message.contains("state=proxy_outbound_observed"))
            assertTrue(finalEvent.message.contains("mode=proxy"))
            assertTrue(finalEvent.message.contains("generation=1"))
            assertTrue(finalEvent.message.contains("final=true"))
        }

    @Test
    fun cancelledFinalCaptureCanRetryAndSealFreshEvidence() =
        runTest {
            var attempts = 0
            val collector =
                DataPlaneEvidenceCollector(
                    mode = Mode.Proxy,
                    proxyEvidenceProvider = {
                        attempts += 1
                        if (attempts == 1) throw CancellationException("injected final capture cancellation")
                        ProxyForwardingEvidence(upstreamApplicationBytes = 64)
                    },
                    clock = TestServiceClock(now = 9L),
                )

            val cancelled =
                runCatching {
                    collector.finalizeAndEnrich(emptyTelemetrySnapshot())
                }.exceptionOrNull()
            val finalized = collector.finalizeAndEnrich(emptyTelemetrySnapshot())

            assertTrue(cancelled is CancellationException)
            assertEquals(2, attempts)
            assertEquals(
                1,
                finalized.proxyTelemetry.nativeEvents.count { event -> event.kind == "data_plane_final" },
            )
            assertTrue(
                finalized.proxyTelemetry.nativeEvents
                    .last()
                    .message
                    .contains("proxy_application_bytes=64"),
            )
        }

    @Test
    fun coordinatorFinalCaptureRetainsCollectorAcrossCaptureAndPublishCancellation() =
        runTest {
            var polls = 0
            var publishAttempts = 0
            val collector =
                DataPlaneEvidenceCollector(
                    mode = Mode.Proxy,
                    proxyEvidenceProvider = {
                        polls += 1
                        if (polls == 1) throw CancellationException("injected capture cancellation")
                        ProxyForwardingEvidence(upstreamApplicationBytes = 64)
                    },
                )
            val activeCollector = AtomicReference<DataPlaneEvidenceCollector?>(collector)
            val capture: suspend (DataPlaneEvidenceCollector) -> VpnTelemetrySnapshot = { evidenceCollector ->
                evidenceCollector.finalizeAndEnrich(emptyTelemetrySnapshot())
            }
            val publish: suspend (VpnTelemetrySnapshot) -> Unit = {
                publishAttempts += 1
                if (publishAttempts == 1) throw CancellationException("injected publish cancellation")
            }

            val captureCancellation =
                runCatching {
                    captureFinalDataPlaneEvidence(activeCollector, capture, publish)
                }.exceptionOrNull()
            assertTrue(captureCancellation is CancellationException)
            assertSame(collector, activeCollector.get())

            val publishCancellation =
                runCatching {
                    captureFinalDataPlaneEvidence(activeCollector, capture, publish)
                }.exceptionOrNull()
            assertTrue(publishCancellation is CancellationException)
            assertSame(collector, activeCollector.get())

            captureFinalDataPlaneEvidence(activeCollector, capture, publish)

            assertEquals(2, polls)
            assertEquals(2, publishAttempts)
            assertNull(activeCollector.get())
        }

    @Test
    fun trackerEmitsOnlyOneFinalEvent() {
        val tracker = DataPlaneCorrelationTracker(Mode.Proxy, TestServiceClock(now = 10L))

        tracker.observe(
            proxyEvidence = ProxyForwardingEvidence(upstreamApplicationBytes = 64),
            tunEvidence = null,
            tunSupport = TunEvidenceSupport.Unsupported,
        )
        tracker.finalEvent()
        tracker.finalEvent()

        assertEquals(1, tracker.events().count { event -> event.kind == "data_plane_final" })
    }

    @Test
    fun mixedAvailabilityDoesNotMakeNegativeCrossLayerClaims() =
        runTest {
            val tunOnly =
                DataPlaneEvidenceCollector(
                    mode = Mode.VPN,
                    proxyEvidenceProvider = { null },
                    tunEvidenceProvider = { TunForwardingEvidence(tunReadBytes = 10, tunWriteBytes = 5) },
                ).enrich(runningTelemetrySnapshot())
            val proxyOnly =
                DataPlaneEvidenceCollector(
                    mode = Mode.VPN,
                    proxyEvidenceProvider = { ProxyForwardingEvidence(upstreamApplicationBytes = 10) },
                    tunEvidenceProvider = { null },
                ).enrich(runningTelemetrySnapshot())

            val tunOnlyMessage =
                tunOnly.proxyTelemetry.nativeEvents
                    .single()
                    .message
            assertTrue(tunOnlyMessage.contains("state=evidence_unavailable_partial"))
            assertTrue(tunOnlyMessage.contains("proxy_outbound=unavailable"))
            assertTrue(tunOnlyMessage.contains("tun_return=observed"))
            val proxyOnlyMessage =
                proxyOnly.proxyTelemetry.nativeEvents
                    .single()
                    .message
            assertTrue(proxyOnlyMessage.contains("state=evidence_unavailable_partial"))
            assertTrue(proxyOnlyMessage.contains("proxy_outbound=observed"))
            assertTrue(proxyOnlyMessage.contains("tun_return=unavailable"))
            listOf(tunOnlyMessage, proxyOnlyMessage).forEach { message ->
                assertFalse(message.contains("state=tun_ingress_no_upstream"))
                assertFalse(message.contains("state=outbound_only"))
                assertFalse(message.contains("state=tun_return_without_proxy_outbound"))
            }
        }

    @Test
    fun unilateralResetRequiresOtherLayerToAdvanceBeforeCrossLayerClaim() {
        val tracker = DataPlaneCorrelationTracker(Mode.VPN, TestServiceClock())
        tracker.observe(
            ProxyForwardingEvidence(upstreamApplicationBytes = 900),
            TunForwardingEvidence(tunWriteBytes = 800),
            TunEvidenceSupport.Supported,
        )

        tracker.observe(
            ProxyForwardingEvidence(upstreamApplicationBytes = 9),
            TunForwardingEvidence(tunWriteBytes = 800),
            TunEvidenceSupport.Supported,
            proxyGenerationAuthoritative = true,
        )

        val afterProxyReset = tracker.events().last().message
        assertTrue(afterProxyReset.contains("state=evidence_unavailable_partial"))
        assertTrue(afterProxyReset.contains("proxy_outbound=observed"))
        assertTrue(afterProxyReset.contains("tun_return=unavailable"))
        assertFalse(afterProxyReset.contains("state=cross_layer_return_observed"))

        tracker.observe(
            ProxyForwardingEvidence(upstreamApplicationBytes = 9),
            TunForwardingEvidence(tunWriteBytes = 800, tunReadErrors = 1),
            TunEvidenceSupport.Supported,
        )

        val afterUnrelatedTunError = tracker.events().last().message
        assertTrue(afterUnrelatedTunError.contains("state=outbound_only"))
        assertTrue(afterUnrelatedTunError.contains("tun_return=not_observed"))
        assertTrue(afterUnrelatedTunError.contains("tun_write_bytes=0"))
        assertFalse(afterUnrelatedTunError.contains("state=cross_layer_return_observed"))

        tracker.observe(
            ProxyForwardingEvidence(upstreamApplicationBytes = 9),
            TunForwardingEvidence(tunWriteBytes = 801, tunReadErrors = 1),
            TunEvidenceSupport.Supported,
        )

        val afterTunAdvance = tracker.events().last().message
        assertTrue(afterTunAdvance.contains("state=cross_layer_return_observed"))
        assertTrue(afterTunAdvance.contains("generation=2"))
        assertTrue(afterTunAdvance.contains("tun_write_bytes=1"))
    }

    @Test
    fun unilateralTunResetUsesProxyFieldDeltasBeforeCrossLayerClaim() {
        val tracker = DataPlaneCorrelationTracker(Mode.VPN, TestServiceClock())
        tracker.observe(
            ProxyForwardingEvidence(upstreamApplicationBytes = 900),
            TunForwardingEvidence(tunWriteBytes = 800),
            TunEvidenceSupport.Supported,
        )

        tracker.observe(
            ProxyForwardingEvidence(upstreamApplicationBytes = 900),
            TunForwardingEvidence(tunWriteBytes = 8),
            TunEvidenceSupport.Supported,
            tunGenerationAuthoritative = true,
        )

        tracker.observe(
            ProxyForwardingEvidence(upstreamApplicationBytes = 900, upstreamOpenFailures = 1),
            TunForwardingEvidence(tunWriteBytes = 8),
            TunEvidenceSupport.Supported,
        )

        val afterUnrelatedProxyFailure = tracker.events().last().message
        assertTrue(afterUnrelatedProxyFailure.contains("state=tun_return_without_proxy_outbound"))
        assertTrue(afterUnrelatedProxyFailure.contains("proxy_outbound=not_observed"))
        assertTrue(afterUnrelatedProxyFailure.contains("proxy_application_bytes=0"))
        assertFalse(afterUnrelatedProxyFailure.contains("state=cross_layer_return_observed"))

        tracker.observe(
            ProxyForwardingEvidence(upstreamApplicationBytes = 901, upstreamOpenFailures = 1),
            TunForwardingEvidence(tunWriteBytes = 8),
            TunEvidenceSupport.Supported,
        )

        val afterProxyAdvance = tracker.events().last().message
        assertTrue(afterProxyAdvance.contains("state=cross_layer_return_observed"))
        assertTrue(afterProxyAdvance.contains("proxy_application_bytes=1"))
    }

    @Test
    fun timestampRollbackDoesNotStartCounterGeneration() {
        val tracker = DataPlaneCorrelationTracker(Mode.VPN, TestServiceClock())
        tracker.observe(
            ProxyForwardingEvidence(
                upstreamApplicationBytes = 10,
                lastUpstreamApplicationForwardedAt = 2_000,
            ),
            TunForwardingEvidence(
                tunWriteBytes = 10,
                lastTunWriteAtEpochMs = 2_000,
            ),
            TunEvidenceSupport.Supported,
        )
        tracker.observe(
            ProxyForwardingEvidence(
                upstreamApplicationBytes = 10,
                lastUpstreamApplicationForwardedAt = 1_000,
            ),
            TunForwardingEvidence(
                tunWriteBytes = 10,
                lastTunWriteAtEpochMs = 1_000,
            ),
            TunEvidenceSupport.Supported,
        )

        assertFalse(tracker.events().any { it.kind == "data_plane_counter_reset" })
    }

    @Test
    fun counterResetStartsNewGenerationWithoutMixingOldMaxima() {
        val tracker = DataPlaneCorrelationTracker(Mode.VPN, TestServiceClock())
        tracker.observe(
            proxyEvidence = ProxyForwardingEvidence(upstreamApplicationBytes = 900),
            tunEvidence = TunForwardingEvidence(tunWriteBytes = 800),
            tunSupport = TunEvidenceSupport.Supported,
        )
        tracker.observe(
            proxyEvidence = ProxyForwardingEvidence(upstreamApplicationBytes = 9),
            tunEvidence = TunForwardingEvidence(tunWriteBytes = 8),
            tunSupport = TunEvidenceSupport.Supported,
            proxyGenerationAuthoritative = true,
            tunGenerationAuthoritative = true,
        )

        val messages = tracker.events().map { it.message }
        assertTrue(messages.any { it.contains("state=counter_reset") && it.contains("generation=2") })
        val newGeneration = messages.last { it.contains("state=cross_layer_return_observed") }
        assertTrue(newGeneration.contains("generation=2"))
        assertTrue(newGeneration.contains("proxy_application_bytes=9"))
        assertTrue(newGeneration.contains("tun_write_bytes=8"))
        assertFalse(newGeneration.contains("proxy_application_bytes=900"))

        repeat(20) { index ->
            val high = 100L + index
            tracker.observe(
                ProxyForwardingEvidence(upstreamApplicationBytes = high),
                TunForwardingEvidence(tunWriteBytes = high),
                TunEvidenceSupport.Supported,
            )
            tracker.observe(
                ProxyForwardingEvidence(upstreamApplicationBytes = 1),
                TunForwardingEvidence(tunWriteBytes = 1),
                TunEvidenceSupport.Supported,
                proxyGenerationAuthoritative = true,
                tunGenerationAuthoritative = true,
            )
        }
        assertEquals(16, tracker.events().size)
        tracker.observe(
            ProxyForwardingEvidence(upstreamApplicationBytes = 2),
            TunForwardingEvidence(tunWriteBytes = 2),
            TunEvidenceSupport.Supported,
        )
        tracker.finalEvent()
        assertEquals(16, tracker.events().size)
        assertEquals("data_plane_final", tracker.events().last().kind)
        assertTrue(
            tracker
                .events()
                .last()
                .message
                .contains("final=true"),
        )
    }

    @Test
    fun delayedOldCollectorCannotPolluteNewRunCollector() =
        runTest {
            val oldStarted = CompletableDeferred<Unit>()
            val releaseOld = CompletableDeferred<Unit>()
            val oldCollector =
                DataPlaneEvidenceCollector(
                    mode = Mode.Proxy,
                    proxyEvidenceProvider = {
                        oldStarted.complete(Unit)
                        releaseOld.await()
                        ProxyForwardingEvidence(upstreamApplicationBytes = 999)
                    },
                )
            val oldPoll = async { oldCollector.enrich(runningTelemetrySnapshot()) }
            oldStarted.await()

            val newCollector =
                DataPlaneEvidenceCollector(
                    mode = Mode.Proxy,
                    proxyEvidenceProvider = { ProxyForwardingEvidence(upstreamApplicationBytes = 7) },
                )
            val newSnapshot = newCollector.enrich(runningTelemetrySnapshot())
            releaseOld.complete(Unit)
            oldPoll.await()
            val replayedNewSnapshot = newCollector.enrich(runningTelemetrySnapshot())

            assertTrue(
                newSnapshot.proxyTelemetry.nativeEvents
                    .single()
                    .message
                    .contains("proxy_application_bytes=7"),
            )
            assertEquals(newSnapshot.proxyTelemetry.nativeEvents, replayedNewSnapshot.proxyTelemetry.nativeEvents)
            assertFalse(
                replayedNewSnapshot.proxyTelemetry.nativeEvents
                    .single()
                    .message
                    .contains("999"),
            )
        }

    @Test
    fun finalCaptureWaitsForInFlightPollAndPublishesLatestEvidence() =
        runTest {
            val firstPollStarted = CompletableDeferred<Unit>()
            val releaseFirstPoll = CompletableDeferred<Unit>()
            var polls = 0
            val collector =
                DataPlaneEvidenceCollector(
                    mode = Mode.Proxy,
                    proxyEvidenceProvider = {
                        polls += 1
                        if (polls == 1) {
                            firstPollStarted.complete(Unit)
                            releaseFirstPoll.await()
                        }
                        ProxyForwardingEvidence(upstreamApplicationBytes = polls * 10L)
                    },
                )

            val inFlight = async { collector.enrich(runningTelemetrySnapshot()) }
            firstPollStarted.await()
            val finalCapture = async { collector.finalizeAndEnrich(runningTelemetrySnapshot()) }
            yield()
            assertEquals(1, polls)

            releaseFirstPoll.complete(Unit)
            inFlight.await()
            val finalEvents = finalCapture.await().proxyTelemetry.nativeEvents

            assertEquals(2, polls)
            assertEquals("data_plane_final", finalEvents.last().kind)
            assertTrue(finalEvents.last().message.contains("proxy_application_bytes=20"))
        }

    @Test
    fun lateEnrichAfterFinalSealCannotPollOrMutateFinalEvidence() =
        runTest {
            val releaseLatePreEnrichPath = CompletableDeferred<Unit>()
            var polls = 0
            val collector =
                DataPlaneEvidenceCollector(
                    mode = Mode.Proxy,
                    proxyEvidenceProvider = {
                        polls += 1
                        ProxyForwardingEvidence(upstreamApplicationBytes = if (polls < 3) polls * 10L else 999L)
                    },
                )
            collector.enrich(runningTelemetrySnapshot())
            val lateSnapshot =
                async {
                    releaseLatePreEnrichPath.await()
                    collector.enrich(runningTelemetrySnapshot())
                }

            val finalSnapshot = collector.finalizeAndEnrich(runningTelemetrySnapshot())
            releaseLatePreEnrichPath.complete(Unit)
            val replayAfterFinal = lateSnapshot.await()

            assertEquals(2, polls)
            assertEquals(finalSnapshot.proxyTelemetry.nativeEvents, replayAfterFinal.proxyTelemetry.nativeEvents)
            assertTrue(
                finalSnapshot.proxyTelemetry.nativeEvents
                    .last()
                    .message
                    .contains("proxy_application_bytes=20"),
            )
            assertFalse(finalSnapshot.proxyTelemetry.nativeEvents.any { it.message.contains("999") })
        }

    private fun emptyTelemetrySnapshot(): VpnTelemetrySnapshot =
        VpnTelemetrySnapshot(
            proxyTelemetry = NativeRuntimeSnapshot.idle("proxy"),
            proxyTelemetryStatus = RuntimeTelemetryStatus.NoData,
            relayTelemetry = NativeRuntimeSnapshot.idle("relay"),
            relayTelemetryStatus = RuntimeTelemetryStatus.NoData,
            warpTelemetry = NativeRuntimeSnapshot.idle("warp"),
            warpTelemetryStatus = RuntimeTelemetryStatus.NoData,
            awgTelemetry = NativeRuntimeSnapshot.idle("amneziawg"),
            awgTelemetryStatus = RuntimeTelemetryStatus.NoData,
            tunnelTelemetry = NativeRuntimeSnapshot.idle("tunnel"),
            tunnelTelemetryStatus = RuntimeTelemetryStatus.NoData,
        )

    private fun runningTelemetrySnapshot(): VpnTelemetrySnapshot =
        emptyTelemetrySnapshot().copy(
            proxyTelemetry = NativeRuntimeSnapshot(source = "proxy", state = "running"),
            proxyTelemetryStatus = RuntimeTelemetryStatus(state = RuntimeTelemetryState.Snapshot),
            tunnelTelemetry = NativeRuntimeSnapshot(source = "tunnel", state = "running"),
            tunnelTelemetryStatus = RuntimeTelemetryStatus(state = RuntimeTelemetryState.Snapshot),
        )
}
