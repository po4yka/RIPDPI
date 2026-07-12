package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.DefaultServiceStateStore
import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeHistoryMonitorPersistenceTest {
    @Test
    fun `telemetry persistence does not cancel an in flight write`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val serviceStateStore = DefaultServiceStateStore()
            val firstWriteStarted = CompletableDeferred<Unit>()
            val releaseFirstWrite = CompletableDeferred<Unit>()
            stores.beforeInsertNativeSessionEvent = { event ->
                if (event.message == "first") {
                    firstWriteStarted.complete(Unit)
                    releaseFirstWrite.await()
                }
            }
            val monitorScope = monitorScope()
            val monitor = createMonitor(stores, serviceStateStore, monitorScope)

            monitor.start()
            runCurrent()
            serviceStateStore.updateTelemetry(telemetryWithEvent("first", createdAt = 1L))
            runCurrent()
            firstWriteStarted.await()
            serviceStateStore.updateTelemetry(telemetryWithEvent("second", createdAt = 2L))
            runCurrent()
            releaseFirstWrite.complete(Unit)
            runCurrent()

            assertEquals(listOf("first", "second"), stores.nativeEventsState.value.map { it.message })
            monitorScope.cancel()
        }

    @Test
    fun `telemetry persistence continues after a failed write`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val serviceStateStore = DefaultServiceStateStore()
            var failNextWrite = true
            stores.beforeInsertNativeSessionEvent = { event ->
                if (event.message == "fails" && failNextWrite) {
                    failNextWrite = false
                    error("injected persistence failure")
                }
            }
            val monitorScope = monitorScope()
            val monitor = createMonitor(stores, serviceStateStore, monitorScope)

            monitor.start()
            runCurrent()
            serviceStateStore.updateTelemetry(telemetryWithEvent("fails", createdAt = 3L))
            runCurrent()
            serviceStateStore.updateTelemetry(telemetryWithEvent("after-failure", createdAt = 4L))
            runCurrent()

            assertTrue(stores.nativeEventsState.value.any { it.message == "after-failure" })
            monitorScope.cancel()
        }

    private fun kotlinx.coroutines.test.TestScope.monitorScope(): CoroutineScope =
        CoroutineScope(
            SupervisorJob() +
                StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, _ -> },
        )

    private fun createMonitor(
        stores: FakeDiagnosticsHistoryStores,
        serviceStateStore: DefaultServiceStateStore,
        scope: CoroutineScope,
    ): RuntimeHistoryStartup =
        createRuntimeHistoryMonitor(
            appSettingsRepository = FakeAppSettingsRepository(),
            stores = stores,
            networkMetadataProvider = FakeNetworkMetadataProvider(),
            diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
            serviceStateStore = serviceStateStore,
            scope = scope,
        )

    private fun telemetryWithEvent(
        message: String,
        createdAt: Long,
    ): ServiceTelemetrySnapshot =
        ServiceTelemetrySnapshot(
            proxyTelemetry =
                NativeRuntimeSnapshot(
                    source = "proxy",
                    nativeEvents =
                        listOf(
                            NativeRuntimeEvent(
                                source = "proxy",
                                level = "info",
                                message = message,
                                createdAt = createdAt,
                            ),
                        ),
                ),
            updatedAt = createdAt,
        )
}
