package com.poyka.ripdpi.ui.screens.onboarding

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.OnboardingValidationRecoveryKind
import com.poyka.ripdpi.activities.OnboardingValidationState
import com.poyka.ripdpi.activities.OnboardingValidationStep
import com.poyka.ripdpi.activities.displayMessage
import com.poyka.ripdpi.activities.suggestedModeFor
import com.poyka.ripdpi.activities.validationRecoveryKind
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStartupRejectedException
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.displayMessage
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.services.OwnedTlsClientFactory
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceStartRejectionReason
import com.poyka.ripdpi.services.ServiceStartResult
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.measureTimeMillis

private const val ValidationStartTimeoutMs = 15_000L
private const val ValidationStopTimeoutMs = 5_000L
private const val ValidationTrafficTimeoutMs = 10_000L
private const val DefaultProxyPort = 1080
private const val DefaultProxyHost = "127.0.0.1"
private const val HttpSuccessCodeMin = 200
private const val HttpSuccessCodeMax = 399

sealed interface OnboardingValidationResult {
    data class Success(
        val latencyMs: Long,
    ) : OnboardingValidationResult

    data class Failed(
        val reason: String,
        val recoveryKind: OnboardingValidationRecoveryKind = OnboardingValidationRecoveryKind.RETRY,
        val suggestedMode: Mode? = null,
        val failedStep: OnboardingValidationStep? = null,
    ) : OnboardingValidationResult
}

interface OnboardingModeValidationRunner {
    suspend fun validate(
        mode: Mode,
        onProgress: (OnboardingValidationState) -> Unit,
    ): OnboardingValidationResult

    fun stopActiveValidation()

    fun retainActiveValidation()
}

@Singleton
class DefaultOnboardingModeValidationRunner
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val serviceController: ServiceController,
        private val serviceStateStore: ServiceStateStore,
        private val tlsClientFactory: OwnedTlsClientFactory,
        private val stringResolver: StringResolver,
        private val dispatchers: AppCoroutineDispatchers,
    ) : OnboardingModeValidationRunner {
        private var ownedValidationMode: Mode? = null

        override suspend fun validate(
            mode: Mode,
            onProgress: (OnboardingValidationState) -> Unit,
        ): OnboardingValidationResult =
            try {
                ensureSelectedModeRunning(mode = mode, onProgress = onProgress)
                onProgress(OnboardingValidationState.CheckingDns(mode))
                runDnsProbe(mode)
                onProgress(OnboardingValidationState.RunningTrafficCheck(mode))
                val latencyMs = runConnectivityProbe(mode)
                OnboardingValidationResult.Success(latencyMs = latencyMs)
            } catch (e: OnboardingProbeException) {
                Logger.w(e) { "Onboarding validation ${e.step} probe failed for $mode" }
                stopActiveValidation()
                OnboardingValidationResult.Failed(
                    reason = e.reasonMessage,
                    recoveryKind = OnboardingValidationRecoveryKind.RETRY,
                    // A DNS failure is fixed by Change DNS, not by switching mode — keep suggestedMode null.
                    suggestedMode = if (e.step == OnboardingValidationStep.Dns) null else mode.alternateOrNull(),
                    failedStep = e.step,
                )
            } catch (e: TimeoutCancellationException) {
                Logger.w(e) { "Onboarding validation timed out for $mode" }
                stopActiveValidation()
                OnboardingValidationResult.Failed(
                    reason = stringResolver.getString(R.string.onboarding_validation_failed_generic),
                    suggestedMode = mode.alternateOrNull(),
                    failedStep = OnboardingValidationStep.Tunnel,
                )
            } catch (e: CancellationException) {
                stopActiveValidation()
                throw e
            } catch (e: IOException) {
                Logger.w(e) { "Onboarding validation failed for $mode" }
                stopActiveValidation()
                OnboardingValidationResult.Failed(
                    reason = e.message ?: stringResolver.getString(R.string.onboarding_validation_failed_generic),
                    suggestedMode = mode.alternateOrNull(),
                    failedStep = OnboardingValidationStep.Connectivity,
                )
            } catch (e: ImmediateServiceStartRejected) {
                Logger.w { "Onboarding validation start rejected for ${e.mode}: ${e.reason}" }
                stopActiveValidation()
                OnboardingValidationResult.Failed(
                    reason = e.reason.displayMessage(stringResolver),
                    recoveryKind = e.reason.validationRecoveryKind(),
                    suggestedMode = e.reason.suggestedModeFor(e.mode),
                    failedStep = OnboardingValidationStep.Tunnel,
                )
            } catch (e: ServiceStartupRejectedException) {
                Logger.w(e) { "Onboarding validation failed for $mode" }
                stopActiveValidation()
                OnboardingValidationResult.Failed(
                    reason = e.message ?: stringResolver.getString(R.string.onboarding_validation_failed_generic),
                    suggestedMode = mode.alternateOrNull(),
                    failedStep = OnboardingValidationStep.Tunnel,
                )
            }

        override fun stopActiveValidation() {
            if (ownedValidationMode == null) {
                return
            }
            serviceController.stop()
            ownedValidationMode = null
        }

        override fun retainActiveValidation() {
            ownedValidationMode = null
        }

        private suspend fun ensureSelectedModeRunning(
            mode: Mode,
            onProgress: (OnboardingValidationState) -> Unit,
        ) {
            val current = serviceStateStore.status.value
            if (current.first == AppStatus.Running && current.second != mode) {
                serviceController.stop()
                ownedValidationMode = null
                awaitHalted()
            }

            onProgress(OnboardingValidationState.StartingMode(mode))
            val updated = serviceStateStore.status.value
            if (updated.first != AppStatus.Running || updated.second != mode) {
                when (val startResult = serviceController.start(mode)) {
                    is ServiceStartResult.Accepted -> {
                        ownedValidationMode = mode
                    }

                    is ServiceStartResult.Rejected -> {
                        throw ImmediateServiceStartRejected(mode = mode, reason = startResult.reason)
                    }
                }
            }
            awaitRunning(mode)
        }

        private suspend fun awaitRunning(mode: Mode) {
            val current = serviceStateStore.status.value
            if (current.first == AppStatus.Running && current.second == mode) {
                return
            }
            withTimeout(ValidationStartTimeoutMs) {
                merge(
                    serviceStateStore.status
                        .filter { it.first == AppStatus.Running && it.second == mode }
                        .map { Unit },
                    serviceStateStore.events
                        .filterIsInstance<ServiceEvent.Failed>()
                        .map { event -> throw ServiceStartupRejectedException(event.reason) },
                ).first()
            }
        }

        private suspend fun awaitHalted() {
            val current = serviceStateStore.status.value
            if (current.first == AppStatus.Halted) {
                return
            }
            withTimeout(ValidationStopTimeoutMs) {
                serviceStateStore.status
                    .filter { (status, _) -> status == AppStatus.Halted }
                    .first()
            }
        }

        private suspend fun runDnsProbe(mode: Mode) {
            Logger.d { "Onboarding DNS probe starting for $mode" }
            val host = resolveDnsProbeHost()
            try {
                performDnsLookup(host)
            } catch (e: TimeoutCancellationException) {
                // TimeoutCancellationException (a CancellationException subtype) means the DNS step
                // itself timed out — tag it as Dns. A plain CancellationException is intentionally NOT
                // caught here, so genuine cancellation propagates untouched.
                throw dnsProbeFailure(cause = e)
            } catch (e: IOException) {
                // UnknownHostException is an IOException, so this single branch covers both lookup failures.
                throw dnsProbeFailure(cause = e, message = e.message)
            }
        }

        private fun resolveDnsProbeHost(): String =
            runCatching { URI(OnboardingConnectivityCheckUrl).host }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: throw dnsProbeFailure()

        private suspend fun performDnsLookup(host: String) {
            withContext(dispatchers.io) {
                withTimeout(ValidationTrafficTimeoutMs) {
                    if (InetAddress.getAllByName(host).isEmpty()) {
                        throw UnknownHostException(host)
                    }
                }
            }
        }

        private fun dnsProbeFailure(
            cause: Throwable? = null,
            message: String? = null,
        ): OnboardingProbeException =
            OnboardingProbeException(
                step = OnboardingValidationStep.Dns,
                reasonMessage =
                    message?.takeIf { it.isNotBlank() }
                        ?: stringResolver.getString(R.string.onboarding_validation_failed_generic),
                cause = cause,
            )

        private suspend fun runConnectivityProbe(mode: Mode): Long =
            try {
                withContext(dispatchers.io) {
                    runConnectivityProbeBlocking(mode)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw OnboardingProbeException(
                    step = OnboardingValidationStep.Connectivity,
                    reasonMessage =
                        e.message?.takeIf { it.isNotBlank() }
                            ?: stringResolver.getString(R.string.onboarding_validation_failed_generic),
                    cause = e,
                )
            }

        private suspend fun runConnectivityProbeBlocking(mode: Mode): Long {
            val settings = appSettingsRepository.snapshot()
            val request =
                Request
                    .Builder()
                    .url(OnboardingConnectivityCheckUrl)
                    .head()
                    .build()
            val client =
                tlsClientFactory.create {
                    connectTimeout(ValidationTrafficTimeoutMs, TimeUnit.MILLISECONDS)
                    readTimeout(ValidationTrafficTimeoutMs, TimeUnit.MILLISECONDS)
                    callTimeout(ValidationTrafficTimeoutMs, TimeUnit.MILLISECONDS)
                    followRedirects(false)
                    if (mode == Mode.Proxy) {
                        proxy(
                            Proxy(
                                Proxy.Type.SOCKS,
                                InetSocketAddress(
                                    settings.proxyIp.ifBlank { DefaultProxyHost },
                                    settings.proxyPort.takeIf { it > 0 } ?: DefaultProxyPort,
                                ),
                            ),
                        )
                    }
                }
            return runCatching {
                var responseCode = 0
                val elapsed =
                    measureTimeMillis {
                        client.newCall(request).execute().use { response ->
                            responseCode = response.code
                        }
                    }
                elapsed.also {
                    if (responseCode !in HttpSuccessCodeMin..HttpSuccessCodeMax) {
                        throwProbeFailure(IOException("HTTP $responseCode"))
                    }
                }
            }.getOrElse { e -> throwProbeFailure(e) }
        }

        private fun throwProbeFailure(e: Throwable): Nothing {
            if (e is CancellationException) throw e
            val message =
                when (e) {
                    is ServiceStartupRejectedException -> {
                        e.reason.displayMessage
                    }

                    else -> {
                        e.message?.takeIf { it.isNotBlank() }
                            ?: stringResolver.getString(R.string.onboarding_validation_failed_generic)
                    }
                }
            throw IOException(message, e)
        }
    }

private class OnboardingProbeException(
    val step: OnboardingValidationStep,
    val reasonMessage: String,
    cause: Throwable? = null,
) : RuntimeException(reasonMessage, cause)

private class ImmediateServiceStartRejected(
    val mode: Mode,
    val reason: ServiceStartRejectionReason,
) : RuntimeException()

private fun Mode.alternateOrNull(): Mode? =
    when (this) {
        Mode.VPN -> Mode.Proxy
        Mode.Proxy -> Mode.VPN
    }

private const val OnboardingConnectivityCheckUrl = "https://connectivitycheck.gstatic.com/generate_204"

@Module
@InstallIn(SingletonComponent::class)
abstract class OnboardingModeValidationRunnerModule {
    @Binds
    @Singleton
    abstract fun bindOnboardingModeValidationRunner(
        runner: DefaultOnboardingModeValidationRunner,
    ): OnboardingModeValidationRunner
}
