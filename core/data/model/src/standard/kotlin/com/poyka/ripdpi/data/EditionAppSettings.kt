package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import kotlinx.serialization.json.JsonObject

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

private const val LegacyTcpSignatureField = 331
private const val PacketAuthorizationField = 433

private fun ByteArray.withoutLegacyEditionFields(): ByteArray =
    withoutSettingsFields(LegacyPacketBackendField, LegacyTcpSignatureField, PacketAuthorizationField)
