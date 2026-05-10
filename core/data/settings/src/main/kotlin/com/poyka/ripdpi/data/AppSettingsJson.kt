package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal val appSettingsJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        prettyPrint = true
    }

fun AppSettings.toJson(): String = appSettingsJson.encodeToString(JsonObject.serializer(), toSnapshot().toJsonObject())

fun appSettingsFromJson(payload: String): AppSettings =
    appSettingsJson
        .parseToJsonElement(payload)
        .jsonObject
        .toAppSettingsSnapshot()
        .toAppSettings()
