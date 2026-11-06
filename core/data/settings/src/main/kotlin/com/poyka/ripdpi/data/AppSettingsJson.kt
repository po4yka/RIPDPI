package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.serialization.RipDpiPrettyContractJson

internal val appSettingsJson =
    RipDpiPrettyContractJson

fun AppSettings.toJson(): String = appSettingsJson.encodeToString(AppSettingsSnapshotSerializer, toSnapshot())

fun appSettingsFromJson(payload: String): AppSettings =
    appSettingsJson
        .decodeFromString(AppSettingsSnapshotSerializer, payload)
        .toAppSettings()
