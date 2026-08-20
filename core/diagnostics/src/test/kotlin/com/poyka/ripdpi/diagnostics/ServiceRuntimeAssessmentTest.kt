package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.RuntimeTelemetryStatus
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceRuntimeAssessmentTest {
    @Test
    fun `assessment qualifies unavailable proxy snapshots with telemetry state`() {
        listOf(RuntimeTelemetryState.NoData, RuntimeTelemetryState.EngineError).forEach { state ->
            val assessment =
                buildServiceRuntimeAssessment(
                    serviceStatus = AppStatus.Running,
                    telemetry =
                        ServiceTelemetrySnapshot(
                            proxyTelemetry = NativeRuntimeSnapshot.idle(source = "proxy"),
                            proxyTelemetryStatus = RuntimeTelemetryStatus(state = state),
                        ),
                )

            assertEquals(state.wireValue, assessment.proxy?.state)
        }
    }
}
