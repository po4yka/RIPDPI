package com.poyka.ripdpi.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.poyka.ripdpi.data.Mode
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

enum class PermissionKind {
    VpnConsent,
    Notifications,
    BatteryOptimization,
}

enum class PermissionStatus {
    Granted,
    Denied,
    RequiresSystemPrompt,
    RequiresSettings,
    NotApplicable,
}

data class PermissionSnapshot(
    val vpnConsent: PermissionStatus = PermissionStatus.RequiresSystemPrompt,
    val notifications: PermissionStatus = PermissionStatus.NotApplicable,
    val batteryOptimization: PermissionStatus = PermissionStatus.NotApplicable,
) {
    fun statusFor(kind: PermissionKind): PermissionStatus =
        when (kind) {
            PermissionKind.VpnConsent -> vpnConsent
            PermissionKind.Notifications -> notifications
            PermissionKind.BatteryOptimization -> batteryOptimization
        }

    fun withStatus(
        kind: PermissionKind,
        status: PermissionStatus,
    ): PermissionSnapshot =
        when (kind) {
            PermissionKind.VpnConsent -> copy(vpnConsent = status)
            PermissionKind.Notifications -> copy(notifications = status)
            PermissionKind.BatteryOptimization -> copy(batteryOptimization = status)
        }
}

sealed interface PermissionAction {
    data object StartConfiguredMode : PermissionAction

    data object StartVpnMode : PermissionAction

    data object ShowVpnPermissionDialog : PermissionAction

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

data class PermissionSummaryUiState(
    val snapshot: PermissionSnapshot = PermissionSnapshot(),
    val issue: PermissionIssueUiState? = null,
    val recommendedIssue: PermissionIssueUiState? = null,
    val items: List<PermissionItemUiState> = emptyList(),
)

interface PermissionStatusProvider {
    fun currentSnapshot(): PermissionSnapshot
}

@Singleton
class AndroidPermissionStatusProvider
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : PermissionStatusProvider {
        override fun currentSnapshot(): PermissionSnapshot =
            PermissionSnapshot(
                vpnConsent =
                    if (VpnService.prepare(context) == null) {
                        PermissionStatus.Granted
                    } else {
                        PermissionStatus.RequiresSystemPrompt
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
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                        PermissionStatus.NotApplicable
                    } else {
                        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                        if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                            PermissionStatus.Granted
                        } else {
                            PermissionStatus.RequiresSettings
                        }
                    },
            )
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
                    PermissionAction.StartConfiguredMode -> buildStartRequirements(mode = configuredMode, snapshot = snapshot)
                    PermissionAction.StartVpnMode -> buildStartRequirements(mode = Mode.VPN, snapshot = snapshot)
                    PermissionAction.ShowVpnPermissionDialog -> emptyList()
                    is PermissionAction.RepairPermission ->
                        buildList {
                            val status = snapshot.statusFor(action.kind)
                            if (status != PermissionStatus.Granted && status != PermissionStatus.NotApplicable) {
                                add(action.kind)
                            }
                        }
                }

            val recommended =
                when (action) {
                    PermissionAction.StartConfiguredMode,
                    PermissionAction.StartVpnMode,
                    -> buildRecommendationList(snapshot)

                    PermissionAction.ShowVpnPermissionDialog,
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
                if (snapshot.notifications != PermissionStatus.Granted) {
                    add(PermissionKind.Notifications)
                }
                if (mode == Mode.VPN && snapshot.vpnConsent != PermissionStatus.Granted) {
                    add(PermissionKind.VpnConsent)
                }
            }

        private fun buildRecommendationList(snapshot: PermissionSnapshot): List<PermissionKind> =
            buildList {
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
    abstract fun bindPermissionStatusProvider(
        provider: AndroidPermissionStatusProvider,
    ): PermissionStatusProvider
}
