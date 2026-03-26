package com.poyka.ripdpi.platform

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.annotation.StringRes
import com.poyka.ripdpi.activities.LauncherIconManager
import com.poyka.ripdpi.automation.AutomationController
import com.poyka.ripdpi.core.clearHostAutolearnStore
import com.poyka.ripdpi.core.hasHostAutolearnStore
import com.poyka.ripdpi.permissions.BatteryOptimizationIntents
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

interface LauncherIconController {
    fun applySelection(
        iconKey: String,
        iconStyle: String,
    )
}

@Singleton
class DefaultLauncherIconController
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
) : LauncherIconController {
    override fun applySelection(
        iconKey: String,
        iconStyle: String,
    ) {
        LauncherIconManager.applySelection(
            context = context,
            iconKey = iconKey,
            iconStyle = iconStyle,
        )
    }
}

interface StringResolver {
    fun getString(
        @StringRes resId: Int,
        vararg formatArgs: Any,
    ): String
}

@Singleton
class AndroidStringResolver
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
) : StringResolver {
    override fun getString(
        @StringRes resId: Int,
        vararg formatArgs: Any,
    ): String = context.getString(resId, *formatArgs)
}

interface PermissionPlatformBridge {
    fun prepareVpnPermissionIntent(): Intent?

    fun createAppSettingsIntent(): Intent

    fun createBatteryOptimizationIntent(): Intent
}

@Singleton
class AndroidPermissionPlatformBridge
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val automationController: Optional<AutomationController>,
) : PermissionPlatformBridge {
    override fun prepareVpnPermissionIntent(): Intent? =
        automationController
            .map { controller -> controller.prepareVpnPermissionIntent(VpnService.prepare(context)) }
            .orElseGet { VpnService.prepare(context) }

    override fun createAppSettingsIntent(): Intent =
        automationController
            .map { controller ->
                controller.createAppSettingsIntent(
                    BatteryOptimizationIntents
                        .createAppDetailsIntent(context.packageName)
                        .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                )
            }.orElseGet {
                BatteryOptimizationIntents
                    .createAppDetailsIntent(context.packageName)
                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            }

    override fun createBatteryOptimizationIntent(): Intent =
        automationController
            .map { controller ->
                controller.createBatteryOptimizationIntent(
                    BatteryOptimizationIntents.create(packageName = context.packageName) { true },
                )
            }.orElseGet {
                BatteryOptimizationIntents.create(packageName = context.packageName) { true }
            }
}

interface HostAutolearnStoreController {
    fun hasStore(): Boolean

    fun clearStore(): Boolean
}

@Singleton
class AndroidHostAutolearnStoreController
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
) : HostAutolearnStoreController {
    override fun hasStore(): Boolean = hasHostAutolearnStore(context)

    override fun clearStore(): Boolean = clearHostAutolearnStore(context)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppPlatformBindingsModule {
    @Binds
    @Singleton
    abstract fun bindLauncherIconController(controller: DefaultLauncherIconController): LauncherIconController

    @Binds
    @Singleton
    abstract fun bindStringResolver(resolver: AndroidStringResolver): StringResolver

    @Binds
    @Singleton
    abstract fun bindPermissionPlatformBridge(bridge: AndroidPermissionPlatformBridge): PermissionPlatformBridge

    @Binds
    @Singleton
    abstract fun bindHostAutolearnStoreController(
        controller: AndroidHostAutolearnStoreController,
    ): HostAutolearnStoreController
}
