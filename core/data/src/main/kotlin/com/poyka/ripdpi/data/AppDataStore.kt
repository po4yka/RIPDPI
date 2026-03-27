package com.poyka.ripdpi.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import com.poyka.ripdpi.proto.AppSettings
import java.io.File

const val AppSettingsStoreFileName = "app_settings.pb"

fun resolveAppSettingsStoreFile(context: Context): File =
    File(context.filesDir, "datastore").resolve(AppSettingsStoreFileName)

internal val Context.settingsStore: DataStore<AppSettings> by dataStore(
    fileName = AppSettingsStoreFileName,
    serializer = AppSettingsSerializer,
    corruptionHandler =
        ReplaceFileCorruptionHandler {
            AppSettingsSerializer.defaultValue
        },
)
