package com.poyka.ripdpi.services

import android.content.Context
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.localSocksCredentialsOrNull
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

internal const val NaiveUpstreamBinaryName = "ripdpi-naive-chromium"
internal const val NaiveRustHelperBinaryName = "ripdpi-naiveproxy"
internal const val NaiveUpstreamVersionPrefix = "naive 154.0.8037.49"
private const val NaiveUpstreamVersionTimeoutMillis = 5_000L

/** Prints the first line of `<exe> --version`, or null when it cannot run. */
internal fun interface NaiveUpstreamVersionProbe {
    suspend fun versionLine(binary: NaiveProxyBinaryRef): String?
}

@Singleton
internal class DefaultNaiveUpstreamVersionProbe
    internal constructor(
        private val ioDispatcher: CoroutineDispatcher,
        private val timeoutMillis: Long,
    ) : NaiveUpstreamVersionProbe {
        @Inject
        constructor(dispatchers: AppCoroutineDispatchers) : this(dispatchers.io, NaiveUpstreamVersionTimeoutMillis)

        override suspend fun versionLine(binary: NaiveProxyBinaryRef): String? =
            runInterruptible(ioDispatcher) {
                try {
                    val process = ProcessBuilder(binary.absolutePath, "--version").redirectErrorStream(true).start()
                    try {
                        process.outputStream.close()
                        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                            null
                        } else {
                            process.inputStream
                                .bufferedReader()
                                .use { it.readLine() }
                                ?.trim()
                        }
                    } finally {
                        process.destroyForcibly()
                    }
                } catch (_: IOException) {
                    null
                }
            }
    }

internal fun isPinnedUpstreamVersion(line: String?): Boolean {
    val rest = line?.takeIf { it.startsWith(NaiveUpstreamVersionPrefix) }?.removePrefix(NaiveUpstreamVersionPrefix)
    // "naive 154.0.8037.491" is another release, not the pinned one.
    return rest != null && (rest.isEmpty() || !(rest[0].isDigit() || rest[0] == '.'))
}

/** Starts the pinned upstream Chromium client for a profile without a server certificate pin. */
@Singleton
internal class NaiveUpstreamLauncher
    internal constructor(
        private val launchDelegate: NaiveProxyLaunchDelegate,
        private val resolveClient: NaiveProxyResolveClient,
        private val versionProbe: NaiveUpstreamVersionProbe,
        private val configStore: NaiveUpstreamConfigStore,
    ) {
        @Inject
        constructor(
            subprocessManager: SubprocessSocksRelayManager,
            resolveClient: DefaultNaiveProxyResolveClient,
            versionProbe: DefaultNaiveUpstreamVersionProbe,
            @ApplicationContext context: Context,
        ) : this(
            DefaultNaiveProxyLaunchDelegate(subprocessManager),
            resolveClient,
            versionProbe,
            NaiveUpstreamConfigStore { context.noBackupFilesDir },
        )

        @Volatile private var activeConfigFile: File? = null

        suspend fun start(config: ResolvedRipDpiRelayConfig) {
            val prelaunchSpec = launchSpec(config, emptyList(), emptyList())
            if (config.outboundBindIp.isNotBlank()) {
                reject(config, prelaunchSpec, "NaiveProxy does not support an outbound bind IP", "config")
            }
            val binary = launchDelegate.resolveBinary(NaiveUpstreamBinaryName)
            val versionLine = versionProbe.versionLine(binary)
            if (!isPinnedUpstreamVersion(versionLine)) {
                reject(
                    config,
                    prelaunchSpec,
                    "NaiveProxy upstream client is not the pinned release ($NaiveUpstreamVersionPrefix)",
                    "relay_compatibility",
                )
            }
            val values = configValues(config, mappedAddress(config, prelaunchSpec))
            val redactions = naiveUpstreamRedactions(values) + naiveExtraHeaderRedactions(config.naiveExtraHeaders)
            val file =
                try {
                    configStore.write(naiveUpstreamConfigJson(values))
                } catch (_: IOException) {
                    reject(config, prelaunchSpec, "NaiveProxy upstream config could not be written", "config")
                }
            activeConfigFile = file
            try {
                launchDelegate.start(binary, config, launchSpec(config, listOf(file.absolutePath), redactions))
            } finally {
                // Upstream reads the file at startup only: readiness has passed or the start failed.
                cleanup()
            }
        }

        /** Idempotent; also called on stop. */
        fun cleanup() {
            val file = activeConfigFile
            activeConfigFile = null
            configStore.delete(file)
        }

        private suspend fun mappedAddress(
            config: ResolvedRipDpiRelayConfig,
            spec: SubprocessSocksRelayLaunchSpec,
        ): String? {
            val proxyHost = proxyHost(config)
            if (isIpLiteralHost(config.server)) {
                return config.server.takeUnless { isIpLiteralHost(proxyHost) }
            }
            val helper = launchDelegate.resolveBinary(NaiveRustHelperBinaryName)
            val result = resolveClient.resolve(helper, config.server, config.serverPort, config.socketProtection)
            return when (result) {
                is NaiveProxyResolveResult.Resolved -> {
                    result.addresses.first()
                }

                is NaiveProxyResolveResult.Failed -> {
                    reject(config, spec, "NaiveProxy protected DNS resolution failed: ${result.message}", "dns")
                }
            }
        }

        // The URI host carries SNI and certificate verification; MAP points it at the dialed address.
        private fun proxyHost(config: ResolvedRipDpiRelayConfig): String =
            config.serverName.takeIf { it.isNotBlank() && !isIpLiteralHost(it) } ?: config.server

        private fun configValues(
            config: ResolvedRipDpiRelayConfig,
            mappedAddress: String?,
        ): NaiveUpstreamConfigValues {
            val local =
                requireNotNull(localSocksCredentialsOrNull(config.localSocksUsername, config.localSocksPassword)) {
                    "NaiveProxy requires local listener credentials"
                }
            return NaiveUpstreamConfigValues(
                proxyHost = proxyHost(config),
                proxyPort = config.serverPort,
                proxyUsername = config.naiveUsername.orEmpty(),
                proxyPassword = config.naivePassword.orEmpty(),
                localUsername = local.username,
                localPassword = local.password,
                localHost = config.localSocksHost,
                localPort = config.localSocksPort,
                extraHeaders = config.naiveExtraHeaders,
                mappedAddress = mappedAddress?.takeIf { !isIpLiteralHost(proxyHost(config)) },
            )
        }

        private fun launchSpec(
            config: ResolvedRipDpiRelayConfig,
            arguments: List<String>,
            redactions: List<String>,
        ) = SubprocessSocksRelayLaunchSpec(
            binaryName = NaiveUpstreamBinaryName,
            versionArguments = listOf("--version"),
            runtimeKind = config.kind,
            upstreamAddress = "${config.server}:${config.serverPort}",
            redactedValues = redactions,
            commandArguments = arguments,
            protectionCapability = SubprocessSocketProtectionCapability.OwnUidExcluded,
        )

        private fun reject(
            config: ResolvedRipDpiRelayConfig,
            spec: SubprocessSocksRelayLaunchSpec,
            message: String,
            failureClass: String,
        ): Nothing {
            launchDelegate.notePrelaunchFailure(config, spec, failureClass, message)
            throw NaiveProxyCompatibilityException(message)
        }
    }
