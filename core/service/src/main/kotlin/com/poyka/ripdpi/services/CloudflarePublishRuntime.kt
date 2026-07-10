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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
open class CloudflarePublishManager
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

        @Volatile private var activeStateDir: File? = null

        @Volatile private var pendingOrigin: ManagedCloudflareProcess? = null

        init {
            evictStaleCredentialDirs()
        }

        private fun credentialRoot(): File = File(context.cacheDir, "cloudflare-publish")

        private fun evictStaleCredentialDirs() {
            val root = credentialRoot()
            if (!root.exists()) return
            root
                .listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("cloudflare-publish-session-") }
                ?.forEach { staleDir ->
                    check(staleDir.deleteRecursively()) {
                        "Unable to delete stale Cloudflare publish state"
                    }
                }
        }

        private fun cleanupStateDir() {
            val stateDir = activeStateDir ?: return
            check(!stateDir.exists() || deleteStateDirectory(stateDir)) {
                "Unable to delete Cloudflare publish state"
            }
            activeStateDir = null
        }

        suspend fun start(config: ResolvedRipDpiRelayConfig) {
            validateRelaySocketProtectionPolicy(config)
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
                        credentialRoot(),
                        "cloudflare-publish-session-${sanitizeSegment(config.profileId)}",
                    ).apply { mkdirs() }
                activeStateDir = stateDir
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
                pendingOrigin = originProcess
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
                pendingOrigin = null
                readinessPoller.waitForOriginReady(state)
                state.originReady = true
                readinessPoller.waitForCloudflaredReady(state)
                state.cloudflaredReady = true
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                withContext(NonCancellable) {
                    val cleanupResult = runCatching { stop() }
                    cleanupResult.exceptionOrNull()?.let(e::addSuppressed)
                }
                throw e
            }
        }

        suspend fun waitForUnexpectedExit(): Int =
            coroutineScope {
                val active = running ?: return@coroutineScope 0
                val originExit = async { awaitProcessExit(active.originProcess.process) to "origin" }
                val cloudflaredExit =
                    async {
                        awaitProcessExit(active.cloudflaredProcess.process) to "cloudflared"
                    }
                val (exitCode, source) =
                    select<Pair<Int, String>> {
                        originExit.onAwait { it }
                        cloudflaredExit.onAwait { it }
                    }
                originExit.cancelAndJoin()
                cloudflaredExit.cancelAndJoin()
                if (exitCode != 0) {
                    active.lastFailureClass = "helper_exit"
                    active.lastError = "Cloudflare publish $source exited with code $exitCode"
                }
                exitCode
            }

        suspend fun stop() {
            withContext(NonCancellable + Dispatchers.IO) {
                val active = running
                var stopFailure: Throwable? = null
                buildList {
                    pendingOrigin?.let(::add)
                    active?.let { runningState ->
                        add(runningState.cloudflaredProcess)
                        add(runningState.originProcess)
                    }
                }.distinctBy { it.process }
                    .forEach { process ->
                        runCatching { processSupervisor.stop(process) }
                            .onFailure { error ->
                                if (stopFailure == null) stopFailure = error
                            }
                    }
                runCatching { processSupervisor.stopOutstandingProcesses() }
                    .onFailure { error ->
                        if (stopFailure == null) stopFailure = error
                    }
                stopFailure?.let { throw it }
                cleanupStateDir()
                running = null
                pendingOrigin = null
                sessionActive.set(false)
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

        private suspend fun awaitProcessExit(process: Process): Int =
            withContext(Dispatchers.IO) {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (process.waitFor(CloudflareProcessExitPollMs, TimeUnit.MILLISECONDS)) {
                        return@withContext process.exitValue()
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }

        private fun sanitizeSegment(raw: String): String = raw.replace(Regex("[^a-zA-Z0-9._-]"), "_")

        internal open fun deleteStateDirectory(stateDir: File): Boolean = stateDir.deleteRecursively()

        private companion object {
            const val CloudflareProcessExitPollMs = 100L
        }
    }

class CloudflarePublishRuntime
    @Inject
    constructor(
        private val relayFactory: RipDpiRelayFactory,
        private val publishManager: CloudflarePublishManager,
    ) : RipDpiRelayRuntime {
        @Volatile private var relayRuntime: RipDpiRelayRuntime? = null

        @Volatile private var activeConfig: ResolvedRipDpiRelayConfig? = null
        private var relayStartSignal = CompletableDeferred<RipDpiRelayRuntime>()

        override suspend fun start(config: ResolvedRipDpiRelayConfig): Int =
            coroutineScope {
                activeConfig = config
                relayStartSignal = CompletableDeferred()
                var publishStarted = false
                var relay: RipDpiRelayRuntime? = null
                try {
                    publishManager.start(config)
                    publishStarted = true
                    relay = relayFactory.create()
                    relayRuntime = relay
                    relayStartSignal.complete(relay)
                    val relayExit = async { relay.start(config) }
                    val helperExit = async { publishManager.waitForUnexpectedExit() }
                    select<Int> {
                        relayExit.onAwait { it }
                        helperExit.onAwait { it }
                    }
                } catch (
                    @Suppress("TooGenericExceptionCaught") startupError: Exception,
                ) {
                    if (!relayStartSignal.isCompleted) {
                        relayStartSignal.completeExceptionally(startupError)
                    }
                    throw startupError
                } finally {
                    withContext(NonCancellable) {
                        runCatching { relay?.stop() }
                        if (publishStarted) {
                            runCatching { publishManager.stop() }
                        }
                    }
                    relayRuntime = null
                    activeConfig = null
                }
            }

        override suspend fun awaitReady(timeoutMillis: Long) {
            withTimeout(timeoutMillis) {
                val relay = relayStartSignal.await()
                relay.awaitReady(timeoutMillis)
            }
        }

        override suspend fun stop() {
            withContext(NonCancellable) {
                try {
                    runCatching { relayRuntime?.stop() }
                    publishManager.stop()
                } finally {
                    relayRuntime = null
                    activeConfig = null
                }
            }
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
        private val runtimeProvider: Provider<CloudflarePublishRuntime>,
    ) : CloudflarePublishRuntimeFactory {
        // Each session receives a fresh CloudflarePublishRuntime; its per-session mutable state
        // (relayRuntime, activeConfig, relayStartSignal) never leaks into the next
        // session. The shared @Singleton CloudflarePublishManager remains intentionally
        // process-wide: it is the concurrency gate that rejects overlapping start() calls and
        // resets its own session state (running, activeStateDir, sessionActive) on stop().
        override fun create(): RipDpiRelayRuntime = runtimeProvider.get()
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
