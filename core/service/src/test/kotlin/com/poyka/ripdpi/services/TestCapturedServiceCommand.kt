package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PauseIntentAuthority
import com.poyka.ripdpi.data.RuntimeAppliedIntent
import com.poyka.ripdpi.data.RuntimeAuthoritySnapshot

/** A real checked command captured before delivery; delayed delivery never reserves newer authority. */
internal class TestCapturedServiceCommand private constructor(
    private val lease: ServiceDispatchLease,
    private val activation: RuntimeAppliedIntent?,
    private val stop: RuntimeAuthoritySnapshot?,
) {
    fun deliver(
        shell: ServiceShellDelegate,
        action: String,
        startId: Int,
    ): Int =
        shell.onStartCommand(
            action,
            startId,
            explicitUserIntentGeneration = lease.processGeneration,
            durableReference = lease.durable.authority,
            activation = activation,
            stopSnapshot = stop,
        )

    companion object {
        fun start(
            authority: PauseIntentAuthority,
            arbiter: ServiceIntentArbiter,
            mode: Mode,
        ): TestCapturedServiceCommand {
            val receipt = authority.reserveStart(mode)
            val lease = checkNotNull(arbiter.dispatchExplicit(receipt))
            return TestCapturedServiceCommand(lease, RuntimeAppliedIntent.Activation(receipt), null)
        }

        fun stop(
            authority: PauseIntentAuthority,
            arbiter: ServiceIntentArbiter,
        ): TestCapturedServiceCommand {
            val receipt = authority.reserveStop()
            val lease = checkNotNull(arbiter.dispatchExplicit(receipt))
            return TestCapturedServiceCommand(lease, null, authority.snapshotAuthority())
        }
    }
}
