package com.poyka.ripdpi.testing

import android.os.ParcelFileDescriptor
import com.poyka.ripdpi.core.ProxyPreferencesResolver
import com.poyka.ripdpi.core.RipDpiProxyFactory
import com.poyka.ripdpi.core.RipDpiProxyRuntime
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.Tun2SocksBridge
import com.poyka.ripdpi.core.Tun2SocksBridgeFactory
import com.poyka.ripdpi.core.Tun2SocksConfig
import com.poyka.ripdpi.core.testing.FaultOutcome
import com.poyka.ripdpi.core.testing.FaultQueue
import com.poyka.ripdpi.core.testing.FaultSpec
import com.poyka.ripdpi.core.testing.faultThrowable
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.VpnTunnelSession
import com.poyka.ripdpi.services.VpnTunnelSessionProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

class FakeAndroidAppSettingsRepository(
    initialSettings: AppSettings = AppSettingsSerializer.defaultValue,
) : AppSettingsRepository {
    private val state = MutableStateFlow(initialSettings)

    override val settings: Flow<AppSettings> = state

    override suspend fun snapshot(): AppSettings = state.value

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
        state.value =
            state.value
                .toBuilder()
                .apply(transform)
                .build()
    }

    override suspend fun replace(settings: AppSettings) {
        state.value = settings
    }
}

class RecordingServiceStateStore(
    initialStatus: Pair<AppStatus, Mode> = AppStatus.Halted to Mode.VPN,
) : ServiceStateStore {
    private val statusState = MutableStateFlow(initialStatus)
    private val eventFlow = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 8)
    private val telemetryState = MutableStateFlow(ServiceTelemetrySnapshot())

    val eventHistory = CopyOnWriteArrayList<ServiceEvent>()

    override val status: StateFlow<Pair<AppStatus, Mode>> = statusState.asStateFlow()
    override val events: SharedFlow<ServiceEvent> = eventFlow.asSharedFlow()
    override val telemetry: StateFlow<ServiceTelemetrySnapshot> = telemetryState.asStateFlow()

    override fun setStatus(
        status: AppStatus,
        mode: Mode,
    ) {
        statusState.value = status to mode
    }

    override fun emitFailed(
        sender: Sender,
        reason: FailureReason,
    ) {
        val event = ServiceEvent.Failed(sender, reason)
        eventHistory += event
        eventFlow.tryEmit(event)
    }

    override fun updateTelemetry(snapshot: ServiceTelemetrySnapshot) {
        telemetryState.value = snapshot
    }
}

class RecordingRipDpiProxyRuntime(
    private val events: MutableList<String>,
) : RipDpiProxyRuntime {
    private var exitCode = CompletableDeferred<Int>()
    private var ready = CompletableDeferred<Unit>()
    var startFailure: Throwable? = null
    var telemetryValue: NativeRuntimeSnapshot = NativeRuntimeSnapshot.idle(source = "proxy")
    val faults = FaultQueue<ProxyRuntimeFaultTarget>()

    var lastPreferences: com.poyka.ripdpi.core.RipDpiProxyPreferences? = null
        private set
    var stopCount: Int = 0
        private set

    override suspend fun startProxy(preferences: com.poyka.ripdpi.core.RipDpiProxyPreferences): Int {
        lastPreferences = preferences
        events += "proxy:start"
        faults.next(ProxyRuntimeFaultTarget.START)?.throwOrIgnore()
        startFailure?.let {
            ready.completeExceptionally(it)
            throw it
        }
        ready.complete(Unit)
        return exitCode.await()
    }

    override suspend fun awaitReady(timeoutMillis: Long) {
        ready.await()
    }

    override suspend fun stopProxy() {
        stopCount += 1
        events += "proxy:stop"
        try {
            faults.next(ProxyRuntimeFaultTarget.STOP)?.throwOrIgnore()
        } finally {
            if (!exitCode.isCompleted) {
                exitCode.complete(0)
            }
        }
    }

    override suspend fun pollTelemetry(): NativeRuntimeSnapshot {
        faults.next(ProxyRuntimeFaultTarget.TELEMETRY)?.throwOrIgnore()
        return telemetryValue
    }

    fun complete(exitCode: Int) {
        if (!this.exitCode.isCompleted) {
            this.exitCode.complete(exitCode)
        }
    }

    fun reset() {
        exitCode = CompletableDeferred()
        ready = CompletableDeferred()
        lastPreferences = null
        stopCount = 0
        startFailure = null
        faults.clear()
    }
}

enum class ProxyRuntimeFaultTarget {
    START,
    STOP,
    TELEMETRY,
}

class RecordingRipDpiProxyFactory(
    private val events: MutableList<String>,
) : RipDpiProxyFactory {
    var lastRuntime: RecordingRipDpiProxyRuntime = RecordingRipDpiProxyRuntime(events)
        private set

    override fun create(): RipDpiProxyRuntime = lastRuntime

    fun reset() {
        lastRuntime = RecordingRipDpiProxyRuntime(events)
    }
}

class RecordingTun2SocksBridge(
    private val events: MutableList<String>,
) : Tun2SocksBridge {
    var startedConfig: Tun2SocksConfig? = null
        private set
    var startedTunFd: Int? = null
        private set
    var stopCount: Int = 0
        private set
    var failOnStart: Throwable? = null
    var failOnStats: Throwable? = null
    var failOnStop: Throwable? = null
    var failOnTelemetry: Throwable? = null
    var statsValue: TunnelStats = TunnelStats()
    var telemetryValue: NativeRuntimeSnapshot =
        NativeRuntimeSnapshot(
            source = "tunnel",
            state = "running",
            health = "healthy",
        )
    val faults = FaultQueue<TunnelBridgeFaultTarget>()

    override suspend fun start(
        config: Tun2SocksConfig,
        tunFd: Int,
    ) {
        events += "tunnel:start"
        startedConfig = config
        startedTunFd = tunFd
        faults.next(TunnelBridgeFaultTarget.START)?.throwOrIgnore()
        failOnStart?.let { throw it }
    }

    override suspend fun stop() {
        stopCount += 1
        events += "tunnel:stop"
        faults.next(TunnelBridgeFaultTarget.STOP)?.throwOrIgnore()
        failOnStop?.let { throw it }
    }

    override suspend fun stats(): TunnelStats {
        faults.next(TunnelBridgeFaultTarget.STATS)?.throwOrIgnore()
        failOnStats?.let { throw it }
        return statsValue
    }

    override suspend fun telemetry(): NativeRuntimeSnapshot {
        faults.next(TunnelBridgeFaultTarget.TELEMETRY)?.throwOrIgnore()
        failOnTelemetry?.let { throw it }
        return telemetryValue.copy(tunnelStats = statsValue)
    }

    fun reset() {
        startedConfig = null
        startedTunFd = null
        stopCount = 0
        failOnStart = null
        failOnStats = null
        failOnStop = null
        failOnTelemetry = null
        statsValue = TunnelStats()
        telemetryValue =
            NativeRuntimeSnapshot(
                source = "tunnel",
                state = "running",
                health = "healthy",
            )
        faults.clear()
    }
}

enum class TunnelBridgeFaultTarget {
    START,
    STOP,
    STATS,
    TELEMETRY,
}

class RecordingTun2SocksBridgeFactory(
    private val events: MutableList<String>,
) : Tun2SocksBridgeFactory {
    val bridge = RecordingTun2SocksBridge(events)

    override fun create(): Tun2SocksBridge = bridge

    fun reset() {
        bridge.reset()
    }
}

class RecordingVpnTunnelSession(
    private val readSide: ParcelFileDescriptor,
    private val writeSide: ParcelFileDescriptor,
    private val events: MutableList<String>,
) : VpnTunnelSession {
    override val tunFd: Int
        get() = readSide.fd

    var isClosed: Boolean = false
        private set

    override fun close() {
        if (!isClosed) {
            isClosed = true
            events += "vpn:session-close"
            readSide.close()
            writeSide.close()
        }
    }

    companion object {
        fun open(events: MutableList<String>): RecordingVpnTunnelSession {
            val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
            return RecordingVpnTunnelSession(readSide, writeSide, events)
        }
    }
}

class RecordingVpnTunnelSessionProvider(
    private val events: MutableList<String>,
) : VpnTunnelSessionProvider {
    var establishFailure: Throwable? = null
    val faults = FaultQueue<VpnSessionFaultTarget>()
    var lastDns: String? = null
        private set
    var lastIpv6: Boolean? = null
        private set
    var session: RecordingVpnTunnelSession = RecordingVpnTunnelSession.open(events)
        private set

    override fun establish(
        service: com.poyka.ripdpi.services.RipDpiVpnService,
        dns: String,
        ipv6: Boolean,
    ): VpnTunnelSession {
        lastDns = dns
        lastIpv6 = ipv6
        events += "vpn:establish"
        faults.next(VpnSessionFaultTarget.ESTABLISH)?.throwOrIgnore()
        establishFailure?.let { throw it }
        return session
    }

    fun reset() {
        session = RecordingVpnTunnelSession.open(events)
        lastDns = null
        lastIpv6 = null
        establishFailure = null
        faults.clear()
    }
}

enum class VpnSessionFaultTarget {
    ESTABLISH,
}

class FixedProxyPreferencesResolver(
    private val preferences: com.poyka.ripdpi.core.RipDpiProxyPreferences = RipDpiProxyUIPreferences(),
) : ProxyPreferencesResolver {
    override suspend fun resolve(): com.poyka.ripdpi.core.RipDpiProxyPreferences = preferences
}

class MutableProxyPreferencesResolver(
    private var delegate: ProxyPreferencesResolver = FixedProxyPreferencesResolver(),
) : ProxyPreferencesResolver {
    override suspend fun resolve(): com.poyka.ripdpi.core.RipDpiProxyPreferences = delegate.resolve()

    fun replaceWith(delegate: ProxyPreferencesResolver) {
        this.delegate = delegate
    }
}

object IntegrationTestOverrides {
    private val orderEvents = CopyOnWriteArrayList<String>()

    lateinit var appSettingsRepository: FakeAndroidAppSettingsRepository
        private set
    lateinit var serviceStateStore: RecordingServiceStateStore
        private set
    lateinit var proxyFactory: RecordingRipDpiProxyFactory
        private set
    lateinit var tun2SocksBridgeFactory: RecordingTun2SocksBridgeFactory
        private set
    lateinit var vpnTunnelSessionProvider: RecordingVpnTunnelSessionProvider
        private set
    lateinit var proxyPreferencesResolver: ProxyPreferencesResolver
        private set

    init {
        reset()
    }

    fun reset() {
        orderEvents.clear()
        appSettingsRepository = FakeAndroidAppSettingsRepository()
        serviceStateStore = RecordingServiceStateStore()
        proxyFactory = RecordingRipDpiProxyFactory(orderEvents)
        tun2SocksBridgeFactory = RecordingTun2SocksBridgeFactory(orderEvents)
        vpnTunnelSessionProvider = RecordingVpnTunnelSessionProvider(orderEvents)
        proxyPreferencesResolver = MutableProxyPreferencesResolver()
    }

    fun overrideProxyPreferencesResolver(resolver: ProxyPreferencesResolver) {
        (proxyPreferencesResolver as? MutableProxyPreferencesResolver)?.replaceWith(resolver)
            ?: run {
                proxyPreferencesResolver = MutableProxyPreferencesResolver(resolver)
            }
    }

    fun orderSnapshot(): List<String> = orderEvents.toList()
}

private fun <T> FaultSpec<T>.throwOrIgnore() {
    when (outcome) {
        FaultOutcome.MALFORMED_PAYLOAD,
        FaultOutcome.BLANK_PAYLOAD,
        -> Unit

        else -> throw faultThrowable(outcome, message)
    }
}
