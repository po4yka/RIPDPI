package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val AppSettingsJsonFormatVersion = 1
private const val DefaultHttpsPort = 443

private val defaultSettings = AppSettingsSerializer.defaultValue

private val appSettingsJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        prettyPrint = true
    }

fun AppSettings.toJson(): String = appSettingsJson.encodeToString(toSnapshot())

fun appSettingsFromJson(payload: String): AppSettings =
    appSettingsJson.decodeFromString<AppSettingsSnapshot>(payload).toAppSettings()
