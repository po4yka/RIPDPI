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
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
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
            val lastNativeError =
                listOfNotNull(
                    telemetry.proxyTelemetry.lastError?.takeIf { it.isNotBlank() },
                    telemetry.tunnelTelemetry.lastError?.takeIf { it.isNotBlank() },
                ).firstOrNull() ?: "none"

            return DiagnosticContextModel(
                service =
                    ServiceContextModel(
                        serviceStatus = serviceStatus.name,
                        configuredMode = Mode.fromString(settings.ripdpiMode.ifEmpty { "vpn" }).name,
                        activeMode = activeMode.name,
                        selectedProfileId = settings.diagnosticsActiveProfileId.ifEmpty { "default" },
                        selectedProfileName = profile?.name ?: "unknown",
                        configSource = if (settings.enableCmdSettings) "command_line" else "ui",
                        proxyEndpoint = "${settings.proxyIp.ifEmpty {
                            "127.0.0.1"
                        }}:${settings.proxyPort.takeIf { it > 0 } ?: 1080}",
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
                        lastAutolearnHost = telemetry.proxyTelemetry.lastAutolearnHost ?: "none",
                        lastAutolearnGroup = telemetry.proxyTelemetry.lastAutolearnGroup?.toString() ?: "none",
                        lastAutolearnAction = telemetry.proxyTelemetry.lastAutolearnAction ?: "none",
                    ),
                permissions =
                    PermissionContextModel(
                        vpnPermissionState = booleanState(VpnService.prepare(context) == null),
                        notificationPermissionState = notificationPermissionState(),
                        batteryOptimizationState = batteryOptimizationState(),
                        dataSaverState = dataSaverState(),
                    ),
                device =
                    DeviceContextModel(
                        appVersionName = packageInfo.versionName ?: "unknown",
                        appVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo),
                        buildType =
                            if ((
                                    context.applicationInfo.flags and
                                        android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
                                ) !=
                                0
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
                    ),
                environment =
                    EnvironmentContextModel(
                        batterySaverState = booleanState(powerManager.isPowerSaveMode),
                        powerSaveModeState = booleanState(powerManager.isPowerSaveMode),
                        networkMeteredState = booleanState(connectivityManager.isActiveNetworkMetered),
                        roamingState = roamingState(),
                    ),
            )
        }

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
            if (!hasPermission(Manifest.permission.ACCESS_NETWORK_STATE)) {
                return "unknown"
            }
            val capabilities =
                connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork) ?: return "unknown"
            return when {
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) -> "disabled"
                else -> "enabled"
            }
        }

        private fun hasPermission(permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
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
