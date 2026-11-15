package com.poyka.ripdpi.data

import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

const val MieruHandshakeDefault = "default"
const val MieruHandshakeStandard = "standard"
const val MieruHandshakeNoWait = "no_wait"

/** Optional values retain the upstream protobuf presence used by share links. */
@Serializable
data class MieruTrafficPattern(
    val seed: Int? = null,
    val unlockAll: Boolean? = null,
    val tcpFragment: MieruTcpFragment? = null,
    val nonce: MieruNoncePattern? = null,
    val padding: MieruPaddingPattern? = null,
    val lowEntropy: MieruLowEntropyPattern? = null,
)

@Serializable
data class MieruTcpFragment(
    val enable: Boolean? = null,
    val maxSleepMs: Int? = null,
)

@Serializable
data class MieruNoncePattern(
    val type: Int? = null,
    val applyToAllUDPPacket: Boolean? = null,
    val minLen: Int? = null,
    val maxLen: Int? = null,
    val customHexStrings: List<String> = emptyList(),
)

@Serializable
data class MieruPaddingPattern(
    val maxMiddlePaddingLen: Int? = null,
    val maxEndPaddingLen: Int? = null,
)

@Serializable
data class MieruLowEntropyPattern(
    val mode: Int? = null,
    val maskRotation: Int? = null,
)

// Limits and choices from pinned v3.36.0 trafficpattern/config.go and nonce.go.
private const val FragmentMaxSleepMs = 100
private const val NonceAutomaticTypeCount = 3
private const val NonceRestrictedTypeCount = 2
private const val NonceMaxLength = 12
private const val NonceRestrictedMinimum = 6
private const val NonceMaxPrefixes = 256
private const val HexCharsPerByte = 2
private const val PaddingMaxLength = 255
private const val PaddingMiddleBias = 128
private const val LowEntropyMaxMode = 4
private const val RotationLowMaximum = 15
private const val RotationHighStep = 16
private const val RotationMaximum = 240
private const val RotationChoiceCount = 31

fun validateMieruAdvancedSettings(
    handshakeMode: String?,
    pattern: MieruTrafficPattern?,
) {
    require(handshakeMode in setOf(null, MieruHandshakeDefault, MieruHandshakeStandard, MieruHandshakeNoWait)) {
        "Unsupported Mieru handshake mode"
    }
    pattern ?: return
    require(pattern.tcpFragment?.maxSleepMs.let { it == null || it in 0..FragmentMaxSleepMs }) {
        "Invalid Mieru fragment delay"
    }
    pattern.nonce?.validate()
    pattern.padding?.validate()
    pattern.lowEntropy?.validate()
}

private fun MieruNoncePattern.validate() {
    require(type.let { it == null || it in 0..NonceAutomaticTypeCount }) { "Invalid Mieru nonce type" }
    require(minLen.let { it == null || it in 0..NonceMaxLength }) { "Invalid Mieru nonce minimum" }
    require(maxLen.let { it == null || it in 0..NonceMaxLength }) { "Invalid Mieru nonce maximum" }
    require(minLen == null || maxLen == null || minLen <= maxLen) { "Invalid Mieru nonce range" }
    require(customHexStrings.size <= NonceMaxPrefixes) { "Too many Mieru nonce prefixes" }
    require(
        customHexStrings.all {
            it.length <= NonceMaxLength * HexCharsPerByte &&
                it.length % HexCharsPerByte == 0 && it.all(::isMieruHexDigit)
        },
    ) { "Invalid Mieru nonce prefix" }
}

private fun MieruPaddingPattern.validate() {
    require(maxMiddlePaddingLen.let { it == null || it in 0..PaddingMaxLength }) { "Invalid Mieru middle padding" }
    require(maxEndPaddingLen.let { it == null || it in 0..PaddingMaxLength }) { "Invalid Mieru end padding" }
}

private fun MieruLowEntropyPattern.validate() {
    require(mode.let { it == null || it in 0..LowEntropyMaxMode }) { "Invalid Mieru low entropy mode" }
    require(
        maskRotation.let {
            it == null || it in 0..RotationLowMaximum ||
                (it in RotationHighStep..RotationMaximum && it % RotationHighStep == 0)
        },
    ) { "Invalid Mieru low entropy rotation" }
}

fun nativeMieruHandshakeMode(value: String?): String {
    validateMieruAdvancedSettings(value, null)
    return if (value == MieruHandshakeNoWait) MieruHandshakeNoWait else MieruHandshakeStandard
}

/** Resolve v3.36.0 defaults once at the Kotlin/native boundary without a device identifier. */
fun MieruTrafficPattern.effective(): MieruTrafficPattern {
    validateMieruAdvancedSettings(MieruHandshakeStandard, this)
    val effectiveSeed = seed ?: mieruFixedInt(Int.MAX_VALUE, "RIPDPI 3.36.0")
    val defaults = MieruPatternDefaults(effectiveSeed, unlockAll == true)
    return copy(
        seed = effectiveSeed,
        unlockAll = unlockAll == true,
        tcpFragment = defaults.fragment(tcpFragment),
        nonce = defaults.nonce(nonce),
        padding = defaults.padding(padding),
        lowEntropy = defaults.entropy(lowEntropy),
    )
}

private class MieruPatternDefaults(
    private val seed: Int,
    private val unlocked: Boolean,
) {
    private fun choice(
        count: Int,
        field: String,
    ): Int = mieruFixedInt(count, "$seed:$field")

    fun fragment(value: MieruTcpFragment?): MieruTcpFragment =
        MieruTcpFragment(
            value?.enable ?: (unlocked && choice(2, "tcpFragment.enable") == 1),
            value?.maxSleepMs ?: if (unlocked) choice(FragmentMaxSleepMs, "tcpFragment.maxSleepMs") + 1 else 0,
        )

    fun nonce(value: MieruNoncePattern?): MieruNoncePattern {
        val minimum =
            value?.minLen ?: if (unlocked) {
                choice(NonceMaxLength + 1, "nonce.minLen")
            } else {
                choice(NonceMaxLength - NonceRestrictedMinimum + 1, "nonce.minLen") + NonceRestrictedMinimum
            }
        val maximum = value?.maxLen ?: minimum + choice(NonceMaxLength + 1 - minimum, "nonce.maxLen")
        return MieruNoncePattern(
            value?.type ?: if (unlocked) {
                choice(NonceAutomaticTypeCount, "nonce.type")
            } else {
                choice(NonceRestrictedTypeCount, "nonce.type") + 1
            },
            value?.applyToAllUDPPacket ?: (choice(2, "nonce.applyToAllUDPPacket") == 1),
            // Upstream nonceRewriteLen clamps an implicit minimum to an explicit smaller maximum.
            minimum.coerceAtMost(maximum),
            maximum,
            value?.customHexStrings.orEmpty(),
        )
    }

    fun padding(value: MieruPaddingPattern?): MieruPaddingPattern =
        MieruPaddingPattern(
            value?.maxMiddlePaddingLen
                ?: (choice(PaddingMaxLength + 1, "padding.maxMiddlePaddingLen") - PaddingMiddleBias).coerceAtLeast(0),
            value?.maxEndPaddingLen ?: if (unlocked) {
                choice(PaddingMaxLength + 1, "padding.maxEndPaddingLen")
            } else {
                PaddingMaxLength
            },
        )

    fun entropy(value: MieruLowEntropyPattern?): MieruLowEntropyPattern {
        val rotationIndex = choice(RotationChoiceCount, "lowEntropy.maskRotation")
        return MieruLowEntropyPattern(
            value?.mode ?: if (unlocked) choice(LowEntropyMaxMode + 1, "lowEntropy.mode") else 0,
            value?.maskRotation ?: if (rotationIndex <= RotationLowMaximum) {
                rotationIndex
            } else {
                (rotationIndex - RotationLowMaximum) * RotationHighStep
            },
        )
    }
}

private fun mieruFixedInt(
    count: Int,
    hint: String,
): Int {
    if (count <= 0) return 0
    val bytes = MessageDigest.getInstance("SHA-256").digest(hint.toByteArray(Charsets.UTF_8))
    return (ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).int and Int.MAX_VALUE) % count
}

private fun isMieruHexDigit(char: Char): Boolean = char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'
