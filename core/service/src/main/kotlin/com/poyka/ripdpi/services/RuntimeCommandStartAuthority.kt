package com.poyka.ripdpi.services

import android.content.Intent
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PauseIntentAuthority
import com.poyka.ripdpi.data.RuntimeActivationEnvelope
import com.poyka.ripdpi.data.RuntimeActivationReceipt
import com.poyka.ripdpi.data.RuntimeAppliedIntent
import com.poyka.ripdpi.data.RuntimeCommandOrigin
import com.poyka.ripdpi.serialization.RipDpiContractJson
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Captured once from a validated service command; no current-authority replacement after suspension. */
internal class RuntimeCommandStartAuthority(
    val original: RuntimeAppliedIntent,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RuntimeCommandStartAuthority>
}

internal fun Intent.stampRuntimeActivation(receipt: RuntimeActivationReceipt) {
    putExtra(RuntimeActivationEnvelopeExtra, RipDpiContractJson.encodeToString(receipt.envelope()))
}

internal fun Intent?.capturedRuntimeActivation(
    authority: PauseIntentAuthority,
    mode: Mode,
): RuntimeAppliedIntent? {
    val envelope =
        this
            ?.getStringExtra(RuntimeActivationEnvelopeExtra)
            ?.let { encoded ->
                runCatching { RipDpiContractJson.decodeFromString<RuntimeActivationEnvelope>(encoded) }.getOrNull()
            }?.takeIf { it.mode == mode.preferenceValue }
    return envelope?.let { captured ->
        authority.activationReceiptFromEnvelope(captured)?.let { receipt ->
            capturedAppliedIntent(authority, mode, captured, receipt)
        }
    }
}

private fun capturedAppliedIntent(
    authority: PauseIntentAuthority,
    mode: Mode,
    envelope: RuntimeActivationEnvelope,
    receipt: RuntimeActivationReceipt,
): RuntimeAppliedIntent? =
    when (val origin = receipt.origin) {
        is RuntimeCommandOrigin.PauseResume -> {
            authority
                .snapshot()
                ?.takeIf { it.token == origin.token && it.generation == envelope.generation }
                ?.let { pause -> RuntimeAppliedIntent.Resume(pause, receipt) }
        }

        is RuntimeCommandOrigin.Recovery -> {
            RuntimeAppliedIntent.Recovery(receipt, mode)
        }

        else -> {
            RuntimeAppliedIntent.Activation(receipt)
        }
    }

private const val RuntimeActivationEnvelopeExtra = "runtimeActivationEnvelope"

internal fun Intent.stampRuntimeStop(snapshot: com.poyka.ripdpi.data.RuntimeAuthoritySnapshot) {
    putExtra(RuntimeStopSnapshotExtra, RipDpiContractJson.encodeToString(snapshot))
}

internal fun Intent?.capturedRuntimeStop(): com.poyka.ripdpi.data.RuntimeAuthoritySnapshot? =
    this?.getStringExtra(RuntimeStopSnapshotExtra)?.let {
        runCatching {
            RipDpiContractJson.decodeFromString<com.poyka.ripdpi.data.RuntimeAuthoritySnapshot>(
                it,
            )
        }.getOrNull()
    }

private const val RuntimeStopSnapshotExtra = "runtimeStopSnapshot"

/** Sticky restart requires mandatory recovery and its captured Running record to remain current. */
internal fun createStickyRecoveryCommand(
    context: android.content.Context,
    authority: PauseIntentAuthority,
    arbiter: ServiceIntentArbiter,
    mode: Mode,
): Intent? {
    val expected = authority.snapshotAuthority()
    return authority.authorizeRecovery(mode, expected)?.let { receipt ->
        arbiter.dispatchExplicit(receipt)?.let { lease ->
            val service = if (mode == Mode.VPN) RipDpiVpnService::class.java else RipDpiProxyService::class.java
            Intent(context, service).apply {
                action = processDeathRecoveryStartAction
                stampRuntimeActivation(receipt)
                putExtra(durableIntentGenerationExtra, receipt.authority.generation)
                putExtra(explicitUserIntentGenerationExtra, lease.processGeneration)
            }
        }
    }
}
