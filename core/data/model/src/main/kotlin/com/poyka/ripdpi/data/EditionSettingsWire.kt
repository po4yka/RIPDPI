package com.poyka.ripdpi.data

import com.google.protobuf.CodedInputStream
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.WireFormat
import java.io.ByteArrayOutputStream

internal const val LegacyPacketBackendField = 135

internal fun ByteArray.withoutSettingsFields(vararg removedFields: Int): ByteArray {
    val input = CodedInputStream.newInstance(this)
    var retained: ByteArrayOutputStream? = null
    var retainedStart = 0
    while (!input.isAtEnd) {
        val fieldStart = input.totalBytesRead
        val tag = input.readTag()
        if (!input.skipField(tag)) throw InvalidProtocolBufferException("Unexpected end-group in settings message")
        if (WireFormat.getTagFieldNumber(tag) in removedFields) {
            val output = retained ?: ByteArrayOutputStream(size).also { retained = it }
            output.write(this, retainedStart, fieldStart - retainedStart)
            retainedStart = input.totalBytesRead
        }
    }
    val output = retained ?: return this
    output.write(this, retainedStart, size - retainedStart)
    return output.toByteArray()
}
