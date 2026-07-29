package com.poyka.ripdpi.diagnostics.exit

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.BypassUsageHistoryStore
import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactReadStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.diagnostics.RuntimeArtifactPersister
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

interface ProcessExitRuntimeReconciler {
    suspend fun reconcileStartupProcessExits(recordedExitEvents: List<NativeSessionEventEntity>)
}

internal object NoopProcessExitRuntimeReconciler : ProcessExitRuntimeReconciler {
    override suspend fun reconcileStartupProcessExits(recordedExitEvents: List<NativeSessionEventEntity>) = Unit
}

@Singleton
class DefaultProcessExitRuntimeReconciler internal constructor(
    private val bypassUsageHistoryStore: BypassUsageHistoryStore,
    private val artifactReadStore: DiagnosticsArtifactReadStore,
    private val artifactWriteStore: DiagnosticsArtifactWriteStore,
    private val runtimeArtifactPersister: RuntimeArtifactPersister,
    private val clock: ProcessExitRuntimeClock,
) : ProcessExitRuntimeReconciler {
    @Inject
    constructor(
        bypassUsageHistoryStore: BypassUsageHistoryStore,
        artifactReadStore: DiagnosticsArtifactReadStore,
        artifactWriteStore: DiagnosticsArtifactWriteStore,
        runtimeArtifactPersister: RuntimeArtifactPersister,
    ) : this(
        bypassUsageHistoryStore = bypassUsageHistoryStore,
        artifactReadStore = artifactReadStore,
        artifactWriteStore = artifactWriteStore,
        runtimeArtifactPersister = runtimeArtifactPersister,
        clock = SystemProcessExitRuntimeClock,
    )

    override suspend fun reconcileStartupProcessExits(recordedExitEvents: List<NativeSessionEventEntity>) {
        val startupTime = clock.nowMillis()
        val remainingSessions = unfinishedVpnSessions().toMutableList()
        qualifyingExitEvents(recordedExitEvents, startupTime).forEach { exitEvent ->
            val classification = exitEvent.toKeyValueTokens().processExitClassificationOrNull() ?: return@forEach
            val correlationId = exitEvent.processExitCorrelationId()
            val existingCorrelation = artifactReadStore.getNativeSessionEvent(correlationId)
            val session =
                if (existingCorrelation == null) {
                    nearestSessionForExit(remainingSessions, exitEvent)
                } else {
                    existingCorrelation
                        .takeIf { correlation ->
                            correlation.matchesCanonicalProjection(exitEvent, classification)
                        }?.connectionSessionId
                        ?.let { connectionSessionId ->
                            remainingSessions.firstOrNull { candidate ->
                                candidate.id == connectionSessionId && candidate.precedes(exitEvent)
                            }
                        }
                }
            if (session == null) return@forEach
            val correlation = exitEvent.toCorrelationEvent(session.id, classification)

            artifactWriteStore.insertNativeSessionEvent(correlation)
            runtimeArtifactPersister.persistTerminalRootCauseAssessment(
                connectionSessionId = session.id,
                createdAt = exitEvent.createdAt,
            )
            finalizeStaleSession(session, exitEvent.createdAt, classification)
            remainingSessions.removeAll { candidate -> candidate.id == session.id }
        }
    }

    private suspend fun unfinishedVpnSessions(): List<BypassUsageSessionEntity> =
        bypassUsageHistoryStore
            .observeBypassUsageSessions(limit = MaxRuntimeExitSessions)
            .first()
            .asSequence()
            .take(MaxRuntimeExitSessions)
            .filter(BypassUsageSessionEntity::isUnfinishedVpnRuntimeSession)
            .sortedWith(
                compareBy<BypassUsageSessionEntity> { session ->
                    maxOf(session.startedAt, session.updatedAt)
                }.thenBy { session ->
                    session.id
                }.reversed(),
            ).toList()

    private fun qualifyingExitEvents(
        events: List<NativeSessionEventEntity>,
        startupTime: Long,
    ): List<NativeSessionEventEntity> =
        events
            .asSequence()
            .take(MaxRuntimeExitEvents)
            .filter { event -> event.isCanonicalGlobalProcessExit() }
            .filter { event -> event.createdAt <= startupTime }
            .filter { event -> startupTime - event.createdAt <= MaxRuntimeExitCorrelationWindowMillis }
            .filter { event -> event.toKeyValueTokens().processExitClassificationOrNull() != null }
            .sortedWith(
                compareBy<NativeSessionEventEntity> { event -> event.createdAt }
                    .thenBy { event -> event.id }
                    .reversed(),
            ).toList()

    private fun nearestSessionForExit(
        sessions: List<BypassUsageSessionEntity>,
        exitEvent: NativeSessionEventEntity,
    ): BypassUsageSessionEntity? = sessions.firstOrNull { session -> session.precedes(exitEvent) }

    private suspend fun finalizeStaleSession(
        session: BypassUsageSessionEntity,
        exitAt: Long,
        classification: ProcessExitClassification,
    ) {
        val current = bypassUsageHistoryStore.getBypassUsageSession(session.id) ?: session
        if (current.finishedAt != null) return
        bypassUsageHistoryStore.upsertBypassUsageSession(
            current.copy(
                finishedAt = exitAt,
                updatedAt = exitAt,
                connectionState = classification.connectionState,
                health = classification.health ?: current.health,
                endedReason = classification.endedReason,
                failureMessage = classification.failureMessage,
            ),
        )
    }
}

internal fun interface ProcessExitRuntimeClock {
    fun nowMillis(): Long
}

private object SystemProcessExitRuntimeClock : ProcessExitRuntimeClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

private fun BypassUsageSessionEntity.isUnfinishedVpnRuntimeSession(): Boolean =
    finishedAt == null &&
        serviceMode == Mode.VPN.name &&
        connectionState in RuntimeConnectionStates

private fun BypassUsageSessionEntity.precedes(exitEvent: NativeSessionEventEntity): Boolean =
    maxOf(startedAt, updatedAt) <= exitEvent.createdAt

private fun NativeSessionEventEntity.isCanonicalGlobalProcessExit(): Boolean =
    connectionSessionId == null &&
        source == DefaultLastExitInspector.Source &&
        subsystem == DefaultLastExitInspector.Subsystem &&
        message.startsWith("process_exit ")

private fun NativeSessionEventEntity.toCorrelationEvent(
    connectionSessionId: String,
    classification: ProcessExitClassification,
): NativeSessionEventEntity {
    val tokens = message.toKeyValueTokens()
    return NativeSessionEventEntity(
        id = processExitCorrelationId(),
        sessionId = null,
        connectionSessionId = connectionSessionId,
        source = ProcessExitCorrelationSource,
        level = "warn",
        message =
            "event=process_exit_correlation verdict=${classification.wireValue} evidence=last_exit_inspector_v1 " +
                "reason=${tokens["reason"]} subtype=${tokens["subtype"]} importance=${tokens["importance"]}",
        createdAt = createdAt,
        subsystem = DefaultLastExitInspector.Subsystem,
    )
}

private fun NativeSessionEventEntity.matchesCanonicalProjection(
    exitEvent: NativeSessionEventEntity,
    classification: ProcessExitClassification,
): Boolean {
    val correlationSessionId = connectionSessionId ?: return false
    return this == exitEvent.toCorrelationEvent(correlationSessionId, classification)
}

private fun NativeSessionEventEntity.processExitCorrelationId(): String =
    "$ProcessExitCorrelationSource:${canonicalPrivacySafeTuple().sha256Hex()}"

private fun NativeSessionEventEntity.canonicalPrivacySafeTuple(): String =
    listOf<String?>(
        id,
        sessionId,
        connectionSessionId,
        source,
        level,
        message,
        createdAt.toString(),
        runtimeId,
        mode,
        policySignature,
        fingerprintHash,
        subsystem,
    ).joinToString(separator = CanonicalTupleSeparator) { value ->
        value?.let { "value:${it.length}:$it" } ?: "null"
    }

private fun Map<String, String>.processExitClassificationOrNull(): ProcessExitClassification? {
    val reason = this["reason"]
    val subtype = this["subtype"]
    val importance = this["importance"]
    if (reason !in CanonicalProcessExitReasons || importance !in ProcessExitImportanceBands) return null
    return when {
        reason in GenericPressureReasons && subtype == DefaultLastExitInspector.NoSubtype -> {
            ProcessExitClassification.ResourcePressure
        }

        reason == "other" && subtype == DefaultLastExitInspector.AndroidMemoryLimiterSubtype -> {
            ProcessExitClassification.ResourcePressure
        }

        reason in ExpectedLifecycleExitReasons && subtype == DefaultLastExitInspector.NoSubtype -> {
            ProcessExitClassification.ExpectedLifecycle
        }

        subtype == DefaultLastExitInspector.NoSubtype -> {
            ProcessExitClassification.Unexpected
        }

        else -> {
            null
        }
    }
}

private data class ProcessExitClassification(
    val wireValue: String,
    val connectionState: String,
    val health: String?,
    val endedReason: String,
    val failureMessage: String?,
) {
    companion object {
        val ResourcePressure: ProcessExitClassification =
            ProcessExitClassification(
                wireValue = "inconclusive",
                connectionState = "Failed",
                health = "degraded",
                endedReason = "process_exit:inconclusive",
                failureMessage = "Android reported a resource-pressure process exit.",
            )
        val ExpectedLifecycle: ProcessExitClassification =
            ProcessExitClassification(
                wireValue = "inconclusive",
                connectionState = "Stopped",
                health = null,
                endedReason = "process_exit:stopped",
                failureMessage = null,
            )
        val Unexpected: ProcessExitClassification =
            ProcessExitClassification(
                wireValue = "inconclusive",
                connectionState = "Failed",
                health = "degraded",
                endedReason = "process_exit:inconclusive",
                failureMessage = "Android reported a process exit.",
            )
    }
}

internal fun String.toProcessExitCorrelationTokens(): Map<String, String> = toKeyValueTokens()

private fun String.toKeyValueTokens(): Map<String, String> =
    split(' ', ';')
        .asSequence()
        .mapNotNull { token ->
            val separator = token.indexOf('=')
            if (separator <= 0 || separator == token.lastIndex) return@mapNotNull null
            val key = token.substring(0, separator).lowercase(Locale.US)
            val value = token.substring(separator + 1).trim(',', '"').lowercase(Locale.US)
            key to value
        }.toMap()

private fun String.sha256Hex(): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return buildString(bytes.size * HexCharactersPerByte) {
        bytes.forEach { byte ->
            val value = byte.toInt() and UnsignedByteMask
            append(HexDigits[value ushr HalfByteBits])
            append(HexDigits[value and HalfByteMask])
        }
    }
}

internal const val ProcessExitCorrelationSource = "application_exit_correlation"

private const val MaxRuntimeExitSessions = 16
private const val MaxRuntimeExitEvents = 16
private const val MaxRuntimeExitCorrelationWindowMillis = 10 * 60 * 1000L
private const val HexCharactersPerByte = 2
private const val HalfByteBits = 4
private const val HalfByteMask = 0x0f
private const val UnsignedByteMask = 0xff
private const val HexDigits = "0123456789abcdef"
private const val CanonicalTupleSeparator = "|"
private val RuntimeConnectionStates = setOf(AppStatus.Running.name, AppStatus.Reconnecting.name)
private val GenericPressureReasons = setOf("low_memory", "excessive_resource_usage")
private val ExpectedLifecycleExitReasons =
    setOf(
        "exit_self",
        "permission_change",
        "user_requested",
        "user_stopped",
        "package_state_change",
        "package_updated",
    )
private val CanonicalProcessExitReasons =
    setOf(
        "unknown",
        "exit_self",
        "signaled",
        "low_memory",
        "crash",
        "crash_native",
        "anr",
        "initialization_failure",
        "permission_change",
        "excessive_resource_usage",
        "user_requested",
        "user_stopped",
        "dependency_died",
        "other",
        "freezer",
        "package_state_change",
        "package_updated",
    )
private val ProcessExitImportanceBands =
    setOf(
        "unknown",
        "foreground",
        "foreground_service",
        "visible",
        "perceptible",
        "service",
        "background",
        "cached",
    )
