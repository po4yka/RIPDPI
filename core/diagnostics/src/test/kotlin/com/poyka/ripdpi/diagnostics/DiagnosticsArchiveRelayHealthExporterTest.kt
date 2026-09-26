package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipFile

internal class DiagnosticsArchiveRelayHealthExporterTest : DiagnosticsArchiveExporterTestBase() {
    @Test
    fun `createArchive exports privacy safe relay health decision provenance`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "session-relay-health-decision",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Relay health decision",
                ).copy(serviceMode = "vpn")
            seedSingleSessionStore(stores, session)
            stores.nativeEventsState.value =
                listOf(relayHealthDecisionEvent())

            val archive =
                createArchiveExporter(stores).createArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = session.id,
                        reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
                        requestedAt = 17L,
                    ),
                )

            ZipFile(archive.absolutePath).use { zip ->
                val trace =
                    zip
                        .getInputStream(zip.getEntry("relay-health-decisions.jsonl"))
                        .bufferedReader()
                        .readText()
                val record = json.parseToJsonElement(trace.trim()).jsonObject
                assertTrue(
                    record
                        .getValue("attemptId")
                        .jsonPrimitive.content
                        .startsWith("attempt-"),
                )
                assertTrue(
                    record
                        .getValue("opaqueProfileId")
                        .jsonPrimitive.content
                        .startsWith("profile-"),
                )
                assertEquals(
                    listOf(
                        "vless_reality",
                        "vless_auth",
                        "application_http",
                        "42",
                        "confirmed_failed",
                        "persistent_network",
                        "completed",
                        "unavailable",
                        "runtime-1",
                        "not_established",
                    ),
                    listOf(
                        record.getValue("transport").jsonPrimitive.content,
                        record.getValue("failureStage").jsonPrimitive.content,
                        record.getValue("targetCategory").jsonPrimitive.content,
                        record.getValue("positiveEvidenceWatermark").jsonPrimitive.content,
                        record.getValue("decision").jsonPrimitive.content,
                        record.getValue("cooldownScope").jsonPrimitive.content,
                        record.getValue("cleanupReceipt").jsonPrimitive.content,
                        record.getValue("connectionCorrelation").jsonPrimitive.content,
                        record.getValue("runtimeCorrelation").jsonPrimitive.content,
                        record.getValue("causalInference").jsonPrimitive.content,
                    ),
                )
                assertTrue(
                    listOf("dad-phone", "203.0.113.9:443", "super-secret-token", "fixture-opaque-profile-token")
                        .none(trace::contains),
                )
            }
        }

    @Test
    fun `relay health identifiers correlate only within one archive`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "session-relay-unlinkable",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Relay health decisions",
                )
            seedSingleSessionStore(stores, session)
            stores.nativeEventsState.value =
                listOf(
                    relayHealthDecisionEvent(),
                    relayHealthDecisionEvent().copy(
                        id = "relay-health-decision-attempt-2",
                        healthAttemptId = "attempt-2",
                        createdAt = 16L,
                    ),
                )
            val exporter = createArchiveExporter(stores)
            val request =
                DiagnosticsArchiveRequest(
                    requestedSessionId = session.id,
                    reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
                    requestedAt = 17L,
                )
            val archives = listOf(exporter.createArchive(request), exporter.createArchive(request))
            val profileAliases =
                archives.map { archive ->
                    ZipFile(archive.absolutePath).use { zip ->
                        val decisions =
                            zip
                                .getInputStream(zip.getEntry("relay-health-decisions.jsonl"))
                                .bufferedReader()
                                .readLines()
                                .map { json.parseToJsonElement(it).jsonObject }
                        val report = zip.getInputStream(zip.getEntry("report.json")).bufferedReader().readText()
                        assertEquals(2, decisions.size)
                        assertEquals(
                            decisions[0].getValue("opaqueProfileId"),
                            decisions[1].getValue("opaqueProfileId"),
                        )
                        assertFalse(decisions[0].getValue("attemptId") == decisions[1].getValue("attemptId"))
                        assertTrue(
                            listOf(
                                "fixture-opaque-profile-token",
                                "attempt-1",
                                "attempt-2",
                                "relay-health-decision-attempt-1",
                            ).none { it in report },
                        )
                        decisions[0].getValue("opaqueProfileId").jsonPrimitive.content
                    }
                }
            assertFalse(profileAliases[0] == profileAliases[1])
        }

    private fun relayHealthDecisionEvent() =
        NativeSessionEventEntity(
            id = "relay-health-decision-attempt-1",
            sessionId = null,
            connectionSessionId = null,
            source = "app",
            level = "warn",
            message = "profile=dad-phone endpoint=203.0.113.9:443 password=super-secret-token",
            createdAt = 15L,
            runtimeId = "runtime-relay-1",
            subsystem = "relay_health_decision",
            healthAttemptId = "attempt-1",
            relayProfileToken = "fixture-opaque-profile-token",
            relayTransport = "vless_reality",
            failureStage = "vless_auth",
            relayTargetCategory = "application_http",
            positiveEvidenceWatermark = 42L,
            relayHealthDecision = "confirmed_failed",
            cooldownScope = "persistent_network",
            cleanupReceipt = "completed",
        )

    @Test
    fun `createArchive marks incomplete relay decision provenance unavailable`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession(
                    id = "session-incomplete-relay-health-decision",
                    profileId = "default",
                    pathMode = ScanPathMode.IN_PATH.name,
                    summary = "Incomplete relay health decision",
                ).copy(serviceMode = "vpn")
            seedSingleSessionStore(stores, session)
            stores.nativeEventsState.value =
                listOf(
                    NativeSessionEventEntity(
                        id = "incomplete-relay-health-decision",
                        source = "app",
                        level = "info",
                        message = "ssid=private endpoint=203.0.113.1 password=secret",
                        createdAt = 15L,
                        subsystem = "relay_health_decision",
                    ),
                )

            val archive =
                createArchiveExporter(stores).createArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = session.id,
                        reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
                        requestedAt = 17L,
                    ),
                )

            ZipFile(archive.absolutePath).use { zip ->
                val trace =
                    zip
                        .getInputStream(zip.getEntry("relay-health-decisions.jsonl"))
                        .bufferedReader()
                        .readText()
                val record = json.parseToJsonElement(trace.trim()).jsonObject
                assertEquals(
                    List(10) { "unavailable" },
                    listOf(
                        record.getValue("attemptId").jsonPrimitive.content,
                        record.getValue("opaqueProfileId").jsonPrimitive.content,
                        record.getValue("transport").jsonPrimitive.content,
                        record.getValue("failureStage").jsonPrimitive.content,
                        record.getValue("targetCategory").jsonPrimitive.content,
                        record.getValue("decision").jsonPrimitive.content,
                        record.getValue("cooldownScope").jsonPrimitive.content,
                        record.getValue("cleanupReceipt").jsonPrimitive.content,
                        record.getValue("connectionCorrelation").jsonPrimitive.content,
                        record.getValue("runtimeCorrelation").jsonPrimitive.content,
                    ),
                )
                assertTrue(listOf("private", "203.0.113.1", "secret").none(trace::contains))
                val completeness =
                    json.decodeFromString(
                        DiagnosticsArchiveCompletenessPayload.serializer(),
                        zip.getInputStream(zip.getEntry("completeness.json")).bufferedReader().readText(),
                    )
                assertEquals(1, completeness.relayAttemptTraces.retainedDecisionCount)
            }
        }
}
