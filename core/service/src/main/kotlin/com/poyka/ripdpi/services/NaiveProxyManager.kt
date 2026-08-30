package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayRuntime
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceStartupRejectedException
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import java.io.File
import java.util.Base64
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private const val NaiveProxyBinaryName = "ripdpi-naiveproxy"
private const val NaiveProxyCredentialsStdinFlag = "--credentials-stdin"
private const val NaiveProxyRestartDelayMs = 750L
private const val NaiveProxyDnsRestartDelayMs = 1_500L
private const val NaiveProxyRestartBudgetWindowMs = 60_000L
private const val NaiveProxyRestartBudgetMaxAttempts = 3
private const val NaiveProxyProbeFailureClass = "relay_compatibility"
private val NaiveProxySupportedProbeSchemas = 1..1

internal data class NaiveProxyRestartDecision(
    val shouldRestart: Boolean,
    val delayMillis: Long = NaiveProxyRestartDelayMs,
    val reasonLabel: String = "unexpected_exit",
)

internal fun naiveProxyRestartDecision(
    exitCode: Int,
    lastFailureClass: String?,
): NaiveProxyRestartDecision {
    if (exitCode == 0) {
        return NaiveProxyRestartDecision(shouldRestart = false, reasonLabel = "clean_exit")
    }
    return when (lastFailureClass?.trim()?.lowercase()) {
        "auth" -> NaiveProxyRestartDecision(false, reasonLabel = "auth")
        "http_connect" -> NaiveProxyRestartDecision(false, reasonLabel = "http_connect")
        "tls" -> NaiveProxyRestartDecision(false, reasonLabel = "tls")
        "config" -> NaiveProxyRestartDecision(false, reasonLabel = "config")
        "dns" -> NaiveProxyRestartDecision(true, delayMillis = NaiveProxyDnsRestartDelayMs, reasonLabel = "dns")
        "connect" -> NaiveProxyRestartDecision(true, reasonLabel = "connect")
        "runtime" -> NaiveProxyRestartDecision(true, reasonLabel = "runtime")
        "helper_exit" -> NaiveProxyRestartDecision(true, reasonLabel = "helper_exit")
        else -> NaiveProxyRestartDecision(true, reasonLabel = "unexpected_exit")
    }
}

internal fun naiveProxyCommandArguments(config: ResolvedRipDpiRelayConfig): List<String> =
    buildList {
        add("--listen")
        add("${config.localSocksHost}:${config.localSocksPort}")
        add("--server")
        add(config.server)
        add("--server-port")
        add(config.serverPort.toString())
        add("--server-name")
        add(config.serverName)
        if (config.naiveUsername != null || config.naivePassword != null) {
            add(NaiveProxyCredentialsStdinFlag)
        }
        config.naivePath.takeIf(String::isNotBlank)?.let {
            add("--path")
            add(it)
        }
    }

internal fun naiveProxyCredentialsStdin(config: ResolvedRipDpiRelayConfig): ByteArray? {
    if (config.naiveUsername == null && config.naivePassword == null) {
        return null
    }
    val encoder = Base64.getEncoder()
    return buildString {
        append(encoder.encodeToString(config.naiveUsername.orEmpty().toByteArray(Charsets.UTF_8)))
        append('\n')
        append(encoder.encodeToString(config.naivePassword.orEmpty().toByteArray(Charsets.UTF_8)))
        append('\n')
    }.toByteArray(Charsets.UTF_8)
}

@Singleton
open class NaiveProxyManager
    internal constructor(
        private val launchDelegate: NaiveProxyLaunchDelegate,
        private val preflightProbe: NaiveProxyPreflightProbe,
    ) {
        @Inject
        internal constructor(
            subprocessManager: SubprocessSocksRelayManager,
            preflightProbe: DefaultNaiveProxyPreflightProbe,
        ) : this(DefaultNaiveProxyLaunchDelegate(subprocessManager), preflightProbe)

        open suspend fun start(config: ResolvedRipDpiRelayConfig) {
            val spec =
                SubprocessSocksRelayLaunchSpec(
                    binaryName = NaiveProxyBinaryName,
                    versionArguments = listOf("--version"),
                    runtimeKind = config.kind,
                    upstreamAddress = "${config.server}:${config.serverPort}",
                    redactedValues = listOfNotNull(config.naiveUsername, config.naivePassword),
                    commandArguments = naiveProxyCommandArguments(config),
                    standardInput = naiveProxyCredentialsStdin(config),
                )
            val binary = launchDelegate.extractBinary(NaiveProxyBinaryName)
            when (val result = preflightProbe.run(binary)) {
                is NaiveProxyPreflightResult.Probed -> {
                    if (!result.probe.isSchemaSupported(NaiveProxySupportedProbeSchemas)) {
                        rejectPrelaunch(
                            config = config,
                            spec = spec,
                            message = "unsupported NaiveProxy probe schema_version ${result.probe.schemaVersion}",
                        )
                    }
                }

                is NaiveProxyPreflightResult.Rejected -> {
                    rejectPrelaunch(config = config, spec = spec, message = result.message)
                }
            }
            launchDelegate.start(
                binary = binary,
                config = config,
                spec = spec,
            )
        }

        private fun rejectPrelaunch(
            config: ResolvedRipDpiRelayConfig,
            spec: SubprocessSocksRelayLaunchSpec,
            message: String,
        ): Nothing {
            launchDelegate.notePrelaunchFailure(
                config = config,
                spec = spec,
                failureClass = NaiveProxyProbeFailureClass,
                message = message,
            )
            throw NaiveProxyCompatibilityException(message)
        }

        open suspend fun waitForExit(): Int = launchDelegate.waitForExit()

        open suspend fun pollTelemetry(): NativeRuntimeSnapshot = launchDelegate.pollTelemetry()

        open fun noteRestarting(reason: String) = launchDelegate.noteRestarting(reason)

        open suspend fun stop() = launchDelegate.stop()
    }

internal class NaiveProxyCompatibilityException(
    message: String,
) : ServiceStartupRejectedException(FailureReason.RelayConfigRejected(message))

internal interface NaiveProxyLaunchDelegate {
    fun extractBinary(binaryName: String): NaiveProxyBinaryRef

    suspend fun start(
        binary: NaiveProxyBinaryRef,
        config: ResolvedRipDpiRelayConfig,
        spec: SubprocessSocksRelayLaunchSpec,
    )

    suspend fun waitForExit(): Int

    suspend fun pollTelemetry(): NativeRuntimeSnapshot

    fun noteRestarting(reason: String)

    fun notePrelaunchFailure(
        config: ResolvedRipDpiRelayConfig,
        spec: SubprocessSocksRelayLaunchSpec,
        failureClass: String,
        message: String,
    )

    suspend fun stop()
}

internal class DefaultNaiveProxyLaunchDelegate(
    private val subprocessManager: SubprocessSocksRelayManager,
) : NaiveProxyLaunchDelegate {
    override fun extractBinary(binaryName: String): NaiveProxyBinaryRef =
        subprocessManager.extractBinary(binaryName).asNaiveProxyBinaryRef()

    override suspend fun start(
        binary: NaiveProxyBinaryRef,
        config: ResolvedRipDpiRelayConfig,
        spec: SubprocessSocksRelayLaunchSpec,
    ) = subprocessManager.start(config, spec, preparedBinary = File(binary.absolutePath))

    override suspend fun waitForExit(): Int = subprocessManager.waitForExit()

    override suspend fun pollTelemetry(): NativeRuntimeSnapshot = subprocessManager.pollTelemetry()

    override fun noteRestarting(reason: String) = subprocessManager.noteRestarting(reason)

    override fun notePrelaunchFailure(
        config: ResolvedRipDpiRelayConfig,
        spec: SubprocessSocksRelayLaunchSpec,
        failureClass: String,
        message: String,
    ) = subprocessManager.notePrelaunchFailure(config, spec, failureClass, message)

    override suspend fun stop() = subprocessManager.stop()
}

class NaiveProxyRuntime
    @Inject
    constructor(
        private val manager: NaiveProxyManager,
    ) : RipDpiRelayRuntime {
        @Volatile private var stopping = false

        // Completed once the first subprocess launch passes its readiness probe, or
        // completed exceptionally if that first launch fails. `awaitReady()` blocks on
        // this so the supervisor only reports Connected after the subprocess has bound.
        @Volatile private var readySignal = CompletableDeferred<Unit>()

        @Suppress("detekt.ReturnCount")
        override suspend fun start(config: ResolvedRipDpiRelayConfig): Int {
            stopping = false
            val signal = CompletableDeferred<Unit>()
            readySignal = signal
            val restartAttempts = ArrayDeque<Long>()
            while (true) {
                if (stopping) {
                    return 0
                }
                if (signal.isCompleted) {
                    manager.start(config)
                } else {
                    try {
                        manager.start(config)
                    } catch (
                        @Suppress("TooGenericExceptionCaught") readinessError: Exception,
                    ) {
                        signal.completeExceptionally(readinessError)
                        throw readinessError
                    }
                    signal.complete(Unit)
                }
                val exitCode = manager.waitForExit()
                if (stopping) {
                    return exitCode
                }
                val restartDecision = naiveProxyRestartDecision(exitCode, manager.pollTelemetry().lastFailureClass)
                if (!restartDecision.shouldRestart) {
                    return exitCode
                }
                val now = System.currentTimeMillis()
                while (restartAttempts.isNotEmpty() &&
                    now - restartAttempts.first() > NaiveProxyRestartBudgetWindowMs
                ) {
                    restartAttempts.removeFirst()
                }
                if (restartAttempts.size >= NaiveProxyRestartBudgetMaxAttempts) {
                    return exitCode
                }
                restartAttempts.addLast(now)
                manager.noteRestarting(
                    reason =
                        "NaiveProxy exited with code $exitCode; " +
                            "restarting ${restartAttempts.size}/$NaiveProxyRestartBudgetMaxAttempts " +
                            "after ${restartDecision.reasonLabel}",
                )
                delay(restartDecision.delayMillis)
                if (stopping) {
                    return exitCode
                }
            }
        }

        override suspend fun awaitReady(timeoutMillis: Long) {
            // Block until the first subprocess launch passes its readiness probe (set in
            // `start()`); a readiness failure is rethrown here so the supervisor fails honestly.
            readySignal.await()
        }

        override suspend fun stop() {
            stopping = true
            readySignal.cancel(CancellationException("NaiveProxy runtime stopped before readiness"))
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
        private val runtimeProvider: Provider<NaiveProxyRuntime>,
    ) : NaiveProxyRuntimeFactory {
        override fun create(): RipDpiRelayRuntime = runtimeProvider.get()
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class NaiveProxyRuntimeFactoryModule {
    @Binds
    @Singleton
    abstract fun bindNaiveProxyRuntimeFactory(factory: DefaultNaiveProxyRuntimeFactory): NaiveProxyRuntimeFactory
}
