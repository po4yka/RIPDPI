package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveEntry
import com.poyka.ripdpi.diagnostics.DiagnosticsArchiveSelection
import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.deriveProbeRetryCount
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class DiagnosticsArchiveCsvEntryBuilder(
    private val json: Json,
    private val redactor: DiagnosticsArchiveRedactor,
) {
    internal fun buildCsvEntries(selection: DiagnosticsArchiveSelection): List<DiagnosticsArchiveEntry> =
        buildList {
            add(textEntry(name = "probe-results.csv", content = buildProbeResultsCsv(selection.primaryResults)))
            add(
                textEntry(
                    name = "native-events.csv",
                    content = buildNativeEventsCsv(selection.primaryEvents, selection.globalEvents),
                ),
            )
            add(
                textEntry(
                    name = "relay-attempt-traces.jsonl",
                    content = buildRelayAttemptTracesJsonl(selection.primaryEvents, selection.globalEvents),
                ),
            )
            add(
                textEntry(
                    name = "relay-health-decisions.jsonl",
                    content = buildRelayHealthDecisionsJsonl(selection.primaryEvents, selection.globalEvents),
                ),
            )
            add(textEntry(name = "telemetry.csv", content = buildTelemetryCsv(selection)))
            selection.logcatSnapshot?.let { snapshot ->
                add(
                    DiagnosticsArchiveEntry(
                        name = "logcat.txt",
                        bytes = snapshot.content.toByteArray(),
                    ),
                )
            }
            selection.fileLogSnapshot?.let { snapshot ->
                add(
                    DiagnosticsArchiveEntry(
                        name = "app-log.txt",
                        bytes = snapshot.content.toByteArray(),
                    ),
                )
            }
            selection.startupJournalSnapshot?.let { snapshot ->
                add(
                    DiagnosticsArchiveEntry(
                        name = "startup-journal.txt",
                        bytes = snapshot.content.toByteArray(),
                    ),
                )
            }
        }

    internal fun buildProbeResultsCsv(results: List<ProbeResultEntity>): String =
        buildString {
            appendLine("sessionId,probeType,target,outcome,probeRetryCount,createdAt,detailJson")
            results.map(redactor::redact).forEach { result ->
                appendLine(
                    listOf(
                        csvField(result.sessionId),
                        csvField(result.probeType),
                        csvField(result.target),
                        csvField(result.outcome),
                        csvField(result.probeRetryCount().orEmpty()),
                        csvField(result.createdAt),
                        csvField(result.detailJson),
                    ).joinToString(","),
                )
            }
        }

    private fun ProbeResultEntity.probeRetryCount(): String? =
        runCatching {
            json.decodeFromString(ListSerializer(ProbeDetail.serializer()), detailJson)
        }.getOrNull()?.let(::deriveProbeRetryCount)?.toString()

    private fun buildRelayAttemptTracesJsonl(
        primaryEvents: List<com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity>,
        globalEvents: List<com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity>,
    ): String {
        val jsonLines = Json(json) { prettyPrint = false }
        val events = selectRelayAttemptTraceEvents(primaryEvents, globalEvents)
        val attemptAliases = relayAttemptAliases(events)
        val connectionAliases =
            events.map { it.connectionSessionId.orEmpty() }.distinct().withIndex().associate {
                it.value to "connection-${it.index + 1}"
            }
        val runtimeAliases =
            events.map { it.runtimeId.orEmpty() }.distinct().withIndex().associate {
                it.value to "runtime-${it.index + 1}"
            }
        return buildString {
            events.forEach { event ->
                appendLine(
                    jsonLines.encodeToString(
                        DiagnosticsArchiveRelayAttemptTraceRecord(
                            connectionCorrelation = connectionAliases.getValue(event.connectionSessionId.orEmpty()),
                            runtimeCorrelation = runtimeAliases.getValue(event.runtimeId.orEmpty()),
                            attemptRef = requireNotNull(attemptAliases[event.relayAttemptKey()]),
                            sequence = requireNotNull(event.attemptSequence),
                            stage = requireNotNull(event.stage),
                            outcome = requireNotNull(event.outcome),
                            durationMs = event.durationMs?.coerceAtLeast(0),
                            failureStage = event.failureStage?.takeIf(RelayTraceStages::contains),
                            failureClass = event.failureClass?.takeIf(::isSafeRelayTraceToken),
                            ioErrorKind = event.ioErrorKind?.takeIf(::isSafeRelayTraceToken),
                            osErrorCode = event.osErrorCode,
                            peerClosePhase = event.peerClosePhase?.takeIf(::isSafeRelayTraceToken),
                            carrierDisposition = event.carrierDisposition?.takeIf(::isSafeRelayTraceToken),
                        ),
                    ),
                )
            }
        }
    }

    private fun buildRelayHealthDecisionsJsonl(
        primaryEvents: List<com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity>,
        globalEvents: List<com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity>,
    ): String {
        val jsonLines = Json(json) { prettyPrint = false }
        val events =
            (primaryEvents + globalEvents)
                .distinctBy { it.id }
                .filter(::isRelayHealthDecisionEvent)
                .sortedWith(compareBy({ it.createdAt }, { it.healthAttemptId.orEmpty() }))
        val connectionAliases =
            events.mapNotNull { it.connectionSessionId?.takeIf(String::isNotBlank) }.distinct().withIndex().associate {
                it.value to "connection-${it.index + 1}"
            }
        val runtimeAliases =
            events.mapNotNull { it.runtimeId?.takeIf(String::isNotBlank) }.distinct().withIndex().associate {
                it.value to "runtime-${it.index + 1}"
            }
        return buildString {
            events.forEach { event ->
                appendLine(
                    jsonLines.encodeToString(
                        DiagnosticsArchiveRelayHealthDecisionRecord(
                            attemptId = event.healthAttemptId.safeRelayDecisionToken(),
                            opaqueProfileId = event.relayProfileToken.safeRelayDecisionToken(),
                            transport = event.relayTransport.safeRelayDecisionToken(),
                            failureStage = event.failureStage?.takeIf(RelayTraceStages::contains) ?: "unavailable",
                            targetCategory = event.relayTargetCategory.safeRelayDecisionToken(),
                            positiveEvidenceWatermark = event.positiveEvidenceWatermark?.coerceAtLeast(0),
                            decision = event.relayHealthDecision.safeRelayDecisionToken(),
                            cooldownScope = event.cooldownScope.safeRelayDecisionToken(),
                            cleanupReceipt = event.cleanupReceipt.safeRelayDecisionToken(),
                            connectionCorrelation =
                                event.connectionSessionId?.let(connectionAliases::get) ?: "unavailable",
                            runtimeCorrelation = event.runtimeId?.let(runtimeAliases::get) ?: "unavailable",
                        ),
                    ),
                )
            }
        }
    }
}

private val RelayTraceToken = Regex("[a-z0-9_]{1,48}")
private val RelayDecisionToken = Regex("[a-zA-Z0-9_-]{1,128}")

private fun isSafeRelayTraceToken(value: String): Boolean = value.matches(RelayTraceToken)

private fun String?.safeRelayDecisionToken(): String = this?.takeIf(RelayDecisionToken::matches) ?: "unavailable"

private fun isRelayHealthDecisionEvent(event: com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity): Boolean =
    event.subsystem == "relay_health_decision"
