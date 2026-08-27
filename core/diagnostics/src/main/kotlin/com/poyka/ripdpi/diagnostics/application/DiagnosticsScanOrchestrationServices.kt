@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.NetworkDiagnosticsBridge
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.diagnostics.DiagnosticsProfileCatalog
import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import com.poyka.ripdpi.diagnostics.contract.engine.ScanReportDisposition as EngineScanReportDisposition

internal data class BridgeSessionHandle(
    val bridge: NetworkDiagnosticsBridge,
    val sessionId: String,
    val registerActiveBridge: Boolean,
)

@Singleton
class ScanAdmissionService
    @Inject
    constructor(
        private val appSettingsRepository: com.poyka.ripdpi.data.AppSettingsRepository,
        private val profileCatalog: DiagnosticsProfileCatalog,
        private val activeScanRegistry: ActiveScanRegistry,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        private companion object {
            const val AutomaticProbeProfileId = "automatic-probing"
        }

        internal suspend fun admitManualStart(
            selectedProfileId: String? = null,
            skipActiveScanCheck: Boolean = false,
            allowSensitiveProfileStart: Boolean = false,
        ): ManualStartAdmission {
            if (!skipActiveScanCheck && activeScanRegistry.hasVisibleActiveScan()) {
                throw DiagnosticsScanStartRejectedException(DiagnosticsScanStartRejectionReason.ScanAlreadyActive)
            }
            val settings = appSettingsRepository.snapshot()
            val profileId =
                selectedProfileId
                    ?.takeIf { it.isNotBlank() }
                    ?: settings.diagnosticsActiveProfileId.ifEmpty { "default" }
            val profile =
                requireNotNull(profileCatalog.getProfile(profileId)) {
                    "Unknown diagnostics profile: $profileId"
                }
            val request = json.decodeProfileSpecWire(profile.requestJson)
            val rejectionReason =
                resolveRejectionReason(request.resolveLegalSafetyPolicy().access, allowSensitiveProfileStart)
            if (rejectionReason != null) {
                throw DiagnosticsScanStartRejectedException(rejectionReason)
            }
            return if (activeScanRegistry.hasHiddenActiveScan) {
                ManualStartAdmission.HiddenAutomaticProbeConflict(settings = settings, profile = profile)
            } else {
                ManualStartAdmission.Admitted(settings = settings, profile = profile)
            }
        }

        private fun resolveRejectionReason(
            access: DiagnosticsJurisdictionProfileAccess,
            allowSensitiveProfileStart: Boolean,
        ): DiagnosticsScanStartRejectionReason? =
            when (access) {
                DiagnosticsJurisdictionProfileAccess.BLOCKED -> {
                    DiagnosticsScanStartRejectionReason.BlockedByLegalSafetyPolicy
                }

                DiagnosticsJurisdictionProfileAccess.MANUAL_ONLY -> {
                    if (!allowSensitiveProfileStart) {
                        DiagnosticsScanStartRejectionReason.SensitiveProfileConsentRequired
                    } else {
                        null
                    }
                }

                DiagnosticsJurisdictionProfileAccess.ALLOWED -> {
                    null
                }
            }

        @Suppress("ReturnCount", "UnusedParameter")
        suspend fun admitAutomaticProbe(
            settings: com.poyka.ripdpi.proto.AppSettings,
        ): com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity? {
            if (activeScanRegistry.hasActiveScan()) {
                return null
            }
            val profile = profileCatalog.getProfile(AutomaticProbeProfileId) ?: return null
            val request = json.decodeProfileSpecWire(profile.requestJson)
            val policy = request.resolveLegalSafetyPolicy()
            return profile.takeIf {
                request.normalizedExecutionPolicy().allowBackground &&
                    policy.access == DiagnosticsJurisdictionProfileAccess.ALLOWED
            }
        }

        suspend fun assertProfileExists(profileId: String) {
            requireNotNull(profileCatalog.getProfile(profileId)) { "Unknown diagnostics profile: $profileId" }
        }
    }

internal sealed interface ManualStartAdmission {
    data class Admitted(
        val settings: com.poyka.ripdpi.proto.AppSettings,
        val profile: com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity,
    ) : ManualStartAdmission

    data class HiddenAutomaticProbeConflict(
        val settings: com.poyka.ripdpi.proto.AppSettings,
        val profile: com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity,
    ) : ManualStartAdmission
}

private data class HiddenScanExecution(
    val sessionId: String,
    val bridge: NetworkDiagnosticsBridge,
    val executionJob: Job? = null,
)

private data class VisibleScanExecution(
    val sessionId: String,
    val bridge: NetworkDiagnosticsBridge,
    val executionJob: Job? = null,
)

private data class CancellableScanExecution(
    val bridge: NetworkDiagnosticsBridge,
    val executionJob: Job?,
    val registerActiveBridge: Boolean,
)

internal class ScanSessionOwnership {
    private val ownerIds = ConcurrentHashMap<String, String>()

    fun remember(
        sessionId: String,
        ownerId: String,
    ) {
        ownerIds[sessionId] = ownerId
    }

    fun remove(sessionId: String) {
        ownerIds.remove(sessionId)
    }

    fun activeSessionIds(ownerId: String): Set<String> =
        ownerIds
            .filterValues { it == ownerId }
            .keys

    fun ownerId(sessionId: String): String? = ownerIds[sessionId]
}

internal class OwnerExecutionRegistry {
    private val jobsByOwner = ConcurrentHashMap<String, MutableSet<Job>>()

    fun register(
        ownerId: String,
        job: Job,
    ) {
        jobsByOwner.computeIfAbsent(ownerId) { ConcurrentHashMap.newKeySet() }.add(job)
    }

    fun unregister(
        ownerId: String,
        job: Job,
    ) {
        jobsByOwner.computeIfPresent(ownerId) { _, jobs ->
            jobs.remove(job)
            jobs.takeIf { it.isNotEmpty() }
        }
    }

    suspend fun cancel(ownerId: String) {
        jobsByOwner.remove(ownerId).orEmpty().toList().forEach { job ->
            job.cancelAndJoin()
        }
    }
}

@Singleton
class ActiveScanRegistry
    internal constructor(
        private val timelineSource: DefaultDiagnosticsTimelineSource,
        private val bridgeMutex: Mutex,
    ) {
        @Inject
        constructor(timelineSource: DefaultDiagnosticsTimelineSource) : this(timelineSource, Mutex())

        private companion object {
            /** How long to wait for the native engine to finalize after cancellation. */
            const val CANCEL_GRACE_PERIOD_MS = 4_000L

            /** Poll interval while waiting for the partial report. */
            const val CANCEL_POLL_INTERVAL_MS = 200L
        }

        private val visibleScanExecutions = LinkedHashMap<String, VisibleScanExecution>()
        private val visibleScanProgress = LinkedHashMap<String, ScanProgress>()
        private val hiddenScanExecutions = LinkedHashMap<String, HiddenScanExecution>()
        private val cancelledSessionIds = ConcurrentHashMap.newKeySet<String>()
        private val cancelledSessionSummaries = ConcurrentHashMap<String, String>()
        private val cancelledSessionReports = ConcurrentHashMap<String, String>()
        internal val cancelledSessionFailures = ConcurrentHashMap<String, Throwable>()
        private val terminalClaims =
            java.util.IdentityHashMap<NetworkDiagnosticsBridge, Pair<String, ScanTerminalClaim>>()
        internal val sessionOwnership = ScanSessionOwnership()
        internal val ownerExecutions = OwnerExecutionRegistry()
        private val scanSessionFingerprints = ConcurrentHashMap<String, NetworkFingerprint>()
        private val scanSessionPreferredDnsPaths = ConcurrentHashMap<String, EncryptedDnsPathCandidate>()
        private val hiddenAutomaticProbeActiveState = MutableStateFlow(false)

        @Volatile
        private var hasRegisteredActiveBridge = false

        val hiddenAutomaticProbeActive: StateFlow<Boolean> = hiddenAutomaticProbeActiveState.asStateFlow()

        internal fun rememberPreparedScan(
            prepared: PreparedDiagnosticsScan,
            ownerId: String? = null,
        ) {
            prepared.networkFingerprint?.let { scanSessionFingerprints[prepared.sessionId] = it }
            prepared.preferredDnsPath?.let { scanSessionPreferredDnsPaths[prepared.sessionId] = it }
            ownerId?.let { sessionOwnership.remember(prepared.sessionId, it) }
        }

        suspend fun removePreparedScan(sessionId: String) {
            val cancellationOwnerStillActive = cancelledSessionIds.contains(sessionId)
            scanSessionFingerprints.remove(sessionId)
            scanSessionPreferredDnsPaths.remove(sessionId)
            cancelledSessionIds.remove(sessionId)
            cancelledSessionSummaries.remove(sessionId)
            cancelledSessionReports.remove(sessionId)
            if (!cancellationOwnerStillActive) {
                cancelledSessionFailures.remove(sessionId)
            }
            sessionOwnership.remove(sessionId)
        }

        fun fingerprint(sessionId: String): NetworkFingerprint? = scanSessionFingerprints[sessionId]

        fun preferredDnsPath(sessionId: String): EncryptedDnsPathCandidate? = scanSessionPreferredDnsPaths[sessionId]

        fun hasVisibleActiveScan(): Boolean =
            timelineSource.activeScanProgress.value != null || hasRegisteredActiveBridge

        val hasHiddenActiveScan: Boolean
            get() = hiddenAutomaticProbeActiveState.value

        internal suspend fun hasRegisteredExecution(sessionId: String): Boolean =
            bridgeMutex.withLock {
                visibleScanExecutions[sessionId]?.let { execution -> execution.executionJob?.isCompleted != true } ==
                    true ||
                    hiddenScanExecutions[sessionId]?.let { execution -> execution.executionJob?.isCompleted != true } ==
                    true
            }

        internal suspend fun cancelActiveScan(): ActiveScanCancellation? {
            val sessionId = bridgeMutex.withLock { visibleScanExecutions.keys.lastOrNull() } ?: return null
            return cancelScan(sessionId)
        }

        internal suspend fun cancelScan(sessionId: String): ActiveScanCancellation? {
            val execution =
                bridgeMutex.withLock {
                    val candidate =
                        visibleScanExecutions[sessionId]?.let { visible ->
                            CancellableScanExecution(
                                bridge = visible.bridge,
                                executionJob = visible.executionJob,
                                registerActiveBridge = true,
                            )
                        } ?: hiddenScanExecutions[sessionId]?.let { hidden ->
                            CancellableScanExecution(
                                bridge = hidden.bridge,
                                executionJob = hidden.executionJob,
                                registerActiveBridge = false,
                            )
                        }
                    if (candidate != null && terminalClaims[candidate.bridge] == null) {
                        terminalClaims[candidate.bridge] = sessionId to ScanTerminalClaim.CANCELLATION
                        rememberCancellation(sessionId, "Diagnostics scan canceled")
                        candidate
                    } else {
                        null
                    }
                } ?: return null
            val bridge = execution.bridge
            var partialReportJson: String? = null
            var failure: Throwable? =
                runCatching {
                    bridge.cancelScan()
                }.exceptionOrNull()

            withContext(NonCancellable) {
                val executionOwnsReport = execution.executionJob != null
                execution.executionJob?.let { job ->
                    val settledAfterNativeCancellation =
                        failure == null &&
                            withTimeoutOrNull(CANCEL_GRACE_PERIOD_MS) {
                                job.join()
                                true
                            } == true
                    if (!settledAfterNativeCancellation) {
                        runCatching { job.cancelAndJoin() }
                            .exceptionOrNull()
                            ?.let { cleanupFailure -> failure = failure.withSuppressed(cleanupFailure) }
                    }
                }
                cancelledSessionFailures.remove(sessionId)?.let { executionFailure ->
                    failure = failure.withSuppressed(executionFailure)
                }
                if (!executionOwnsReport) {
                    runCatching {
                        partialReportJson = awaitCancellationReport(bridge, graceMs = CANCEL_GRACE_PERIOD_MS)
                        partialReportJson?.let { cancelledSessionReports[sessionId] = it }
                    }.exceptionOrNull()
                        ?.let { cleanupFailure -> failure = failure.withSuppressed(cleanupFailure) }
                }
                val needsManualCleanup =
                    bridgeMutex.withLock {
                        if (execution.registerActiveBridge) {
                            visibleScanExecutions[sessionId]?.bridge === bridge
                        } else {
                            hiddenScanExecutions[sessionId]?.bridge === bridge
                        }
                    }
                if (needsManualCleanup) {
                    runCatching { bridge.destroy() }
                        .exceptionOrNull()
                        ?.let { cleanupFailure -> failure = failure.withSuppressed(cleanupFailure) }
                    clearBridge(
                        bridge = bridge,
                        sessionId = sessionId,
                        registerActiveBridge = execution.registerActiveBridge,
                    )
                }
            }
            return ActiveScanCancellation(sessionId, partialReportJson, failure)
        }

        /**
         * Retrieve and remove the partial report captured during [cancelActiveScan].
         */
        fun consumeCancelledSessionReport(sessionId: String): String? = cancelledSessionReports.remove(sessionId)

        /**
         * After signaling cancellation, poll the native bridge for up to [graceMs].
         * Native may first return a one-shot CHECKPOINT; keep it as a fallback,
         * but continue polling for the TERMINAL report until the grace period ends.
         */
        private suspend fun awaitCancellationReport(
            bridge: NetworkDiagnosticsBridge,
            graceMs: Long,
        ): String? {
            var latestCheckpointReport: String? = null
            var terminalReport: String? = null
            withTimeoutOrNull<Unit>(graceMs) {
                while (terminalReport == null) {
                    val report = bridge.takeReportJson()
                    if (report == null) {
                        delay(CANCEL_POLL_INTERVAL_MS)
                    } else {
                        when (report.diagnosticsReportDispositionOrNull()) {
                            EngineScanReportDisposition.TERMINAL -> terminalReport = report
                            EngineScanReportDisposition.CHECKPOINT -> latestCheckpointReport = report
                            null -> Unit
                        }
                        if (terminalReport == null) {
                            delay(CANCEL_POLL_INTERVAL_MS)
                        }
                    }
                }
            }
            return terminalReport ?: latestCheckpointReport
        }

        internal suspend fun cancelHiddenAutomaticProbe(
            cancellationSummary: String,
            timeoutMs: Long,
            beforeCancel: suspend (sessionId: String) -> Unit = {},
        ): HiddenProbeCancellationResult {
            val hiddenExecution =
                bridgeMutex.withLock {
                    hiddenScanExecutions.values.firstOrNull()
                }
            val cancellationPrepared =
                if (hiddenExecution == null) {
                    false
                } else {
                    try {
                        beforeCancel(hiddenExecution.sessionId)
                        true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                }
            val result =
                if (hiddenExecution == null) {
                    HiddenProbeCancellationResult.NoActiveProbe
                } else if (!cancellationPrepared) {
                    HiddenProbeCancellationResult.Failed(hiddenExecution.sessionId)
                } else {
                    val cancellationRegistered =
                        bridgeMutex.withLock {
                            val currentExecution = hiddenScanExecutions[hiddenExecution.sessionId]
                            val isCurrentAndActive =
                                currentExecution?.bridge === hiddenExecution.bridge &&
                                    currentExecution.executionJob === hiddenExecution.executionJob &&
                                    currentExecution.executionJob?.isCompleted != true
                            if (isCurrentAndActive && terminalClaims[hiddenExecution.bridge] == null) {
                                terminalClaims[hiddenExecution.bridge] =
                                    hiddenExecution.sessionId to ScanTerminalClaim.CANCELLATION
                                rememberCancellation(hiddenExecution.sessionId, cancellationSummary)
                                true
                            } else {
                                false
                            }
                        }
                    if (cancellationRegistered) {
                        try {
                            hiddenExecution.bridge.cancelScan()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // The execution job is still cancelled below so registry cleanup can finish.
                        }
                        val cancelled =
                            withTimeoutOrNull(timeoutMs) {
                                hiddenExecution.executionJob?.cancelAndJoin()
                                true
                            } == true
                        if (cancelled) {
                            HiddenProbeCancellationResult.Cancelled(hiddenExecution.sessionId)
                        } else {
                            HiddenProbeCancellationResult.Failed(hiddenExecution.sessionId)
                        }
                    } else {
                        HiddenProbeCancellationResult.NoActiveProbe
                    }
                }
            return result
        }

        suspend fun registerBridge(
            bridge: NetworkDiagnosticsBridge,
            sessionId: String,
            registerActiveBridge: Boolean,
        ) {
            if (registerActiveBridge) {
                bridgeMutex.withLock {
                    visibleScanExecutions[sessionId] =
                        VisibleScanExecution(
                            sessionId = sessionId,
                            bridge = bridge,
                        )
                    hasRegisteredActiveBridge = visibleScanExecutions.isNotEmpty()
                }
            } else {
                bridgeMutex.withLock {
                    hiddenScanExecutions[sessionId] =
                        HiddenScanExecution(
                            sessionId = sessionId,
                            bridge = bridge,
                        )
                    hiddenAutomaticProbeActiveState.value = hiddenScanExecutions.isNotEmpty()
                }
            }
        }

        suspend fun registerExecution(
            sessionId: String,
            job: Job,
            registerActiveBridge: Boolean,
        ): Boolean =
            bridgeMutex.withLock {
                // A cancellation that already claimed this session's bridge is tearing
                // it down; registering the execution job would start a scan on a dying
                // bridge when startup lands inside the cancel window. Claims from other
                // sessions on a shared bridge instance do not block this session.
                fun executionClaimedBySameSession(bridge: NetworkDiagnosticsBridge): Boolean =
                    terminalClaims[bridge]?.takeIf { it.second == ScanTerminalClaim.CANCELLATION }?.first == sessionId

                if (registerActiveBridge) {
                    val existing = visibleScanExecutions[sessionId] ?: return@withLock false
                    if (executionClaimedBySameSession(existing.bridge)) return@withLock false
                    visibleScanExecutions[sessionId] = existing.copy(executionJob = job)
                } else {
                    val existing = hiddenScanExecutions[sessionId] ?: return@withLock false
                    if (executionClaimedBySameSession(existing.bridge)) return@withLock false
                    hiddenScanExecutions[sessionId] = existing.copy(executionJob = job)
                }
                true
            }

        fun cancellationSummaryFor(sessionId: String): String? =
            if (cancelledSessionIds.contains(sessionId)) {
                cancelledSessionSummaries[sessionId] ?: "Diagnostics scan canceled"
            } else {
                null
            }

        internal suspend fun claimCompletion(
            sessionId: String,
            bridge: NetworkDiagnosticsBridge,
            consumedReportJson: String,
        ): Boolean =
            withContext(NonCancellable) {
                bridgeMutex.withLock {
                    val bridgeIsRegistered =
                        visibleScanExecutions[sessionId]?.bridge === bridge ||
                            hiddenScanExecutions[sessionId]?.bridge === bridge
                    when {
                        !bridgeIsRegistered -> {
                            if (cancelledSessionIds.contains(sessionId)) {
                                cancelledSessionReports[sessionId] = consumedReportJson
                            }
                            false
                        }

                        terminalClaims[bridge]?.second == ScanTerminalClaim.CANCELLATION -> {
                            // `takeReportJson()` is a destructive native read. If cancellation
                            // won immediately before this completion poll, transfer the consumed
                            // report to the cancellation owner instead of losing the only partial
                            // report copy.
                            cancelledSessionReports[sessionId] = consumedReportJson
                            false
                        }

                        terminalClaims[bridge] == null -> {
                            terminalClaims[bridge] = sessionId to ScanTerminalClaim.COMPLETION
                            true
                        }

                        else -> {
                            false
                        }
                    }
                }
            }

        internal suspend fun detachBridge(
            bridge: NetworkDiagnosticsBridge,
            sessionId: String,
            registerActiveBridge: Boolean,
        ): BridgeDetachment =
            if (registerActiveBridge) {
                bridgeMutex.withLock {
                    if (visibleScanExecutions[sessionId]?.bridge === bridge) {
                        visibleScanExecutions.remove(sessionId)
                        visibleScanProgress.remove(sessionId)
                        terminalClaims.remove(bridge)
                    }
                    hasRegisteredActiveBridge = visibleScanExecutions.isNotEmpty()
                    val nextProgress = visibleProgressForActiveExecution(visibleScanExecutions, visibleScanProgress)
                    BridgeDetachment(
                        confirmed = visibleScanExecutions[sessionId]?.bridge !== bridge,
                        publication = { timelineSource.updateActiveScanProgress(nextProgress) },
                    )
                }
            } else {
                bridgeMutex.withLock {
                    if (hiddenScanExecutions[sessionId]?.bridge === bridge) {
                        hiddenScanExecutions.remove(sessionId)
                        terminalClaims.remove(bridge)
                    }
                    hiddenAutomaticProbeActiveState.value = hiddenScanExecutions.isNotEmpty()
                    BridgeDetachment(
                        confirmed = hiddenScanExecutions[sessionId]?.bridge !== bridge,
                        publication = {},
                    )
                }
            }

        suspend fun clearBridge(
            bridge: NetworkDiagnosticsBridge,
            sessionId: String,
            registerActiveBridge: Boolean,
        ) {
            val detachment = detachBridge(bridge, sessionId, registerActiveBridge)
            check(detachment.confirmed) { "Diagnostics bridge detachment was not confirmed" }
            detachment.publish()
        }

        suspend fun updateProgress(
            sessionId: String,
            progress: ScanProgress?,
        ) {
            // Publish while still holding bridgeMutex: a StateFlow assignment never
            // blocks, and publishing outside the lock lets an older computation win
            // the publication race and regress the visible progress.
            bridgeMutex.withLock {
                if (progress == null) {
                    visibleScanProgress.remove(sessionId)
                } else {
                    visibleScanProgress[sessionId] = progress
                }
                timelineSource.updateActiveScanProgress(
                    visibleProgressForActiveExecution(visibleScanExecutions, visibleScanProgress),
                )
            }
        }

        private fun rememberCancellation(
            sessionId: String,
            summary: String,
        ) {
            cancelledSessionIds.add(sessionId)
            cancelledSessionSummaries[sessionId] = summary
        }
    }

internal fun ActiveScanRegistry.hasActiveScan(): Boolean = hasVisibleActiveScan() || hasHiddenActiveScan

private fun visibleProgressForActiveExecution(
    executions: Map<String, VisibleScanExecution>,
    progress: Map<String, ScanProgress>,
): ScanProgress? =
    executions.keys
        .toList()
        .asReversed()
        .firstNotNullOfOrNull(progress::get)

private val diagnosticsReportDispositionJson = RipDpiJson

internal fun String.diagnosticsReportDispositionOrNull(): EngineScanReportDisposition? =
    runCatching {
        diagnosticsReportDispositionJson.decodeEngineScanReportDisposition(this)
    }.getOrNull()

internal class BridgeReportPollingState(
    private val isInPathRouteCurrent: () -> Boolean = { false },
) {
    private var routeRemainedCurrent = isInPathRouteCurrent()
    var ownedInPathRouteAtCompletion: Boolean = false
        private set

    fun observeRoute() {
        if (terminalReportJson == null) {
            routeRemainedCurrent = routeRemainedCurrent && isInPathRouteCurrent()
        }
    }

    var latestCheckpointJson: String? = null
        private set
    var terminalReportJson: String? = null
        private set

    fun observe(reportJson: String): EngineScanReportDisposition? =
        reportJson.diagnosticsReportDispositionOrNull().also { disposition ->
            observeRoute()
            when (disposition) {
                EngineScanReportDisposition.CHECKPOINT -> {
                    latestCheckpointJson = reportJson
                }

                EngineScanReportDisposition.TERMINAL -> {
                    // Freeze at report acceptance, before completion or persistence can suspend.
                    ownedInPathRouteAtCompletion = routeRemainedCurrent
                    terminalReportJson = reportJson
                }

                null -> {
                    Unit
                }
            }
        }
}

internal sealed interface TerminalReportAwaitOutcome {
    data class Terminal(
        val reportJson: String,
        val ownedInPathRouteAtCompletion: Boolean,
    ) : TerminalReportAwaitOutcome

    data class TerminalUnavailable(
        val latestCheckpointJson: String?,
    ) : TerminalReportAwaitOutcome
}

private fun Throwable?.withSuppressed(additional: Throwable): Throwable =
    this?.apply {
        if (this !== additional) addSuppressed(additional)
    } ?: additional
