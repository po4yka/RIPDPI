package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PauseIntentAuthority
import com.poyka.ripdpi.data.RuntimeActivationReceipt
import com.poyka.ripdpi.data.RuntimeAppliedIntent
import com.poyka.ripdpi.data.RuntimeAppliedUseIdentity
import com.poyka.ripdpi.data.RuntimeAppliedUseReceipt

/** Unit precondition for an earlier successful process; no claim of live native readiness. */
internal fun testAppliedRuntimeCommand(
    authority: PauseIntentAuthority,
    mode: Mode,
): RuntimeActivationReceipt {
    val receipt = authority.reserveStart(mode)
    testAcknowledgeRuntimeCommand(authority, receipt)
    return receipt
}

internal fun testAcknowledgeRuntimeCommand(
    authority: PauseIntentAuthority,
    receipt: RuntimeActivationReceipt,
) {
    val identity =
        RuntimeAppliedUseIdentity(
            java.util.UUID
                .randomUUID()
                .toString(),
            1L,
            receipt.mode.preferenceValue,
        )
    check(authority.claimActivation(receipt, identity))
    val original =
        if (receipt.origin is com.poyka.ripdpi.data.RuntimeCommandOrigin.Recovery) {
            RuntimeAppliedIntent.Recovery(receipt, receipt.mode)
        } else {
            RuntimeAppliedIntent.Activation(receipt)
        }
    check(
        authority.acknowledgeApplied(
            original,
            RuntimeAppliedUseReceipt(
                identity,
                emptyList(),
                1_000L,
                checkNotNull(authority.states.value).profileUtility.catalogGeneration,
                false,
                "0".repeat(64),
            ),
        ),
    )
}
