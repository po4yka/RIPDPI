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
    override suspend fun captureRuntimeAuthority(): com.poyka.ripdpi.data.PauseAuthorityRef =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            profileRecovery.readRecovered { pauseAuthority.reference() }
        }

    override suspend fun prepareUserCommand(
        command: com.poyka.ripdpi.data.RuntimeUserCommand,
    ): com.poyka.ripdpi.data.DurableCommandReceipt =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            profileRecovery.readRecovered { pauseAuthority.supersede(command) }
        }

    override suspend fun start(mode: Mode): ServiceStartResult =
        startPrepared(
            mode,
            prepareUserCommand(
                com.poyka.ripdpi.data.RuntimeUserCommand
                    .Start(mode),
            ),
        )

    override fun startPrepared(
        mode: Mode,
        receipt: com.poyka.ripdpi.data.DurableCommandReceipt,
    ): ServiceStartResult {
        val lease =
            serviceIntentArbiter.dispatchExplicit(receipt)
                ?: return ServiceStartResult.Rejected(mode, ServiceStartRejectionReason.Superseded)
        val result = dispatch.start(mode, startAction, dispatchLease = lease)
        if (result is ServiceStartResult.Accepted) {
            serviceIntentArbiter.runIfExplicitUserIntentCurrent(lease.processGeneration) {
                if (serviceIntentArbiter.isCurrent(lease)) runtimeResumeIntentTracker.recordAcceptedStart()
            }
        }
        return result
    }

    override fun stopPrepared(receipt: com.poyka.ripdpi.data.DurableCommandReceipt): Boolean {
        val lease = serviceIntentArbiter.dispatchExplicit(receipt) ?: return false
        dispatch.stop(stopAction, lease, receipt.authority)
        return true
    }

    override fun finishOwnedRuntime(receipt: com.poyka.ripdpi.data.DurableCommandReceipt): Boolean {
        if (!pauseAuthority.finishOwnedRuntime(receipt)) return false
        return stopPrepared(receipt)
    }

    override suspend fun stop() {
        stopPrepared(prepareUserCommand(com.poyka.ripdpi.data.RuntimeUserCommand.Stop))
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
        if (isServiceRecoveryStartAction(action) &&
            !pauseAuthority.allowsRecovery(checkNotNull(expectedDurableReference), mode)
        ) {
            return ServiceStartResult.Rejected(mode, ServiceStartRejectionReason.PausePending)
        }
        if (serviceAutomationController.map { it.interceptStart(mode) }.orElse(false)) {
            return ServiceStartResult.Accepted(mode)
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
        return ServiceStartResult.Accepted(mode)
    }

    private fun Intent.stampExplicitIntent(
        action: String,
        lease: ServiceDispatchLease?,
    ) {
        if (action == startAction || action == transportActivationStartAction || action == stopAction) {
            putExtra(explicitUserIntentGenerationExtra, checkNotNull(lease).processGeneration)
            putExtra(durableIntentGenerationExtra, lease.durable.authority.generation)
        }
    }

    fun stop(
        action: String,
        lease: ServiceDispatchLease?,
        reference: com.poyka.ripdpi.data.PauseAuthorityRef,
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
