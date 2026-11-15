package com.poyka.ripdpi.data.uri

import com.poyka.ripdpi.data.MieruHandshakeStandard
import com.poyka.ripdpi.data.MieruLowEntropyPattern
import com.poyka.ripdpi.data.MieruNoncePattern
import com.poyka.ripdpi.data.MieruPaddingPattern
import com.poyka.ripdpi.data.MieruTcpFragment
import com.poyka.ripdpi.data.MieruTrafficPattern
import com.poyka.ripdpi.data.validateMieruAdvancedSettings
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** The pinned v3.36.0 TrafficPattern protobuf, bounded before nested allocation. */
internal object MieruTrafficPatternCodec {
    private const val MaximumBytes = 65536
    private const val PatternSeed = 1
    private const val PatternUnlockAll = 2
    private const val PatternFragment = 3
    private const val PatternNonce = 4
    private const val PatternPadding = 5
    private const val PatternLowEntropy = 6
    private const val FragmentEnable = 1
    private const val FragmentMaxSleep = 2
    private const val NonceType = 1
    private const val NonceAllUdpPackets = 2
    private const val NonceMinLength = 3
    private const val NonceMaxLength = 4
    private const val NonceCustomHex = 5
    private const val PaddingMiddleMaximum = 1
    private const val PaddingEndMaximum = 2
    private const val EntropyMode = 1
    private const val EntropyMaskRotation = 2

    fun decodeBase64(value: String): MieruTrafficPattern {
        require(value.length <= MaximumBytes * 2) { "Mieru traffic pattern exceeds size limit" }
        require(value.length % Base64GroupSize == 0) { "Invalid Mieru traffic pattern Base64 padding" }
        return decode(Base64.getDecoder().decode(value))
    }

    fun encodeBase64(value: MieruTrafficPattern): String = Base64.getEncoder().encodeToString(encode(value))

    fun decode(bytes: ByteArray): MieruTrafficPattern {
        val fields =
            fields(
                bytes,
                setOf(PatternSeed, PatternUnlockAll, PatternFragment, PatternNonce, PatternPadding, PatternLowEntropy),
            )
        val pattern =
            MieruTrafficPattern(
                seed = fields.int(PatternSeed),
                unlockAll = fields.bool(PatternUnlockAll),
                tcpFragment =
                    fields.message(PatternFragment)?.let {
                        val fragment = fields(it, setOf(FragmentEnable, FragmentMaxSleep))
                        MieruTcpFragment(fragment.bool(FragmentEnable), fragment.int(FragmentMaxSleep))
                    },
                nonce =
                    fields.message(PatternNonce)?.let {
                        val nonce =
                            fields(
                                it,
                                setOf(NonceType, NonceAllUdpPackets, NonceMinLength, NonceMaxLength, NonceCustomHex),
                                repeated = setOf(NonceCustomHex),
                            )
                        MieruNoncePattern(
                            nonce.int(NonceType),
                            nonce.bool(NonceAllUdpPackets),
                            nonce.int(NonceMinLength),
                            nonce.int(NonceMaxLength),
                            nonce.filter { f -> f.id == NonceCustomHex }.map { f ->
                                requireNotNull(f.bytes).let(::utf8)
                            },
                        )
                    },
                padding =
                    fields.message(PatternPadding)?.let {
                        val padding = fields(it, setOf(PaddingMiddleMaximum, PaddingEndMaximum))
                        MieruPaddingPattern(padding.int(PaddingMiddleMaximum), padding.int(PaddingEndMaximum))
                    },
                lowEntropy =
                    fields.message(PatternLowEntropy)?.let {
                        val entropy = fields(it, setOf(EntropyMode, EntropyMaskRotation))
                        MieruLowEntropyPattern(entropy.int(EntropyMode), entropy.int(EntropyMaskRotation))
                    },
            )
        validateMieruAdvancedSettings(MieruHandshakeStandard, pattern)
        return pattern
    }

    fun encode(pattern: MieruTrafficPattern): ByteArray {
        validateMieruAdvancedSettings(MieruHandshakeStandard, pattern)
        return message {
            int(PatternSeed, pattern.seed)
            bool(PatternUnlockAll, pattern.unlockAll)
            pattern.tcpFragment?.let { fragment ->
                nested(PatternFragment) {
                    bool(FragmentEnable, fragment.enable)
                    int(FragmentMaxSleep, fragment.maxSleepMs)
                }
            }
            pattern.nonce?.let { nonce ->
                nested(PatternNonce) {
                    int(NonceType, nonce.type)
                    bool(NonceAllUdpPackets, nonce.applyToAllUDPPacket)
                    int(NonceMinLength, nonce.minLen)
                    int(NonceMaxLength, nonce.maxLen)
                    nonce.customHexStrings.forEach { bytes(NonceCustomHex, it.toByteArray(Charsets.UTF_8)) }
                }
            }
            pattern.padding?.let { padding ->
                nested(PatternPadding) {
                    int(PaddingMiddleMaximum, padding.maxMiddlePaddingLen)
                    int(PaddingEndMaximum, padding.maxEndPaddingLen)
                }
            }
            pattern.lowEntropy?.let { entropy ->
                nested(PatternLowEntropy) {
                    int(EntropyMode, entropy.mode)
                    int(EntropyMaskRotation, entropy.maskRotation)
                }
            }
        }.also { require(it.size <= MaximumBytes) }
    }

    private fun fields(
        data: ByteArray,
        allowed: Set<Int>,
        repeated: Set<Int> = emptySet(),
    ): List<MieruProtoField> {
        require(data.size <= MaximumBytes) { "Mieru traffic pattern exceeds size limit" }
        val seen = mutableSetOf<Int>()
        return readMieruProtobufFields(data, MaximumBytes).also { fields ->
            require(fields.all { it.id in allowed && (seen.add(it.id) || it.id in repeated) }) {
                "Unknown or repeated Mieru field"
            }
        }
    }

    private fun List<MieruProtoField>.int(id: Int): Int? =
        firstOrNull { it.id == id }?.let {
            val number = requireNotNull(it.number)
            require(number == number.toInt().toLong()) { "Mieru integer overflow" }
            number.toInt()
        }

    private fun List<MieruProtoField>.bool(id: Int): Boolean? =
        int(id)?.let {
            require(it in 0..1)
            it == 1
        }

    private fun List<MieruProtoField>.message(id: Int): ByteArray? =
        firstOrNull {
            it.id == id
        }?.let { requireNotNull(it.bytes) }

    private fun utf8(bytes: ByteArray): String =
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(
                CodingErrorAction.REPORT,
            ).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    private fun message(block: Writer.() -> Unit): ByteArray = Writer().apply(block).output.toByteArray()

    private class Writer {
        val output = ByteArrayOutputStream()

        fun int(
            id: Int,
            value: Int?,
        ) {
            value?.let {
                varint((id shl ProtoTagTypeBits).toLong())
                varint(it.toLong())
            }
        }

        fun bool(
            id: Int,
            value: Boolean?,
        ) {
            value?.let { int(id, if (it) 1 else 0) }
        }

        fun bytes(
            id: Int,
            value: ByteArray,
        ) {
            varint(((id shl ProtoTagTypeBits) or ProtoWireLen).toLong())
            varint(value.size.toLong())
            output.write(value)
        }

        fun nested(
            id: Int,
            block: Writer.() -> Unit,
        ) {
            bytes(id, message(block))
        }

        private fun varint(initial: Long) {
            var value = initial
            do {
                val byte = (value and ProtoVarintPayloadMask.toLong()).toInt()
                value = value ushr ProtoVarintBits
                output.write(if (value == 0L) byte else byte or ProtoVarintContinuation)
            } while (value != 0L)
        }
    }
}
