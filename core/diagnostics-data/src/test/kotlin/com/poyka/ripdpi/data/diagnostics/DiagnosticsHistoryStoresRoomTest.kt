package com.poyka.ripdpi.data.diagnostics

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.NetworkDnsPathPreferenceRetentionLimit
import com.poyka.ripdpi.data.NetworkDnsPathPreferenceRetentionMaxAgeMs
import com.poyka.ripdpi.data.NetworkEdgePreferenceRetentionLimit
import com.poyka.ripdpi.data.NetworkEdgePreferenceRetentionMaxAgeMs
import com.poyka.ripdpi.data.PreferredEdgeCandidate
import com.poyka.ripdpi.data.PreferredEdgeIpVersionV4
import com.poyka.ripdpi.data.PreferredEdgeTransportTcp
import com.poyka.ripdpi.data.RememberedNetworkPolicyRetentionLimit
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusObserved
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusSuppressed
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusValidated
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

abstract class DiagnosticsRoomStoreTestBase {
    protected lateinit var db: DiagnosticsDatabase
    protected lateinit var dao: DiagnosticsDao
    protected lateinit var clock: DiagnosticsHistoryClock

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, DiagnosticsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.diagnosticsDao()
        clock = MutableDiagnosticsHistoryClock(now = 40L * DiagnosticsHistoryDayMillis)
    }

    @After
    fun tearDown() {
        db.close()
    }

    protected fun rowCount(table: String): Int =
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsHistoryStoresRoomTest : DiagnosticsRoomStoreTestBase() {
    @Test
    fun `profile catalog observes stored profiles and pack versions`() =
        runTest {
            val catalog = RoomDiagnosticsProfileCatalog(dao)

            catalog.upsertProfile(profile(id = "older", updatedAt = 10L))
            catalog.upsertProfile(profile(id = "newer", updatedAt = 20L))
            catalog.upsertPackVersion(TargetPackVersionEntity(packId = "newer", version = 3, importedAt = 30L))

            val observedProfiles = catalog.observeProfiles().first()

            assertEquals(listOf("newer", "older"), observedProfiles.map { it.id })
            assertEquals("newer", catalog.getProfile("newer")?.id)
            assertEquals(3, catalog.getPackVersion("newer")?.version)
        }

    @Test
    fun `scan record store replaces probe results transactionally`() =
        runTest {
            val store = RoomDiagnosticsScanRecordStore(db, dao)
            val session = scanSession(id = "scan-1", startedAt = 20L, finishedAt = 30L)

            store.upsertScanSession(session)
            store.replaceProbeResults(
                sessionId = session.id,
                results =
                    listOf(
                        probeResult(id = "probe-1", sessionId = session.id, createdAt = 21L),
                        probeResult(id = "probe-2", sessionId = session.id, createdAt = 22L),
                    ),
            )
            store.replaceProbeResults(
                sessionId = session.id,
                results = listOf(probeResult(id = "probe-3", sessionId = session.id, createdAt = 23L)),
            )

            assertEquals(session.id, store.getScanSession(session.id)?.id)
            assertEquals(listOf("probe-3"), store.getProbeResults(session.id).map { it.id })
            assertEquals(1, rowCount("probe_results"))
        }

    @Test
    fun `failure artifact batch rolls back every row when event insertion aborts`() =
        runTest {
            val store = RoomDiagnosticsFailureArtifactStore(dao)
            db.openHelper.writableDatabase.execSQL(
                """
                CREATE TRIGGER abort_failure_event
                BEFORE INSERT ON native_session_events
                BEGIN
                    SELECT RAISE(ABORT, 'forced failure event abort');
                END
                """.trimIndent(),
            )

            val result =
                runCatching {
                    store.persistFailureArtifacts(
                        usageSession =
                            bypassUsageSession(
                                id = "failure-connection",
                                startedAt = 100L,
                                finishedAt = 100L,
                            ).copy(connectionState = "Failed", failureMessage = "boom"),
                        snapshot =
                            snapshot(
                                id = "failure-snapshot",
                                sessionId = null,
                                connectionSessionId = "failure-connection",
                                capturedAt = 100L,
                            ).copy(snapshotKind = "failure"),
                        context =
                            context(
                                id = "failure-context",
                                sessionId = null,
                                connectionSessionId = "failure-connection",
                                capturedAt = 100L,
                            ).copy(contextKind = "failure"),
                        telemetry =
                            telemetry(
                                id = "failure-telemetry",
                                sessionId = null,
                                connectionSessionId = "failure-connection",
                                createdAt = 100L,
                            ).copy(connectionState = "Failed"),
                        event =
                            nativeEvent(
                                id = "failure-event",
                                sessionId = null,
                                connectionSessionId = "failure-connection",
                                createdAt = 100L,
                            ),
                    )
                }

            assertTrue(result.isFailure)
            assertEquals(0, rowCount("network_snapshots"))
            assertEquals(0, rowCount("diagnostic_context_snapshots"))
            assertEquals(0, rowCount("telemetry_samples"))
            assertEquals(0, rowCount("native_session_events"))
            assertEquals(0, rowCount("bypass_usage_sessions"))
        }

    @Test
    fun `artifact store exposes global and connection scoped reads after writes`() =
        runTest {
            val store = RoomDiagnosticsArtifactStore(db, dao)

            store.upsertSnapshot(
                snapshot(id = "snap-1", sessionId = "scan-1", connectionSessionId = "conn-1", capturedAt = 20L),
            )
            store.upsertSnapshot(
                snapshot(id = "snap-2", sessionId = "scan-2", connectionSessionId = "conn-2", capturedAt = 30L),
            )
            store.upsertContextSnapshot(
                context(id = "ctx-1", sessionId = "scan-1", connectionSessionId = "conn-1", capturedAt = 20L),
            )
            store.upsertContextSnapshot(
                context(id = "ctx-2", sessionId = "scan-2", connectionSessionId = "conn-2", capturedAt = 30L),
            )
            store.insertTelemetrySample(
                telemetry(id = "tel-1", sessionId = "scan-1", connectionSessionId = "conn-1", createdAt = 20L),
            )
            store.insertTelemetrySample(
                telemetry(id = "tel-2", sessionId = "scan-2", connectionSessionId = "conn-2", createdAt = 30L),
            )
            store.insertNativeSessionEvent(
                nativeEvent(id = "evt-1", sessionId = "scan-1", connectionSessionId = "conn-1", createdAt = 20L),
            )
            store.insertNativeSessionEvent(
                nativeEvent(id = "evt-2", sessionId = "scan-2", connectionSessionId = "conn-2", createdAt = 30L),
            )
            store.insertNativeSessionEvent(
                NativeSessionEventEntity(
                    id = "transition-1",
                    sessionId = "scan-1",
                    connectionSessionId = "conn-1",
                    source = "android_network_callback",
                    level = "info",
                    message = "kind=available;generation=1;sequence=1",
                    createdAt = 5L,
                    subsystem = "network_transition",
                ),
            )
            store.insertNativeSessionEvent(
                nativeEvent(id = "evt-global", sessionId = null, createdAt = 10L),
            )
            repeat(201) { index ->
                store.insertNativeSessionEvent(
                    nativeEvent(id = "evt-scan-$index", sessionId = "scan-flood", createdAt = 100L + index),
                )
            }
            store.insertExportRecord(exportRecord(id = "exp-1", sessionId = "scan-1", createdAt = 25L))

            assertEquals(listOf("snap-2", "snap-1"), store.observeSnapshots(limit = 10).first().map { it.id })
            assertEquals(
                listOf("snap-1"),
                store.observeConnectionSnapshots(connectionSessionId = "conn-1", limit = 10).first().map { it.id },
            )
            assertEquals(listOf("ctx-1"), store.observeConnectionContexts("conn-1", 10).first().map { it.id })
            assertEquals(listOf("tel-1"), store.observeConnectionTelemetry("conn-1", 10).first().map { it.id })
            assertEquals(
                listOf("evt-1", "transition-1"),
                store.observeConnectionNativeEvents("conn-1", 10).first().map { it.id },
            )
            assertEquals(
                listOf("transition-1"),
                store.observeConnectionNetworkTransitionEvents("conn-1").first().map { it.id },
            )
            assertEquals("evt-1", store.getNativeEventById("evt-1")?.id)
            assertNull(store.getNativeEventById("evt-missing"))
            assertEquals("transition-1", store.getNativeSessionEvent("transition-1")?.id)
            assertEquals(listOf("evt-global"), store.getGlobalNativeEvents(limit = 10).map { it.id })
            assertEquals(listOf("exp-1"), store.observeExportRecords(limit = 10).first().map { it.id })
        }

    @Test
    fun `archive stage telemetry excludes unscoped samples`() =
        runTest {
            val store = RoomDiagnosticsArtifactStore(db, dao)
            store.insertTelemetrySample(telemetry("stage-session", "scan-1", createdAt = 20L))
            store.insertTelemetrySample(telemetry("stage-connection", null, "conn-1", createdAt = 21L))
            store.insertTelemetrySample(telemetry("run-unscoped", null, null, createdAt = 22L))

            val samples =
                store.getTelemetryForArchiveStage(
                    sessionId = "scan-1",
                    connectionSessionIds = listOf("conn-1"),
                    startedAt = 10L,
                    finishedAt = 25L,
                    limit = 10,
                )

            assertEquals(listOf("stage-connection", "stage-session"), samples.map { it.id })
        }

    @Test
    fun `connection evidence limit excludes separately loaded network transitions`() =
        runTest {
            val store = RoomDiagnosticsArtifactStore(db, dao)
            val connectionSessionId = "conn-root-cause"
            val evidence =
                listOf(
                    nativeEvent(
                        id = "dns-evidence",
                        sessionId = null,
                        connectionSessionId = connectionSessionId,
                        createdAt = 10L,
                    ).copy(subsystem = "dns"),
                    nativeEvent(
                        id = "protect-evidence",
                        sessionId = null,
                        connectionSessionId = connectionSessionId,
                        createdAt = 11L,
                    ).copy(subsystem = "protect"),
                    nativeEvent(
                        id = "data-plane-evidence",
                        sessionId = null,
                        connectionSessionId = connectionSessionId,
                        createdAt = 12L,
                    ).copy(subsystem = "data_plane"),
                )
            evidence.forEach { event -> store.insertNativeSessionEvent(event) }
            repeat(65) { index ->
                store.insertNativeSessionEvent(
                    NativeSessionEventEntity(
                        id = "transition-$index",
                        sessionId = null,
                        connectionSessionId = connectionSessionId,
                        source = "android_network_callback",
                        level = "info",
                        message = "kind=available generation=$index sequence=${index + 1}",
                        createdAt = 100L + index,
                        subsystem = "network_transition",
                    ),
                )
            }

            assertEquals(
                listOf("data-plane-evidence", "protect-evidence", "dns-evidence"),
                store.observeConnectionRootCauseEvents(connectionSessionId, 64).first().map { it.id },
            )
            assertEquals(
                64,
                store.observeConnectionNativeEvents(connectionSessionId, 64).first().size,
            )
            assertEquals(
                65,
                store.observeConnectionNetworkTransitionEvents(connectionSessionId).first().size,
            )
        }

    @Test
    fun `artifact store returns latest telemetry sample by fingerprint and mode`() =
        runTest {
            val store = RoomDiagnosticsArtifactStore(db, dao)

            store.insertTelemetrySample(
                telemetry(
                    id = "tel-1",
                    sessionId = "scan-1",
                    createdAt = 20L,
                    activeMode = "VPN",
                    fingerprintHash = "fp-1",
                    failureClass = "network_handover",
                ),
            )
            store.insertTelemetrySample(
                telemetry(
                    id = "tel-2",
                    sessionId = "scan-2",
                    createdAt = 30L,
                    activeMode = "VPN",
                    fingerprintHash = "fp-1",
                    failureClass = "dns_tampering",
                ),
            )
            store.insertTelemetrySample(
                telemetry(
                    id = "tel-3",
                    sessionId = "scan-3",
                    createdAt = 40L,
                    activeMode = "Proxy",
                    fingerprintHash = "fp-1",
                    failureClass = "dns_tampering",
                ),
            )

            assertEquals(
                "tel-2",
                store
                    .getLatestTelemetrySampleForFingerprint(
                        activeMode = "VPN",
                        fingerprintHash = "fp-1",
                        createdAfter = 0L,
                    )?.id,
            )
            assertEquals(
                null,
                store.getLatestTelemetrySampleForFingerprint(
                    activeMode = "VPN",
                    fingerprintHash = "fp-1",
                    createdAfter = 35L,
                ),
            )
        }

    @Test
    fun `artifact store persists durable state and clears it with terminal native event transaction`() =
        runTest {
            val store = RoomDiagnosticsArtifactStore(db, dao)
            val pending =
                DiagnosticsDurableStateEntity(
                    key = "remote-acceptance",
                    value = "run-a",
                    updatedAt = 10L,
                )

            store.upsertDurableState(pending)
            store.insertNativeSessionEventAndClearDurableState(
                event = nativeEvent(id = "evt-terminal", sessionId = null, createdAt = 20L),
                key = pending.key,
                expectedValue = pending.value,
            )

            assertEquals("evt-terminal", store.getNativeEventById("evt-terminal")?.id)
            assertNull(store.getDurableState(pending.key))
        }

    @Test
    fun `artifact store atomically writes native event only for current durable generation`() =
        runTest {
            val store = RoomDiagnosticsArtifactStore(db, dao)
            val current =
                DiagnosticsDurableStateEntity(
                    key = "remote-acceptance",
                    value = "generation-1",
                    updatedAt = 10L,
                )
            val replacement = current.copy(value = "generation-2", updatedAt = 20L)
            val currentEvent = nativeEvent(id = "evt-current", sessionId = null, createdAt = 20L)

            store.upsertDurableState(current)

            assertTrue(
                store.insertNativeSessionEventAndUpsertDurableStateIfCurrent(
                    event = currentEvent,
                    state = replacement,
                    expectedValue = current.value,
                ),
            )
            assertEquals(currentEvent, store.getNativeEventById(currentEvent.id))
            assertEquals(replacement, store.getDurableState(current.key))

            val staleEvent = nativeEvent(id = "evt-stale", sessionId = null, createdAt = 30L)
            assertFalse(
                store.insertNativeSessionEventAndUpsertDurableStateIfCurrent(
                    event = staleEvent,
                    state = current.copy(value = "stale-replacement", updatedAt = 30L),
                    expectedValue = current.value,
                ),
            )
            assertNull(store.getNativeEventById(staleEvent.id))
            assertEquals(replacement, store.getDurableState(current.key))
        }

    @Test
    fun `artifact store atomically replaces only the current durable generation`() =
        runTest {
            val store = RoomDiagnosticsArtifactStore(db, dao)
            val malformed =
                DiagnosticsDurableStateEntity(
                    key = "remote-acceptance",
                    value = "malformed-generation",
                    updatedAt = 10L,
                )
            val replacement = malformed.copy(value = "generation-2", updatedAt = 20L)
            store.upsertDurableState(malformed)

            assertTrue(store.replaceDurableStateIfCurrent(replacement, malformed.value))
            assertEquals(replacement, store.getDurableState(malformed.key))

            assertFalse(
                store.replaceDurableStateIfCurrent(
                    state = malformed.copy(value = "stale-replacement", updatedAt = 30L),
                    expectedValue = malformed.value,
                ),
            )
            assertEquals(replacement, store.getDurableState(malformed.key))
        }

    @Test
    fun `artifact store bounds durable state by age and count transactionally`() =
        runTest {
            val store = RoomDiagnosticsArtifactStore(db, dao)
            store.upsertDurableState(
                DiagnosticsDurableStateEntity(
                    key = "bounded:stale",
                    value = "stale",
                    updatedAt = 1L,
                ),
            )

            repeat(4) { index ->
                store.upsertBoundedDurableState(
                    state =
                        DiagnosticsDurableStateEntity(
                            key = "bounded:$index",
                            value = "value-$index",
                            updatedAt = 10L + index,
                        ),
                    keyPrefix = "bounded:",
                    minimumUpdatedAt = 10L,
                    retainCount = 3,
                )
            }

            assertEquals(
                listOf("bounded:1", "bounded:2", "bounded:3"),
                dao.getDiagnosticsDurableStateByPrefix("bounded:", 10).map { state -> state.key },
            )
        }

    @Test
    fun `durable prefix operations do not match sql wildcard lookalikes`() =
        runTest {
            val literalPrefix = "policy_handover_delivery:"
            val matching = DiagnosticsDurableStateEntity("${literalPrefix}real", "real", 1L)
            val lookalike = DiagnosticsDurableStateEntity("policyXhandoverXdelivery:canary", "canary", 1L)
            dao.upsertDiagnosticsDurableState(matching)
            dao.upsertDiagnosticsDurableState(lookalike)

            assertEquals(
                listOf(matching.key),
                dao.getDiagnosticsDurableStateByPrefix(literalPrefix, 10).map { state -> state.key },
            )

            dao.clearDiagnosticsDurableStateByPrefixOlderThan(literalPrefix, minimumUpdatedAt = 2L)

            assertNull(dao.getDiagnosticsDurableState(matching.key))
            assertEquals(lookalike, dao.getDiagnosticsDurableState(lookalike.key))

            dao.upsertDiagnosticsDurableState(matching)
            dao.clearDiagnosticsDurableStateByPrefix(literalPrefix)

            assertNull(dao.getDiagnosticsDurableState(matching.key))
            assertEquals(lookalike, dao.getDiagnosticsDurableState(lookalike.key))
        }

    @Test
    fun `lookalike terminal dependency does not protect remembered policy retention`() =
        runTest {
            val rememberedStore = RoomRememberedNetworkPolicyRecordStore(dao, clock)
            val retentionStore = RoomDiagnosticsHistoryRetentionStore(dao, clock)
            val policy =
                rememberedPolicy(
                    fingerprintHash = "lookalike-prefix-policy",
                    mode = "vpn",
                    status = RememberedNetworkPolicyStatusObserved,
                    updatedAt = diagnosticsHistoryRetentionThreshold(clock.now(), 14) - 1L,
                )
            val policyId = rememberedStore.upsertRememberedNetworkPolicy(policy)
            dao.upsertDiagnosticsDurableState(
                DiagnosticsDurableStateEntity(
                    key = "runtimeXterminalXpolicy:canary",
                    value = policyId.toString(),
                    updatedAt = clock.now(),
                ),
            )

            retentionStore.trimOldData(retentionDays = 14)

            assertNull(rememberedStore.getRememberedNetworkPolicy(policy.fingerprintHash, policy.mode))
            assertNotNull(dao.getDiagnosticsDurableState("runtimeXterminalXpolicy:canary"))
        }

    @Test
    fun `bypass usage history store persists and observes sessions`() =
        runTest {
            val store = RoomBypassUsageHistoryStore(dao)

            store.upsertBypassUsageSession(
                bypassUsageSession(
                    id = "usage-1",
                    startedAt = 20L,
                    finishedAt = 30L,
                ).copy(
                    rememberedPolicyMatchedFingerprintHash = "fp-match",
                    rememberedPolicySource = RememberedNetworkPolicySource.AUTOMATIC_PROBING_BACKGROUND.storageValue,
                    rememberedPolicyAppliedByExactMatch = true,
                    rememberedPolicyPreviousSuccessCount = 4,
                    rememberedPolicyPreviousFailureCount = 1,
                    rememberedPolicyPreviousConsecutiveFailureCount = 0,
                ),
            )
            store.upsertBypassUsageSession(bypassUsageSession(id = "usage-2", startedAt = 40L, finishedAt = 50L))

            assertEquals(
                "usage-2",
                store
                    .observeBypassUsageSessions(limit = 10)
                    .first()
                    .first()
                    .id,
            )
            val persisted = store.getBypassUsageSession("usage-1")
            assertEquals("usage-1", persisted?.id)
            assertEquals("fp-match", persisted?.rememberedPolicyMatchedFingerprintHash)
            assertEquals(
                RememberedNetworkPolicySource.AUTOMATIC_PROBING_BACKGROUND.storageValue,
                persisted?.rememberedPolicySource,
            )
            assertEquals(true, persisted?.rememberedPolicyAppliedByExactMatch)
            assertEquals(4, persisted?.rememberedPolicyPreviousSuccessCount)
            assertEquals(1, persisted?.rememberedPolicyPreviousFailureCount)
            assertEquals(0, persisted?.rememberedPolicyPreviousConsecutiveFailureCount)
        }

    @Test
    fun `terminal outbox atomically checkpoints session and marker`() =
        runTest {
            val store = RoomDiagnosticsTerminalOutboxStore(db, dao)
            val finished = bypassUsageSession(id = "usage-terminal", startedAt = 20L, finishedAt = 30L)
            val marker =
                DiagnosticsDurableStateEntity(
                    key = "runtime_terminal_outbox:usage-terminal",
                    value = "runtime-events",
                    updatedAt = 30L,
                )

            store.beginTerminalOutbox(finished, marker, policyDependency = null)

            assertEquals(finished, dao.getBypassUsageSession(finished.id))
            assertEquals(listOf(marker), store.getPendingTerminalOutboxes())
            assertTrue(dao.getGlobalNativeEvents().isEmpty())

            val terminalEvent = nativeEvent(id = "terminal-event", sessionId = null, createdAt = 30L)
            val terminalSample = telemetry(id = "terminal-sample", sessionId = null, createdAt = 30L)
            val artifactMarker = marker.copy(value = "terminal-sample")
            assertTrue(
                store.checkpointTerminalArtifacts(
                    events = listOf(terminalEvent),
                    telemetrySample = terminalSample,
                    expectedMarker = marker,
                    replacementMarker = artifactMarker,
                ),
            )
            assertEquals(terminalEvent, dao.getNativeEventById(terminalEvent.id))
            assertEquals(terminalSample, dao.observeTelemetry().first().single())

            val terminalPolicy =
                rememberedPolicy(
                    fingerprintHash = "terminal-policy",
                    mode = "vpn",
                    status = RememberedNetworkPolicyStatusValidated,
                    updatedAt = 20L,
                ).copy(id = 7L, failureCount = 1)
            val policyMarker = marker.copy(value = "session-upsert")
            assertTrue(store.checkpointTerminalPolicy(terminalPolicy, artifactMarker, policyMarker))

            assertEquals(terminalPolicy, dao.getRememberedNetworkPolicy("terminal-policy", "vpn"))

            val sessionCheckpoint = finished.copy(connectionState = "Stopped")
            val sessionMarker = marker.copy(value = "root-cause-assessment")
            assertTrue(store.checkpointTerminalSession(sessionCheckpoint, policyMarker, sessionMarker))

            assertEquals(sessionCheckpoint, dao.getBypassUsageSession(finished.id))
            assertEquals(listOf(sessionMarker), store.getPendingTerminalOutboxes())

            val assessment = nativeEvent(id = "terminal-assessment", sessionId = null, createdAt = 30L)
            assertFalse(
                store.completeTerminalOutboxWithAssessment(
                    assessment = assessment,
                    marker = sessionMarker.copy(value = "stale-owner"),
                ),
            )
            assertNull(dao.getNativeEventById(assessment.id))
            assertEquals(listOf(sessionMarker), store.getPendingTerminalOutboxes())

            assertTrue(store.completeTerminalOutboxWithAssessment(assessment, sessionMarker))

            assertTrue(store.getPendingTerminalOutboxes().isEmpty())
            assertEquals(assessment, dao.getNativeEventById(assessment.id))
        }

    @Test
    fun `terminal outbox migrates legacy native event marker atomically`() =
        runTest {
            val store = RoomDiagnosticsTerminalOutboxStore(db, dao)
            val legacyMarker =
                nativeEvent(
                    id = "runtime_terminal_outbox:usage-legacy",
                    sessionId = null,
                    createdAt = 30L,
                ).copy(
                    message = "legacy-v1-marker",
                    subsystem = "runtime_terminal_outbox",
                )
            dao.insertNativeSessionEvent(legacyMarker)

            assertEquals(
                listOf(
                    DiagnosticsDurableStateEntity(
                        key = legacyMarker.id,
                        value = legacyMarker.message,
                        updatedAt = legacyMarker.createdAt,
                    ),
                ),
                store.getPendingTerminalOutboxes(),
            )
            assertNull(dao.getNativeEventById(legacyMarker.id))
        }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsNetworkPolicyStoresRoomTest : DiagnosticsRoomStoreTestBase() {
    @Test
    fun `remembered policy record store returns validated match and prunes old records`() =
        runTest {
            val store = RoomRememberedNetworkPolicyRecordStore(dao, clock)
            val freshValidated =
                rememberedPolicy(
                    fingerprintHash = "fp-match",
                    mode = "vpn",
                    status = RememberedNetworkPolicyStatusValidated,
                    lastValidatedAt = clock.now() - 100L,
                    updatedAt = clock.now() - 100L,
                )
            store.upsertRememberedNetworkPolicy(freshValidated)
            store.upsertRememberedNetworkPolicy(
                rememberedPolicy(
                    fingerprintHash = "fp-suppressed",
                    mode = "vpn",
                    status = RememberedNetworkPolicyStatusSuppressed,
                    lastValidatedAt = clock.now() - 50L,
                    suppressedUntil = clock.now() + 1_000L,
                    updatedAt = clock.now() - 50L,
                ),
            )
            repeat(RememberedNetworkPolicyRetentionLimit + 2) { index ->
                store.upsertRememberedNetworkPolicy(
                    rememberedPolicy(
                        fingerprintHash = "fp-$index",
                        mode = "vpn",
                        status = RememberedNetworkPolicyStatusObserved,
                        updatedAt = clock.now() - 1_000L + index,
                    ),
                )
            }
            assertEquals("fp-match", store.findValidatedRememberedNetworkPolicy("fp-match", "vpn")?.fingerprintHash)
            assertNull(store.findValidatedRememberedNetworkPolicy("fp-suppressed", "vpn"))

            store.pruneRememberedNetworkPolicies()

            assertEquals(RememberedNetworkPolicyRetentionLimit, rowCount("remembered_network_policies"))
        }

    @Test
    fun `remembered policy count pruning remains bounded while terminal evidence is pending`() =
        runTest {
            val store = RoomRememberedNetworkPolicyRecordStore(dao, clock)
            val artifactStore = RoomDiagnosticsArtifactStore(db, dao)
            var protectedPolicyId = 0L
            repeat(RememberedNetworkPolicyRetentionLimit + 2) { index ->
                val policyId =
                    store.upsertRememberedNetworkPolicy(
                        rememberedPolicy(
                            fingerprintHash = "fp-pending-$index",
                            mode = "vpn",
                            status = RememberedNetworkPolicyStatusObserved,
                            updatedAt = clock.now() - index,
                        ),
                    )
                if (index == RememberedNetworkPolicyRetentionLimit + 1) protectedPolicyId = policyId
            }
            artifactStore.upsertDurableState(
                DiagnosticsDurableStateEntity(
                    key = "runtime_terminal_policy:usage-pending",
                    value = protectedPolicyId.toString(),
                    updatedAt = clock.now(),
                ),
            )

            store.pruneRememberedNetworkPolicies()

            assertEquals(RememberedNetworkPolicyRetentionLimit + 1, rowCount("remembered_network_policies"))
            assertNotNull(
                dao.getRememberedNetworkPolicy(
                    "fp-pending-${RememberedNetworkPolicyRetentionLimit + 1}",
                    "vpn",
                ),
            )
        }

    @Test
    fun `remembered policy pruning never deletes dependencies beyond count limit`() =
        runTest {
            val store = RoomRememberedNetworkPolicyRecordStore(dao, clock)
            val artifactStore = RoomDiagnosticsArtifactStore(db, dao)
            repeat(RememberedNetworkPolicyRetentionLimit + 1) { index ->
                val policyId =
                    store.upsertRememberedNetworkPolicy(
                        rememberedPolicy(
                            fingerprintHash = "fp-protected-$index",
                            mode = "vpn",
                            status = RememberedNetworkPolicyStatusObserved,
                            updatedAt = clock.now() - index,
                        ),
                    )
                artifactStore.upsertDurableState(
                    DiagnosticsDurableStateEntity(
                        key = "runtime_terminal_policy:usage-protected-$index",
                        value = policyId.toString(),
                        updatedAt = clock.now(),
                    ),
                )
            }

            store.pruneRememberedNetworkPolicies()

            assertEquals(RememberedNetworkPolicyRetentionLimit + 1, rowCount("remembered_network_policies"))
            repeat(RememberedNetworkPolicyRetentionLimit + 1) { index ->
                assertNotNull(dao.getRememberedNetworkPolicy("fp-protected-$index", "vpn"))
            }
        }

    @Test
    fun `dns path preference store upserts and prunes records`() =
        runTest {
            val store = RoomNetworkDnsPathPreferenceRecordStore(dao, clock)

            repeat(NetworkDnsPathPreferenceRetentionLimit + 2) { index ->
                store.upsertNetworkDnsPathPreference(
                    dnsPreference(
                        fingerprintHash = "fp-$index",
                        updatedAt = clock.now() - 1_000L + index,
                    ),
                )
            }
            store.upsertNetworkDnsPathPreference(
                dnsPreference(
                    fingerprintHash = "fp-stale",
                    updatedAt = clock.now() - NetworkDnsPathPreferenceRetentionMaxAgeMs - 1L,
                ),
            )

            assertNotNull(store.getNetworkDnsPathPreference("fp-0"))

            store.pruneNetworkDnsPathPreferences()

            assertNull(store.getNetworkDnsPathPreference("fp-stale"))
            assertEquals(NetworkDnsPathPreferenceRetentionLimit, rowCount("network_dns_path_preferences"))
        }

    @Test
    fun `network edge preference store isolates records and prunes stale rows`() =
        runTest {
            val store = RoomNetworkEdgePreferenceRecordStore(dao, clock)

            repeat(NetworkEdgePreferenceRetentionLimit + 2) { index ->
                store.upsertNetworkEdgePreference(
                    edgePreference(
                        fingerprintHash = "fp-$index",
                        host = "host-$index.example",
                        transportKind = PreferredEdgeTransportTcp,
                        updatedAt = clock.now() - 1_000L + index,
                    ),
                )
            }
            store.upsertNetworkEdgePreference(
                edgePreference(
                    fingerprintHash = "fp-stale",
                    host = "stale.example",
                    transportKind = PreferredEdgeTransportTcp,
                    updatedAt = clock.now() - NetworkEdgePreferenceRetentionMaxAgeMs - 1L,
                ),
            )
            store.upsertNetworkEdgePreference(
                edgePreference(
                    fingerprintHash = "fp-runtime",
                    host = "alpha.example",
                    transportKind = PreferredEdgeTransportTcp,
                    updatedAt = clock.now(),
                ),
            )
            store.upsertNetworkEdgePreference(
                edgePreference(
                    fingerprintHash = "fp-runtime",
                    host = "alpha.example",
                    transportKind = "quic",
                    updatedAt = clock.now(),
                ),
            )

            assertEquals(2, store.getNetworkEdgePreferencesForFingerprint("fp-runtime").size)

            store.pruneNetworkEdgePreferences()

            assertNull(store.getNetworkEdgePreference("fp-stale", "stale.example", PreferredEdgeTransportTcp))
            assertEquals(NetworkEdgePreferenceRetentionLimit, rowCount("network_edge_preferences"))
        }

    @Test
    fun `clearing remembered network memory does not wipe diagnostics history`() =
        runTest {
            val rememberedStore = RoomRememberedNetworkPolicyRecordStore(dao, clock)
            val dnsStore = RoomNetworkDnsPathPreferenceRecordStore(dao, clock)
            val edgeStore = RoomNetworkEdgePreferenceRecordStore(dao, clock)
            val artifactStore = RoomDiagnosticsArtifactStore(db, dao)
            val bypassStore = RoomBypassUsageHistoryStore(dao)

            rememberedStore.upsertRememberedNetworkPolicy(
                rememberedPolicy(
                    fingerprintHash = "fp-clear",
                    mode = "vpn",
                    status = RememberedNetworkPolicyStatusValidated,
                    lastValidatedAt = clock.now(),
                    updatedAt = clock.now(),
                ),
            )
            dnsStore.upsertNetworkDnsPathPreference(
                dnsPreference(
                    fingerprintHash = "fp-clear",
                    updatedAt = clock.now(),
                ),
            )
            edgeStore.upsertNetworkEdgePreference(
                edgePreference(
                    fingerprintHash = "fp-clear",
                    host = "example.com",
                    transportKind = PreferredEdgeTransportTcp,
                    updatedAt = clock.now(),
                ),
            )
            artifactStore.upsertSnapshot(
                snapshot(id = "snap-keep", sessionId = "scan-1", capturedAt = clock.now()),
            )
            artifactStore.insertNativeSessionEvent(
                nativeEvent(id = "evt-keep", sessionId = "scan-1", createdAt = clock.now()),
            )
            bypassStore.upsertBypassUsageSession(
                bypassUsageSession(id = "usage-keep", startedAt = clock.now() - 100L, finishedAt = clock.now()),
            )

            rememberedStore.clearRememberedNetworkPolicies()
            dnsStore.clearNetworkDnsPathPreferences()
            edgeStore.clearNetworkEdgePreferences()

            assertEquals(0, rowCount("remembered_network_policies"))
            assertEquals(0, rowCount("network_dns_path_preferences"))
            assertEquals(0, rowCount("network_edge_preferences"))
            assertEquals(1, rowCount("network_snapshots"))
            assertEquals(1, rowCount("native_session_events"))
            assertEquals(1, rowCount("bypass_usage_sessions"))
        }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsHistoryRetentionRoomTest : DiagnosticsRoomStoreTestBase() {
    @Test
    @Suppress("LongMethod")
    fun `history retention store trims old rows across diagnostics tables`() =
        runTest {
            val scanStore = RoomDiagnosticsScanRecordStore(db, dao)
            val artifactStore = RoomDiagnosticsArtifactStore(db, dao)
            val bypassStore = RoomBypassUsageHistoryStore(dao)
            val rememberedStore = RoomRememberedNetworkPolicyRecordStore(dao, clock)
            val dnsStore = RoomNetworkDnsPathPreferenceRecordStore(dao, clock)
            val retentionStore = RoomDiagnosticsHistoryRetentionStore(dao, clock)
            val threshold = diagnosticsHistoryRetentionThreshold(clock.now(), 14)

            scanStore.upsertScanSession(scanSession(id = "scan-old", startedAt = 5L, finishedAt = threshold - 10L))
            scanStore.upsertScanSession(
                scanSession(
                    id = "scan-new",
                    startedAt = threshold + 10L,
                    finishedAt =
                        threshold + 20L,
                ),
            )
            scanStore.replaceProbeResults(
                "scan-old",
                listOf(probeResult(id = "probe-old", sessionId = "scan-old", createdAt = threshold - 10L)),
            )
            scanStore.replaceProbeResults(
                "scan-new",
                listOf(probeResult(id = "probe-new", sessionId = "scan-new", createdAt = threshold + 10L)),
            )
            artifactStore.upsertSnapshot(
                snapshot(id = "snap-old", sessionId = "scan-old", capturedAt = threshold - 10L),
            )
            artifactStore.upsertSnapshot(
                snapshot(id = "snap-new", sessionId = "scan-new", capturedAt = threshold + 10L),
            )
            artifactStore.upsertContextSnapshot(
                context(
                    id = "ctx-old",
                    sessionId = "scan-old",
                    capturedAt =
                        threshold - 10L,
                ),
            )
            artifactStore.upsertContextSnapshot(
                context(
                    id = "ctx-new",
                    sessionId = "scan-new",
                    capturedAt =
                        threshold + 10L,
                ),
            )
            artifactStore.insertTelemetrySample(
                telemetry(
                    id = "tel-old",
                    sessionId = "scan-old",
                    createdAt =
                        threshold - 10L,
                ),
            )
            artifactStore.insertTelemetrySample(
                telemetry(
                    id = "tel-new",
                    sessionId = "scan-new",
                    createdAt =
                        threshold + 10L,
                ),
            )
            artifactStore.insertNativeSessionEvent(
                nativeEvent(
                    id = "evt-old",
                    sessionId = "scan-old",
                    createdAt =
                        threshold - 10L,
                ),
            )
            artifactStore.insertNativeSessionEvent(
                nativeEvent(
                    id = "evt-new",
                    sessionId = "scan-new",
                    createdAt =
                        threshold + 10L,
                ),
            )
            artifactStore.insertExportRecord(
                exportRecord(
                    id = "exp-old",
                    sessionId = "scan-old",
                    createdAt =
                        threshold - 10L,
                ),
            )
            artifactStore.insertExportRecord(
                exportRecord(
                    id = "exp-new",
                    sessionId = "scan-new",
                    createdAt =
                        threshold + 10L,
                ),
            )
            bypassStore.upsertBypassUsageSession(
                bypassUsageSession(
                    id = "usage-old",
                    startedAt = 1L,
                    finishedAt =
                        threshold - 10L,
                ),
            )
            val pendingSession =
                bypassUsageSession(
                    id = "usage-pending",
                    startedAt = 1L,
                    finishedAt = threshold - 10L,
                )
            val pendingMarker =
                DiagnosticsDurableStateEntity(
                    key = "runtime_terminal_outbox:${pendingSession.id}",
                    value = "RUNTIME_EVENTS",
                    updatedAt = threshold - 10L,
                )
            bypassStore.upsertBypassUsageSession(pendingSession)
            artifactStore.insertTelemetrySample(
                telemetry(
                    id = "tel-pending",
                    sessionId = null,
                    connectionSessionId = pendingSession.id,
                    createdAt = threshold - 10L,
                ),
            )
            artifactStore.insertNativeSessionEvent(
                nativeEvent(
                    id = "evt-pending",
                    sessionId = null,
                    connectionSessionId = pendingSession.id,
                    createdAt = threshold - 10L,
                ),
            )
            val pendingPolicy =
                rememberedPolicy(
                    fingerprintHash = "policy-pending",
                    mode = "vpn",
                    status = RememberedNetworkPolicyStatusObserved,
                    updatedAt = threshold - 10L,
                )
            val pendingPolicyId = rememberedStore.upsertRememberedNetworkPolicy(pendingPolicy)
            RoomDiagnosticsTerminalOutboxStore(db, dao).beginTerminalOutbox(
                finishedSession = pendingSession,
                marker = pendingMarker,
                policyDependency =
                    DiagnosticsDurableStateEntity(
                        key = "runtime_terminal_policy:${pendingSession.id}",
                        value = pendingPolicyId.toString(),
                        updatedAt = pendingMarker.updatedAt,
                    ),
            )
            bypassStore.upsertBypassUsageSession(
                bypassUsageSession(
                    id = "usage-new",
                    startedAt = threshold + 1L,
                    finishedAt =
                        threshold + 20L,
                ),
            )
            dnsStore.upsertNetworkDnsPathPreference(
                dnsPreference(
                    fingerprintHash = "dns-old",
                    updatedAt =
                        threshold - 10L,
                ),
            )
            dnsStore.upsertNetworkDnsPathPreference(
                dnsPreference(
                    fingerprintHash = "dns-new",
                    updatedAt =
                        threshold + 10L,
                ),
            )

            listOf(
                "RUNTIME_EVENTS",
                "TERMINAL_SAMPLE",
                "POLICY_FINALIZATION",
                "SESSION_UPSERT",
                "ROOT_CAUSE_ASSESSMENT",
            ).forEach { phase ->
                artifactStore.upsertDurableState(pendingMarker.copy(value = phase))
                retentionStore.trimOldData(retentionDays = 14)
                assertEquals(pendingSession, bypassStore.getBypassUsageSession(pendingSession.id))
                assertNotNull(artifactStore.getNativeSessionEvent("evt-pending"))
                assertTrue(
                    artifactStore
                        .observeConnectionTelemetry(pendingSession.id)
                        .first()
                        .any { sample -> sample.id == "tel-pending" },
                )
                assertNotNull(
                    rememberedStore.getRememberedNetworkPolicy(
                        pendingPolicy.fingerprintHash,
                        pendingPolicy.mode,
                    ),
                )
            }

            assertEquals(1, rowCount("scan_sessions"))
            assertEquals(1, rowCount("probe_results"))
            assertEquals(1, rowCount("network_snapshots"))
            assertEquals(1, rowCount("diagnostic_context_snapshots"))
            assertEquals(2, rowCount("telemetry_samples"))
            assertEquals(2, rowCount("native_session_events"))
            assertEquals(1, rowCount("export_records"))
            assertEquals(2, rowCount("bypass_usage_sessions"))
            assertEquals(1, rowCount("network_dns_path_preferences"))
            assertEquals("scan-new", scanStore.getScanSession("scan-new")?.id)
            assertNull(scanStore.getScanSession("scan-old"))
            assertNotNull(artifactStore.getDurableState(pendingMarker.key))
            assertEquals(pendingSession, bypassStore.getBypassUsageSession(pendingSession.id))
            assertNotNull(dnsStore.getNetworkDnsPathPreference("dns-new"))
            assertNull(dnsStore.getNetworkDnsPathPreference("dns-old"))

            val finalMarker = requireNotNull(artifactStore.getDurableState(pendingMarker.key))
            assertTrue(RoomDiagnosticsTerminalOutboxStore(db, dao).completeTerminalOutbox(finalMarker))
            assertNull(artifactStore.getDurableState("runtime_terminal_policy:${pendingSession.id}"))
            retentionStore.trimOldData(retentionDays = 14)

            assertNull(bypassStore.getBypassUsageSession(pendingSession.id))
            assertNull(artifactStore.getNativeSessionEvent("evt-pending"))
            assertTrue(artifactStore.observeConnectionTelemetry(pendingSession.id).first().isEmpty())
            assertNull(
                rememberedStore.getRememberedNetworkPolicy(
                    pendingPolicy.fingerprintHash,
                    pendingPolicy.mode,
                ),
            )
        }

    @Test
    fun `history retention waits for legacy terminal outbox migration`() =
        runTest {
            val artifactStore = RoomDiagnosticsArtifactStore(db, dao)
            val bypassStore = RoomBypassUsageHistoryStore(dao)
            val retentionStore = RoomDiagnosticsHistoryRetentionStore(dao, clock)
            val terminalStore = RoomDiagnosticsTerminalOutboxStore(db, dao)
            val threshold = diagnosticsHistoryRetentionThreshold(clock.now(), 14)
            val legacySession =
                bypassUsageSession(
                    id = "usage-legacy-retention",
                    startedAt = 1L,
                    finishedAt = threshold - 10L,
                )
            val unrelatedSession =
                bypassUsageSession(
                    id = "usage-unrelated-retention",
                    startedAt = 1L,
                    finishedAt = threshold - 10L,
                )
            bypassStore.upsertBypassUsageSession(legacySession)
            bypassStore.upsertBypassUsageSession(unrelatedSession)
            artifactStore.insertNativeSessionEvent(
                nativeEvent(
                    id = "evt-legacy-retention",
                    sessionId = null,
                    connectionSessionId = legacySession.id,
                    createdAt = threshold - 10L,
                ),
            )
            artifactStore.insertNativeSessionEvent(
                nativeEvent(
                    id = "evt-unrelated-retention",
                    sessionId = null,
                    connectionSessionId = unrelatedSession.id,
                    createdAt = threshold - 10L,
                ),
            )
            val legacyMarker =
                NativeSessionEventEntity(
                    id = "runtime_terminal_outbox:${legacySession.id}",
                    source = "runtime",
                    level = "info",
                    message = "legacy-marker-payload",
                    createdAt = threshold - 10L,
                    subsystem = "runtime_terminal_outbox",
                )
            artifactStore.insertNativeSessionEvent(legacyMarker)

            retentionStore.trimOldData(retentionDays = 14)

            assertNotNull(artifactStore.getNativeSessionEvent(legacyMarker.id))
            assertNotNull(bypassStore.getBypassUsageSession(legacySession.id))
            assertNotNull(bypassStore.getBypassUsageSession(unrelatedSession.id))
            assertNotNull(artifactStore.getNativeSessionEvent("evt-legacy-retention"))
            assertNotNull(artifactStore.getNativeSessionEvent("evt-unrelated-retention"))

            assertEquals(1, terminalStore.getPendingTerminalOutboxes().size)
            retentionStore.trimOldData(retentionDays = 14)

            assertNull(artifactStore.getNativeSessionEvent(legacyMarker.id))
            assertNotNull(artifactStore.getDurableState(legacyMarker.id))
            assertNotNull(bypassStore.getBypassUsageSession(legacySession.id))
            assertNotNull(artifactStore.getNativeSessionEvent("evt-legacy-retention"))
            assertNull(bypassStore.getBypassUsageSession(unrelatedSession.id))
            assertNull(artifactStore.getNativeSessionEvent("evt-unrelated-retention"))
        }

    @Test
    @Suppress("LongMethod")
    fun `history reset store clears runtime history but preserves profiles`() =
        runTest {
            val profileCatalog = RoomDiagnosticsProfileCatalog(dao)
            val scanStore = RoomDiagnosticsScanRecordStore(db, dao)
            val artifactStore = RoomDiagnosticsArtifactStore(db, dao)
            val bypassStore = RoomBypassUsageHistoryStore(dao)
            val rememberedStore = RoomRememberedNetworkPolicyRecordStore(dao, clock)
            val dnsStore = RoomNetworkDnsPathPreferenceRecordStore(dao, clock)
            val edgeStore = RoomNetworkEdgePreferenceRecordStore(dao, clock)
            val resetStore = RoomDiagnosticsHistoryResetStore(db, dao)
            val session = scanSession(id = "scan-reset", startedAt = 20L, finishedAt = 30L)

            profileCatalog.upsertProfile(profile(id = "profile-reset", updatedAt = 10L))
            scanStore.upsertScanSession(session)
            scanStore.replaceProbeResults(
                sessionId = session.id,
                results = listOf(probeResult(id = "probe-reset", sessionId = session.id, createdAt = 21L)),
            )
            artifactStore.upsertSnapshot(snapshot(id = "snap-reset", sessionId = session.id, capturedAt = 22L))
            artifactStore.upsertContextSnapshot(context(id = "ctx-reset", sessionId = session.id, capturedAt = 23L))
            artifactStore.insertTelemetrySample(telemetry(id = "tel-reset", sessionId = session.id, createdAt = 24L))
            artifactStore.insertNativeSessionEvent(
                nativeEvent(id = "evt-reset", sessionId = session.id, createdAt = 25L),
            )
            artifactStore.upsertDurableState(
                DiagnosticsDurableStateEntity(
                    key = "remote_acceptance_pending_generation",
                    value = "run-reset",
                    updatedAt = 25L,
                ),
            )
            artifactStore.upsertDurableState(
                DiagnosticsDurableStateEntity(
                    key = "runtime_terminal_outbox:usage-reset",
                    value = "terminal-reset",
                    updatedAt = 25L,
                ),
            )
            artifactStore.upsertDurableState(
                DiagnosticsDurableStateEntity(
                    key = "policy_handover_delivery:handover-reset",
                    value = "handover-reset",
                    updatedAt = 25L,
                ),
            )
            artifactStore.insertExportRecord(exportRecord(id = "exp-reset", sessionId = session.id, createdAt = 26L))
            bypassStore.upsertBypassUsageSession(
                bypassUsageSession(id = "usage-reset", startedAt = 27L, finishedAt = 28L),
            )
            rememberedStore.upsertRememberedNetworkPolicy(
                rememberedPolicy(
                    fingerprintHash = "remembered-reset",
                    mode = "VPN",
                    status = RememberedNetworkPolicyStatusObserved,
                    updatedAt = 29L,
                ),
            )
            dnsStore.upsertNetworkDnsPathPreference(
                dnsPreference(
                    fingerprintHash = "dns-reset",
                    updatedAt = 30L,
                ),
            )
            dao.upsertBlockedPath(
                NetworkDnsBlockedPathEntity(
                    fingerprintHash = "dns-reset",
                    pathKey = "path-reset",
                    blockReason = "test",
                    updatedAt = 31L,
                ),
            )
            edgeStore.upsertNetworkEdgePreference(
                edgePreference(
                    fingerprintHash = "edge-reset",
                    host = "example.test",
                    transportKind = PreferredEdgeTransportTcp,
                    updatedAt = 32L,
                ),
            )

            resetStore.clearRuntimeHistory()

            assertEquals(listOf("profile-reset"), profileCatalog.observeProfiles().first().map { it.id })
            assertEquals(0, rowCount("scan_sessions"))
            assertEquals(0, rowCount("probe_results"))
            assertEquals(0, rowCount("network_snapshots"))
            assertEquals(0, rowCount("diagnostic_context_snapshots"))
            assertEquals(0, rowCount("telemetry_samples"))
            assertEquals(0, rowCount("native_session_events"))
            assertEquals(0, rowCount("export_records"))
            assertEquals(0, rowCount("bypass_usage_sessions"))
            assertEquals(0, rowCount("remembered_network_policies"))
            assertEquals(0, rowCount("network_dns_path_preferences"))
            assertEquals(0, rowCount("network_dns_blocked_paths"))
            assertEquals(0, rowCount("network_edge_preferences"))
            assertEquals(0, rowCount("diagnostics_durable_state"))
            assertNull(artifactStore.getDurableState("remote_acceptance_pending_generation"))
            assertNull(artifactStore.getDurableState("runtime_terminal_outbox:usage-reset"))
            assertNull(artifactStore.getDurableState("policy_handover_delivery:handover-reset"))
        }

    @Test
    fun `production database builder destructively migrates v2 database when allowDestructiveFallback is true`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "diagnostics-legacy-v2-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)

        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { legacyDb ->
            legacyDb.execSQL("CREATE TABLE legacy_marker(id TEXT NOT NULL PRIMARY KEY)")
            legacyDb.execSQL("PRAGMA user_version = 2")
        }

        val migratedDb =
            DiagnosticsDatabaseModule.buildDiagnosticsDatabase(
                context,
                databaseName,
                allowDestructiveFallback = true,
            )
        try {
            migratedDb.openHelper.writableDatabase

            assertTrue(tableExists(migratedDb, "diagnostic_profiles"))
            assertTrue(tableExists(migratedDb, "network_edge_preferences"))
            assertFalse(tableExists(migratedDb, "legacy_marker"))
        } finally {
            migratedDb.close()
            context.deleteDatabase(databaseName)
        }
    }
}

private fun tableExists(
    database: DiagnosticsDatabase,
    table: String,
): Boolean {
    val openHelper = database.openHelper
    val supportDb = openHelper.writableDatabase
    val cursor =
        supportDb.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$table'",
        )
    return cursor.use { resultCursor ->
        resultCursor.moveToFirst()
        resultCursor.getInt(0) == 1
    }
}

private class MutableDiagnosticsHistoryClock(
    private var now: Long,
) : DiagnosticsHistoryClock {
    override fun now(): Long = now
}

private fun profile(
    id: String,
    updatedAt: Long,
) = DiagnosticProfileEntity(
    id = id,
    name = id,
    source = "test",
    version = 1,
    requestJson = "{}",
    updatedAt = updatedAt,
)

private fun scanSession(
    id: String,
    startedAt: Long,
    finishedAt: Long?,
) = ScanSessionEntity(
    id = id,
    profileId = "default",
    pathMode = "RAW_PATH",
    serviceMode = "VPN",
    status = "completed",
    summary = id,
    reportJson = "{}",
    startedAt = startedAt,
    finishedAt = finishedAt,
)

private fun probeResult(
    id: String,
    sessionId: String,
    createdAt: Long,
) = ProbeResultEntity(
    id = id,
    sessionId = sessionId,
    probeType = "dns",
    target = "blocked.example",
    outcome = "dns_blocked",
    detailJson = "[]",
    createdAt = createdAt,
)

private fun snapshot(
    id: String,
    sessionId: String?,
    connectionSessionId: String? = null,
    capturedAt: Long,
) = NetworkSnapshotEntity(
    id = id,
    sessionId = sessionId,
    connectionSessionId = connectionSessionId,
    snapshotKind = "post_scan",
    payloadJson = "{}",
    capturedAt = capturedAt,
)

private fun context(
    id: String,
    sessionId: String?,
    connectionSessionId: String? = null,
    capturedAt: Long,
) = DiagnosticContextEntity(
    id = id,
    sessionId = sessionId,
    connectionSessionId = connectionSessionId,
    contextKind = "post_scan",
    payloadJson = "{}",
    capturedAt = capturedAt,
)

private fun telemetry(
    id: String,
    sessionId: String?,
    connectionSessionId: String? = null,
    createdAt: Long,
    activeMode: String = "VPN",
    fingerprintHash: String? = null,
    failureClass: String? = null,
) = TelemetrySampleEntity(
    id = id,
    sessionId = sessionId,
    connectionSessionId = connectionSessionId,
    activeMode = activeMode,
    connectionState = "Running",
    networkType = "wifi",
    publicIp = "198.51.100.10",
    failureClass = failureClass,
    telemetryNetworkFingerprintHash = fingerprintHash,
    txPackets = 1L,
    txBytes = 64L,
    rxPackets = 2L,
    rxBytes = 128L,
    createdAt = createdAt,
)

private fun nativeEvent(
    id: String,
    sessionId: String?,
    connectionSessionId: String? = null,
    createdAt: Long,
) = NativeSessionEventEntity(
    id = id,
    sessionId = sessionId,
    connectionSessionId = connectionSessionId,
    source = "native",
    level = "info",
    message = id,
    createdAt = createdAt,
)

private fun exportRecord(
    id: String,
    sessionId: String?,
    createdAt: Long,
) = ExportRecordEntity(
    id = id,
    sessionId = sessionId,
    uri = "content://test/$id",
    fileName = "$id.zip",
    createdAt = createdAt,
)

private fun bypassUsageSession(
    id: String,
    startedAt: Long,
    finishedAt: Long?,
) = BypassUsageSessionEntity(
    id = id,
    startedAt = startedAt,
    finishedAt = finishedAt,
    updatedAt = finishedAt ?: startedAt,
    serviceMode = "VPN",
    approachProfileId = "profile",
    approachProfileName = "Profile",
    strategyId = "strategy",
    strategyLabel = "Strategy",
    strategyJson = "{}",
    networkType = "wifi",
    txBytes = 1L,
    rxBytes = 2L,
    totalErrors = 0L,
    routeChanges = 0L,
    restartCount = 0,
    endedReason = null,
)

private fun rememberedPolicy(
    fingerprintHash: String,
    mode: String,
    status: String,
    updatedAt: Long,
    lastValidatedAt: Long? = null,
    suppressedUntil: Long? = null,
) = RememberedNetworkPolicyEntity(
    fingerprintHash = fingerprintHash,
    mode = mode,
    summaryJson = "{}",
    proxyConfigJson = "{}",
    source = RememberedNetworkPolicySource.MANUAL_SESSION.encodeStorageValue(),
    status = status,
    firstObservedAt = updatedAt,
    lastValidatedAt = lastValidatedAt,
    suppressedUntil = suppressedUntil,
    updatedAt = updatedAt,
)

private fun dnsPreference(
    fingerprintHash: String,
    updatedAt: Long,
) = NetworkDnsPathPreferenceEntity(
    fingerprintHash = fingerprintHash,
    summaryJson = "{}",
    pathJson =
        kotlinx.serialization.json.Json.encodeToString(
            EncryptedDnsPathCandidate.serializer(),
            EncryptedDnsPathCandidate(
                resolverId = "cloudflare",
                resolverLabel = "Cloudflare",
                protocol = EncryptedDnsProtocolDoh,
                host = "cloudflare-dns.com",
                port = 443,
                tlsServerName = "cloudflare-dns.com",
                bootstrapIps = listOf("1.1.1.1"),
                dohUrl = "https://cloudflare-dns.com/dns-query",
                dnscryptProviderName = "",
                dnscryptPublicKey = "",
            ),
        ),
    updatedAt = updatedAt,
)

private fun edgePreference(
    fingerprintHash: String,
    host: String,
    transportKind: String,
    updatedAt: Long,
) = NetworkEdgePreferenceEntity(
    fingerprintHash = fingerprintHash,
    host = host,
    transportKind = transportKind,
    summaryJson = "{}",
    edgesJson =
        kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(PreferredEdgeCandidate.serializer()),
            listOf(
                PreferredEdgeCandidate(
                    ip = "203.0.113.10",
                    transportKind = transportKind,
                    ipVersion = PreferredEdgeIpVersionV4,
                ),
            ),
        ),
    updatedAt = updatedAt,
)
