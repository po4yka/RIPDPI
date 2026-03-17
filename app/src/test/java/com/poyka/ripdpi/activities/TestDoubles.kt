package com.poyka.ripdpi.activities

import android.content.Intent
import com.poyka.ripdpi.core.TunnelStats
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.platform.LauncherIconController
import com.poyka.ripdpi.platform.PermissionPlatformBridge
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.platform.TrafficStatsReader
import com.poyka.ripdpi.permissions.PermissionSnapshot
import com.poyka.ripdpi.permissions.PermissionStatusProvider
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.FailureReason
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceEvent
import com.poyka.ripdpi.services.ServiceStateStore
import com.poyka.ripdpi.services.ServiceTelemetrySnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeAppSettingsRepository(
    initialSettings: AppSettings = com.poyka.ripdpi.data.AppSettingsSerializer.defaultValue,
) : AppSettingsRepository {
    private val state = MutableStateFlow(initialSettings)

    override val settings: Flow<AppSettings> = state

    override suspend fun snapshot(): AppSettings = state.value

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
        state.value = state.value.toBuilder().apply(transform).build()
    }

    override suspend fun replace(settings: AppSettings) {
        state.value = settings
    }
}

class FakeServiceStateStore(
    initialStatus: Pair<AppStatus, Mode> = AppStatus.Halted to Mode.VPN,
) : ServiceStateStore {
    private val statusState = MutableStateFlow(initialStatus)
    private val eventFlow = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 1)
    private val telemetryState = MutableStateFlow(ServiceTelemetrySnapshot())

    override val status: StateFlow<Pair<AppStatus, Mode>> = statusState.asStateFlow()
    override val events: SharedFlow<ServiceEvent> = eventFlow.asSharedFlow()
    override val telemetry: StateFlow<ServiceTelemetrySnapshot> = telemetryState.asStateFlow()

    override fun setStatus(
        status: AppStatus,
        mode: Mode,
    ) {
        statusState.value = status to mode
    }

    override fun emitFailed(sender: Sender, reason: FailureReason) {
        eventFlow.tryEmit(ServiceEvent.Failed(sender, reason))
    }

    override fun updateTelemetry(snapshot: ServiceTelemetrySnapshot) {
        telemetryState.value = snapshot
    }
}

class FakeServiceController : ServiceController {
    val startedModes = mutableListOf<Mode>()
    var stopCount = 0

    override fun start(mode: Mode) {
        startedModes += mode
    }

    override fun stop() {
        stopCount += 1
    }
}

class FakeLauncherIconController : LauncherIconController {
    data class Selection(
        val iconKey: String,
        val iconStyle: String,
    )

    var lastSelection: Selection? = null

    override fun applySelection(
        iconKey: String,
        iconStyle: String,
    ) {
        lastSelection = Selection(iconKey, iconStyle)
    }
}

class FakeStringResolver : StringResolver {
    override fun getString(
        resId: Int,
        vararg formatArgs: Any,
    ): String = buildString {
        append(resId)
        if (formatArgs.isNotEmpty()) {
            append(':')
            append(formatArgs.joinToString(","))
        }
    }
}

class FakeTrafficStatsReader(
    var transferredBytes: Long = 0L,
) : TrafficStatsReader {
    override fun currentTransferredBytes(): Long = transferredBytes
}

class FakePermissionPlatformBridge(
    var vpnPermissionIntent: Intent? = Intent("fake.vpn.permission"),
    var appSettingsIntent: Intent = Intent("fake.app.settings"),
    var batteryOptimizationIntent: Intent = Intent("fake.battery.optimization"),
) : PermissionPlatformBridge {
    override fun prepareVpnPermissionIntent(): Intent? = vpnPermissionIntent

    override fun createAppSettingsIntent(): Intent = appSettingsIntent

    override fun createBatteryOptimizationIntent(): Intent = batteryOptimizationIntent
}

class FakePermissionStatusProvider(
    var snapshot: PermissionSnapshot = PermissionSnapshot(),
) : PermissionStatusProvider {
    override fun currentSnapshot(): PermissionSnapshot = snapshot
}
