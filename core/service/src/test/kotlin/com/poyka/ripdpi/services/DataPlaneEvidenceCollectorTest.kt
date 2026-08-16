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
    fun lifecycleGenerationChangeRejectsPreviousForwardingOutcome() =
        runTest {
            val collector =
                DataPlaneEvidenceCollector(
                    mode = Mode.VPN,
                    proxyEvidenceProvider = { error("explicit evidence observation expected") },
                )
            collector.enrich(
                runningTelemetrySnapshot(),
                RuntimeForwardingEvidence.Available(ProxyForwardingEvidence(upstreamApplicationBytes = 100)),
                RuntimeForwardingEvidence.Available(TunForwardingEvidence(tunWriteBytes = 100)),
                lifecycleGeneration = 1L,
            )
            collector.enrich(
                runningTelemetrySnapshot(),
                RuntimeForwardingEvidence.Available(ProxyForwardingEvidence(upstreamApplicationBytes = 200)),
                RuntimeForwardingEvidence.Available(TunForwardingEvidence(tunWriteBytes = 200)),
                lifecycleGeneration = 1L,
            )
            assertEquals("cross_layer_return_observed", collector.currentOutcome().wireValue)
            assertFalse(collector.currentOutcome().terminalFailure)

            collector.enrich(
                runningTelemetrySnapshot(),
                RuntimeForwardingEvidence.Available(ProxyForwardingEvidence(upstreamApplicationBytes = 200)),
                RuntimeForwardingEvidence.Available(TunForwardingEvidence(tunWriteBytes = 200)),
                lifecycleGeneration = 2L,
            )

            assertEquals("evidence_unavailable_partial", collector.currentOutcome().wireValue)
            assertFalse(collector.currentOutcome().terminalFailure)
        }

    @Test
    fun olderPollFinishingAfterReplacementCannotMutateReplacementOutcome() =
        runTest {
            val collector =
                DataPlaneEvidenceCollector(
                    mode = Mode.VPN,
                    proxyEvidenceProvider = { error("explicit evidence observation expected") },
                )
            val replacement =
                collector.enrichWithOutcome(
                    runningTelemetrySnapshot(),
                    RuntimeForwardingEvidence.Available(ProxyForwardingEvidence(upstreamApplicationBytes = 100)),
                    RuntimeForwardingEvidence.Available(TunForwardingEvidence(tunWriteBytes = 100)),
                    lifecycleGeneration = 2L,
                )

            val stale =
                collector.enrichWithOutcome(
                    runningTelemetrySnapshot(),
                    RuntimeForwardingEvidence.Available(ProxyForwardingEvidence.Empty),
                    RuntimeForwardingEvidence.Available(TunForwardingEvidence(tunReadBytes = 10_000)),
                    lifecycleGeneration = 1L,
                )

            assertEquals(replacement.outcome, stale.outcome)
            assertEquals(replacement.outcome, collector.currentOutcome())
        }

    @Test
    fun terminalForwardingFailureRequiresRepeatedCurrentGenerationEvidence() =
        runTest {
            val collector =
                DataPlaneEvidenceCollector(
                    mode = Mode.VPN,
                    proxyEvidenceProvider = { error("explicit evidence observation expected") },
                )

            repeat(2) {
                collector.enrich(
                    runningTelemetrySnapshot(),
                    RuntimeForwardingEvidence.Available(ProxyForwardingEvidence.Empty),
                    RuntimeForwardingEvidence.Available(TunForwardingEvidence(tunReadBytes = 100)),
                )
                assertFalse(collector.currentOutcome().terminalFailure)
            }
            collector.enrich(
                runningTelemetrySnapshot(),
                RuntimeForwardingEvidence.Available(ProxyForwardingEvidence.Empty),
                RuntimeForwardingEvidence.Available(TunForwardingEvidence(tunReadBytes = 100)),
            )

            assertEquals("tun_ingress_no_upstream", collector.currentOutcome().wireValue)
            assertTrue(collector.currentOutcome().terminalFailure)
        }

    @Test
    fun transientNegativePollClearsTerminalFailureCandidateWhenTrafficRecovers() =
        runTest {
            val collector =
                DataPlaneEvidenceCollector(
                    mode = Mode.VPN,
                    proxyEvidenceProvider = { error("explicit evidence observation expected") },
                )
            collector.enrich(
                runningTelemetrySnapshot(),
                RuntimeForwardingEvidence.Available(ProxyForwardingEvidence.Empty),
                RuntimeForwardingEvidence.Available(TunForwardingEvidence(tunReadBytes = 100)),
            )
            assertEquals("tun_ingress_no_upstream", collector.currentOutcome().wireValue)
            assertFalse(collector.currentOutcome().terminalFailure)

            collector.enrich(
                runningTelemetrySnapshot(),
                RuntimeForwardingEvidence.Available(ProxyForwardingEvidence(upstreamApplicationBytes = 100)),
                RuntimeForwardingEvidence.Available(TunForwardingEvidence(tunReadBytes = 100, tunWriteBytes = 100)),
            )

            assertEquals("cross_layer_return_observed", collector.currentOutcome().wireValue)
            assertFalse(collector.currentOutcome().terminalFailure)
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
    fun unavailableLeaseDoesNotResetUntilRestartEvidenceSharesRunningLease() =
        runTest {
            val collector =
                DataPlaneEvidenceCollector(
                    mode = Mode.Proxy,
                    proxyEvidenceProvider = { error("explicit evidence observation expected") },
                )

            collector.enrich(
                runningTelemetrySnapshot(),
                RuntimeForwardingEvidence.Available(
                    ProxyForwardingEvidence(upstreamApplicationBytes = 200),
                ),
            )
            val afterHandleLoss =
                collector.enrich(
                    runningTelemetrySnapshot(),
                    RuntimeForwardingEvidence.Unavailable,
                )

            assertFalse(afterHandleLoss.proxyTelemetry.nativeEvents.any { it.kind == "data_plane_counter_reset" })

            val afterRestart =
                collector.finalizeAndEnrich(
                    runningTelemetrySnapshot(),
                    RuntimeForwardingEvidence.Available(ProxyForwardingEvidence.Empty),
                )

            assertTrue(afterRestart.proxyTelemetry.nativeEvents.any { it.kind == "data_plane_counter_reset" })
            val finalEvent = afterRestart.proxyTelemetry.nativeEvents.last()
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
