package com.poyka.ripdpi.services

import android.content.Context
import android.os.Build
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayRuntime
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

private const val NaiveProxyBinaryName = "ripdpi-naiveproxy"
private const val NaiveProxyReadyPollIntervalMs = 100L
private const val NaiveProxyReadyTimeoutMs = 5_000L

@Singleton
class NaiveProxyManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        @Volatile private var process: Process? = null

        @Volatile private var config: ResolvedRipDpiRelayConfig? = null

        @Volatile private var lastError: String? = null

        suspend fun start(config: ResolvedRipDpiRelayConfig) {
            withContext(Dispatchers.IO) {
                stopInternal()
                val binary = extractBinary()
                val command =
                    buildList {
                        add(binary.absolutePath)
                        add("--listen")
                        add("${config.localSocksHost}:${config.localSocksPort}")
                        add("--server")
                        add("${config.server}:${config.serverPort}")
                        add("--server-name")
                        add(config.serverName)
                        config.naiveUsername?.let {
                            add("--username")
                            add(it)
                        }
                        config.naivePassword?.let {
                            add("--password")
                            add(it)
                        }
                        config.naivePath.takeIf(String::isNotBlank)?.let {
                            add("--path")
                            add(it)
                        }
                    }

                process = ProcessBuilder(command).redirectErrorStream(true).start()
                this@NaiveProxyManager.config = config
                lastError = null
            }

            waitUntilReady(config)
        }

        suspend fun waitForExit(): Int =
            withContext(Dispatchers.IO) {
                process?.waitFor() ?: 0
            }

        suspend fun pollTelemetry(): NativeRuntimeSnapshot =
            withContext(Dispatchers.IO) {
                val activeConfig = config
                val running = isRunning()
                NativeRuntimeSnapshot(
                    source = "relay",
                    state = if (running) "running" else "idle",
                    health = if (running) "running" else "idle",
                    listenerAddress = activeConfig?.let { "${it.localSocksHost}:${it.localSocksPort}" },
                    upstreamAddress = activeConfig?.let { "${it.server}:${it.serverPort}" },
                    profileId = activeConfig?.profileId,
                    protocolKind = activeConfig?.kind,
                    tcpCapable = true,
                    udpCapable = false,
                    lastError = lastError,
                )
            }

        suspend fun stop() {
            withContext(Dispatchers.IO) {
                stopInternal()
            }
        }

        private fun stopInternal() {
            val activeProcess = process ?: return
            process = null
            config = null
            try {
                activeProcess.destroy()
                runCatching { activeProcess.waitFor() }
                if (runCatching { activeProcess.exitValue() }.isFailure) {
                    activeProcess.destroyForcibly()
                }
            } catch (error: IOException) {
                lastError = error.message
            } catch (error: SecurityException) {
                lastError = error.message
            }
        }

        private fun isRunning(): Boolean {
            val activeProcess = process ?: return false
            return runCatching {
                activeProcess.exitValue()
                false
            }.getOrDefault(true)
        }

        private suspend fun waitUntilReady(config: ResolvedRipDpiRelayConfig) {
            val deadline = System.currentTimeMillis() + NaiveProxyReadyTimeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (!isRunning()) {
                    lastError = "NaiveProxy subprocess exited before readiness"
                    error(lastError ?: "NaiveProxy subprocess exited")
                }
                if (canConnect(config.localSocksHost, config.localSocksPort)) {
                    return
                }
                delay(NaiveProxyReadyPollIntervalMs)
            }
            lastError = "NaiveProxy readiness timed out"
            error(lastError ?: "NaiveProxy readiness timed out")
        }

        private fun canConnect(
            host: String,
            port: Int,
        ): Boolean =
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 250)
                }
                true
            }.getOrDefault(false)

        private fun extractBinary(): File {
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val assetPath = "bin/$abi/$NaiveProxyBinaryName"
            val target = File(context.filesDir, NaiveProxyBinaryName)
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            target.setExecutable(true, true)
            return target
        }
    }

class NaiveProxyRuntime
    @Inject
    constructor(
        private val manager: NaiveProxyManager,
    ) : RipDpiRelayRuntime {
        override suspend fun start(config: ResolvedRipDpiRelayConfig): Int {
            manager.start(config)
            return manager.waitForExit()
        }

        override suspend fun awaitReady(timeoutMillis: Long) {
            // `start()` waits for a successful health check before returning.
        }

        override suspend fun stop() {
            manager.stop()
        }

        override suspend fun pollTelemetry(): NativeRuntimeSnapshot = manager.pollTelemetry()
    }

interface NaiveProxyRuntimeFactory {
    fun create(): RipDpiRelayRuntime
}

@Singleton
class DefaultNaiveProxyRuntimeFactory
    @Inject
    constructor(
        private val runtime: NaiveProxyRuntime,
    ) : NaiveProxyRuntimeFactory {
        override fun create(): RipDpiRelayRuntime = runtime
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class NaiveProxyRuntimeFactoryModule {
    @Binds
    @Singleton
    abstract fun bindNaiveProxyRuntimeFactory(factory: DefaultNaiveProxyRuntimeFactory): NaiveProxyRuntimeFactory
}
