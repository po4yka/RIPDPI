package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.services.observedQualityRuntime

/** Home and Connection Health use the same observed quality source. */
internal fun resolveConnectionQuality(telemetry: ServiceTelemetrySnapshot) = telemetry.observedQualityRuntime()
