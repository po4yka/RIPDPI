package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.LocalSocksCredentials
import com.poyka.ripdpi.core.RelaySocketProtection
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayProfileRecord
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// The helper stops its own CONNECT, DNS lookup included, after 15 s; this adds process start and exit.
private const val NaiveProxyCheckTimeoutMillis = 25_000L
private const val NaiveProxyCheckFlag = "--check"
private const val NaiveProxyCheckLinePrefix = "RIPDPI-CHECK|naiveproxy|"

enum class NaiveProxyCheckOutcome {
    /** The server answered without a 407. A probe-resistant server also answers a wrong password so. */
    Ok,
    Auth,
    Tls,
    Dns,
    Connect,
    Timeout,

    /** The profile is incomplete or the helper could not run. */
    Failed,
}

internal fun parseNaiveProxyCheckLine(line: String?): NaiveProxyCheckOutcome =
    when (line?.trim()?.takeIf { it.startsWith(NaiveProxyCheckLinePrefix) }?.removePrefix(NaiveProxyCheckLinePrefix)) {
        "ok" -> NaiveProxyCheckOutcome.Ok
        "auth" -> NaiveProxyCheckOutcome.Auth
        "tls" -> NaiveProxyCheckOutcome.Tls
        "dns" -> NaiveProxyCheckOutcome.Dns
        "connect" -> NaiveProxyCheckOutcome.Connect
        "timeout" -> NaiveProxyCheckOutcome.Timeout
        else -> NaiveProxyCheckOutcome.Failed
    }

/** Checks an unsaved NaiveProxy profile from the editor without starting a relay. */
fun interface NaiveProxyProfileCheck {
    suspend fun check(
        profile: RelayProfileRecord,
        credentials: RelayCredentialRecord,
    ): NaiveProxyCheckOutcome
}

/**
 * Checks an unsaved NaiveProxy profile without starting a relay. The Rust helper opens one CONNECT
 * to a reserved `.invalid` name through the server, so only the server sees traffic. A pin-free
 * profile is checked with the helper's TLS stack, not the upstream client. Credentials travel on
 * stdin, as for a relay start.
 */
@Singleton
internal class NaiveProxyProfileChecker internal constructor(
    private val prepare: suspend (RelayProfileRecord, RelayCredentialRecord) -> ResolvedRipDpiRelayConfig,
    private val binary: () -> File,
    private val ioDispatcher: CoroutineDispatcher,
    private val timeoutMillis: Long = NaiveProxyCheckTimeoutMillis,
    private val protectPath: () -> String? = { null },
) : NaiveProxyProfileCheck {
    @Inject
    constructor(
        configuration: CandidateRelayProbeConfiguration,
        subprocessManager: SubprocessSocksRelayManager,
        dispatchers: AppCoroutineDispatchers,
        protection: ActiveProtectSocketPathProvider,
    ) : this(
        prepare = { profile, credentials ->
            val environment = configuration.capture()
            configuration.prepare(profile, credentials, environment).withNetworkMode(
                if (environment.vpnProtectionRequired) RelayRuntimeNetworkMode.Vpn else RelayRuntimeNetworkMode.Proxy,
            )
        },
        binary = { subprocessManager.resolveHelperBinary(NaiveRustHelperBinaryName) },
        ioDispatcher = dispatchers.io,
        protectPath = protection::current,
    )

    override suspend fun check(
        profile: RelayProfileRecord,
        credentials: RelayCredentialRecord,
    ): NaiveProxyCheckOutcome {
        val config =
            try {
                prepare(profile, credentials)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return NaiveProxyCheckOutcome.Failed
            }
        val capturedPath = protectPath()?.takeIf(String::isNotBlank)
        return if ((config.socketProtection == RelaySocketProtection.VpnRequired) != (capturedPath != null)) {
            NaiveProxyCheckOutcome.Failed
        } else {
            // The check opens no listener, but the helper reads listener credentials like a relay start.
            val listener = LocalSocksCredentials.generate()
            val checkConfig =
                config.copy(
                    localSocksUsername = listener.username,
                    localSocksPassword = listener.password,
                )
            runInterruptible(ioDispatcher) {
                try {
                    val command =
                        listOf(binary().absolutePath, NaiveProxyCheckFlag) + naiveProxyCommandArguments(checkConfig)
                    runCheck(command, checkConfig, capturedPath)
                } catch (error: InterruptedException) {
                    throw error
                } catch (_: Exception) {
                    // A missing helper (IllegalStateException), a start failure or a pipe error.
                    NaiveProxyCheckOutcome.Failed
                }
            }
        }
    }

    private fun runCheck(
        command: List<String>,
        config: ResolvedRipDpiRelayConfig,
        capturedPath: String?,
    ): NaiveProxyCheckOutcome {
        check(protectPath() == capturedPath) { "VPN protection changed before the profile check" }
        // Stdout carries only the result line; stderr is dropped so that it cannot fill a pipe.
        val process =
            ProcessBuilder(command)
                .apply {
                    environment().remove(ActiveProtectSocketPathProvider.ENV_VAR)
                    capturedPath?.let { environment()[ActiveProtectSocketPathProvider.ENV_VAR] = it }
                    redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
                }.start()
        try {
            check(protectPath() == capturedPath) { "VPN protection changed after the profile check started" }
            process.outputStream.use { it.write(naiveProxyCredentialsStdin(config)) }
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) return NaiveProxyCheckOutcome.Timeout
            val line =
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.firstOrNull { it.startsWith(NaiveProxyCheckLinePrefix) }
                }
            return if (protectPath() == capturedPath) parseNaiveProxyCheckLine(line) else NaiveProxyCheckOutcome.Failed
        } finally {
            process.destroyForcibly()
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class NaiveProxyProfileCheckModule {
    @Binds
    abstract fun bindNaiveProxyProfileCheck(checker: NaiveProxyProfileChecker): NaiveProxyProfileCheck
}
