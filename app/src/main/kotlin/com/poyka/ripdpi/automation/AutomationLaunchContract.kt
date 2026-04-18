package com.poyka.ripdpi.automation

import android.content.Intent
import com.poyka.ripdpi.activities.MainViewModel
import com.poyka.ripdpi.permissions.PermissionSnapshot
import com.poyka.ripdpi.ui.navigation.Route
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

object AutomationLaunchContract {
    const val LaunchAction = "com.poyka.ripdpi.automation.LAUNCH"
    const val LaunchUriScheme = "ripdpi-debug"
    const val LaunchUriHost = "automation"
    const val LaunchUriPath = "/launch"
    const val LaunchParamEnabled = "enabled"
    const val LaunchParamResetState = "reset_state"
    const val LaunchParamStartRoute = "start_route"
    const val LaunchParamDisableMotion = "disable_motion"
    const val LaunchParamPermissionPreset = "permission_preset"
    const val LaunchParamServicePreset = "service_preset"
    const val LaunchParamDataPreset = "data_preset"
    const val Enabled = "com.poyka.ripdpi.automation.ENABLED"
    const val ResetState = "com.poyka.ripdpi.automation.RESET_STATE"
    const val StartRoute = "com.poyka.ripdpi.automation.START_ROUTE"
    const val DisableMotion = "com.poyka.ripdpi.automation.DISABLE_MOTION"
    const val PermissionPreset = "com.poyka.ripdpi.automation.PERMISSION_PRESET"
    const val ServicePreset = "com.poyka.ripdpi.automation.SERVICE_PRESET"
    const val DataPreset = "com.poyka.ripdpi.automation.DATA_PRESET"
}

enum class AutomationPermissionPreset(
    val wireValue: String,
) {
    Granted("granted"),
    NotificationsMissing("notifications_missing"),
    VpnMissing("vpn_missing"),
    BatteryReview("battery_review"),
    ;

    companion object {
        fun fromWireValue(value: String?): AutomationPermissionPreset? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class AutomationServicePreset(
    val wireValue: String,
) {
    Idle("idle"),
    ConnectedProxy("connected_proxy"),
    ConnectedVpn("connected_vpn"),
    Live("live"),
    ;

    companion object {
        fun fromWireValue(value: String?): AutomationServicePreset? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class AutomationDataPreset(
    val wireValue: String,
) {
    CleanHome("clean_home"),
    SettingsReady("settings_ready"),
    DiagnosticsDemo("diagnostics_demo"),
    ;

    companion object {
        fun fromWireValue(value: String?): AutomationDataPreset? = entries.firstOrNull { it.wireValue == value }
    }
}

data class AutomationLaunchConfig(
    val enabled: Boolean = false,
    val resetState: Boolean = false,
    val startRoute: String? = null,
    val disableMotion: Boolean = false,
    val permissionPreset: AutomationPermissionPreset = AutomationPermissionPreset.Granted,
    val servicePreset: AutomationServicePreset = AutomationServicePreset.Idle,
    val dataPreset: AutomationDataPreset = AutomationDataPreset.CleanHome,
)

internal fun resolveAutomationLaunchConfig(
    instrumentationArgs: Map<String, Any?> = emptyMap(),
    intentArgs: Map<String, Any?> = emptyMap(),
): AutomationLaunchConfig {
    val enabled =
        readBoolean(
            intentArgs = intentArgs,
            instrumentationArgs = instrumentationArgs,
            key = AutomationLaunchContract.Enabled,
        ) ?: false
    if (!enabled) {
        return AutomationLaunchConfig()
    }

    return AutomationLaunchConfig(
        enabled = true,
        resetState =
            readBoolean(
                intentArgs = intentArgs,
                instrumentationArgs = instrumentationArgs,
                key = AutomationLaunchContract.ResetState,
            ) ?: false,
        startRoute =
            readString(
                intentArgs = intentArgs,
                instrumentationArgs = instrumentationArgs,
                key = AutomationLaunchContract.StartRoute,
            )?.takeIf { it.isNotBlank() && it in ValidAutomationRoutes },
        disableMotion =
            readBoolean(
                intentArgs = intentArgs,
                instrumentationArgs = instrumentationArgs,
                key = AutomationLaunchContract.DisableMotion,
            ) ?: false,
        permissionPreset =
            AutomationPermissionPreset.fromWireValue(
                readString(
                    intentArgs = intentArgs,
                    instrumentationArgs = instrumentationArgs,
                    key = AutomationLaunchContract.PermissionPreset,
                ),
            ) ?: AutomationPermissionPreset.Granted,
        servicePreset =
            AutomationServicePreset.fromWireValue(
                readString(
                    intentArgs = intentArgs,
                    instrumentationArgs = instrumentationArgs,
                    key = AutomationLaunchContract.ServicePreset,
                ),
            ) ?: AutomationServicePreset.Idle,
        dataPreset =
            AutomationDataPreset.fromWireValue(
                readString(
                    intentArgs = intentArgs,
                    instrumentationArgs = instrumentationArgs,
                    key = AutomationLaunchContract.DataPreset,
                ),
            ) ?: AutomationDataPreset.CleanHome,
    )
}

private val ValidAutomationRoutes: Set<String> = Route.all.mapTo(linkedSetOf()) { it.stableRoute }

private fun readBoolean(
    intentArgs: Map<String, Any?>,
    instrumentationArgs: Map<String, Any?>,
    key: String,
): Boolean? = parseBoolean(intentArgs[key]) ?: parseBoolean(instrumentationArgs[key])

private fun readString(
    intentArgs: Map<String, Any?>,
    instrumentationArgs: Map<String, Any?>,
    key: String,
): String? = parseString(intentArgs[key]) ?: parseString(instrumentationArgs[key])

private fun parseBoolean(value: Any?): Boolean? =
    when (value) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull()
        else -> null
    }

private fun parseString(value: Any?): String? =
    when (value) {
        null -> null
        is String -> value
        else -> value.toString()
    }

interface AutomationController {
    fun prepareLaunch(intent: Intent?): AutomationLaunchConfig

    fun currentLaunchConfig(): AutomationLaunchConfig = AutomationLaunchConfig()

    fun currentPermissionSnapshot(defaultSnapshot: PermissionSnapshot): PermissionSnapshot = defaultSnapshot

    fun prepareVpnPermissionIntent(defaultIntent: Intent?): Intent? = defaultIntent

    fun createAppSettingsIntent(defaultIntent: Intent): Intent = defaultIntent

    fun createBatteryOptimizationIntent(defaultIntent: Intent): Intent = defaultIntent

    fun interceptHostCommand(
        command: Any,
        viewModel: MainViewModel,
    ): Boolean = false
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AutomationControllerOptionalBindingsModule {
    @BindsOptionalOf
    abstract fun bindAutomationController(): AutomationController
}
