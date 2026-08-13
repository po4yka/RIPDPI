package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.BypassStrategySignature
import com.poyka.ripdpi.serialization.RipDpiEncodeDefaultsJson

private const val EffectiveConfigFingerprintVersion = "effective-config-v1"

internal fun BypassStrategySignature.effectiveConfigFingerprint(): String {
    val privacySafeProjection =
        copy(
            dnsStrategyLabel = null,
            fakeSniValue = null,
            quicFakeHost = null,
        )
    val canonical =
        RipDpiEncodeDefaultsJson.encodeToString(
            BypassStrategySignature.serializer(),
            privacySafeProjection,
        )
    val digest = sha256Hex("$EffectiveConfigFingerprintVersion\u0000$canonical")
    return "$EffectiveConfigFingerprintVersion:$digest"
}
