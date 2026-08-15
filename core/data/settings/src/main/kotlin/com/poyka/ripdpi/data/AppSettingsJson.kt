package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.serialization.RipDpiPrettyContractJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal val appSettingsJson =
    RipDpiPrettyContractJson

fun AppSettings.toJson(): String = appSettingsJson.encodeToString(JsonObject.serializer(), toSnapshot().toJsonObject())

fun appSettingsFromJson(payload: String): AppSettings =
    appSettingsJson
        .parseToJsonElement(payload)
        .jsonObject
        .toAppSettingsSnapshot()
        .toAppSettings()
