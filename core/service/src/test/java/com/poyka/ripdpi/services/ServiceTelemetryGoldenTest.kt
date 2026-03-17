package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.TunnelStats
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class ServiceTelemetryGoldenTest {
    private val json = Json { explicitNulls = true }

    @Test
    fun runningMergedServiceTelemetryMatchesGolden() {
        val store = DefaultServiceStateStore()

        store.setStatus(AppStatus.Running, Mode.Proxy)
        store.updateTelemetry(
            ServiceTelemetrySnapshot(
                mode = Mode.Proxy,
                status = AppStatus.Running,
                tunnelStats = TunnelStats(txPackets = 3, txBytes = 30, rxPackets = 4, rxBytes = 40),
                proxyTelemetry = proxyTelemetry(),
                tunnelTelemetry = tunnelTelemetry(),
                updatedAt = 123L,
            ),
        )

        GoldenContractSupport.assertJsonGolden(
            "running_merged.json",
            telemetryAsJson(store.telemetry.value),
            ::scrubVolatileFields,
        )
    }

    @Test
    fun failureRetentionAndHaltMergeMatchGolden() {
        val store = DefaultServiceStateStore()

        store.setStatus(AppStatus.Running, Mode.Proxy)
        store.updateTelemetry(
            ServiceTelemetrySnapshot(
                mode = Mode.Proxy,
                status = AppStatus.Running,
                tunnelStats = TunnelStats(txPackets = 3, txBytes = 30, rxPackets = 4, rxBytes = 40),
                proxyTelemetry = proxyTelemetry(),
                tunnelTelemetry = tunnelTelemetry(),
                updatedAt = 123L,
            ),
        )
        store.emitFailed(Sender.Proxy, FailureReason.NativeError("proxy error"))
        store.setStatus(AppStatus.Halted, Mode.Proxy)
        store.updateTelemetry(
            ServiceTelemetrySnapshot(
                mode = Mode.Proxy,
                status = AppStatus.Halted,
                tunnelStats = TunnelStats(txPackets = 5, txBytes = 50, rxPackets = 6, rxBytes = 60),
                proxyTelemetry = proxyTelemetry(),
                tunnelTelemetry = tunnelTelemetry(),
                updatedAt = 456L,
            ),
        )

        GoldenContractSupport.assertJsonGolden(
            "halted_failure_retained.json",
            telemetryAsJson(store.telemetry.value),
            ::scrubVolatileFields,
        )
    }

    private fun telemetryAsJson(snapshot: ServiceTelemetrySnapshot): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("mode", snapshot.mode?.name)
                put("status", snapshot.status.name)
                put("serviceStartedAt", snapshot.serviceStartedAt)
                put("restartCount", snapshot.restartCount)
                put("lastFailureSender", snapshot.lastFailureSender?.name)
                put("lastFailureAt", snapshot.lastFailureAt)
                put("updatedAt", snapshot.updatedAt)
                put(
                    "tunnelStats",
                    json.parseToJsonElement(json.encodeToString(TunnelStats.serializer(), snapshot.tunnelStats)),
                )
                put(
                    "proxyTelemetry",
                    json.parseToJsonElement(
                        json.encodeToString(NativeRuntimeSnapshot.serializer(), snapshot.proxyTelemetry),
                    ),
                )
                put(
                    "tunnelTelemetry",
                    json.parseToJsonElement(
                        json.encodeToString(NativeRuntimeSnapshot.serializer(), snapshot.tunnelTelemetry),
                    ),
                )
            },
        )

    private fun scrubVolatileFields(value: JsonElement): JsonElement =
        when (value) {
            is JsonObject -> {
                JsonObject(
                    value.mapValues { (key, element) ->
                        when (key) {
                            "serviceStartedAt",
                            "lastFailureAt",
                            "updatedAt",
                            "capturedAt",
                            "createdAt",
                            -> Json.parseToJsonElement("0")

                            else -> scrubVolatileFields(element)
                        }
                    },
                )
            }

            else -> {
                value
            }
        }

    private fun proxyTelemetry(): NativeRuntimeSnapshot =
        NativeRuntimeSnapshot(
            source = "proxy",
            state = "running",
            health = "degraded",
            totalSessions = 9,
            totalErrors = 1,
            routeChanges = 2,
            lastRouteGroup = 1,
            listenerAddress = "127.0.0.1:<port>",
            lastTarget = "203.0.113.10:443",
            autolearnEnabled = true,
            learnedHostCount = 4,
            penalizedHostCount = 1,
            lastAutolearnHost = "example.org",
            lastAutolearnGroup = 1,
            lastAutolearnAction = "host_promoted",
            nativeEvents =
                listOf(
                    NativeRuntimeEvent(
                        source = "proxy",
                        level = "info",
                        message = "accepted",
                        createdAt = 0L,
                    ),
                ),
            capturedAt = 0L,
        )

    private fun tunnelTelemetry(): NativeRuntimeSnapshot =
        NativeRuntimeSnapshot(
            source = "tunnel",
            state = "running",
            health = "healthy",
            tunnelStats = TunnelStats(txPackets = 3, txBytes = 30, rxPackets = 4, rxBytes = 40),
            nativeEvents =
                listOf(
                    NativeRuntimeEvent(
                        source = "tunnel",
                        level = "warn",
                        message = "slow upstream",
                        createdAt = 0L,
                    ),
                ),
            capturedAt = 0L,
        )
}
