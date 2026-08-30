package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultServiceStateStore
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NoopWidgetNotifier
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.WidgetSnapshot
import com.poyka.ripdpi.data.WidgetStateRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceSessionStateInitializerTest {
    @Test
    fun `state writer owner uses the service session scope`() {
        assertTrue(
            ServiceSessionStateInitializer::class.java.isAnnotationPresent(ServiceSessionScope::class.java),
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `generation switch does not lose current event with active subscriber`() =
        runTest {
            val store = DefaultServiceStateStore(TestWidgetStateRepository(), NoopWidgetNotifier, backgroundScope)
            val observed = mutableListOf<String>()
            backgroundScope.launch {
                store.events.collect { event ->
                    observed +=
                        ((event as ServiceEvent.Failed).reason as FailureReason.Unexpected).cause.message.orEmpty()
                }
            }
            val oldSession = ServiceSessionStateInitializer(store).initialize(Mode.VPN)
            runCurrent()

            oldSession.emitFailed(Sender.VPN, FailureReason.Unexpected(IllegalStateException("old")))
            val newSession = ServiceSessionStateInitializer(store).initialize(Mode.VPN)
            newSession.emitFailed(Sender.VPN, FailureReason.Unexpected(IllegalStateException("new")))
            runCurrent()

            assertEquals(listOf("new"), observed)
        }

    @Test
    fun `new vpn session starts from fresh transient state`() =
        runTest {
            val store = DefaultServiceStateStore()
            val oldSession = ServiceSessionStateInitializer(store).initialize(Mode.VPN)
            oldSession.setStatus(AppStatus.Running, Mode.VPN)
            oldSession.updateTelemetry(
                ServiceTelemetrySnapshot(
                    mode = Mode.VPN,
                    status = AppStatus.Running,
                    proxyTelemetry = NativeRuntimeSnapshot.idle(source = "old-proxy"),
                    serviceStartedAt = 123L,
                    restartCount = 7,
                    lastFailureSender = Sender.VPN,
                    lastFailureAt = 456L,
                ),
            )
            oldSession.emitFailed(Sender.VPN, FailureReason.Unexpected(IllegalStateException("old session")))

            val newSession = ServiceSessionStateInitializer(store)
            val newSessionStore = newSession.initialize(Mode.VPN)
            assertNotSame(oldSession, newSessionStore)

            oldSession.setStatus(AppStatus.Running, Mode.VPN)
            oldSession.updateTelemetry(
                ServiceTelemetrySnapshot(
                    mode = Mode.VPN,
                    status = AppStatus.Running,
                    proxyTelemetry = NativeRuntimeSnapshot.idle(source = "late-old-proxy"),
                ),
            )
            oldSession.emitFailed(Sender.VPN, FailureReason.Unexpected(IllegalStateException("late old session")))

            assertEquals(AppStatus.Halted to Mode.VPN, store.status.value)
            assertEquals(
                ServiceTelemetrySnapshot(
                    mode = Mode.VPN,
                    status = AppStatus.Halted,
                    restartCount = 7,
                ),
                store.telemetry.value,
            )
            assertNull(withTimeoutOrNull(100) { store.events.first() as ServiceEvent.Failed })

            newSessionStore.emitFailed(Sender.VPN, FailureReason.Unexpected(IllegalStateException("new session")))
            val currentEvent = withTimeout(1_000) { store.events.first() as ServiceEvent.Failed }
            assertEquals("new session", (currentEvent.reason as FailureReason.Unexpected).cause.message)

            store.setStatus(AppStatus.Running, Mode.VPN)
            assertSame(newSessionStore, newSession.initialize(Mode.VPN))
            assertEquals(AppStatus.Running to Mode.VPN, store.status.value)
        }

    @Test
    fun `closed session rejects late callbacks before another session starts`() =
        runTest {
            val store = DefaultServiceStateStore()
            val initializer = ServiceSessionStateInitializer(store)
            val session = initializer.initialize(Mode.VPN)
            session.setStatus(AppStatus.Running, Mode.VPN)

            initializer.close()
            val afterClose = store.telemetry.value
            session.setStatus(AppStatus.Halted, Mode.Proxy)
            session.updateTelemetry(ServiceTelemetrySnapshot(mode = Mode.Proxy, status = AppStatus.Halted))
            session.emitFailed(Sender.Proxy, FailureReason.Unexpected(IllegalStateException("late")))

            assertEquals(AppStatus.Halted to Mode.VPN, store.status.value)
            assertEquals(afterClose, store.telemetry.value)
            assertNull(withTimeoutOrNull(100) { store.events.first() as ServiceEvent.Failed })
        }

    @Test
    fun `terminal failure remains observable after session closes`() =
        runTest {
            val store = DefaultServiceStateStore()
            val initializer = ServiceSessionStateInitializer(store)
            initializer.initialize(Mode.VPN).apply {
                setStatus(AppStatus.Running, Mode.VPN)
                updateTelemetry(
                    ServiceTelemetrySnapshot(
                        mode = Mode.VPN,
                        status = AppStatus.Running,
                        proxyTelemetry =
                            NativeRuntimeSnapshot(
                                source = "proxy",
                                state = "running",
                                health = "healthy",
                                activeSessions = 3,
                            ),
                        relayTelemetry =
                            NativeRuntimeSnapshot(
                                source = "relay",
                                state = "running",
                                health = "healthy",
                                activeSessions = 1,
                            ),
                    ),
                )
            }

            initializer.close(Sender.VPN, FailureReason.PermissionLost("VPN"))

            val failure = withTimeout(1_000) { store.events.first() as ServiceEvent.Failed }
            assertEquals(Sender.VPN, failure.sender)
            assertEquals(FailureReason.PermissionLost("VPN"), failure.reason)
            assertEquals(AppStatus.Running, failure.statusAtFailure)
            assertEquals(Mode.VPN, failure.modeAtFailure)
            assertEquals(AppStatus.Halted to Mode.VPN, store.status.value)
            assertEquals(Sender.VPN, store.telemetry.value.lastFailureSender)
            assertEquals("idle", store.telemetry.value.proxyTelemetry.health)
            assertEquals("idle", store.telemetry.value.relayTelemetry.health)
            assertEquals(0L, store.telemetry.value.proxyTelemetry.activeSessions)
            assertEquals(0L, store.telemetry.value.relayTelemetry.activeSessions)
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `active subscriber receives terminal failure during close`() =
        runTest {
            val store = DefaultServiceStateStore(TestWidgetStateRepository(), NoopWidgetNotifier, backgroundScope)
            val observed = mutableListOf<FailureReason>()
            backgroundScope.launch {
                store.events.collect { observed += (it as ServiceEvent.Failed).reason }
            }
            val initializer = ServiceSessionStateInitializer(store)
            initializer.initialize(Mode.VPN)
            runCurrent()

            initializer.close(Sender.VPN, FailureReason.PermissionLost("VPN"))
            runCurrent()

            assertEquals(listOf(FailureReason.PermissionLost("VPN")), observed)
        }

    private class TestWidgetStateRepository : WidgetStateRepository {
        private val state = MutableStateFlow(WidgetSnapshot())

        override suspend fun write(snapshot: WidgetSnapshot) {
            state.value = snapshot
        }

        override fun observe(): Flow<WidgetSnapshot> = state.asStateFlow()

        override suspend fun snapshot(): WidgetSnapshot = state.value
    }
}
