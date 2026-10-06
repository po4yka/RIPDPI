package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiXrayRuntime
import com.poyka.ripdpi.core.StopCause
import com.poyka.ripdpi.core.XrayProtectController
import com.poyka.ripdpi.core.XrayRuntimeOwner
import com.poyka.ripdpi.data.xray.XrayConfigRenderer
import com.poyka.ripdpi.data.xray.XrayProfile
import com.poyka.ripdpi.data.xray.XrayProviderBuildInfo
import com.poyka.ripdpi.data.xray.XrayProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.ServerSocket
import javax.inject.Inject
import javax.inject.Singleton

/** Uses the real process-owned Xray lane without displacing a running provider. */
@Singleton
class CandidateXrayPayloadProbe internal constructor(
    private val owner: XrayRuntimeOwner,
    private val protection: ActiveProtectSocketPathProvider,
    private val environment: suspend () -> CandidateRelayProbeEnvironment,
    private val http: CandidateHttpPayloadProbe,
) : CandidateXrayMeasurements {
    @Inject
    internal constructor(
        owner: XrayRuntimeOwner,
        protection: ActiveProtectSocketPathProvider,
        configuration: CandidateRelayProbeConfiguration,
        http: CandidateHttpPayloadProbe,
    ) : this(owner, protection, configuration::capture, http)

    private val mutex = Mutex()
    private var pending: RipDpiXrayRuntime? = null
    private val cleanup = MutableStateFlow(false)
    override val cleanupPending = cleanup.asStateFlow()

    override suspend fun retryCleanup(): Boolean =
        mutex.withLock {
            val released = withContext(NonCancellable) { release() }
            currentCoroutineContext().ensureActive()
            released
        }

    override suspend fun measure(
        profile: XrayProfile,
        url: String,
    ): CandidateRelayMeasurement =
        mutex.withLock {
            if (!withContext(NonCancellable) { release() }) return@withLock CandidateRelayMeasurement.CleanupPending
            currentCoroutineContext().ensureActive()
            if (owner.isOccupied) return@withLock CandidateRelayMeasurement.Busy
            val before = environment()
            val protect = protection.captureDirectProtection()
            if (before.vpnProtectionRequired && protect == null) {
                return@withLock CandidateRelayMeasurement.EnvironmentChanged
            }
            val controller = protect ?: XrayProtectController { protection.current() == null }
            val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val candidate = profile.copy(inbound = profile.inbound.copy(listen = "127.0.0.1", port = port))
            val rendered = XrayConfigRenderer().render(candidate, XrayProviderBuildInfo.upstreamTag)
            if (rendered !is XrayConfigRenderer.Result.Success) return@withLock CandidateRelayMeasurement.Unsupported
            val runtime = RipDpiXrayRuntime(owner, XrayProviderConfig(localInboundPort = port))
            pending = runtime
            var stage = CandidateMeasurementStage.Ready
            var outcome: CandidateRelayMeasurement
            try {
                val ready =
                    withTimeoutOrNull(CandidateXrayReadyTimeoutMillis) {
                        runtime.start(rendered.config.toString(), controller)
                        runtime.awaitReady(CandidateXrayReadyTimeoutMillis)
                        true
                    } == true
                outcome =
                    if (!ready) {
                        CandidateRelayMeasurement.TimedOut(stage)
                    } else if (before != environment()) {
                        CandidateRelayMeasurement.EnvironmentChanged
                    } else {
                        stage = CandidateMeasurementStage.Http
                        val result =
                            withTimeoutOrNull(
                                CandidateXrayHttpTimeoutMillis,
                            ) { http.probe(RelayProbeEndpoint("127.0.0.1", port), url) }
                        when {
                            result == null -> {
                                CandidateRelayMeasurement.TimedOut(stage)
                            }

                            !result.succeeded -> {
                                CandidateRelayMeasurement.Failed(stage)
                            }

                            before != environment() -> {
                                CandidateRelayMeasurement.EnvironmentChanged
                            }

                            else -> {
                                CandidateRelayMeasurement.Succeeded(
                                    result.latencyMillis,
                                    CandidateConfigurationProofs.xray(rendered.config.toString()),
                                )
                            }
                        }
                    }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { release() }
                throw cancelled
            } catch (_: Exception) {
                outcome = CandidateRelayMeasurement.Failed(stage)
            }
            val released = withContext(NonCancellable) { release() }
            currentCoroutineContext().ensureActive()
            if (released) outcome else CandidateRelayMeasurement.CleanupPending
        }

    private suspend fun release(): Boolean {
        val runtime = pending ?: return true
        val result =
            try {
                runtime.stop(CandidateXrayStopTimeoutMillis)
            } catch (_: Exception) {
                StopCause.Pending
            }
        val released = result == StopCause.Clean || result == StopCause.AlreadyStopped
        if (released) pending = null
        cleanup.value = !released
        return released
    }
}

private const val CandidateXrayReadyTimeoutMillis = 5_000L
private const val CandidateXrayHttpTimeoutMillis = 15_000L
private const val CandidateXrayStopTimeoutMillis = 5_000L
