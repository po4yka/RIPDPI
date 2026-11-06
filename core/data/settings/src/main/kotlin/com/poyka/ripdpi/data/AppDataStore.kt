package com.poyka.ripdpi.data

import android.content.Context
import java.io.File

const val AppSettingsStoreFileName = "app_settings.pb"

/** The old protobuf file is read only during import into Room. */
fun resolveAppSettingsStoreFile(context: Context): File =
    File(context.filesDir, "datastore").resolve(AppSettingsStoreFileName)
