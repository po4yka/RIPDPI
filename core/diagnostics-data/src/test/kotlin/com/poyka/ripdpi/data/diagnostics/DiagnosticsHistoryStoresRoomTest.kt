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
import com.poyka.ripdpi.data.RememberedNetworkPolicyRetentionMaxAgeMs
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusObserved
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusSuppressed
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusValidated
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsHistoryStoresRoomTest {
    private lateinit var db: DiagnosticsDatabase
    private lateinit var dao: DiagnosticsDao
    private lateinit var clock: MutableDiagnosticsHistoryClock

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
    fun `artifact store exposes global and connection scoped reads after writes`() =
        runTest {
            val store = RoomDiagnosticsArtifactStore(dao)

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
            store.insertExportRecord(exportRecord(id = "exp-1", sessionId = "scan-1", createdAt = 25L))

            assertEquals(listOf("snap-2", "snap-1"), store.observeSnapshots(limit = 10).first().map { it.id })
            assertEquals(
                listOf("snap-1"),
                store.observeConnectionSnapshots(connectionSessionId = "conn-1", limit = 10).first().map { it.id },
            )
            assertEquals(listOf("ctx-1"), store.observeConnectionContexts("conn-1", 10).first().map { it.id })
            assertEquals(listOf("tel-1"), store.observeConnectionTelemetry("conn-1", 10).first().map { it.id })
            assertEquals(listOf("evt-1"), store.observeConnectionNativeEvents("conn-1", 10).first().map { it.id })
            assertEquals(listOf("exp-1"), store.observeExportRecords(limit = 10).first().map { it.id })
        }

    @Test
    fun `artifact store returns latest telemetry sample by fingerprint and mode`() =
        runTest {
            val store = RoomDiagnosticsArtifactStore(dao)

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
            store.upsertRememberedNetworkPolicy(
                rememberedPolicy(
                    fingerprintHash = "fp-stale",
                    mode = "vpn",
                    status = RememberedNetworkPolicyStatusObserved,
                    updatedAt = clock.now() - RememberedNetworkPolicyRetentionMaxAgeMs - 1L,
                ),
            )

            assertEquals("fp-match", store.findValidatedRememberedNetworkPolicy("fp-match", "vpn")?.fingerprintHash)
            assertNull(store.findValidatedRememberedNetworkPolicy("fp-suppressed", "vpn"))

            store.pruneRememberedNetworkPolicies()

            assertNull(store.getRememberedNetworkPolicy("fp-stale", "vpn"))
            assertEquals(RememberedNetworkPolicyRetentionLimit, rowCount("remembered_network_policies"))
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
            val artifactStore = RoomDiagnosticsArtifactStore(dao)
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

    @Test
    @Suppress("LongMethod")
    fun `history retention store trims old rows across diagnostics tables`() =
        runTest {
            val scanStore = RoomDiagnosticsScanRecordStore(db, dao)
            val artifactStore = RoomDiagnosticsArtifactStore(dao)
            val bypassStore = RoomBypassUsageHistoryStore(dao)
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

            retentionStore.trimOldData(retentionDays = 14)

            assertEquals(1, rowCount("scan_sessions"))
            assertEquals(1, rowCount("probe_results"))
            assertEquals(1, rowCount("network_snapshots"))
            assertEquals(1, rowCount("diagnostic_context_snapshots"))
            assertEquals(1, rowCount("telemetry_samples"))
            assertEquals(1, rowCount("native_session_events"))
            assertEquals(1, rowCount("export_records"))
            assertEquals(1, rowCount("bypass_usage_sessions"))
            assertEquals(1, rowCount("network_dns_path_preferences"))
            assertEquals("scan-new", scanStore.getScanSession("scan-new")?.id)
            assertNull(scanStore.getScanSession("scan-old"))
            assertNotNull(dnsStore.getNetworkDnsPathPreference("dns-new"))
            assertNull(dnsStore.getNetworkDnsPathPreference("dns-old"))
        }

    private fun rowCount(table: String): Int =
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private class MutableDiagnosticsHistoryClock(
        private var now: Long,
    ) : DiagnosticsHistoryClock {
        override fun now(): Long = now
    }
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
