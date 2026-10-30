package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayRuntime
import com.poyka.ripdpi.core.localSocksCredentialsOrNull
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.NaiveExtraHeadersResult
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceStartupRejectedException
import com.poyka.ripdpi.data.parseNaiveExtraHeaders
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
private const val NaiveProxyRestartBudgetWindowMs = 60_000L
private const val NaiveProxyRestartBudgetMaxAttempts = 3
private const val NaiveProxyProbeFailureClass = "relay_compatibility"
private val NaiveProxySupportedProbeSchemas = 1..1
private const val NaiveProxyLocalAuthFeature = "local-listener-auth"

internal data class NaiveProxyRestartDecision(
    val shouldRestart: Boolean,
    val delayMillis: Long = NaiveProxyRestartDelayMs,
    val reasonLabel: String = "unexpected_exit",
)

/**
 * The helper exits only on a startup failure, which `start()` already throws, or on
 * a crash. Upstream failures after READY belong to single client streams, so they do
 * not decide whether a crashed helper restarts; the restart budget bounds retries.
 */
internal fun naiveProxyRestartDecision(exitCode: Int): NaiveProxyRestartDecision =
    if (exitCode == 0) {
        NaiveProxyRestartDecision(shouldRestart = false, reasonLabel = "clean_exit")
    } else {
        NaiveProxyRestartDecision(shouldRestart = true)
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
        add(NaiveProxyCredentialsStdinFlag)
        config.naivePath.takeIf(String::isNotBlank)?.let {
            add("--path")
            add(it)
        }
    }

private fun naiveExtraHeaderRedactions(headers: String): List<String> =
    when (val parsed = parseNaiveExtraHeaders(headers)) {
        is NaiveExtraHeadersResult.Valid -> parsed.values
        is NaiveExtraHeadersResult.Invalid -> listOf(headers).filter(String::isNotBlank)
    }

internal fun naiveProxyCredentialsStdin(config: ResolvedRipDpiRelayConfig): ByteArray {
    val localCredentials =
        requireNotNull(localSocksCredentialsOrNull(config.localSocksUsername, config.localSocksPassword)) {
            "NaiveProxy requires local listener credentials"
        }
    val encoder = Base64.getEncoder()
    return buildString {
        append(encoder.encodeToString(config.naiveUsername.orEmpty().toByteArray(Charsets.UTF_8)))
        append('\n')
        append(encoder.encodeToString(config.naivePassword.orEmpty().toByteArray(Charsets.UTF_8)))
        append('\n')
        append(encoder.encodeToString(localCredentials.username.toByteArray(Charsets.UTF_8)))
        append('\n')
        append(encoder.encodeToString(localCredentials.password.toByteArray(Charsets.UTF_8)))
        append('\n')
        append(encoder.encodeToString(config.naiveExtraHeaders.toByteArray(Charsets.UTF_8)))
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
                    redactedValues =
                        listOfNotNull(
                            config.naiveUsername,
                            config.naivePassword,
                            config.localSocksUsername,
                            config.localSocksPassword,
                        ) + naiveExtraHeaderRedactions(config.naiveExtraHeaders),
                    commandArguments = naiveProxyCommandArguments(config),
                    standardInput = naiveProxyCredentialsStdin(config),
                )
            if (config.outboundBindIp.isNotBlank()) {
                // The helper cannot bind a source address; refuse instead of dialing from another one.
                rejectPrelaunch(
                    config = config,
                    spec = spec,
                    message = "NaiveProxy does not support an outbound bind IP",
                    failureClass = "config",
                )
            }
            val binary = launchDelegate.resolveBinary(NaiveProxyBinaryName)
            when (val result = preflightProbe.run(binary)) {
                is NaiveProxyPreflightResult.Probed -> {
                    if (!result.probe.isSchemaSupported(NaiveProxySupportedProbeSchemas)) {
                        rejectPrelaunch(
                            config = config,
                            spec = spec,
                            message = "unsupported NaiveProxy probe schema_version ${result.probe.schemaVersion}",
                        )
                    }
                    if (NaiveProxyLocalAuthFeature !in result.probe.features) {
                        rejectPrelaunch(
                            config = config,
                            spec = spec,
                            message = "NaiveProxy helper does not support local listener authentication",
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
            failureClass: String = NaiveProxyProbeFailureClass,
        ): Nothing {
            launchDelegate.notePrelaunchFailure(
                config = config,
                spec = spec,
                failureClass = failureClass,
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
    fun resolveBinary(binaryName: String): NaiveProxyBinaryRef

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
    override fun resolveBinary(binaryName: String): NaiveProxyBinaryRef =
        subprocessManager.resolveHelperBinary(binaryName).asNaiveProxyBinaryRef()

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
                val restartDecision = naiveProxyRestartDecision(exitCode)
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
