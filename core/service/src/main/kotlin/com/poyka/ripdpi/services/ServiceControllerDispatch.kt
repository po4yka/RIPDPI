package com.poyka.ripdpi.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.boot.BootSessionStateStore
import com.poyka.ripdpi.data.startAction
import com.poyka.ripdpi.data.stopAction
import java.util.Optional

internal class PreparedServiceUserCommands(
    private val dispatch: ServiceControllerDispatch,
    private val profileRecovery: com.poyka.ripdpi.data.ProfileMutationRecoveryAccess,
    private val pauseAuthority: com.poyka.ripdpi.data.PauseIntentAuthority,
    private val serviceIntentArbiter: ServiceIntentArbiter,
    private val runtimeResumeIntentTracker: RuntimeResumeIntentTracker,
) : ServiceUserCommands {
    override suspend fun prepareStart(mode: Mode): com.poyka.ripdpi.data.RuntimeActivationReceipt =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            profileRecovery.readRecovered { pauseAuthority.reserveStart(mode) }
        }

    override suspend fun prepareStop(): com.poyka.ripdpi.data.RuntimeStopReceipt =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            profileRecovery.readRecovered { pauseAuthority.reserveStop() }
        }

    override suspend fun prepareStopIfCurrent(
        expected: com.poyka.ripdpi.data.RuntimeAuthoritySnapshot,
    ): com.poyka.ripdpi.data.RuntimeStopReceipt? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            profileRecovery.readRecovered { pauseAuthority.reserveStopIfCurrent(expected) }
        }

    override suspend fun start(mode: Mode): ServiceStartResult = startPrepared(mode, prepareStart(mode))

    override fun startProfileActivation(
        mode: Mode,
        receipt: com.poyka.ripdpi.data.ProfileActivationReceipt,
    ): ServiceStartResult {
        val bound =
            pauseAuthority.bindProfileActivation(receipt, mode)
                ?: return ServiceStartResult.Rejected(mode, ServiceStartRejectionReason.Superseded)
        return startPrepared(mode, bound)
    }

    override fun startPrepared(
        mode: Mode,
        receipt: com.poyka.ripdpi.data.RuntimeActivationReceipt,
    ): ServiceStartResult {
        val lease =
            if (receipt.mode == mode) serviceIntentArbiter.dispatchExplicit(receipt) else null
        if (lease == null) return ServiceStartResult.Rejected(mode, ServiceStartRejectionReason.Superseded)
        val result = dispatch.start(mode, startAction, dispatchLease = lease)
        if (result is ServiceStartResult.Accepted) {
            serviceIntentArbiter.runIfExplicitUserIntentCurrent(lease.processGeneration) {
                if (serviceIntentArbiter.isCurrent(lease)) runtimeResumeIntentTracker.recordAcceptedStart()
            }
        }
        return result
    }

    override fun stopPrepared(receipt: com.poyka.ripdpi.data.RuntimeStopReceipt): Boolean {
        val lease = serviceIntentArbiter.dispatchExplicit(receipt) ?: return false
        val snapshot = pauseAuthority.snapshotAuthority()
        return if (snapshot.command?.commandId != receipt.commandId || snapshot.reference != receipt.authority) {
            false
        } else {
            dispatch.stop(stopAction, lease, receipt.authority, snapshot)
            true
        }
    }

    override fun finishOwnedRuntime(receipt: com.poyka.ripdpi.data.RuntimeActivationReceipt): Boolean {
        val stopped = pauseAuthority.finishOwnedRuntime(receipt) ?: return false
        return stopPrepared(stopped)
    }

    override suspend fun stop() {
        stopPrepared(prepareStop())
    }
}

internal class ServiceControllerDispatch(
    private val context: Context,
    private val serviceStateStore: ServiceStateStore,
    private val serviceAutomationController: Optional<ServiceAutomationController>,
    private val foregroundServiceStarter: ForegroundServiceStarter,
    private val bootSessionStateStore: BootSessionStateStore,
    private val runtimeResumeIntentTracker: RuntimeResumeIntentTracker,
    private val serviceIntentArbiter: ServiceIntentArbiter,
    private val pauseAuthority: com.poyka.ripdpi.data.PauseIntentAuthority,
) {
    private fun acceptedDispatch(
        mode: Mode,
        lease: ServiceDispatchLease?,
    ): ServiceStartResult =
        (lease?.durable as? com.poyka.ripdpi.data.RuntimeActivationReceipt)?.let(ServiceStartResult::Accepted)
            ?: ServiceStartResult.MaintenanceAccepted(mode)

    @Suppress("ReturnCount")
    fun start(
        mode: Mode,
        action: String,
        transportFailoverRequestId: Long? = null,
        transportFailoverTarget: TransportFailoverTarget? = null,
        dispatchLease: ServiceDispatchLease? = null,
        expectedDurableReference: com.poyka.ripdpi.data.PauseAuthorityRef? = null,
    ): ServiceStartResult {
        if (expectedDurableReference != null &&
            !serviceIntentArbiter.isDurableCurrent(expectedDurableReference)
        ) {
            return ServiceStartResult.Rejected(mode, ServiceStartRejectionReason.Superseded)
        }
        if (serviceAutomationController.map { it.interceptStart(mode) }.orElse(false)) {
            return acceptedDispatch(mode, dispatchLease)
        }
        return if (mode == Mode.VPN) {
            serviceIntentArbiter.dispatchVpnStart {
                dispatchStartInternal(
                    mode,
                    action,
                    transportFailoverRequestId,
                    transportFailoverTarget,
                    dispatchLease,
                    expectedDurableReference,
                )
            }
        } else {
            dispatchStartInternal(
                mode,
                action,
                transportFailoverRequestId,
                transportFailoverTarget,
                dispatchLease,
                expectedDurableReference,
            )
        }
    }

    @Suppress("ReturnCount")
    private fun dispatchStartInternal(
        mode: Mode,
        action: String,
        transportFailoverRequestId: Long? = null,
        transportFailoverTarget: TransportFailoverTarget? = null,
        dispatchLease: ServiceDispatchLease? = null,
        expectedDurableReference: com.poyka.ripdpi.data.PauseAuthorityRef? = null,
    ): ServiceStartResult {
        if (dispatchLease != null && !serviceIntentArbiter.isCurrent(dispatchLease)) {
            return ServiceStartResult.Rejected(mode, ServiceStartRejectionReason.Superseded)
        }
        if (mode == Mode.VPN && VpnService.prepare(context) != null) {
            Logger.i {
                "Cannot start VPN service: VPN consent not given"
            }
            return ServiceStartResult.Rejected(mode, ServiceStartRejectionReason.VpnConsentMissing)
        }
        when (mode) {
            Mode.VPN -> {
                Logger.i { "Starting VPN" }
                val intent =
                    Intent(context, RipDpiVpnService::class.java).apply {
                        this.action = action
                        stampExplicitIntent(action, dispatchLease)
                        expectedDurableReference?.let { putExtra(durableIntentGenerationExtra, it.generation) }
                        putExtra(vpnStartGenerationExtra, serviceIntentArbiter.captureVpnStartGeneration())
                        transportFailoverRequestId?.let { requestId ->
                            putExtra(transportFailoverRequestIdExtra, requestId)
                        }
                        transportFailoverTarget?.let { target ->
                            putExtra(transportFailoverTargetKindExtra, target.transportKind)
                            putExtra(transportFailoverTargetProfileIdExtra, target.profileId)
                        }
                    }
                try {
                    foregroundServiceStarter.startForegroundService(context, intent)
                } catch (e: IllegalStateException) {
                    // ForegroundServiceStartNotAllowedException extends IllegalStateException on API 31+
                    Logger.w(e) { "Foreground service start blocked" }
                    return ServiceStartResult.Rejected(
                        mode = mode,
                        reason = ServiceStartRejectionReason.ForegroundServiceBlocked(e.message),
                    )
                }
            }

            Mode.Proxy -> {
                Logger.i { "Starting proxy" }
                val intent =
                    Intent(context, RipDpiProxyService::class.java).apply {
                        this.action = action
                        stampExplicitIntent(action, dispatchLease)
                        expectedDurableReference?.let { putExtra(durableIntentGenerationExtra, it.generation) }
                    }
                try {
                    foregroundServiceStarter.startForegroundService(context, intent)
                } catch (e: IllegalStateException) {
                    // ForegroundServiceStartNotAllowedException extends IllegalStateException on API 31+
                    Logger.w(e) { "Foreground service start blocked" }
                    return ServiceStartResult.Rejected(
                        mode = mode,
                        reason = ServiceStartRejectionReason.ForegroundServiceBlocked(e.message),
                    )
                }
            }
        }
        return acceptedDispatch(mode, dispatchLease)
    }

    private fun Intent.stampExplicitIntent(
        action: String,
        lease: ServiceDispatchLease?,
    ) {
        (lease?.durable as? com.poyka.ripdpi.data.RuntimeActivationReceipt)?.let(::stampRuntimeActivation)
        if (lease != null) {
            putExtra(explicitUserIntentGenerationExtra, lease.processGeneration)
            putExtra(durableIntentGenerationExtra, lease.durable.authority.generation)
        } else if (action == startAction || action == transportActivationStartAction || action == stopAction) {
            error("Prepared command lease is required")
        }
    }

    fun stop(
        action: String,
        lease: ServiceDispatchLease?,
        reference: com.poyka.ripdpi.data.PauseAuthorityRef,
        expectedSnapshot: com.poyka.ripdpi.data.RuntimeAuthoritySnapshot,
    ) {
        if (!serviceIntentArbiter.isDurableCurrent(reference) ||
            (lease != null && !serviceIntentArbiter.isCurrent(lease))
        ) {
            return
        }
        val currentMode = serviceStateStore.status.value.second
        if (serviceAutomationController.map { it.interceptStop(currentMode) }.orElse(false)) {
            if (action == stopAction) {
                // Automation consumed the user Stop, so no service callback will
                // record acceptance. Commit the same durable intent here.
                bootSessionStateStore.setWasRunningAtUpdate(false)
                runtimeResumeIntentTracker.recordAcceptedStop()
            }
            return
        }
        val intent =
            when (currentMode) {
                Mode.VPN -> {
                    Logger.i { "Stopping VPN" }
                    Intent(context, RipDpiVpnService::class.java).apply {
                        this.action = action
                        stampRuntimeStop(expectedSnapshot)
                        stampExplicitIntent(action, lease)
                        putExtra(durableIntentGenerationExtra, reference.generation)
                        putExtra(
                            explicitUserIntentGenerationExtra,
                            lease?.processGeneration ?: serviceIntentArbiter.captureExplicitUserIntentGeneration(),
                        )
                    }
                }

                Mode.Proxy -> {
                    Logger.i { "Stopping proxy" }
                    Intent(context, RipDpiProxyService::class.java).apply {
                        this.action = action
                        stampRuntimeStop(expectedSnapshot)
                        stampExplicitIntent(action, lease)
                        putExtra(durableIntentGenerationExtra, reference.generation)
                        putExtra(
                            explicitUserIntentGenerationExtra,
                            lease?.processGeneration ?: serviceIntentArbiter.captureExplicitUserIntentGeneration(),
                        )
                    }
                }
            }
        try {
            foregroundServiceStarter.startForegroundService(context, intent)
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException extends IllegalStateException on API 31+
            Logger.w(e) { "Foreground service start blocked" }
        }
    }
}
