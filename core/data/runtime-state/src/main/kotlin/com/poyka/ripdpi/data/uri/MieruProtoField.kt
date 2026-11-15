package com.poyka.ripdpi.data.uri

internal class MieruProtoField(
    val id: Int,
    val number: Long?,
    val bytes: ByteArray?,
)

internal fun List<MieruProtoField>.text(number: Int): String? =
    firstOrNull { it.id == number }?.bytes?.let {
        Charsets.UTF_8
            .newDecoder()
            .decode(java.nio.ByteBuffer.wrap(it))
            .toString()
    }

/** Minimal protobuf wire reader: varint and length-delimited fields are kept, fixed-width ones skipped. */
internal fun readMieruProtobufFields(
    data: ByteArray,
    maximumBytes: Int = MieruMaximumUriBytes,
): List<MieruProtoField> {
    require(data.size <= maximumBytes) { "Mieru protobuf exceeds size limit" }
    val fields = mutableListOf<MieruProtoField>()
    var pos = 0

    fun varint(): Long {
        var result = 0L
        for (i in 0 until ProtoVarintMaxBytes) {
            require(pos < data.size) { "truncated varint" }
            val b = data[pos++].toInt()
            require(i != ProtoVarintFinalIndex || b and ProtoVarintOverflowMask == 0) { "varint overflow" }
            result = result or ((b and ProtoVarintPayloadMask).toLong() shl (ProtoVarintBits * i))
            if ((b and ProtoVarintContinuation) == 0) return result
        }
        throw IllegalArgumentException("varint too long")
    }
    while (pos < data.size) {
        require(fields.size < MieruMaximumFields) { "Too many Mieru protobuf fields" }
        val tag = varint()
        require(tag > 0 && (tag ushr ProtoTagTypeBits) <= ProtoMaximumFieldNumber) { "invalid field number" }
        val number = (tag ushr ProtoTagTypeBits).toInt()
        when ((tag and ProtoTagTypeMask.toLong()).toInt()) {
            ProtoWireVarint -> {
                fields += MieruProtoField(number, varint(), null)
            }

            ProtoWireLen -> {
                val length = varint()
                require(length in 0..(data.size - pos).toLong()) { "bad length" }
                fields += MieruProtoField(number, null, data.copyOfRange(pos, pos + length.toInt()))
                pos += length.toInt()
            }

            ProtoWireFixed64 -> {
                fields += MieruProtoField(number, null, null)
                pos += Long.SIZE_BYTES
            }

            ProtoWireFixed32 -> {
                fields += MieruProtoField(number, null, null)
                pos += Int.SIZE_BYTES
            }

            else -> {
                throw IllegalArgumentException("unsupported wire type")
            }
        }
        require(pos <= data.size) { "truncated field" }
    }
    return fields
}
