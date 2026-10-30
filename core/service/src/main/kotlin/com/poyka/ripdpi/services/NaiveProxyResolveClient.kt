package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RelaySocketProtection
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

private const val ResolveTimeoutMillis = 10_000L
private const val ResolveReapTimeoutMillis = 500L
private const val ResolvedPrefix = "RIPDPI-RESOLVED|"
private const val ResolveErrorPrefix = "RIPDPI-ERROR|"
private const val ResolveErrorMessageIndex = 3
private const val ResolveErrorPartLimit = 4
private const val MaxFailureMessageLength = 200

internal sealed interface NaiveProxyResolveResult {
    data class Resolved(
        val addresses: List<String>,
    ) : NaiveProxyResolveResult

    data class Failed(
        val message: String,
    ) : NaiveProxyResolveResult
}

/** Resolves a proxy hostname through the Rust helper's protected DoH resolver. */
internal fun interface NaiveProxyResolveClient {
    suspend fun resolve(
        binary: NaiveProxyBinaryRef,
        host: String,
        port: Int,
        protection: RelaySocketProtection,
    ): NaiveProxyResolveResult
}

@Singleton
internal class DefaultNaiveProxyResolveClient
    internal constructor(
        private val ioDispatcher: CoroutineDispatcher,
        private val protectPathProvider: () -> String?,
        private val timeoutMillis: Long,
        private val processStarter: (List<String>, Map<String, String>) -> Process = { command, environment ->
            ProcessBuilder(command).redirectErrorStream(true).apply { environment().putAll(environment) }.start()
        },
    ) : NaiveProxyResolveClient {
        @Inject
        constructor(
            dispatchers: AppCoroutineDispatchers,
            protectPathProvider: ActiveProtectSocketPathProvider,
        ) : this(dispatchers.io, protectPathProvider::current, ResolveTimeoutMillis)

        override suspend fun resolve(
            binary: NaiveProxyBinaryRef,
            host: String,
            port: Int,
            protection: RelaySocketProtection,
        ): NaiveProxyResolveResult {
            val environment = mutableMapOf<String, String>()
            if (protection != RelaySocketProtection.Inactive) {
                val path =
                    protectPathProvider()?.takeIf(String::isNotBlank)
                        ?: return NaiveProxyResolveResult.Failed("socket-protection service is unavailable")
                environment[ActiveProtectSocketPathProvider.ENV_VAR] = path
            }
            val command = listOf(binary.absolutePath, "--resolve", host, "--server-port", port.toString())
            return runInterruptible(ioDispatcher) { run(command, environment) }
        }

        private fun run(
            command: List<String>,
            environment: Map<String, String>,
        ): NaiveProxyResolveResult =
            try {
                val process = processStarter(command, environment)
                try {
                    process.outputStream.close()
                    awaitResult(process)
                } finally {
                    terminate(process)
                }
            } catch (_: IOException) {
                NaiveProxyResolveResult.Failed("resolver could not run")
            } catch (_: SecurityException) {
                NaiveProxyResolveResult.Failed("resolver could not run")
            }

        // Drain while the process runs: a full pipe would block it until the timeout.
        private fun awaitResult(process: Process): NaiveProxyResolveResult {
            val output = AtomicReference<String?>(null)
            val reader =
                thread(name = "naive-resolve-output", isDaemon = true) {
                    try {
                        output.set(process.inputStream.bufferedReader().use { it.readText() })
                    } catch (_: IOException) {
                        // Closed by terminate: no output.
                    }
                }
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                return NaiveProxyResolveResult.Failed("resolver timed out")
            }
            reader.join(ResolveReapTimeoutMillis)
            val text = output.get()
            return if (text == null) {
                NaiveProxyResolveResult.Failed("resolver output could not be read")
            } else {
                parse(process.exitValue(), text)
            }
        }

        private fun parse(
            exitCode: Int,
            stdout: String,
        ): NaiveProxyResolveResult {
            val lines = stdout.lines().map(String::trim).filter(String::isNotEmpty)
            if (exitCode != 0) {
                val detail =
                    lines
                        .firstOrNull { it.startsWith(ResolveErrorPrefix) }
                        ?.split('|', limit = ResolveErrorPartLimit)
                        ?.getOrNull(ResolveErrorMessageIndex)
                        ?.take(MaxFailureMessageLength)
                        ?.takeIf(String::isNotBlank)
                return NaiveProxyResolveResult.Failed(detail ?: "resolver exited with code $exitCode")
            }
            val addresses =
                lines
                    .firstOrNull { it.startsWith(ResolvedPrefix) }
                    ?.removePrefix(ResolvedPrefix)
                    ?.split(',')
                    ?.map(String::trim)
                    ?.filter(String::isNotEmpty)
                    .orEmpty()
            return if (addresses.isEmpty()) {
                NaiveProxyResolveResult.Failed("resolver returned no address")
            } else {
                NaiveProxyResolveResult.Resolved(addresses)
            }
        }

        private fun terminate(process: Process) {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(ResolveReapTimeoutMillis, TimeUnit.MILLISECONDS)
            }
            runCatching { process.inputStream.close() }
        }
    }
