package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayFactory
import com.poyka.ripdpi.core.RipDpiRelayRuntime
import com.poyka.ripdpi.core.XrayNativeBridge
import com.poyka.ripdpi.core.XrayRuntimeOwner
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayProfileRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

/** Controlled engine ports for app unit tests; the real service probe alone creates opaque proof. */
class ProfileUtilityProbeFixtures(
    http: CandidateHttpPayloadProbe,
    environment: suspend () -> CandidateRelayProbeEnvironment,
    failStop: () -> Boolean,
    recordRuntime: (ControlledRelayRuntime) -> Unit,
) {
    val relay: CandidateRelayPayloadProbe =
        CandidateRelayPayloadProbe(
            resolve = { profile, credentials, captured -> resolved(profile, credentials, captured) },
            runtimeFactory =
                object : RipDpiRelayFactory {
                    override fun create(): RipDpiRelayRuntime =
                        ControlledRelayRuntime(failStop).also(recordRuntime).delegate
                },
            httpProbe = http,
            environment = environment,
            dispatcher = Dispatchers.IO,
        )
    private val xrayOwner =
        XrayRuntimeOwner(
            Proxy.newProxyInstance(
                XrayNativeBridge::class.java.classLoader,
                arrayOf(XrayNativeBridge::class.java),
            ) { _, method, _ -> error("Unexpected controlled Xray native call: ${method.name}") } as XrayNativeBridge,
            Dispatchers.IO,
        )
    val xray: CandidateXrayPayloadProbe =
        CandidateXrayPayloadProbe(
            xrayOwner,
            ActiveProtectSocketPathProvider(),
            environment,
            http,
        )

    fun occupyXray(block: () -> Unit) =
        xrayOwner.resolveEndpoint {
            block()
            emptyList()
        }
}

private fun resolved(
    profile: RelayProfileRecord,
    credentials: RelayCredentialRecord,
    environment: CandidateRelayProbeEnvironment,
) = ResolvedRipDpiRelayConfig(
    enabled = true,
    kind = profile.kind,
    profileId = profile.id,
    server = profile.server,
    serverPort = profile.serverPort,
    serverName = profile.serverName,
    realityPublicKey = "",
    realityShortId = "",
    chainEntryServer = "",
    chainEntryPort = 0,
    chainEntryServerName = "",
    chainEntryPublicKey = "",
    chainEntryShortId = "",
    chainExitServer = "",
    chainExitPort = 0,
    chainExitServerName = "",
    chainExitPublicKey = "",
    chainExitShortId = "",
    masqueUrl = "",
    masqueUseHttp2Fallback = false,
    localSocksHost = "127.0.0.1",
    localSocksPort = 0,
    udpEnabled = false,
    tcpFallbackEnabled = false,
    tlsFingerprintProfile = environment.tlsProfile,
    trojanPassword = credentials.trojanPassword,
)

class ControlledRelayRuntime(
    private val failStop: () -> Boolean,
) {
    var profileId: String = ""
        private set

    private val started = CompletableDeferred<Unit>()
    val finished = CompletableDeferred<Unit>()
    val stopCalls = AtomicInteger()
    val failedStops = MutableStateFlow(0)
    private lateinit var startJob: Job

    internal val delegate: RipDpiRelayRuntime =
        object : RipDpiRelayRuntime {
            override suspend fun start(config: ResolvedRipDpiRelayConfig): Int {
                check(config.localSocksPort == 0) { "Candidate must request an ephemeral local SOCKS port" }
                profileId = config.profileId
                startJob = checkNotNull(currentCoroutineContext()[Job])
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    finished.complete(Unit)
                }
            }

            override suspend fun awaitReady(timeoutMillis: Long) {
                started.await()
            }

            override suspend fun pollTelemetry() =
                NativeRuntimeSnapshot(
                    source = "relay",
                    listenerAddress = "127.0.0.1:1234",
                )

            override suspend fun stop() {
                stopCalls.incrementAndGet()
                if (failStop()) {
                    failedStops.value += 1
                    error("Controlled unit stop failure")
                }
                startJob.cancel()
            }
        }
}
