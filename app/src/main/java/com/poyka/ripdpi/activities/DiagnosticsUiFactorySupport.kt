package com.poyka.ripdpi.activities

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

internal class DiagnosticsUiFactorySupport
    @Inject
    constructor(
        @param:ApplicationContext
        val context: Context,
        val json: Json = Json { ignoreUnknownKeys = true },
        val timestampFormatter: SimpleDateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.US),
    )
