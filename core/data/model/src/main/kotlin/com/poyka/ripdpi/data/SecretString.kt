package com.poyka.ripdpi.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Wrapper for a secret value that suppresses [toString], [hashCode], and
 * [equals] so secrets are never inadvertently written to logs or crash reports.
 * Serialization writes the raw value; callers must handle storage encryption
 * separately (e.g. via Android Keystore).
 */
open class Redacted<T>(
    val value: T,
) {
    override fun toString(): String = "<redacted>"

    override fun hashCode(): Int = 0

    override fun equals(other: Any?): Boolean = other is Redacted<*> && value == other.value
}

/**
 * A [Redacted] string secret with a dedicated [KSerializer] so
 * kotlinx.serialization can round-trip it as a plain JSON string.
 */
@Serializable(with = SecretStringSerializer::class)
class SecretString(
    value: String,
) : Redacted<String>(value) {
    companion object {
        val EMPTY = SecretString("")
    }
}

internal object SecretStringSerializer : KSerializer<SecretString> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("SecretString", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: SecretString,
    ) = encoder.encodeString(value.value)

    override fun deserialize(decoder: Decoder): SecretString = SecretString(decoder.decodeString())
}
