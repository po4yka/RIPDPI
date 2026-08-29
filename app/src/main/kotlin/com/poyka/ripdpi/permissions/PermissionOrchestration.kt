package com.poyka.ripdpi.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.poyka.ripdpi.automation.AutomationController
import com.poyka.ripdpi.data.LocalNetworkPermission
import com.poyka.ripdpi.data.LocalNetworkPermissionApi
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.services.AndroidHardKillSwitchStateStore
import com.poyka.ripdpi.services.AndroidHardKillSwitchStatus
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

enum class PermissionKind {
    VpnConsent,
    AlwaysOnVpn,
    VpnLockdown,
    Notifications,
    BatteryOptimization,
    LocalNetwork,
}

enum class PermissionStatus {
    Granted,
    Denied,
    RequiresSystemPrompt,
    RequiresSettings,
    NotApplicable,
    Unknown,
}

data class PermissionSnapshot(
    val localNetwork: PermissionStatus = PermissionStatus.NotApplicable,
    val vpnConsent: PermissionStatus = PermissionStatus.RequiresSystemPrompt,
    val alwaysOnVpn: PermissionStatus = PermissionStatus.Unknown,
    val vpnLockdown: PermissionStatus = PermissionStatus.Unknown,
    val notifications: PermissionStatus = PermissionStatus.NotApplicable,
    val batteryOptimization: PermissionStatus = PermissionStatus.NotApplicable,
) {
    fun statusFor(kind: PermissionKind): PermissionStatus =
        when (kind) {
            PermissionKind.LocalNetwork -> localNetwork
            PermissionKind.VpnConsent -> vpnConsent
            PermissionKind.AlwaysOnVpn -> alwaysOnVpn
            PermissionKind.VpnLockdown -> vpnLockdown
            PermissionKind.Notifications -> notifications
            PermissionKind.BatteryOptimization -> batteryOptimization
        }

    fun withStatus(
        kind: PermissionKind,
        status: PermissionStatus,
    ): PermissionSnapshot =
        when (kind) {
            PermissionKind.LocalNetwork -> copy(localNetwork = status)
            PermissionKind.VpnConsent -> copy(vpnConsent = status)
            PermissionKind.AlwaysOnVpn -> copy(alwaysOnVpn = status)
            PermissionKind.VpnLockdown -> copy(vpnLockdown = status)
            PermissionKind.Notifications -> copy(notifications = status)
            PermissionKind.BatteryOptimization -> copy(batteryOptimization = status)
        }
}

sealed interface PermissionAction {
    data object StartConfiguredMode : PermissionAction

    data object StartProxyMode : PermissionAction

    data object StartVpnMode : PermissionAction

    data object RunHomeAnalysis : PermissionAction

    data class RepairPermission(
        val kind: PermissionKind,
    ) : PermissionAction
}

data class PermissionResolution(
    val required: List<PermissionKind>,
    val recommended: List<PermissionKind>,
    val blockedBy: PermissionKind?,
)

enum class PermissionResult {
    Granted,
    Denied,
    DeniedPermanently,
    ReturnedFromSettings,
}

enum class PermissionRecovery {
    RetryPrompt,
    OpenSettings,
    ShowVpnPermissionDialog,
    OpenBatteryOptimizationSettings,
}

data class PermissionIssueUiState(
    val kind: PermissionKind,
    val title: String,
    val message: String,
    val recovery: PermissionRecovery,
    val actionLabel: String,
    val blocking: Boolean,
)

data class PermissionItemUiState(
    val kind: PermissionKind,
    val title: String,
    val subtitle: String,
    val statusLabel: String,
    val actionLabel: String? = null,
    val enabled: Boolean = true,
)

data class BackgroundGuidanceUiState(
    val title: String,
    val message: String,
)

data class PermissionSummaryUiState(
    val snapshot: PermissionSnapshot = PermissionSnapshot(),
    val issue: PermissionIssueUiState? = null,
    val recommendedIssue: PermissionIssueUiState? = null,
    val backgroundGuidance: BackgroundGuidanceUiState? = null,
    val items: ImmutableList<PermissionItemUiState> = persistentListOf(),
)

interface PermissionStatusProvider {
    fun currentSnapshot(): PermissionSnapshot
}

@Singleton
class AndroidPermissionStatusProvider
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val automationController: Optional<AutomationController>,
        private val hardKillSwitchStateStore: AndroidHardKillSwitchStateStore,
    ) : PermissionStatusProvider {
        private fun currentLocalNetworkStatus(): PermissionStatus =
            when {
                Build.VERSION.SDK_INT < LocalNetworkPermissionApi -> PermissionStatus.NotApplicable

                ContextCompat.checkSelfPermission(context, LocalNetworkPermission) ==
                    PackageManager.PERMISSION_GRANTED -> PermissionStatus.Granted

                else -> PermissionStatus.RequiresSystemPrompt
            }

        override fun currentSnapshot(): PermissionSnapshot {
            val hardKillSwitch = hardKillSwitchStateStore.snapshot.value
            val baseSnapshot =
                PermissionSnapshot(
                    localNetwork = currentLocalNetworkStatus(),
                    vpnConsent =
                        if (VpnService.prepare(context) == null) {
                            PermissionStatus.Granted
                        } else {
                            PermissionStatus.RequiresSystemPrompt
                        },
                    alwaysOnVpn =
                        when (hardKillSwitch.status) {
                            AndroidHardKillSwitchStatus.ENABLED -> {
                                PermissionStatus.Granted
                            }

                            AndroidHardKillSwitchStatus.NOT_ENABLED -> {
                                if (hardKillSwitch.alwaysOn == true) {
                                    PermissionStatus.Granted
                                } else {
                                    PermissionStatus.RequiresSettings
                                }
                            }

                            AndroidHardKillSwitchStatus.UNKNOWN -> {
                                PermissionStatus.Unknown
                            }
                        },
                    vpnLockdown =
                        when (hardKillSwitch.status) {
                            AndroidHardKillSwitchStatus.ENABLED -> {
                                PermissionStatus.Granted
                            }

                            AndroidHardKillSwitchStatus.NOT_ENABLED -> {
                                if (hardKillSwitch.lockdown == true) {
                                    PermissionStatus.Granted
                                } else {
                                    PermissionStatus.RequiresSettings
                                }
                            }

                            AndroidHardKillSwitchStatus.UNKNOWN -> {
                                PermissionStatus.Unknown
                            }
                        },
                    notifications =
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            PermissionStatus.Granted
                        } else if (
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            PermissionStatus.Granted
                        } else {
                            PermissionStatus.RequiresSystemPrompt
                        },
                    batteryOptimization =
                        run {
                            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                            if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                                PermissionStatus.Granted
                            } else {
                                PermissionStatus.RequiresSettings
                            }
                        },
                )
            return automationController
                .map { it.currentPermissionSnapshot(baseSnapshot) }
                .orElse(baseSnapshot)
        }
    }

@Singleton
class PermissionCoordinator
    @Inject
    constructor() {
        fun resolve(
            action: PermissionAction,
            configuredMode: Mode,
            snapshot: PermissionSnapshot,
        ): PermissionResolution {
            val required =
                when (action) {
                    PermissionAction.StartConfiguredMode -> {
                        buildStartRequirements(mode = configuredMode, snapshot = snapshot)
                    }

                    PermissionAction.StartProxyMode -> {
                        buildStartRequirements(mode = Mode.Proxy, snapshot = snapshot)
                    }

                    PermissionAction.StartVpnMode -> {
                        buildStartRequirements(mode = Mode.VPN, snapshot = snapshot)
                    }

                    PermissionAction.RunHomeAnalysis -> {
                        buildList {
                            if (snapshot.vpnConsent != PermissionStatus.Granted &&
                                snapshot.vpnConsent != PermissionStatus.NotApplicable
                            ) {
                                add(PermissionKind.VpnConsent)
                            }
                        }
                    }

                    is PermissionAction.RepairPermission -> {
                        buildList {
                            val status = snapshot.statusFor(action.kind)
                            if (status != PermissionStatus.Granted && status != PermissionStatus.NotApplicable) {
                                add(action.kind)
                            }
                        }
                    }
                }

            val recommended =
                when (action) {
                    PermissionAction.StartConfiguredMode,
                    -> buildRecommendationList(mode = configuredMode, snapshot = snapshot)

                    PermissionAction.StartProxyMode -> buildRecommendationList(mode = Mode.Proxy, snapshot = snapshot)

                    PermissionAction.StartVpnMode -> buildRecommendationList(mode = Mode.VPN, snapshot = snapshot)

                    PermissionAction.RunHomeAnalysis,
                    is PermissionAction.RepairPermission,
                    -> emptyList()
                }

            return PermissionResolution(
                required = required,
                recommended = recommended,
                blockedBy = required.firstOrNull(),
            )
        }

        private fun buildStartRequirements(
            mode: Mode,
            snapshot: PermissionSnapshot,
        ): List<PermissionKind> =
            buildList {
                if (mode == Mode.VPN && snapshot.vpnConsent != PermissionStatus.Granted) {
                    add(PermissionKind.VpnConsent)
                }
            }

        private fun buildRecommendationList(
            mode: Mode,
            snapshot: PermissionSnapshot,
        ): List<PermissionKind> =
            buildList {
                if (
                    snapshot.notifications != PermissionStatus.Granted &&
                    snapshot.notifications != PermissionStatus.NotApplicable
                ) {
                    add(PermissionKind.Notifications)
                }
                if (mode == Mode.VPN &&
                    snapshot.alwaysOnVpn != PermissionStatus.Granted &&
                    snapshot.alwaysOnVpn != PermissionStatus.NotApplicable
                ) {
                    add(PermissionKind.AlwaysOnVpn)
                }
                if (mode == Mode.VPN &&
                    snapshot.vpnLockdown != PermissionStatus.Granted &&
                    snapshot.vpnLockdown != PermissionStatus.NotApplicable
                ) {
                    add(PermissionKind.VpnLockdown)
                }
                if (
                    snapshot.batteryOptimization != PermissionStatus.Granted &&
                    snapshot.batteryOptimization != PermissionStatus.NotApplicable
                ) {
                    add(PermissionKind.BatteryOptimization)
                }
            }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class PermissionStatusProviderModule {
    @Binds
    @Singleton
    abstract fun bindPermissionStatusProvider(provider: AndroidPermissionStatusProvider): PermissionStatusProvider
}
