package com.poyka.ripdpi.activities

import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.serialization.json.Json

internal class DiagnosticsUiFactorySupport(
    val json: Json = Json { ignoreUnknownKeys = true },
    val timestampFormatter: SimpleDateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.US),
)
