package com.poyka.ripdpi.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import com.poyka.ripdpi.proto.AppSettings

internal val Context.settingsStore: DataStore<AppSettings> by dataStore(
    fileName = "app_settings.pb",
    serializer = AppSettingsSerializer,
    corruptionHandler = ReplaceFileCorruptionHandler {
        AppSettingsSerializer.defaultValue
    },
)
