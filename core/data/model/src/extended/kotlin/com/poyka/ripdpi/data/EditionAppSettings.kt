package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

fun AppSettings.externalPacketOperationsEnabled(): Boolean = rootModeAuthorized

fun AppSettings.advancedTcpSignatureRequested(): Boolean = fakeMd5Sig

fun AppSettings.Builder.setAdvancedTcpSignatureRequested(requested: Boolean): AppSettings.Builder =
    setFakeMd5Sig(requested)

fun JsonObject.withEditionBackupSettings(settings: AppSettings): JsonObject =
    JsonObject(this + (TcpSignatureBackupKey to JsonPrimitive(settings.fakeMd5Sig)))

fun AppSettings.Builder.applyEditionBackupSettings(fields: JsonObject): AppSettings.Builder =
    fields[TcpSignatureBackupKey]?.let { setFakeMd5Sig(it.jsonPrimitive.boolean) } ?: this

fun AppSettings.withoutDeviceScopedEditionSettings(): AppSettings =
    decodeStoredAppSettings(toBuilder().clearRootModeAuthorized().build().toByteArray())

fun decodeStoredAppSettings(bytes: ByteArray): AppSettings =
    AppSettings.parseFrom(bytes.withoutSettingsFields(LegacyPacketBackendField))

fun encodeStoredAppSettings(settings: AppSettings): ByteArray =
    settings.toByteArray().withoutSettingsFields(LegacyPacketBackendField)

val sensitiveEditionSettingsPaths: Set<String> = setOf("settings.root_mode_authorized")

internal fun packetOperationUnavailable(operation: String): String = "$operation requires root mode"

private const val TcpSignatureBackupKey = "fakeMd5Sig"
