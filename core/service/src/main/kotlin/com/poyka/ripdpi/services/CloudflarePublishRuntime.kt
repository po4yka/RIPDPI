package com.poyka.ripdpi.services

import android.content.Context
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayFactory
import com.poyka.ripdpi.core.RipDpiRelayRuntime
import com.poyka.ripdpi.data.NativeError
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudflarePublishManager
    @Inject
    internal constructor(
        @param:ApplicationContext private val context: Context,
        private val configParser: CloudflarePublishConfigParser,
        private val processSupervisor: CloudflarePublishProcessSupervisor,
        private val readinessPoller: CloudflarePublishReadinessPoller,
        private val telemetryProjector: CloudflarePublishTelemetryProjector,
    ) {
        private val sessionActive = AtomicBoolean(false)

        @Volatile private var running: RunningCloudflarePublish? = null

        suspend fun start(config: ResolvedRipDpiRelayConfig) {
            require(config.kind == com.poyka.ripdpi.data.RelayKindCloudflareTunnel) {
                "Cloudflare publish runtime only supports Cloudflare Tunnel profiles"
            }
            if (!sessionActive.compareAndSet(false, true)) {
                throw NativeError.AlreadyRunning("CloudflarePublishManager")
            }
            try {
                val originSpec = configParser.parseLocalOriginSpec(config.cloudflarePublishLocalOriginUrl)
                val metricsPort = findLoopbackPort()
                val metricsAddress = "127.0.0.1:$metricsPort"
                val stateDir =
                    File(
                        context.filesDir,
                        "cloudflare-publish/${sanitizeSegment(config.profileId)}",
                    ).apply { mkdirs() }
                val originReadySignal = CompletableDeferred<String>()
                var runningState: RunningCloudflarePublish? = null
                var pendingLastError: String? = null
                var pendingFailureClass: String? = null
                val originProcess =
                    processSupervisor.launchOriginProcess(
                        config = config,
                        originSpec = originSpec,
                        stateDir = stateDir,
                        readySignal = originReadySignal,
                        onError = { message, failureClass ->
                            runningState?.lastError = message
                            runningState?.lastFailureClass = failureClass
                            if (runningState == null) {
                                pendingLastError = message
                                pendingFailureClass = failureClass
                            }
                        },
                    )
                runningState =
                    RunningCloudflarePublish(
                        originProcess = originProcess,
                        cloudflaredProcess =
                            processSupervisor.launchCloudflaredProcess(
                                config = config,
                                originSpec = originSpec,
                                metricsAddress = metricsAddress,
                                stateDir = stateDir,
                                lastErrorSink = { message, failureClass ->
                                    runningState?.lastError = message
                                    runningState?.lastFailureClass = failureClass
                                },
                                onRegisteredTunnelConnection = {
                                    running?.cloudflaredReady = true
                                },
                            ),
                        metricsAddress = metricsAddress,
                        originReadySignal = originReadySignal,
                        originReady = false,
                        cloudflaredReady = false,
                    )
                val state = requireNotNull(runningState)
                pendingLastError?.let { state.lastError = it }
                pendingFailureClass?.let { state.lastFailureClass = it }
                running = state
                var ready = false
                try {
                    readinessPoller.waitForOriginReady(state)
                    state.originReady = true
                    readinessPoller.waitForCloudflaredReady(state)
                    state.cloudflaredReady = true
                    ready = true
                } finally {
                    if (!ready) runCatching { stop() }
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                sessionActive.set(false)
                throw e
            }
        }

        suspend fun waitForUnexpectedExit(): Int =
            coroutineScope {
                val active = running ?: return@coroutineScope 0
                val originExit = async(Dispatchers.IO) { active.originProcess.process.waitFor() to "origin" }
                val cloudflaredExit =
                    async(Dispatchers.IO) {
                        active.cloudflaredProcess.process.waitFor() to "cloudflared"
                    }
                val (exitCode, source) =
                    select<Pair<Int, String>> {
                        originExit.onAwait { it }
                        cloudflaredExit.onAwait { it }
                    }
                if (exitCode != 0) {
                    active.lastFailureClass = "helper_exit"
                    active.lastError = "Cloudflare publish $source exited with code $exitCode"
                }
                exitCode
            }

        suspend fun stop() {
            withContext(Dispatchers.IO) {
                val active = running
                running = null
                sessionActive.set(false)
                if (active == null) {
                    return@withContext
                }
                processSupervisor.stop(active.cloudflaredProcess)
                processSupervisor.stop(active.originProcess)
            }
        }

        fun pollTelemetry(relayTelemetry: NativeRuntimeSnapshot): NativeRuntimeSnapshot {
            val active = running ?: return relayTelemetry
            return telemetryProjector.project(
                relayTelemetry = relayTelemetry,
                active = active,
            )
        }

        private fun findLoopbackPort(): Int =
            ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { socket ->
                socket.localPort
            }

        private fun sanitizeSegment(raw: String): String = raw.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

class CloudflarePublishRuntime
    @Inject
    constructor(
        private val relayFactory: RipDpiRelayFactory,
        private val publishManager: CloudflarePublishManager,
    ) : RipDpiRelayRuntime {
        @Volatile private var stopping = false

        @Volatile private var relayRuntime: RipDpiRelayRuntime? = null

        @Volatile private var activeConfig: ResolvedRipDpiRelayConfig? = null
        private var relayStartSignal = CompletableDeferred<RipDpiRelayRuntime>()

        override suspend fun start(config: ResolvedRipDpiRelayConfig): Int =
            coroutineScope {
                stopping = false
                activeConfig = config
                relayStartSignal = CompletableDeferred()
                publishManager.start(config)
                val relay = relayFactory.create()
                relayRuntime = relay
                relayStartSignal.complete(relay)
                val relayExit = async { relay.start(config) }
                val helperExit = async { publishManager.waitForUnexpectedExit() }
                val exitCode =
                    select<Int> {
                        relayExit.onAwait { code ->
                            publishManager.stop()
                            code
                        }
                        helperExit.onAwait { code ->
                            if (!stopping) {
                                relay.stop()
                            }
                            code
                        }
                    }
                relayRuntime = null
                activeConfig = null
                exitCode
            }

        override suspend fun awaitReady(timeoutMillis: Long) {
            withTimeout(timeoutMillis) {
                val relay = relayStartSignal.await()
                relay.awaitReady(timeoutMillis)
            }
        }

        override suspend fun stop() {
            stopping = true
            runCatching { relayRuntime?.stop() }
            publishManager.stop()
            relayRuntime = null
            activeConfig = null
        }

        override suspend fun pollTelemetry(): NativeRuntimeSnapshot {
            val relayTelemetry = relayRuntime?.pollTelemetry() ?: NativeRuntimeSnapshot(source = "relay")
            return activeConfig?.let {
                publishManager.pollTelemetry(
                    relayTelemetry =
                        relayTelemetry.copy(
                            upstreamAddress = relayTelemetry.upstreamAddress ?: it.server.takeIf(String::isNotBlank),
                        ),
                )
            } ?: relayTelemetry
        }
    }

interface CloudflarePublishRuntimeFactory {
    fun create(): RipDpiRelayRuntime
}

@Singleton
class DefaultCloudflarePublishRuntimeFactory
    @Inject
    constructor(
        private val runtime: CloudflarePublishRuntime,
    ) : CloudflarePublishRuntimeFactory {
        override fun create(): RipDpiRelayRuntime = runtime
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class CloudflarePublishRuntimeFactoryModule {
    @Binds
    @Singleton
    abstract fun bindCloudflarePublishRuntimeFactory(
        factory: DefaultCloudflarePublishRuntimeFactory,
    ): CloudflarePublishRuntimeFactory
}
