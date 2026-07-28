package com.poyka.ripdpi.diagnostics.exit

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.BypassUsageHistoryStore
import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.diagnostics.RuntimeArtifactPersister
import kotlinx.coroutines.flow.first
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
    private val artifactWriteStore: DiagnosticsArtifactWriteStore,
    private val runtimeArtifactPersister: RuntimeArtifactPersister,
    private val clock: ProcessExitRuntimeClock,
) : ProcessExitRuntimeReconciler {
    @Inject
    constructor(
        bypassUsageHistoryStore: BypassUsageHistoryStore,
        artifactWriteStore: DiagnosticsArtifactWriteStore,
        runtimeArtifactPersister: RuntimeArtifactPersister,
    ) : this(
        bypassUsageHistoryStore = bypassUsageHistoryStore,
        artifactWriteStore = artifactWriteStore,
        runtimeArtifactPersister = runtimeArtifactPersister,
        clock = SystemProcessExitRuntimeClock,
    )

    override suspend fun reconcileStartupProcessExits(recordedExitEvents: List<NativeSessionEventEntity>) {
        val startupTime = clock.nowMillis()
        val session = latestSingleUnfinishedVpnSession() ?: return
        val exitEvent = newestQualifyingExitEvent(recordedExitEvents, session, startupTime) ?: return
        val correlation = exitEvent.toCorrelationEvent(session.id)

        artifactWriteStore.insertNativeSessionEvent(correlation)
        runtimeArtifactPersister.persistTerminalRootCauseAssessment(
            connectionSessionId = session.id,
            createdAt = exitEvent.createdAt,
        )
        finalizeStaleSession(session, exitEvent.createdAt)
    }

    private suspend fun latestSingleUnfinishedVpnSession(): BypassUsageSessionEntity? =
        bypassUsageHistoryStore
            .observeBypassUsageSessions(limit = MaxRuntimeExitSessions)
            .first()
            .asSequence()
            .take(MaxRuntimeExitSessions)
            .filter(BypassUsageSessionEntity::isUnfinishedVpnRuntimeSession)
            .maxWithOrNull(
                compareBy<BypassUsageSessionEntity> { session ->
                    maxOf(session.startedAt, session.updatedAt)
                }.thenBy { session ->
                    session.id
                },
            )

    private fun newestQualifyingExitEvent(
        events: List<NativeSessionEventEntity>,
        session: BypassUsageSessionEntity,
        startupTime: Long,
    ): NativeSessionEventEntity? {
        val lowerBound = maxOf(session.startedAt, session.updatedAt)
        return events
            .asSequence()
            .take(MaxRuntimeExitEvents)
            .filter { event -> event.isCanonicalGlobalProcessExit() }
            .filter { event -> event.createdAt in lowerBound..startupTime }
            .filter { event -> startupTime - event.createdAt <= MaxRuntimeExitCorrelationWindowMillis }
            .filter { event -> event.toKeyValueTokens().isQualifyingProcessKill() }
            .maxWithOrNull(
                compareBy<NativeSessionEventEntity> { event -> event.createdAt }
                    .thenBy { event -> event.id },
            )
    }

    private suspend fun finalizeStaleSession(
        session: BypassUsageSessionEntity,
        exitAt: Long,
    ) {
        val current = bypassUsageHistoryStore.getBypassUsageSession(session.id) ?: session
        if (current.finishedAt != null) return
        bypassUsageHistoryStore.upsertBypassUsageSession(
            current.copy(
                finishedAt = exitAt,
                updatedAt = exitAt,
                connectionState = "Failed",
                health = "degraded",
                endedReason = "process_exit:oem_process_kill",
                failureMessage = "Android reported a memory-pressure process exit.",
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

private fun NativeSessionEventEntity.isCanonicalGlobalProcessExit(): Boolean =
    connectionSessionId == null &&
        source == DefaultLastExitInspector.Source &&
        subsystem == DefaultLastExitInspector.Subsystem &&
        message.startsWith("process_exit ")

private fun NativeSessionEventEntity.toCorrelationEvent(connectionSessionId: String): NativeSessionEventEntity {
    val tokens = message.toKeyValueTokens()
    return NativeSessionEventEntity(
        id = "$ProcessExitCorrelationSource:$connectionSessionId",
        sessionId = null,
        connectionSessionId = connectionSessionId,
        source = ProcessExitCorrelationSource,
        level = "warn",
        message =
            "event=process_exit_correlation verdict=oem_process_kill evidence=last_exit_inspector_v1 " +
                "reason=${tokens["reason"]} subtype=${tokens["subtype"]} importance=${tokens["importance"]}",
        createdAt = createdAt,
        subsystem = DefaultLastExitInspector.Subsystem,
    )
}

private fun Map<String, String>.isQualifyingProcessKill(): Boolean {
    val reason = this["reason"]
    val subtype = this["subtype"]
    val importance = this["importance"]
    val reasonQualified =
        reason in ProcessKillReasons ||
            (reason == "other" && subtype == DefaultLastExitInspector.AndroidMemoryLimiterSubtype)
    return reasonQualified && importance in ProcessKillImportanceBands
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

internal const val ProcessExitCorrelationSource = "application_exit_correlation"

private const val MaxRuntimeExitSessions = 16
private const val MaxRuntimeExitEvents = 16
private const val MaxRuntimeExitCorrelationWindowMillis = 10 * 60 * 1000L
private val RuntimeConnectionStates = setOf(AppStatus.Running.name, AppStatus.Reconnecting.name)
private val ProcessKillReasons = setOf("low_memory", "excessive_resource_usage")
private val ProcessKillImportanceBands = setOf("foreground_service", "service", "perceptible")
