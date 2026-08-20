package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.ServiceStatus
import kotlinx.coroutines.CoroutineScope

internal typealias TelemetryJobReplacer = (suspend CoroutineScope.() -> Unit) -> Unit

internal class ServiceRuntimeModeHooks<TSession>(
    val serviceLabel: String,
    val startHooks: ServiceRuntimeStartHooks<TSession>,
    val stopHooks: ServiceRuntimeStopHooks<TSession>,
    val handoverHooks: ServiceRuntimeHandoverHooks<TSession>,
    val statusHooks: ServiceRuntimeStatusHooks,
    val permissionHooks: ServiceRuntimePermissionHooks = ServiceRuntimePermissionHooks(),
) where TSession : ServiceRuntimeSession, TSession : HandoverAwareSession

internal class ServiceRuntimeStartHooks<TSession>(
    val createRuntimeSession: () -> TSession,
    val resolveInitialConnectionPolicy: suspend () -> ConnectionPolicyResolution,
    val applyActiveConnectionPolicy: (TSession, ConnectionPolicyResolution, String, Long) -> Unit,
    val startResolvedRuntime: suspend (TSession, ConnectionPolicyResolution) -> RuntimeStartEvidence,
    val publishRuntimeStartEvidence: suspend (TSession, ConnectionPolicyResolution, RuntimeStartEvidence) -> Unit,
    val startModeTelemetryUpdates: (TelemetryJobReplacer) -> Unit,
) where TSession : ServiceRuntimeSession, TSession : HandoverAwareSession

internal class ServiceRuntimeStopHooks<TSession>(
    val stopModeRuntime: suspend (Boolean) -> Unit,
    val captureFinalTelemetry: suspend () -> Unit = {},
    val onAfterStopCleanup: (TSession?) -> Unit = {},
) where TSession : ServiceRuntimeSession, TSession : HandoverAwareSession

internal class ServiceRuntimeHandoverHooks<TSession>(
    val resolveConnectionPolicy: suspend (NetworkFingerprint, String) -> ConnectionPolicyResolution,
    val restartAfterHandover: suspend (TSession, ConnectionPolicyResolution, Long) -> Unit,
    val classifyFailure: (Exception) -> FailureReason,
    val retainFailClosedAfterExhaustion: suspend () -> Boolean = { false },
) where TSession : ServiceRuntimeSession, TSession : HandoverAwareSession

internal class ServiceRuntimeStatusHooks(
    val updateStatus: (ServiceStatus, FailureReason?) -> Unit,
    val classifyStartupFailure: (Exception) -> FailureReason,
)

internal class ServiceRuntimePermissionHooks(
    val onPermissionRevoked: suspend (PermissionChangeEvent) -> Unit = {},
)

internal class ServiceRuntimeSharedState<TSession>(
    val currentSession: () -> TSession?,
    val setRuntimeSession: (TSession?) -> Unit,
    val currentStatus: () -> ServiceStatus,
    val isStopping: () -> Boolean,
    val setStopping: (Boolean) -> Unit,
    val setHandoverRestarting: (Boolean) -> Unit,
) where TSession : ServiceRuntimeSession, TSession : HandoverAwareSession
