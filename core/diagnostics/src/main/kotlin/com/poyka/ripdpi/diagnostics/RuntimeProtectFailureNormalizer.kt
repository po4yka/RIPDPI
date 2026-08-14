package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.NativeRuntimeEvent

internal fun NativeRuntimeEvent.toPrivacySafePersistedRuntimeEvent(): NativeRuntimeEvent? =
    when (kind) {
        RawProtectEventKind -> normalizeRawProtectFailure()
        CanonicalProtectFailureKind -> normalizeCanonicalProtectFailure()
        else -> this
    }

private fun NativeRuntimeEvent.normalizeRawProtectFailure(): NativeRuntimeEvent? {
    val tokens = message.toKeyValueTokens()
    val backend = tokens["backend"]?.takeIf(ProtectBackends::contains)
    val outcome = tokens["outcome"]?.takeIf(ProtectFailureOutcomes::contains)
    val canonicalMessage =
        backend != null && outcome != null && message == "vpn protect backend=$backend outcome=$outcome"
    val canonicalEnvelope =
        source == RawProtectEventSource && level == "debug" && subsystem == ProtectSubsystem && canonicalMessage
    return if (canonicalEnvelope) canonicalProtectFailure() else null
}

private fun NativeRuntimeEvent.normalizeCanonicalProtectFailure(): NativeRuntimeEvent? {
    val canonical =
        source == CanonicalProtectEventSource &&
            level == "warn" &&
            subsystem == ProtectSubsystem &&
            message == CanonicalProtectFailureMessage
    return if (canonical) canonicalProtectFailure() else null
}

private fun NativeRuntimeEvent.canonicalProtectFailure(): NativeRuntimeEvent =
    copy(
        source = CanonicalProtectEventSource,
        level = "warn",
        message = CanonicalProtectFailureMessage,
        kind = CanonicalProtectFailureKind,
        runtimeId = null,
        mode = null,
        policySignature = null,
        fingerprintHash = null,
        subsystem = ProtectSubsystem,
    )

private const val RawProtectEventKind = "vpn_protect"
private const val RawProtectEventSource = "vpn_protect"
private const val CanonicalProtectFailureKind = "protect_failure"
private const val CanonicalProtectEventSource = "service"
private const val CanonicalProtectFailureMessage = "event=protect_failed"
private const val ProtectSubsystem = "protect"
private val ProtectBackends = setOf("jni", "uds")
private val ProtectFailureOutcomes = setOf("rejected", "error")
