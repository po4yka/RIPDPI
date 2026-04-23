package com.poyka.ripdpi.data

sealed interface RuntimeTelemetryOutcome {
    data class Snapshot(
        val snapshot: NativeRuntimeSnapshot,
    ) : RuntimeTelemetryOutcome

    data object NoData : RuntimeTelemetryOutcome

    data class EngineError(
        val message: String,
        val causeClass: String? = null,
    ) : RuntimeTelemetryOutcome
}

enum class RuntimeTelemetryState(
    val wireValue: String,
) {
    Snapshot("snapshot"),
    NoData("no_data"),
    EngineError("engine_error"),
}

data class RuntimeTelemetryStatus(
    val state: RuntimeTelemetryState = RuntimeTelemetryState.NoData,
    val message: String? = null,
    val causeClass: String? = null,
) {
    companion object {
        val NoData = RuntimeTelemetryStatus(state = RuntimeTelemetryState.NoData)
    }
}

fun RuntimeTelemetryOutcome.toStatus(): RuntimeTelemetryStatus =
    when (this) {
        is RuntimeTelemetryOutcome.Snapshot -> {
            RuntimeTelemetryStatus(state = RuntimeTelemetryState.Snapshot)
        }

        RuntimeTelemetryOutcome.NoData -> {
            RuntimeTelemetryStatus.NoData
        }

        is RuntimeTelemetryOutcome.EngineError -> {
            RuntimeTelemetryStatus(
                state = RuntimeTelemetryState.EngineError,
                message = message,
                causeClass = causeClass,
            )
        }
    }

fun RuntimeTelemetryStatus.toRuntimeException(): Exception =
    when (causeClass) {
        java.io.IOException::class.java.name -> {
            java.io.IOException(message ?: "Runtime telemetry polling failed")
        }

        IllegalStateException::class.java.name -> {
            IllegalStateException(message ?: "Runtime telemetry polling failed")
        }

        else -> {
            Exception(message ?: "Runtime telemetry polling failed")
        }
    }
