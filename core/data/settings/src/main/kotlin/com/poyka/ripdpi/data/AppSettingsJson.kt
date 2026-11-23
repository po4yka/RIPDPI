package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.serialization.RipDpiPrettyContractJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal val appSettingsJson =
    RipDpiPrettyContractJson

fun AppSettings.toJson(): String {
    val fields = appSettingsJson.encodeToJsonElement(AppSettingsSnapshotSerializer, toSnapshot()).jsonObject
    return appSettingsJson.encodeToString(JsonObject.serializer(), fields.withEditionBackupSettings(this))
}

fun appSettingsFromJson(payload: String): AppSettings {
    val fields = appSettingsJson.parseToJsonElement(payload).jsonObject
    return appSettingsJson
        .decodeFromJsonElement(AppSettingsSnapshotSerializer, fields)
        .toAppSettings(AppSettings.newBuilder().applyEditionBackupSettings(fields))
}
