package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.BootPolicyStartReceipt
import com.poyka.ripdpi.data.DurableCommandReceipt
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PauseAuthorityRef
import com.poyka.ripdpi.data.RuntimeAuthoritySnapshot
import com.poyka.ripdpi.data.RuntimeUserCommand

abstract class TestSynchronousServiceController : ServiceController {
    val testAuthority =
        com.poyka.ripdpi.data
            .testPauseAuthority()

    abstract fun recordStart(mode: Mode): ServiceStartResult

    abstract fun recordStop()

    override suspend fun captureRuntimeAuthority() = testAuthority.reference()

    override suspend fun captureRuntimeSnapshot() = testAuthority.snapshotAuthority()

    override suspend fun prepareUserCommand(command: RuntimeUserCommand) = testAuthority.supersede(command)

    override suspend fun authorizeBootPolicyStart(
        mode: Mode,
        expected: RuntimeAuthoritySnapshot,
    ) = testAuthority.authorizeBootPolicyStart(mode, expected)

    override fun startBootPolicy(
        mode: Mode,
        receipt: BootPolicyStartReceipt,
    ) = recordStart(mode)

    override suspend fun start(mode: Mode) = startPrepared(mode, prepareUserCommand(RuntimeUserCommand.Start(mode)))

    override suspend fun stop() {
        stopPrepared(prepareUserCommand(RuntimeUserCommand.Stop))
    }

    override fun startPrepared(
        mode: Mode,
        receipt: DurableCommandReceipt,
    ) = recordStart(mode)

    override fun stopPrepared(receipt: DurableCommandReceipt): Boolean {
        recordStop()
        return true
    }

    override fun finishOwnedRuntime(receipt: DurableCommandReceipt): Boolean {
        recordStop()
        return true
    }

    override fun startForBootRecovery(
        mode: Mode,
        broadcastAction: String,
        reference: PauseAuthorityRef,
    ) = recordStart(mode)

    override fun startForProcessDeathRecovery(
        mode: Mode,
        reference: PauseAuthorityRef,
    ) = recordStart(mode)

    override fun restartVpnForTransportFailover(
        requestId: Long,
        expectedTarget: TransportFailoverTarget,
        reference: PauseAuthorityRef,
    ) = recordStart(Mode.VPN)

    override fun startForDiagnostics(
        mode: Mode,
        reference: PauseAuthorityRef,
    ) = recordStart(mode)

    override fun stopForDiagnostics(reference: PauseAuthorityRef) = recordStop()

    override fun stopForDiagnosticsCompensation(reference: PauseAuthorityRef) = recordStop()
}
