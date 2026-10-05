package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.RuntimeTelemetryOutcome

internal suspend fun AmneziaWgRuntimeSupervisor.pollTelemetry(): RuntimeTelemetryOutcome {
    val runtime = runtime ?: return RuntimeTelemetryOutcome.NoData
    return runCatching { runtime.pollTelemetry() }
        .fold(
            onSuccess = { RuntimeTelemetryOutcome.Snapshot(it) },
            onFailure = { error ->
                RuntimeTelemetryOutcome.EngineError(
                    message = error.message ?: "AmneziaWG telemetry polling failed",
                    causeClass = error.javaClass.name,
                )
            },
        )
}
