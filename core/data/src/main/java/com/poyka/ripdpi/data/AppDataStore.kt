package com.poyka.ripdpi.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import com.poyka.ripdpi.proto.AppSettings

val Context.settingsStore: DataStore<AppSettings> by dataStore(
    fileName = "app_settings.pb",
    serializer = AppSettingsSerializer,
)
