package com.poyka.ripdpi.activities

import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionDetail
import com.poyka.ripdpi.diagnostics.RememberedPolicyApplicationAudit
import com.poyka.ripdpi.platform.AndroidStringResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.poyka.ripdpi.diagnostics.DiagnosticContextSnapshot as DiagnosticContextEntity
import com.poyka.ripdpi.diagnostics.DiagnosticNetworkSnapshot as NetworkSnapshotEntity

@RunWith(RobolectricTestRunner::class)
class HistoryConnectionDetailUiFactoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val factory =
        HistoryConnectionDetailUiFactory(
            context = ApplicationProvider.getApplicationContext(),
            stringResolver = AndroidStringResolver(ApplicationProvider.getApplicationContext()),
            coreSupport = testDiagnosticsUiCoreSupport(),
        )

    @Test
    fun `connection history conceals legacy network addresses without a details toggle`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val strings = AndroidStringResolver(context)
        val realFactory =
            HistoryConnectionDetailUiFactory(
                context = context,
                stringResolver = strings,
                coreSupport = DiagnosticsUiCoreSupport(DiagnosticsUiFormatter(), strings),
            )
        val original = historySnapshot(connectionSessionId = "connection-1")
        val legacy =
            original.copy(
                snapshot =
                    requireNotNull(original.snapshot).copy(
                        privateDnsMode = "resolver.private.example",
                        dnsServers = listOf("192.0.2.53", "198.51.100.53"),
                        publicIp = "203.0.113.7",
                    ),
            )
        val detail =
            realFactory.toConnectionDetail(
                DiagnosticConnectionDetail(
                    session = historyConnectionSession(),
                    snapshots = listOf(legacy),
                    contexts = emptyList(),
                    telemetry = emptyList(),
                    events = emptyList(),
                ),
            )
        val fields =
            detail.snapshots
                .single()
                .fields
                .associate { it.label to it.value }

        assertEquals("Hidden (2)", fields["DNS"])
        assertEquals("Hidden", fields["Private DNS"])
        assertEquals("Hidden", fields["Public IP"])
        assertEquals("true", fields["Validated"])
        assertTrue(fields.values.none { it.contains("192.0.2.53") || it.contains("resolver.private.example") })
    }

    @Test
    fun `connection history explains values redacted before storage`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val strings = AndroidStringResolver(context)
        val realFactory =
            HistoryConnectionDetailUiFactory(
                context = context,
                stringResolver = strings,
                coreSupport = DiagnosticsUiCoreSupport(DiagnosticsUiFormatter(), strings),
            )
        val original = historySnapshot(connectionSessionId = "connection-1")
        val stored =
            original.copy(
                snapshot =
                    requireNotNull(original.snapshot).copy(
                        dnsServers = listOf("redacted", "redacted"),
                        privateDnsMode = "redacted",
                        publicIp = "redacted",
                    ),
            )
        val detail =
            realFactory.toConnectionDetail(
                DiagnosticConnectionDetail(
                    session = historyConnectionSession(),
                    snapshots = listOf(stored),
                    contexts = emptyList(),
                    telemetry = emptyList(),
                    events = emptyList(),
                ),
            )
        val fields =
            detail.snapshots
                .single()
                .fields
                .associate { it.label to it.value }

        assertEquals("Redacted (not stored) · Hidden (2)", fields["DNS"])
        assertEquals("Redacted (not stored)", fields["Private DNS"])
        assertEquals("Redacted (not stored)", fields["Public IP"])
    }

    @Test
    fun `connection detail maps highlights dedupes contexts and ignores malformed payloads`() {
        val detail =
            factory.toConnectionDetail(
                DiagnosticConnectionDetail(
                    session = historyConnectionSession(finishedAt = null, health = "degraded"),
                    snapshots =
                        listOf(
                            historySnapshot(connectionSessionId = "connection-1"),
                            NetworkSnapshotEntity(
                                id = "broken",
                                sessionId = null,
                                connectionSessionId = "connection-1",
                                snapshotKind = "passive",
                                payloadJson = "{broken",
                                capturedAt = 1L,
                            ),
                        ),
                    contexts =
                        listOf(
                            historyContext(id = "context-1", connectionSessionId = "connection-1"),
                            historyContext(id = "context-2", connectionSessionId = "connection-1"),
                            DiagnosticContextEntity(
                                id = "broken",
                                sessionId = null,
                                connectionSessionId = "connection-1",
                                contextKind = "post_scan",
                                payloadJson = "{broken",
                                capturedAt = 1L,
                            ),
                        ),
                    telemetry = listOf(historyTelemetry(connectionSessionId = "connection-1", createdAt = 999L)),
                    events = listOf(historyEvent(connectionSessionId = "connection-1")),
                ),
            )

        assertEquals("connection-1", detail.session.id)
        assertEquals(DiagnosticsTone.Warning, detail.session.tone)
        assertTrue(detail.highlights.any { it.label == "Failure class" && it.value == "dns_tampering" })
        assertTrue(detail.highlights.any { it.label == "Strategy" && it.value == "hostfake + quic_burst" })
        assertTrue(detail.highlights.any { it.label == "Retries" && it.value == "3" })
        val rtt = detail.highlights.single { it.label == context.getString(R.string.diagnostics_metric_rtt_band) }
        assertEquals(context.getString(R.string.measurement_rtt_band), rtt.explanation)
        val tx = detail.highlights.single { it.label == context.getString(R.string.diagnostics_metric_tx) }
        assertEquals(context.getString(R.string.measurement_byte_total), tx.explanation)
        assertEquals(context.getString(R.string.measurement_session_total), tx.scopeLabel)
        val errors = detail.highlights.single { it.label == context.getString(R.string.diagnostics_metric_errors) }
        assertEquals(context.getString(R.string.measurement_session_total), errors.scopeLabel)

        assertEquals(1, detail.snapshots.size)
        assertEquals(5, detail.contextGroups.size)
        assertTrue(
            detail.contextGroups.any { group ->
                group.fields.any { it.value == context.getString(R.string.diagnostics_local_not_collected) }
            },
        )
        assertEquals("Field telemetry", detail.contextGroups.last().title)
        assertTrue(
            detail.contextGroups
                .last()
                .fields
                .any { it.label == "Network fingerprint" },
        )
        assertEquals(1, detail.events.size)
    }

    @Test
    fun `connection row derives summary and duration metrics`() {
        val row =
            factory.toConnectionRowUiModel(
                historyConnectionSession(
                    state = "Failed",
                    mode = "Proxy",
                    health = "healthy",
                    failureMessage = "Socket closed",
                    startedAt = 0L,
                    finishedAt = 125_000L,
                    updatedAt = 125_000L,
                ),
            )

        assertEquals("Proxy failed", row.title)
        assertEquals("Socket closed", row.summary)
        assertTrue(row.metrics.any { it.label == "Duration" && it.value == "2m 5s" })
        assertEquals(DiagnosticsTone.Negative, row.tone)
    }

    @Test
    fun `connection detail adds remembered policy audit section and row badge`() {
        val row =
            factory.toConnectionRowUiModel(
                historyConnectionSession(
                    rememberedPolicyAudit =
                        RememberedPolicyApplicationAudit(
                            matchedFingerprintHash = "abcdef0123456789fedcba9876543210",
                            source = RememberedNetworkPolicySource.AUTOMATIC_PROBING_BACKGROUND,
                            appliedByExactMatch = true,
                            previousSuccessCount = 4,
                            previousFailureCount = 1,
                            previousConsecutiveFailureCount = 0,
                        ),
                ),
            )
        val detail =
            factory.toConnectionDetail(
                DiagnosticConnectionDetail(
                    session =
                        historyConnectionSession(
                            rememberedPolicyAudit =
                                RememberedPolicyApplicationAudit(
                                    matchedFingerprintHash = "abcdef0123456789fedcba9876543210",
                                    source = RememberedNetworkPolicySource.AUTOMATIC_PROBING_BACKGROUND,
                                    appliedByExactMatch = true,
                                    previousSuccessCount = 4,
                                    previousFailureCount = 1,
                                    previousConsecutiveFailureCount = 0,
                                ),
                        ),
                    snapshots = emptyList(),
                    contexts = emptyList(),
                    telemetry = emptyList(),
                    events = emptyList(),
                ),
            )

        assertEquals("Remembered policy", row.rememberedPolicyBadge)
        assertTrue(detail.contextGroups.any { it.title == "Remembered policy" })
        val rememberedGroup = detail.contextGroups.first { it.title == "Remembered policy" }
        assertTrue(rememberedGroup.fields.any { it.label == "Source" && it.value == "Automatic probing (background)" })
        assertTrue(rememberedGroup.fields.any { it.label == "Match reason" && it.value == "Exact network match" })
        assertTrue(rememberedGroup.fields.any { it.label == "Matched fingerprint" && it.value == "abcdef...3210" })
        assertTrue(rememberedGroup.fields.any { it.label == "Previous successes" && it.value == "4" })
        assertTrue(rememberedGroup.fields.any { it.label == "Previous failures" && it.value == "1" })
    }
}
