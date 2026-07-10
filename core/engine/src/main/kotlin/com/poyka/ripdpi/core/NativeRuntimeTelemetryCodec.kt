package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NativeRuntimeTelemetrySchemaVersion
import kotlinx.serialization.json.Json

internal fun Json.decodeNativeRuntimeSnapshot(payload: String): NativeRuntimeSnapshot =
    decodeFromString(NativeRuntimeSnapshot.serializer(), payload).also { snapshot ->
        require(snapshot.schemaVersion == NativeRuntimeTelemetrySchemaVersion) {
            "Unsupported native runtime telemetry schema version: ${snapshot.schemaVersion}"
        }
    }
