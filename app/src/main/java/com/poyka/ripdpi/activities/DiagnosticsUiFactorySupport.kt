package com.poyka.ripdpi.activities

import android.content.Context
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Locale

internal class DiagnosticsUiFactorySupport(
    val context: Context,
    val json: Json = Json { ignoreUnknownKeys = true },
    val timestampFormatter: SimpleDateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.US),
)
