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
import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkHandoverEvent
import com.poyka.ripdpi.data.OrderedServiceStateStore
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceHistoryEvent
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.NetworkHandoverMonitor
import com.poyka.ripdpi.services.PermissionChangeEvent
import com.poyka.ripdpi.services.PermissionWatchdog
import com.poyka.ripdpi.services.VpnAppRoutingPlan
import com.poyka.ripdpi.services.VpnTunnelNetworkParameters
import com.poyka.ripdpi.services.VpnTunnelSession
import com.poyka.ripdpi.services.VpnTunnelSessionProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
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
) : OrderedServiceStateStore {
    private val statusState = MutableStateFlow(initialStatus)
    private val eventFlow = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 8)
    private val historyEventStream = Channel<ServiceHistoryEvent>(capacity = Channel.UNLIMITED)
    private val telemetryState = MutableStateFlow(ServiceTelemetrySnapshot())

    val eventHistory = CopyOnWriteArrayList<ServiceEvent>()

    override val status: StateFlow<Pair<AppStatus, Mode>> = statusState.asStateFlow()
    override val events: SharedFlow<ServiceEvent> = eventFlow.asSharedFlow()
    override val historyEvents: Flow<ServiceHistoryEvent> = historyEventStream.receiveAsFlow()
    override val telemetry: StateFlow<ServiceTelemetrySnapshot> = telemetryState.asStateFlow()

    init {
        publishHistory(ServiceHistoryEvent.StatusChanged(initialStatus.first, initialStatus.second))
    }

    override fun setStatus(
        status: AppStatus,
        mode: Mode,
    ) {
        val previousStatus = statusState.value
        val nextStatus = status to mode
        statusState.value = nextStatus
        if (previousStatus != nextStatus) {
            publishHistory(ServiceHistoryEvent.StatusChanged(status, mode))
        }
    }

    override fun emitFailed(
        sender: Sender,
        reason: FailureReason,
    ) {
        val (status, mode) = statusState.value
        val event = ServiceEvent.Failed(sender, reason, status, mode)
        eventHistory += event
        eventFlow.tryEmit(event)
        publishHistory(ServiceHistoryEvent.Failed(event))
    }

    override fun updateTelemetry(snapshot: ServiceTelemetrySnapshot) {
        telemetryState.value = snapshot
    }

    private fun publishHistory(event: ServiceHistoryEvent) {
        check(historyEventStream.trySend(event).isSuccess) { "Service history event stream is unavailable" }
    }
}

class RecordingRipDpiProxyRuntime(
    private val events: MutableList<String>,
) : RipDpiProxyRuntime {
    private companion object {
        private const val TestListenerAddress = "127.0.0.1:1090"

        fun defaultTelemetry(): NativeRuntimeSnapshot =
            NativeRuntimeSnapshot.idle(source = "proxy").copy(listenerAddress = TestListenerAddress)
    }

    private var exitCode = CompletableDeferred<Int>()
    private var ready = CompletableDeferred<Unit>()
    var startFailure: Throwable? = null
    var telemetryValue: NativeRuntimeSnapshot = defaultTelemetry()
    val faults = FaultQueue<ProxyRuntimeFaultTarget>()

    var lastPreferences: com.poyka.ripdpi.core.RipDpiProxyPreferences? = null
        private set
    var stopCount: Int = 0
        private set

    override suspend fun startProxy(preferences: com.poyka.ripdpi.core.RipDpiProxyPreferences): Int {
        if (exitCode.isCompleted) {
            exitCode = CompletableDeferred()
            ready = CompletableDeferred()
        }
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

    override suspend fun updateNetworkSnapshot(snapshot: NativeNetworkSnapshot) = Unit

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
        telemetryValue = defaultTelemetry()
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
    val startedConfigs = CopyOnWriteArrayList<Tun2SocksConfig>()
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
        flowAttributionBridge: Any?,
    ) {
        events += "tunnel:start"
        startedConfig = config
        startedConfigs += config
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
        startedConfigs.clear()
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
    private var hasReturnedSession = false
    var establishFailure: Throwable? = null
    val faults = FaultQueue<VpnSessionFaultTarget>()
    var lastDns: String? = null
        private set
    var lastIpv6: Boolean? = null
        private set
    var lastAppRoutingPlan: VpnAppRoutingPlan? = null
        private set
    var lastInterfaceSettings: AppSettings? = null
        private set
    var lastNetworkParameters: VpnTunnelNetworkParameters? = null
        private set
    var session: RecordingVpnTunnelSession = RecordingVpnTunnelSession.open(events)
        private set

    override suspend fun establish(
        host: com.poyka.ripdpi.services.VpnTunnelBuilderHost,
        dns: String,
        ipv6: Boolean,
        appRoutingPlan: VpnAppRoutingPlan,
        interfaceSettings: AppSettings,
        httpProxyPort: Int?,
        networkParameters: VpnTunnelNetworkParameters,
        profileInterface: com.poyka.ripdpi.services.VpnProfileInterface?,
    ): VpnTunnelSession {
        lastDns = dns
        lastIpv6 = ipv6
        lastAppRoutingPlan = appRoutingPlan
        lastInterfaceSettings = interfaceSettings
        lastNetworkParameters = networkParameters
        events += "vpn:establish"
        faults.next(VpnSessionFaultTarget.ESTABLISH)?.throwOrIgnore()
        establishFailure?.let { throw it }
        if (hasReturnedSession) {
            session = RecordingVpnTunnelSession.open(events)
        }
        hasReturnedSession = true
        return session
    }

    fun reset() {
        session = RecordingVpnTunnelSession.open(events)
        hasReturnedSession = false
        lastDns = null
        lastIpv6 = null
        lastAppRoutingPlan = null
        lastInterfaceSettings = null
        lastNetworkParameters = null
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

class RecordingNetworkHandoverMonitor : NetworkHandoverMonitor {
    private val eventFlow = MutableSharedFlow<NetworkHandoverEvent>(extraBufferCapacity = 8)

    override val events: SharedFlow<NetworkHandoverEvent> = eventFlow.asSharedFlow()

    val subscriberCount: Int
        get() = eventFlow.subscriptionCount.value

    suspend fun emit(event: NetworkHandoverEvent) {
        eventFlow.emit(event)
    }
}

class RecordingPermissionWatchdog : PermissionWatchdog {
    private val changeFlow = MutableSharedFlow<PermissionChangeEvent>(extraBufferCapacity = 8)

    override val changes: SharedFlow<PermissionChangeEvent> = changeFlow.asSharedFlow()

    val subscriberCount: Int
        get() = changeFlow.subscriptionCount.value

    fun emit(event: PermissionChangeEvent) {
        changeFlow.tryEmit(event)
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
    lateinit var networkHandoverMonitor: RecordingNetworkHandoverMonitor
        private set
    lateinit var permissionWatchdog: RecordingPermissionWatchdog
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
        networkHandoverMonitor = RecordingNetworkHandoverMonitor()
        permissionWatchdog = RecordingPermissionWatchdog()
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
