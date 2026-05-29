package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.boot.BootSessionPointer
import com.poyka.ripdpi.data.boot.BootSessionStateStore
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BootSessionRecorderTest {
    @Test
    fun `records pointer with active profile id and mode when a session becomes running`() =
        runTest {
            val serviceState = FakeServiceStateStore()
            val store = RecordingBootSessionStateStore()
            val recorder =
                BootSessionRecorder(
                    serviceStateStore = serviceState,
                    appSettingsRepository = FakeAppSettingsRepository(activeProfileId = "profile-7"),
                    bootSessionStateStore = store,
                    appScope = backgroundScope,
                )

            recorder.register()
            runCurrent()
            serviceState.setStatus(AppStatus.Running, Mode.VPN)
            runCurrent()

            assertEquals(
                BootSessionPointer(profileId = "profile-7", mode = Mode.VPN),
                store.lastSession(),
            )
        }

    @Test
    fun `does not record a pointer while the service is halted`() =
        runTest {
            val serviceState = FakeServiceStateStore()
            val store = RecordingBootSessionStateStore()
            val recorder =
                BootSessionRecorder(
                    serviceStateStore = serviceState,
                    appSettingsRepository = FakeAppSettingsRepository(activeProfileId = "p"),
                    bootSessionStateStore = store,
                    appScope = backgroundScope,
                )

            recorder.register()
            runCurrent()
            // Status stays Halted (the FakeServiceStateStore seed value).

            assertNull(store.lastSession())
            assertEquals(0, store.recordCalls)
        }

    @Test
    fun `re-records the pointer when the running mode changes`() =
        runTest {
            val serviceState = FakeServiceStateStore()
            val store = RecordingBootSessionStateStore()
            val recorder =
                BootSessionRecorder(
                    serviceStateStore = serviceState,
                    appSettingsRepository = FakeAppSettingsRepository(activeProfileId = "p"),
                    bootSessionStateStore = store,
                    appScope = backgroundScope,
                )

            recorder.register()
            runCurrent()
            serviceState.setStatus(AppStatus.Running, Mode.Proxy)
            runCurrent()
            serviceState.setStatus(AppStatus.Halted, Mode.Proxy)
            runCurrent()
            serviceState.setStatus(AppStatus.Running, Mode.VPN)
            runCurrent()

            assertEquals(Mode.VPN, store.lastSession()?.mode)
        }

    private class FakeServiceStateStore : ServiceStateStore {
        private val _status = MutableStateFlow(AppStatus.Halted to Mode.VPN)
        override val status: StateFlow<Pair<AppStatus, Mode>> = _status.asStateFlow()

        private val _events = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 4)
        override val events: SharedFlow<ServiceEvent> = _events.asSharedFlow()

        private val _telemetry = MutableStateFlow(ServiceTelemetrySnapshot())
        override val telemetry: StateFlow<ServiceTelemetrySnapshot> = _telemetry.asStateFlow()

        override fun setStatus(
            status: AppStatus,
            mode: Mode,
        ) {
            _status.value = status to mode
        }

        override fun emitFailed(
            sender: Sender,
            reason: FailureReason,
        ) = Unit

        override fun updateTelemetry(snapshot: ServiceTelemetrySnapshot) = Unit
    }

    private class FakeAppSettingsRepository(
        activeProfileId: String,
    ) : AppSettingsRepository {
        private val value = AppSettings.newBuilder().setDiagnosticsActiveProfileId(activeProfileId).build()

        override val settings: Flow<AppSettings> = flowOf(value)

        override suspend fun snapshot(): AppSettings = value

        override suspend fun update(transform: AppSettings.Builder.() -> Unit) = Unit

        override suspend fun replace(settings: AppSettings) = Unit
    }

    private class RecordingBootSessionStateStore : BootSessionStateStore {
        private var pointer: BootSessionPointer? = null
        var recordCalls: Int = 0
            private set

        override fun lastSession(): BootSessionPointer? = pointer

        override fun recordSession(
            profileId: String,
            mode: Mode,
        ) {
            recordCalls += 1
            pointer = BootSessionPointer(profileId = profileId, mode = mode)
        }

        override fun clear() {
            pointer = null
        }

        override fun wasRunningAtUpdate(): Boolean = false

        override fun setWasRunningAtUpdate(value: Boolean) = Unit
    }
}
