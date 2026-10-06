package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PauseAuthorityRef
import com.poyka.ripdpi.data.ProfileActivationReceipt
import com.poyka.ripdpi.data.RuntimeActivationReceipt
import com.poyka.ripdpi.data.RuntimeAuthoritySnapshot
import com.poyka.ripdpi.data.RuntimeStopReceipt

abstract class TestSynchronousServiceController : ServiceController {
    val testAuthority =
        com.poyka.ripdpi.data
            .testPauseAuthority()
    val intentArbiter = ServiceIntentArbiter(testAuthority)

    abstract fun recordStart(
        mode: Mode,
        receipt: RuntimeActivationReceipt?,
    ): ServiceStartResult

    abstract fun recordStop()

    override suspend fun captureRuntimeSnapshot() = testAuthority.snapshotAuthority()

    override suspend fun prepareStart(mode: Mode) = testAuthority.reserveStart(mode)

    override suspend fun prepareStop() = testAuthority.reserveStop()

    override suspend fun prepareStopIfCurrent(expected: RuntimeAuthoritySnapshot) =
        testAuthority.reserveStopIfCurrent(expected)

    override fun preflight(mode: Mode): ServiceStartPreflightResult = ServiceStartPreflightResult.Allowed

    override suspend fun authorizeBootPolicyStart(
        mode: Mode,
        expected: RuntimeAuthoritySnapshot,
    ): RuntimeActivationReceipt? =
        if (preflight(mode) is ServiceStartPreflightResult.Rejected) {
            null
        } else {
            intentArbiter.recovery { testAuthority.authorizeBootPolicyStart(mode, expected) }
        }

    override fun startBootPolicy(
        mode: Mode,
        receipt: RuntimeActivationReceipt,
    ) = startPrepared(mode, receipt)

    override suspend fun start(mode: Mode) = startPrepared(mode, prepareStart(mode))

    override suspend fun stop() {
        stopPrepared(prepareStop())
    }

    override fun startProfileActivation(
        mode: Mode,
        receipt: ProfileActivationReceipt,
    ): ServiceStartResult {
        val bound =
            testAuthority.bindProfileActivation(receipt, mode)
                ?: return ServiceStartResult.Rejected(mode, ServiceStartRejectionReason.Superseded)
        return startPrepared(mode, bound)
    }

    override fun startPrepared(
        mode: Mode,
        receipt: RuntimeActivationReceipt,
    ): ServiceStartResult {
        val lease = if (receipt.mode == mode) intentArbiter.dispatchExplicit(receipt) else null
        return if (lease == null || !intentArbiter.isCurrent(lease)) {
            ServiceStartResult.Rejected(mode, ServiceStartRejectionReason.Superseded)
        } else {
            startAfterPreflight(mode, receipt)
        }
    }

    private fun startAfterPreflight(
        mode: Mode,
        receipt: RuntimeActivationReceipt?,
    ): ServiceStartResult =
        when (val checked = preflight(mode)) {
            is ServiceStartPreflightResult.Rejected -> ServiceStartResult.Rejected(mode, checked.reason)
            ServiceStartPreflightResult.Allowed -> recordStart(mode, receipt)
        }

    override fun stopPrepared(receipt: RuntimeStopReceipt): Boolean {
        val lease = intentArbiter.dispatchExplicit(receipt) ?: return false
        val snapshot = testAuthority.snapshotAuthority()
        return if (!intentArbiter.isCurrent(lease) || snapshot.command?.commandId != receipt.commandId ||
            snapshot.reference != receipt.authority
        ) {
            false
        } else {
            recordStop()
            true
        }
    }

    override fun finishOwnedRuntime(receipt: RuntimeActivationReceipt): Boolean {
        val stopped = testAuthority.finishOwnedRuntime(receipt) ?: return false
        return stopPrepared(stopped)
    }

    override fun startForBootRecovery(
        mode: Mode,
        broadcastAction: String,
        reference: RuntimeAuthoritySnapshot,
    ) = startRecovery(mode, reference)

    override fun startForProcessDeathRecovery(
        mode: Mode,
        reference: RuntimeAuthoritySnapshot,
    ) = startRecovery(mode, reference)

    override fun restartVpnForTransportFailover(
        requestId: Long,
        expectedTarget: TransportFailoverTarget,
        reference: PauseAuthorityRef,
    ): ServiceStartResult {
        if (!intentArbiter.isDurableCurrent(reference)) {
            return ServiceStartResult.Rejected(Mode.VPN, ServiceStartRejectionReason.Superseded)
        }
        return startAfterPreflight(Mode.VPN, null)
    }

    override fun startForDiagnostics(
        mode: Mode,
        reference: RuntimeAuthoritySnapshot,
    ) = startRecovery(mode, reference)

    override fun stopForDiagnostics(reference: RuntimeAuthoritySnapshot) {
        if (testAuthority.snapshotAuthority() == reference) recordStop()
    }

    override fun stopForDiagnosticsCompensation(reference: RuntimeAuthoritySnapshot) {
        if (testAuthority.snapshotAuthority() == reference) recordStop()
    }

    private fun startRecovery(
        mode: Mode,
        reference: RuntimeAuthoritySnapshot,
    ): ServiceStartResult {
        val receipt =
            testAuthority.authorizeRecovery(mode, reference)
                ?: return ServiceStartResult.Rejected(mode, ServiceStartRejectionReason.Superseded)
        return startPrepared(mode, receipt)
    }
}
