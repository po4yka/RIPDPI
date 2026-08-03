@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import android.content.Context
import com.poyka.ripdpi.core.NetworkDiagnosticsBridge
import com.poyka.ripdpi.core.NetworkDiagnosticsBridgeFactory
import com.poyka.ripdpi.core.resolveHostAutolearnStorePath
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.data.ResolverOverrideStore
import com.poyka.ripdpi.data.ServerCapabilityStore
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.deriveStrategyLaneFamilies
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsProfileCatalog
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.NetworkEdgePreferenceStore
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.diagnostics.finalization.DiagnosticsReportPersister
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

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
            const val CANCEL_GRACE_PERIOD_MS = 3_000L

            /** Poll interval while waiting for the partial report. */
            const val CANCEL_POLL_INTERVAL_MS = 200L
        }

        private val visibleScanExecutions = LinkedHashMap<String, VisibleScanExecution>()
        private val visibleScanProgress = LinkedHashMap<String, ScanProgress>()
        private val hiddenScanExecutions = LinkedHashMap<String, HiddenScanExecution>()
        private val cancelledSessionIds = ConcurrentHashMap.newKeySet<String>()
        private val cancelledSessionSummaries = ConcurrentHashMap<String, String>()
        private val cancelledSessionReports = ConcurrentHashMap<String, String>()
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

        fun removePreparedScan(sessionId: String) {
            scanSessionFingerprints.remove(sessionId)
            scanSessionPreferredDnsPaths.remove(sessionId)
            cancelledSessionIds.remove(sessionId)
            cancelledSessionSummaries.remove(sessionId)
            cancelledSessionReports.remove(sessionId)
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
                } ?: return null
            val bridge = execution.bridge
            rememberCancellation(sessionId, "Diagnostics scan canceled")
            var partialReportJson: String? = null
            var failure: Throwable? =
                runCatching {
                    bridge.cancelScan()

                    // Give the native engine a brief grace period to finalize its partial
                    // report after receiving the cancellation signal.  The Rust side sets
                    // is_finished=true and builds a report with whatever candidates completed.
                    partialReportJson = awaitPartialReport(bridge, graceMs = CANCEL_GRACE_PERIOD_MS)
                    partialReportJson?.let { cancelledSessionReports[sessionId] = it }
                }.exceptionOrNull()

            withContext(NonCancellable) {
                execution.executionJob?.let { job ->
                    runCatching { job.cancelAndJoin() }
                        .exceptionOrNull()
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
         * After signaling cancellation, poll the native bridge for up to [graceMs]
         * to retrieve the partial report the engine builds on cancellation.
         */
        private suspend fun awaitPartialReport(
            bridge: NetworkDiagnosticsBridge,
            graceMs: Long,
        ): String? {
            var report: String? = null
            withTimeoutOrNull<Unit>(graceMs) {
                while (report == null) {
                    report = bridge.takeReportJson()
                    if (report == null) {
                        delay(CANCEL_POLL_INTERVAL_MS)
                    }
                }
            }
            return report
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
                            if (isCurrentAndActive) {
                                rememberCancellation(hiddenExecution.sessionId, cancellationSummary)
                            }
                            isCurrentAndActive
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
        ): Boolean {
            if (registerActiveBridge) {
                return bridgeMutex.withLock {
                    val existing = visibleScanExecutions[sessionId] ?: return@withLock false
                    visibleScanExecutions[sessionId] = existing.copy(executionJob = job)
                    true
                }
            }
            return bridgeMutex.withLock {
                val existing = hiddenScanExecutions[sessionId] ?: return@withLock false
                hiddenScanExecutions[sessionId] = existing.copy(executionJob = job)
                true
            }
        }

        fun cancellationSummaryFor(sessionId: String): String? =
            if (cancelledSessionIds.contains(sessionId)) {
                cancelledSessionSummaries[sessionId] ?: "Diagnostics scan canceled"
            } else {
                null
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
            val nextProgress =
                bridgeMutex.withLock {
                    if (progress == null) {
                        visibleScanProgress.remove(sessionId)
                    } else {
                        visibleScanProgress[sessionId] = progress
                    }
                    visibleProgressForActiveExecution(visibleScanExecutions, visibleScanProgress)
                }
            timelineSource.updateActiveScanProgress(nextProgress)
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

private fun Throwable?.withSuppressed(additional: Throwable): Throwable =
    this?.apply {
        if (this !== additional) addSuppressed(additional)
    } ?: additional

@Singleton
class BridgeExecutionService
    @Inject
    constructor(
        private val networkDiagnosticsBridgeFactory: NetworkDiagnosticsBridgeFactory,
        private val activeScanRegistry: ActiveScanRegistry,
        private val retirementQueue: BridgeRetirementQueue,
    ) {
        internal suspend fun createHandle(
            sessionId: String,
            registerActiveBridge: Boolean,
        ): BridgeSessionHandle {
            val bridge = networkDiagnosticsBridgeFactory.create()
            val handle =
                BridgeSessionHandle(
                    bridge = bridge,
                    sessionId = sessionId,
                    registerActiveBridge = registerActiveBridge,
                )
            val registrationFailure =
                runCatching {
                    activeScanRegistry.registerBridge(bridge, sessionId, registerActiveBridge)
                }.exceptionOrNull()
            if (registrationFailure != null) {
                withContext(NonCancellable) {
                    runCatching { retireAfterStartupFailure(handle) }
                        .exceptionOrNull()
                        ?.takeIf { it !== registrationFailure }
                        ?.let(registrationFailure::addSuppressed)
                }
                throw registrationFailure
            }
            return handle
        }

        internal suspend fun start(
            handle: BridgeSessionHandle,
            requestJson: String,
        ) {
            val startFailure =
                runCatching {
                    handle.bridge.startScan(
                        requestJson = requestJson,
                        sessionId = handle.sessionId,
                    )
                }.exceptionOrNull()
            when (startFailure) {
                null -> Unit

                is CancellationException,
                is Error,
                -> throw startFailure

                else -> throw DiagnosticsBridgeStartException(startFailure)
            }
        }

        internal suspend fun retireAfterStartupFailure(handle: BridgeSessionHandle) {
            val reservation =
                checkNotNull(retirementQueue.tryReserve(handle.bridge)) {
                    "Diagnostics bridge retirement queue is closed"
                }
            val detachmentResult =
                runCatching {
                    withContext(NonCancellable) {
                        activeScanRegistry.detachBridge(
                            bridge = handle.bridge,
                            sessionId = handle.sessionId,
                            registerActiveBridge = handle.registerActiveBridge,
                        )
                    }
                }
            detachmentResult.exceptionOrNull()?.let { failure ->
                retirementQueue.abort(reservation)
                throw failure
            }
            val detachment = detachmentResult.getOrThrow()
            if (!detachment.confirmed) {
                retirementQueue.abort(reservation)
                error("Diagnostics bridge detachment was not confirmed")
            }
            retirementQueue.commit(reservation)
            detachment.publish()
        }

        internal suspend fun destroy(handle: BridgeSessionHandle) {
            try {
                handle.bridge.destroy()
            } finally {
                activeScanRegistry.clearBridge(
                    bridge = handle.bridge,
                    sessionId = handle.sessionId,
                    registerActiveBridge = handle.registerActiveBridge,
                )
            }
        }
    }

@Singleton
class PassiveEventPersistenceService
    @Inject
    constructor(
        private val artifactWriteStore: DiagnosticsArtifactWriteStore,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        internal suspend fun persist(
            sessionId: String,
            bridge: NetworkDiagnosticsBridge,
        ) {
            DiagnosticsReportPersister.persistNativeEvents(
                sessionId = sessionId,
                payload = bridge.pollPassiveEventsJson(),
                artifactWriteStore = artifactWriteStore,
                json = json,
            )
        }
    }

@Singleton
class BridgePollingService
    @Inject
    constructor(
        private val passiveEventPersistenceService: PassiveEventPersistenceService,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        private companion object {
            private const val FinishedReportPollAttempts = 30
            private const val FinishedReportPollDelayMs = 250L
            private const val PollTimeoutNativeDeadlineGraceMs = 60_000L
            const val PollScanResultTimeoutMs = 360_000L + PollTimeoutNativeDeadlineGraceMs
            const val PollScanIntervalMs = 400L
        }

        internal suspend fun persistPassiveEvents(handle: BridgeSessionHandle) {
            passiveEventPersistenceService.persist(handle.sessionId, handle.bridge)
        }

        internal suspend fun pollProgress(handle: BridgeSessionHandle): ScanProgress? =
            handle.bridge
                .pollProgressJson()
                ?.let(json::decodeEngineProgressWire)
                ?.toScanProgress()

        internal suspend fun awaitFinishedReportJson(handle: BridgeSessionHandle): String? {
            repeat(FinishedReportPollAttempts) { attempt ->
                handle.bridge.takeReportJson()?.let { return it }
                if (attempt < FinishedReportPollAttempts - 1) {
                    delay(FinishedReportPollDelayMs)
                }
            }
            return null
        }

        internal suspend fun awaitCompletion(
            prepared: PreparedDiagnosticsScan,
            handle: BridgeSessionHandle,
            activeScanRegistry: ActiveScanRegistry,
            onFinishedReportJson: suspend (String) -> Unit,
        ) {
            try {
                withTimeout(PollScanResultTimeoutMs) {
                    while (true) {
                        persistPassiveEvents(handle)
                        handle.bridge.takeReportJson()?.let { reportJson ->
                            onFinishedReportJson(reportJson)
                            return@withTimeout
                        }
                        val progress = pollProgress(handle)
                        if (prepared.exposeProgress) {
                            activeScanRegistry.updateProgress(prepared.sessionId, progress)
                        }
                        if (progress?.isFinished == true) {
                            val reportJson =
                                checkNotNull(awaitFinishedReportJson(handle)) {
                                    "Diagnostics scan completed without a report"
                                }
                            onFinishedReportJson(reportJson)
                            break
                        }
                        delay(PollScanIntervalMs)
                    }
                }
            } catch (error: TimeoutCancellationException) {
                awaitFinishedReportJson(handle)?.let { reportJson ->
                    onFinishedReportJson(reportJson)
                    return
                }
                throw IllegalStateException("Diagnostics scan timed out waiting for native completion", error)
            }
        }
    }
