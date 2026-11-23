package com.poyka.ripdpi.data

import com.google.protobuf.CodedOutputStream
import com.google.protobuf.WireFormat
import com.poyka.ripdpi.proto.AppSettings
import java.io.ByteArrayOutputStream

internal fun settingsWireFixture(
    includeDeviceScopedField: Boolean,
    includeSignatureField: Boolean = includeDeviceScopedField,
    includeAuthorizedField: Boolean = includeDeviceScopedField,
): ByteArray {
    val bytes = ByteArrayOutputStream()
    val output = CodedOutputStream.newInstance(bytes)
    AppSettings
        .newBuilder()
        .setProxyPort(5432)
        .build()
        .writeTo(output)
    output.writeString(18_001, "unrecognized setting")
    if (includeDeviceScopedField) output.writeBool(135, true)
    // A matching field number inside an unknown group belongs to that group.
    output.writeTag(18_002, WireFormat.WIRETYPE_START_GROUP)
    output.writeBool(135, true)
    output.writeBool(331, true)
    output.writeBool(433, true)
    output.writeTag(18_002, WireFormat.WIRETYPE_END_GROUP)
    if (includeSignatureField) output.writeBool(331, true)
    if (includeAuthorizedField) output.writeBool(433, true)
    output.writeFixed32(18_003, 42)
    output.flush()
    return bytes.toByteArray()
}
