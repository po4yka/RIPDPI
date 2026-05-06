package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.DeviceContextModel
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.EnvironmentContextModel
import com.poyka.ripdpi.diagnostics.PermissionContextModel
import com.poyka.ripdpi.diagnostics.ServiceContextModel

internal fun DiagnosticsUiFactorySupport.toOverviewContextGroup(
    context: DiagnosticContextModel,
): DiagnosticsContextGroupUiModel =
    DiagnosticsContextGroupUiModel(
        title = this.context.getString(R.string.diagnostics_section_device),
        fields = overviewContextFields(context),
    )

internal fun DiagnosticsUiFactorySupport.toLiveContextGroups(
    context: DiagnosticContextModel,
): List<DiagnosticsContextGroupUiModel> =
    listOf(
        liveServiceGroup(context.service),
        liveEnvironmentGroup(context.permissions, context.environment),
        liveHostLearningGroup(context.service),
    )

internal fun DiagnosticsUiFactorySupport.toContextUiGroups(
    context: DiagnosticContextModel,
    showSensitiveDetails: Boolean,
): List<DiagnosticsContextGroupUiModel> =
    listOf(
        serviceContextGroup(context.service, showSensitiveDetails),
        permissionContextGroup(context.permissions),
        deviceContextGroup(context.device),
        environmentContextGroup(context.environment),
    )

private fun DiagnosticsUiFactorySupport.overviewContextFields(
    context: DiagnosticContextModel,
): List<DiagnosticsFieldUiModel> =
    listOf(
        field(R.string.diagnostics_field_app, context.device.appVersionName),
        field(R.string.diagnostics_field_device, "${context.device.manufacturer} ${context.device.model}"),
        field(
            R.string.diagnostics_field_android,
            this.context.getString(
                R.string.diagnostics_field_android_version_format,
                context.device.androidVersion,
                context.device.apiLevel,
            ),
        ),
        field(R.string.diagnostics_field_mode, context.service.activeMode),
        field(R.string.diagnostics_field_profile, context.service.selectedProfileName),
        field(R.string.diagnostics_field_host_learning, buildHostAutolearnOverviewSummary(context.service)),
        field(
            R.string.diagnostics_field_restrictions,
            listOf(context.permissions.dataSaverState, context.environment.powerSaveModeState).joinToString(" · "),
        ),
    )

private fun DiagnosticsUiFactorySupport.liveServiceGroup(
    service: ServiceContextModel,
): DiagnosticsContextGroupUiModel =
    DiagnosticsContextGroupUiModel(
        title = context.getString(R.string.diagnostics_field_service),
        fields =
            listOf(
                field(R.string.diagnostics_field_status, service.serviceStatus),
                field(R.string.diagnostics_field_mode, service.activeMode),
                field(R.string.diagnostics_field_profile, service.selectedProfileName),
                field(
                    R.string.diagnostics_field_uptime,
                    service.sessionUptimeMs?.let(::formatDurationMs)
                        ?: context.getString(R.string.diagnostics_field_unknown),
                ),
            ),
    )

private fun DiagnosticsUiFactorySupport.liveEnvironmentGroup(
    permissions: PermissionContextModel,
    environment: EnvironmentContextModel,
): DiagnosticsContextGroupUiModel =
    DiagnosticsContextGroupUiModel(
        title = context.getString(R.string.diagnostics_field_environment),
        fields =
            listOf(
                field(R.string.diagnostics_field_data_saver, permissions.dataSaverState),
                field(R.string.diagnostics_field_power_save, environment.powerSaveModeState),
                field(R.string.diagnostics_field_metered, environment.networkMeteredState),
                field(R.string.diagnostics_field_roaming, environment.roamingState),
            ),
    )

private fun DiagnosticsUiFactorySupport.liveHostLearningGroup(
    service: ServiceContextModel,
): DiagnosticsContextGroupUiModel =
    DiagnosticsContextGroupUiModel(
        title = context.getString(R.string.diagnostics_field_host_learning),
        fields =
            listOf(
                field(R.string.diagnostics_field_enabled, formatAutolearnState(service.hostAutolearnEnabled)),
                field(R.string.diagnostics_field_learned_hosts, service.learnedHostCount.toString()),
                field(R.string.diagnostics_field_penalized, service.penalizedHostCount.toString()),
                field(R.string.diagnostics_field_blocked_hosts, service.blockedHostCount.toString()),
                field(R.string.diagnostics_field_last_host, formatAutolearnHost(service.lastAutolearnHost)),
                field(R.string.diagnostics_field_last_group, formatAutolearnGroup(service.lastAutolearnGroup)),
                field(R.string.diagnostics_field_last_action, formatAutolearnAction(service.lastAutolearnAction)),
                field(R.string.diagnostics_field_last_block_signal, formatBlockSignal(service.lastBlockSignal)),
                field(R.string.diagnostics_field_last_block_provider, formatBlockProvider(service.lastBlockProvider)),
            ),
    )

private fun DiagnosticsUiFactorySupport.serviceContextGroup(
    service: ServiceContextModel,
    showSensitiveDetails: Boolean,
): DiagnosticsContextGroupUiModel =
    DiagnosticsContextGroupUiModel(
        title = context.getString(R.string.diagnostics_field_service),
        fields = serviceContextFields(service, showSensitiveDetails),
    )

private fun DiagnosticsUiFactorySupport.serviceContextFields(
    service: ServiceContextModel,
    showSensitiveDetails: Boolean,
): List<DiagnosticsFieldUiModel> =
    listOf(
        field(R.string.diagnostics_field_status, service.serviceStatus),
        field(R.string.diagnostics_field_configured_mode, service.configuredMode),
        field(R.string.diagnostics_field_active_mode, service.activeMode),
        field(R.string.diagnostics_field_profile, service.selectedProfileName),
        field(R.string.diagnostics_field_config_source, service.configSource),
        field(
            R.string.diagnostics_field_proxy,
            if (showSensitiveDetails) service.proxyEndpoint else redactValue(service.proxyEndpoint),
        ),
        field(R.string.diagnostics_field_chain, service.chainSummary),
        field(R.string.diagnostics_field_desync, service.desyncMethod),
        field(R.string.diagnostics_field_route_group, service.routeGroup),
        field(R.string.diagnostics_field_autolearn, formatAutolearnState(service.hostAutolearnEnabled)),
        field(R.string.diagnostics_field_learned_hosts, service.learnedHostCount.toString()),
        field(R.string.diagnostics_field_penalized_hosts, service.penalizedHostCount.toString()),
        field(R.string.diagnostics_field_blocked_hosts, service.blockedHostCount.toString()),
        field(R.string.diagnostics_field_last_learned_host, formatAutolearnHost(service.lastAutolearnHost)),
        field(R.string.diagnostics_field_last_learned_group, formatAutolearnGroup(service.lastAutolearnGroup)),
        field(R.string.diagnostics_field_last_autolearn_action, formatAutolearnAction(service.lastAutolearnAction)),
        field(R.string.diagnostics_field_last_block_signal, formatBlockSignal(service.lastBlockSignal)),
        field(R.string.diagnostics_field_last_block_provider, formatBlockProvider(service.lastBlockProvider)),
        field(R.string.diagnostics_field_restart_count, service.restartCount.toString()),
        field(
            R.string.diagnostics_field_uptime,
            service.sessionUptimeMs?.let(::formatDurationMs) ?: context.getString(R.string.diagnostics_field_unknown),
        ),
        field(R.string.diagnostics_field_last_native_error, service.lastNativeErrorHeadline),
    )

private fun DiagnosticsUiFactorySupport.permissionContextGroup(
    permissions: PermissionContextModel,
): DiagnosticsContextGroupUiModel =
    DiagnosticsContextGroupUiModel(
        title = context.getString(R.string.diagnostics_field_permissions),
        fields =
            listOf(
                field(R.string.diagnostics_field_vpn_permission, permissions.vpnPermissionState),
                field(R.string.diagnostics_field_notification_permission, permissions.notificationPermissionState),
                field(R.string.diagnostics_field_battery_optimization, permissions.batteryOptimizationState),
                field(R.string.diagnostics_field_data_saver, permissions.dataSaverState),
            ),
    )

private fun DiagnosticsUiFactorySupport.deviceContextGroup(
    device: DeviceContextModel,
): DiagnosticsContextGroupUiModel =
    DiagnosticsContextGroupUiModel(
        title = context.getString(R.string.diagnostics_field_device),
        fields =
            listOf(
                field(
                    R.string.diagnostics_field_app_version,
                    context.getString(
                        R.string.diagnostics_field_app_version_format,
                        device.appVersionName,
                        device.buildType,
                    ),
                ),
                field(R.string.diagnostics_field_version_code, device.appVersionCode.toString()),
                field(R.string.diagnostics_field_device, "${device.manufacturer} ${device.model}"),
                field(
                    R.string.diagnostics_field_android,
                    context.getString(
                        R.string.diagnostics_field_android_version_format,
                        device.androidVersion,
                        device.apiLevel,
                    ),
                ),
                field(R.string.diagnostics_field_abi, device.primaryAbi),
                field(R.string.diagnostics_field_locale, device.locale),
                field(R.string.diagnostics_field_timezone, device.timezone),
            ),
    )

private fun DiagnosticsUiFactorySupport.environmentContextGroup(
    environment: EnvironmentContextModel,
): DiagnosticsContextGroupUiModel =
    DiagnosticsContextGroupUiModel(
        title = context.getString(R.string.diagnostics_field_environment),
        fields =
            listOf(
                field(R.string.diagnostics_field_battery_saver, environment.batterySaverState),
                field(R.string.diagnostics_field_power_save, environment.powerSaveModeState),
                field(R.string.diagnostics_field_network_metered, environment.networkMeteredState),
                field(R.string.diagnostics_field_roaming, environment.roamingState),
            ),
    )

private fun DiagnosticsUiFactorySupport.field(
    labelRes: Int,
    value: String,
): DiagnosticsFieldUiModel = DiagnosticsFieldUiModel(context.getString(labelRes), value)
