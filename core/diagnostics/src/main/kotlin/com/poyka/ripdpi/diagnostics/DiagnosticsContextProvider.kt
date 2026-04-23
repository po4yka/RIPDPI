package com.poyka.ripdpi.diagnostics

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.RuntimeTelemetryStatus
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.diagnostics.DiagnosticsProfileCatalog
import com.poyka.ripdpi.data.effectiveChainSummary
import com.poyka.ripdpi.data.effectiveTcpChainSteps
import com.poyka.ripdpi.data.primaryDesyncMethod
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

interface DiagnosticsContextProvider {
    suspend fun captureContext(): DiagnosticContextModel
}

@Singleton
class AndroidDiagnosticsContextProvider
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val appSettingsRepository: AppSettingsRepository,
        private val profileCatalog: DiagnosticsProfileCatalog,
        private val serviceStateStore: ServiceStateStore,
    ) : DiagnosticsContextProvider {
        private val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        private val powerManager =
            context.getSystemService(Context.POWER_SERVICE) as PowerManager

        @SuppressLint("MissingPermission")
        override suspend fun captureContext(): DiagnosticContextModel {
            val settings = appSettingsRepository.snapshot()
            val profile =
                settings.diagnosticsActiveProfileId
                    .takeIf { it.isNotBlank() }
                    ?.let { profileCatalog.getProfile(it) }
            val serviceStatus = serviceStateStore.status.value.first
            val activeMode = serviceStateStore.status.value.second
            val telemetry = serviceStateStore.telemetry.value
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val tcpSteps = settings.effectiveTcpChainSteps()
            val lastNativeError = relevantLastNativeError(serviceStatus, telemetry)
            return DiagnosticContextModel(
                service =
                    buildServiceContext(
                        settings,
                        profile,
                        serviceStatus,
                        activeMode,
                        telemetry,
                        tcpSteps,
                        lastNativeError,
                    ),
                permissions =
                    PermissionContextModel(
                        vpnPermissionState = booleanState(VpnService.prepare(context) == null),
                        notificationPermissionState = notificationPermissionState(),
                        batteryOptimizationState = batteryOptimizationState(),
                        dataSaverState = dataSaverState(),
                    ),
                device = buildDeviceContext(packageInfo),
                environment =
                    EnvironmentContextModel(
                        batterySaverState = booleanState(powerManager.isPowerSaveMode),
                        powerSaveModeState = booleanState(powerManager.isPowerSaveMode),
                        networkMeteredState = booleanState(connectivityManager.isActiveNetworkMetered),
                        roamingState = roamingState(),
                    ),
            )
        }

        @Suppress("LongParameterList")
        private fun buildServiceContext(
            settings: com.poyka.ripdpi.proto.AppSettings,
            profile: com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity?,
            serviceStatus: com.poyka.ripdpi.data.AppStatus,
            activeMode: Mode,
            telemetry: com.poyka.ripdpi.data.ServiceTelemetrySnapshot,
            tcpSteps: List<com.poyka.ripdpi.data.TcpChainStepModel>,
            lastNativeError: String,
        ): ServiceContextModel =
            ServiceContextModel(
                serviceStatus = serviceStatus.name,
                configuredMode = Mode.fromString(settings.ripdpiMode.ifEmpty { "vpn" }).name,
                activeMode = activeMode.name,
                selectedProfileId = settings.diagnosticsActiveProfileId.ifEmpty { "default" },
                selectedProfileName = profile?.name ?: "unknown",
                configSource = if (settings.enableCmdSettings) "command_line" else "ui",
                proxyEndpoint =
                    "${settings.proxyIp.ifEmpty { "127.0.0.1" }}" +
                        ":${settings.proxyPort.takeIf { it > 0 } ?: 1080}",
                desyncMethod = primaryDesyncMethod(tcpSteps).ifEmpty { "none" },
                chainSummary = settings.effectiveChainSummary(),
                routeGroup = telemetry.proxyTelemetry.lastRouteGroup?.toString() ?: "unknown",
                sessionUptimeMs =
                    telemetry.serviceStartedAt
                        ?.takeIf { serviceStatus.name == "Running" }
                        ?.let { System.currentTimeMillis() - it },
                lastNativeErrorHeadline = lastNativeError,
                restartCount = telemetry.restartCount,
                hostAutolearnEnabled = booleanState(telemetry.proxyTelemetry.autolearnEnabled),
                learnedHostCount = telemetry.proxyTelemetry.learnedHostCount,
                penalizedHostCount = telemetry.proxyTelemetry.penalizedHostCount,
                blockedHostCount = telemetry.proxyTelemetry.blockedHostCount,
                lastBlockSignal = telemetry.proxyTelemetry.lastBlockSignal ?: "none",
                lastBlockProvider = telemetry.proxyTelemetry.lastBlockProvider ?: "none",
                lastAutolearnHost = telemetry.proxyTelemetry.lastAutolearnHost ?: "none",
                lastAutolearnGroup = telemetry.proxyTelemetry.lastAutolearnGroup?.toString() ?: "none",
                lastAutolearnAction = telemetry.proxyTelemetry.lastAutolearnAction ?: "none",
                proxy = telemetry.proxyTelemetry.toRuntimeComponentSummary(),
                tunnel = telemetry.tunnelTelemetry.toRuntimeComponentSummary(),
                relay = telemetry.relayTelemetry.toRuntimeComponentSummary(),
                warp = telemetry.warpTelemetry.toRuntimeComponentSummary(),
            )

        private fun buildDeviceContext(packageInfo: android.content.pm.PackageInfo): DeviceContextModel =
            DeviceContextModel(
                appVersionName = packageInfo.versionName ?: "unknown",
                appVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo),
                buildType =
                    if (
                        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                    ) {
                        "debug"
                    } else {
                        "release"
                    },
                androidVersion = Build.VERSION.RELEASE ?: "unknown",
                apiLevel = Build.VERSION.SDK_INT,
                manufacturer = Build.MANUFACTURER ?: "unknown",
                model = Build.MODEL ?: "unknown",
                primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
                locale = Locale.getDefault().toLanguageTag(),
                timezone = TimeZone.getDefault().id,
            )

        private fun notificationPermissionState(): String =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                "not_required"
            } else {
                booleanState(
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED,
                )
            }

        private fun batteryOptimizationState(): String =
            booleanState(powerManager.isIgnoringBatteryOptimizations(context.packageName))

        private fun dataSaverState(): String =
            when (connectivityManager.restrictBackgroundStatus) {
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED -> "disabled"
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "enabled"
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "whitelisted"
                else -> "unknown"
            }

        @SuppressLint("MissingPermission")
        private fun roamingState(): String {
            if (!hasPermission(Manifest.permission.ACCESS_NETWORK_STATE)) return "unknown"
            val capabilities =
                connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            return when {
                capabilities == null -> "unknown"
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) -> "disabled"
                else -> "enabled"
            }
        }

        private fun hasPermission(permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        private fun relevantLastNativeError(
            serviceStatus: AppStatus,
            telemetry: ServiceTelemetrySnapshot,
        ): String {
            val haltedWithIdleRuntime =
                serviceStatus == AppStatus.Halted &&
                    telemetry.runtimeFieldTelemetry.failureClass == null &&
                    telemetry.proxyTelemetry.isIdleRuntimeSnapshot() &&
                    telemetry.tunnelTelemetry.isIdleRuntimeSnapshot()
            if (haltedWithIdleRuntime) {
                return "none"
            }
            return listOfNotNull(
                telemetry.proxyTelemetryStatus.engineErrorMessageOrNull(),
                telemetry.tunnelTelemetryStatus.engineErrorMessageOrNull(),
                telemetry.proxyTelemetry.lastError?.takeIf { it.isNotBlank() },
                telemetry.tunnelTelemetry.lastError?.takeIf { it.isNotBlank() },
            ).firstOrNull() ?: "none"
        }
    }

internal fun NativeRuntimeSnapshot.toRuntimeComponentSummary(): RuntimeComponentSummary =
    RuntimeComponentSummary(
        state = state,
        health = health,
        activeSessions = activeSessions,
        lastError = lastError?.takeIf { it.isNotBlank() } ?: "none",
        lastFailureClass = lastFailureClass?.takeIf { it.isNotBlank() } ?: "none",
        listenerAddress = listenerAddress,
        upstreamAddress = upstreamAddress,
        capturedAt = capturedAt.takeIf { it > 0L },
    )

internal fun buildServiceRuntimeAssessment(
    serviceStatus: AppStatus,
    telemetry: ServiceTelemetrySnapshot,
): ConnectivityServiceRuntimeAssessment {
    val failureClass = telemetry.runtimeFieldTelemetry.failureClass?.wireValue ?: "none"
    val lastError =
        listOfNotNull(
            telemetry.proxyTelemetryStatus.engineErrorMessageOrNull(),
            telemetry.tunnelTelemetryStatus.engineErrorMessageOrNull(),
            telemetry.relayTelemetryStatus.engineErrorMessageOrNull(),
            telemetry.warpTelemetryStatus.engineErrorMessageOrNull(),
            telemetry.proxyTelemetry.lastError?.takeIf { it.isNotBlank() },
            telemetry.tunnelTelemetry.lastError?.takeIf { it.isNotBlank() },
            telemetry.relayTelemetry.lastError?.takeIf { it.isNotBlank() },
            telemetry.warpTelemetry.lastError?.takeIf { it.isNotBlank() },
        ).firstOrNull() ?: "none"
    val actionable =
        serviceStatus == AppStatus.Halted &&
            (
                failureClass != "none" ||
                    telemetry.proxyTelemetryStatus.state == RuntimeTelemetryState.EngineError ||
                    telemetry.tunnelTelemetryStatus.state == RuntimeTelemetryState.EngineError ||
                    telemetry.relayTelemetryStatus.state == RuntimeTelemetryState.EngineError ||
                    telemetry.warpTelemetryStatus.state == RuntimeTelemetryState.EngineError ||
                    lastError != "none" ||
                    !telemetry.proxyTelemetry.isIdleRuntimeSnapshot() ||
                    !telemetry.tunnelTelemetry.isIdleRuntimeSnapshot()
            )
    val summary =
        when {
            actionable -> "Native runtime captured a halted service state with actionable proxy/tunnel failure details."
            serviceStatus == AppStatus.Running -> "Service remained running during diagnostics."
            else -> "No actionable native runtime failure recorded."
        }
    return ConnectivityServiceRuntimeAssessment(
        serviceStatus = serviceStatus.name,
        nativeFailureClass = failureClass,
        lastNativeErrorHeadline = lastError,
        actionable = actionable,
        proxy = telemetry.proxyTelemetry.toRuntimeComponentSummary(),
        tunnel = telemetry.tunnelTelemetry.toRuntimeComponentSummary(),
        relay = telemetry.relayTelemetry.toRuntimeComponentSummary(),
        warp = telemetry.warpTelemetry.toRuntimeComponentSummary(),
        summary = summary,
    )
}

private fun NativeRuntimeSnapshot.isIdleRuntimeSnapshot(): Boolean =
    state == "idle" &&
        health == "idle" &&
        activeSessions == 0L &&
        totalErrors == 0L &&
        lastError.isNullOrBlank() &&
        lastFailureClass.isNullOrBlank()

private fun RuntimeTelemetryStatus.engineErrorMessageOrNull(): String? =
    if (state == RuntimeTelemetryState.EngineError) {
        message?.takeIf { it.isNotBlank() } ?: "runtime telemetry polling failed"
    } else {
        null
    }

internal fun booleanState(value: Boolean?): String =
    when (value) {
        true -> "enabled"
        false -> "disabled"
        null -> "unknown"
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsContextProviderModule {
    @Binds
    @Singleton
    abstract fun bindDiagnosticsContextProvider(
        provider: AndroidDiagnosticsContextProvider,
    ): DiagnosticsContextProvider
}
