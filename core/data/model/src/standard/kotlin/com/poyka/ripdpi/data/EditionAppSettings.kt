package com.poyka.ripdpi.data

import com.google.protobuf.CodedInputStream
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.WireFormat
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream

fun AppSettings.externalPacketOperationsEnabled(): Boolean = false

fun AppSettings.advancedTcpSignatureRequested(): Boolean = false

@Suppress("UNUSED_PARAMETER")
fun AppSettings.Builder.setAdvancedTcpSignatureRequested(requested: Boolean): AppSettings.Builder = this

@Suppress("UNUSED_PARAMETER")
fun JsonObject.withEditionBackupSettings(settings: AppSettings): JsonObject = this

@Suppress("UNUSED_PARAMETER")
fun AppSettings.Builder.applyEditionBackupSettings(fields: JsonObject): AppSettings.Builder = this

fun AppSettings.withoutDeviceScopedEditionSettings(): AppSettings = decodeStoredAppSettings(toByteArray())

fun decodeStoredAppSettings(bytes: ByteArray): AppSettings = AppSettings.parseFrom(bytes.withoutLegacyEditionFields())

fun encodeStoredAppSettings(settings: AppSettings): ByteArray = settings.toByteArray().withoutLegacyEditionFields()

val sensitiveEditionSettingsPaths: Set<String> = emptySet()

internal fun packetOperationUnavailable(operation: String): String = "$operation is not supported in this build"

private const val LegacyPacketBackendField = 135
private const val LegacyTcpSignatureField = 331

private fun ByteArray.withoutLegacyEditionFields(): ByteArray {
    val input = CodedInputStream.newInstance(this)
    var retained: ByteArrayOutputStream? = null
    var retainedStart = 0
    while (!input.isAtEnd) {
        val fieldStart = input.totalBytesRead
        val tag = input.readTag()
        if (!input.skipField(tag)) throw InvalidProtocolBufferException("Unexpected end-group in settings message")
        val field = WireFormat.getTagFieldNumber(tag)
        if (field == LegacyPacketBackendField || field == LegacyTcpSignatureField) {
            val output = retained ?: ByteArrayOutputStream(size).also { retained = it }
            output.write(this, retainedStart, fieldStart - retainedStart)
            retainedStart = input.totalBytesRead
        }
    }
    val output = retained ?: return this
    output.write(this, retainedStart, size - retainedStart)
    return output.toByteArray()
}
