package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.core.testing.FaultOutcome
import com.poyka.ripdpi.core.testing.FaultQueue
import com.poyka.ripdpi.core.testing.FaultSpec
import com.poyka.ripdpi.core.testing.faultThrowable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class FakeProxyPreferencesResolver(
    private var preferences: RipDpiProxyPreferences = RipDpiProxyUIPreferences(),
) : ProxyPreferencesResolver {
    override suspend fun resolve(): RipDpiProxyPreferences = preferences

    fun setPreferences(preferences: RipDpiProxyPreferences) {
        this.preferences = preferences
    }
}

class FakeRipDpiProxyFactory(
    private val proxy: RipDpiProxyRuntime,
) : RipDpiProxyFactory {
    override fun create(): RipDpiProxyRuntime = proxy
}

class FakeRipDpiProxyRuntime : RipDpiProxyRuntime {
    var startResult: Int = 0
    var stopCount: Int = 0
    var startCount: Int = 0
    var lastPreferences: RipDpiProxyPreferences? = null
    var telemetryValue: NativeRuntimeSnapshot = NativeRuntimeSnapshot.idle(source = "proxy")

    override suspend fun startProxy(preferences: RipDpiProxyPreferences): Int {
        startCount += 1
        lastPreferences = preferences
        return startResult
    }

    override suspend fun awaitReady(timeoutMillis: Long) {
        // Fake runtime is ready as soon as startProxy is invoked.
    }

    override suspend fun stopProxy() {
        stopCount += 1
    }

    override suspend fun pollTelemetry(): NativeRuntimeSnapshot = telemetryValue

    override suspend fun updateNetworkSnapshot(snapshot: NativeNetworkSnapshot) {
        // No-op in fake.
    }
}

enum class ProxyBindingFaultTarget {
    CREATE,
    START,
    STOP,
    TELEMETRY,
}

class FakeRipDpiProxyBindings : RipDpiProxyBindings {
    companion object {
        private const val DEFAULT_START_BLOCK_TIMEOUT_MS = 5_000L
    }

    var createdHandle: Long = 1L
    var startResult: Int = 0
    var createFailure: Throwable? = null
    var startFailure: Throwable? = null
    var stopFailure: Throwable? = null
    var telemetryFailure: Throwable? = null
    var startedSignal: CompletableDeferred<Long>? = null
    var startBlocker: CompletableDeferred<Unit>? = null
    var startBlockTimeoutMillis: Long = DEFAULT_START_BLOCK_TIMEOUT_MS
    var lastCreatePayload: String? = null
    var lastStartedHandle: Long? = null
    var lastStoppedHandle: Long? = null
    var lastDestroyedHandle: Long? = null
    var telemetryJson: String? = null
    val faults = FaultQueue<ProxyBindingFaultTarget>()
    val createdPayloads = mutableListOf<String>()
    val startedHandles = mutableListOf<Long>()
    val stoppedHandles = mutableListOf<Long>()
    val destroyedHandles = mutableListOf<Long>()
    val telemetryHandles = mutableListOf<Long>()

    override fun create(configJson: String): Long {
        faults.next(ProxyBindingFaultTarget.CREATE)?.throwOrIgnore()
        createFailure?.let { throw it }
        lastCreatePayload = configJson
        createdPayloads += configJson
        return createdHandle
    }

    override fun start(handle: Long): Int {
        lastStartedHandle = handle
        startedHandles += handle
        startedSignal?.complete(handle)
        startBlocker?.let { blocker ->
            try {
                runBlocking {
                    withTimeout(startBlockTimeoutMillis) {
                        blocker.await()
                    }
                }
            } catch (error: TimeoutCancellationException) {
                throw AssertionError(
                    "FakeRipDpiProxyBindings.start blocked for more than ${startBlockTimeoutMillis}ms",
                    error,
                )
            }
        }
        faults.next(ProxyBindingFaultTarget.START)?.throwOrIgnore()
        startFailure?.let { throw it }
        return startResult
    }

    override fun stop(handle: Long) {
        lastStoppedHandle = handle
        stoppedHandles += handle
        faults.next(ProxyBindingFaultTarget.STOP)?.throwOrIgnore()
        stopFailure?.let { throw it }
    }

    override fun pollTelemetry(handle: Long): String? {
        telemetryHandles += handle
        faults.next(ProxyBindingFaultTarget.TELEMETRY)?.let { fault ->
            return fault.payloadResult() ?: telemetryJson
        }
        telemetryFailure?.let { throw it }
        return telemetryJson
    }

    override fun destroy(handle: Long) {
        lastDestroyedHandle = handle
        destroyedHandles += handle
    }

    override fun updateNetworkSnapshot(handle: Long, snapshotJson: String) {
        // No-op in fake.
    }
}

class FakeTun2SocksBridge : Tun2SocksBridge {
    var startedConfig: Tun2SocksConfig? = null
    var startedTunFd: Int? = null
    var stopCount: Int = 0
    var statsValue: TunnelStats = TunnelStats()
    var statsFailure: Throwable? = null
    var telemetryValue: NativeRuntimeSnapshot = NativeRuntimeSnapshot.idle(source = "tunnel")

    override suspend fun start(
        config: Tun2SocksConfig,
        tunFd: Int,
    ) {
        startedConfig = config
        startedTunFd = tunFd
    }

    override suspend fun stop() {
        stopCount += 1
    }

    override suspend fun stats(): TunnelStats {
        statsFailure?.let { throw it }
        return statsValue
    }

    override suspend fun telemetry(): NativeRuntimeSnapshot = telemetryValue
}

enum class TunnelBindingFaultTarget {
    CREATE,
    START,
    STOP,
    STATS,
    TELEMETRY,
}

class FakeTun2SocksBridgeFactory(
    private val bridge: Tun2SocksBridge = FakeTun2SocksBridge(),
) : Tun2SocksBridgeFactory {
    override fun create(): Tun2SocksBridge = bridge
}

class FakeTun2SocksBindings : Tun2SocksBindings {
    var createdHandle: Long = 1L
    var createFailure: Throwable? = null
    var startFailure: Throwable? = null
    var stopFailure: Throwable? = null
    var statsFailure: Throwable? = null
    var telemetryFailure: Throwable? = null
    var lastCreatePayload: String? = null
    var lastStartHandle: Long? = null
    var lastStartTunFd: Int? = null
    var lastStopHandle: Long? = null
    var lastDestroyedHandle: Long? = null
    var nativeStats: LongArray = longArrayOf()
    var telemetryJson: String? = null
    val faults = FaultQueue<TunnelBindingFaultTarget>()
    val createdPayloads = mutableListOf<String>()
    val startedHandles = mutableListOf<Long>()
    val stoppedHandles = mutableListOf<Long>()
    val destroyedHandles = mutableListOf<Long>()
    val statsHandles = mutableListOf<Long>()
    val telemetryHandles = mutableListOf<Long>()

    override fun create(configJson: String): Long {
        faults.next(TunnelBindingFaultTarget.CREATE)?.throwOrIgnore()
        createFailure?.let { throw it }
        lastCreatePayload = configJson
        createdPayloads += configJson
        return createdHandle
    }

    override fun start(
        handle: Long,
        tunFd: Int,
    ) {
        lastStartHandle = handle
        lastStartTunFd = tunFd
        startedHandles += handle
        faults.next(TunnelBindingFaultTarget.START)?.throwOrIgnore()
        startFailure?.let { throw it }
    }

    override fun stop(handle: Long) {
        lastStopHandle = handle
        stoppedHandles += handle
        faults.next(TunnelBindingFaultTarget.STOP)?.throwOrIgnore()
        stopFailure?.let { throw it }
    }

    override fun getStats(handle: Long): LongArray {
        statsHandles += handle
        faults.next(TunnelBindingFaultTarget.STATS)?.throwOrIgnore()
        statsFailure?.let { throw it }
        return nativeStats
    }

    override fun getTelemetry(handle: Long): String? {
        telemetryHandles += handle
        faults.next(TunnelBindingFaultTarget.TELEMETRY)?.let { fault ->
            return fault.payloadResult() ?: telemetryJson
        }
        telemetryFailure?.let { throw it }
        return telemetryJson
    }

    override fun destroy(handle: Long) {
        lastDestroyedHandle = handle
        destroyedHandles += handle
    }
}

enum class DiagnosticsBindingFaultTarget {
    CREATE,
    START_SCAN,
    CANCEL,
    POLL_PROGRESS,
    TAKE_REPORT,
    PASSIVE_EVENTS,
    DESTROY,
}

class FakeNetworkDiagnosticsBindings : NetworkDiagnosticsBindings {
    enum class ScanState {
        READY,
        SCANNING,
    }

    var createdHandle: Long = 1L
    var createFailure: Throwable? = null
    var startFailure: Throwable? = null
    var pollProgressFailure: Throwable? = null
    var takeReportFailure: Throwable? = null
    var passiveEventsFailure: Throwable? = null
    var progressJson: String? = null
    var reportJson: String? = null
    var passiveEventsJson: String? = "[]"
    var state: ScanState = ScanState.READY
    val faults = FaultQueue<DiagnosticsBindingFaultTarget>()
    var lastStartedHandle: Long? = null
    var lastStartedRequestJson: String? = null
    var lastStartedSessionId: String? = null
    val cancelledHandles = mutableListOf<Long>()
    val destroyedHandles = mutableListOf<Long>()
    val progressHandles = mutableListOf<Long>()
    val reportHandles = mutableListOf<Long>()
    val passiveEventHandles = mutableListOf<Long>()

    override fun create(): Long {
        faults.next(DiagnosticsBindingFaultTarget.CREATE)?.throwOrIgnore()
        createFailure?.let { throw it }
        return createdHandle
    }

    override fun startScan(
        handle: Long,
        requestJson: String,
        sessionId: String,
    ) {
        if (state == ScanState.SCANNING) {
            throw IllegalStateException("diagnostics scan already running")
        }
        lastStartedHandle = handle
        lastStartedRequestJson = requestJson
        lastStartedSessionId = sessionId
        faults.next(DiagnosticsBindingFaultTarget.START_SCAN)?.throwOrIgnore()
        startFailure?.let { throw it }
        state = ScanState.SCANNING
    }

    override fun cancelScan(handle: Long) {
        cancelledHandles += handle
        faults.next(DiagnosticsBindingFaultTarget.CANCEL)?.throwOrIgnore()
        state = ScanState.READY
    }

    override fun pollProgress(handle: Long): String? {
        progressHandles += handle
        faults.next(DiagnosticsBindingFaultTarget.POLL_PROGRESS)?.let { fault ->
            return fault.payloadResult() ?: progressJson
        }
        pollProgressFailure?.let { throw it }
        return progressJson
    }

    override fun takeReport(handle: Long): String? {
        reportHandles += handle
        faults.next(DiagnosticsBindingFaultTarget.TAKE_REPORT)?.let { fault ->
            return fault.payloadResult() ?: reportJson
        }
        takeReportFailure?.let { throw it }
        return reportJson
    }

    override fun pollPassiveEvents(handle: Long): String? {
        passiveEventHandles += handle
        faults.next(DiagnosticsBindingFaultTarget.PASSIVE_EVENTS)?.let { fault ->
            return fault.payloadResult() ?: passiveEventsJson
        }
        passiveEventsFailure?.let { throw it }
        return passiveEventsJson
    }

    override fun destroy(handle: Long) {
        destroyedHandles += handle
        faults.next(DiagnosticsBindingFaultTarget.DESTROY)?.throwOrIgnore()
        state = ScanState.READY
    }
}

private fun <T> FaultSpec<T>.throwOrIgnore() {
    when (outcome) {
        FaultOutcome.MALFORMED_PAYLOAD,
        FaultOutcome.BLANK_PAYLOAD,
        -> Unit

        else -> throw faultThrowable(outcome, message)
    }
}

private fun <T> FaultSpec<T>.payloadResult(): String? =
    when (outcome) {
        FaultOutcome.MALFORMED_PAYLOAD -> {
            payload ?: """{"malformed"""
        }

        FaultOutcome.BLANK_PAYLOAD -> {
            payload ?: ""
        }

        else -> {
            throw faultThrowable(outcome, message)
        }
    }
