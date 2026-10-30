package com.poyka.ripdpi.platform

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import android.view.View
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.poyka.ripdpi.activities.LauncherIconManager
import com.poyka.ripdpi.automation.AutomationController
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

    fun getQuantityString(
        @PluralsRes resId: Int,
        quantity: Int,
        vararg formatArgs: Any,
    ): String
}

@Singleton
class AndroidStringResolver
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : StringResolver {
        // Format arguments are a short array, so the copy that the spread makes is negligible.
        @Suppress("SpreadOperator")
        override fun getString(
            @StringRes resId: Int,
            vararg formatArgs: Any,
        ): String {
            val localized = ContextCompat.getContextForLanguage(context)
            return localized.getString(resId, *localized.bidiIsolated(formatArgs))
        }

        @Suppress("SpreadOperator")
        override fun getQuantityString(
            @PluralsRes resId: Int,
            quantity: Int,
            vararg formatArgs: Any,
        ): String {
            val localized = ContextCompat.getContextForLanguage(context)
            return localized.resources.getQuantityString(resId, quantity, *localized.bidiIsolated(formatArgs))
        }
    }

/**
 * In an RTL locale, a Latin text argument such as "Wi-Fi" otherwise pulls a following
 * number ("25 October") into its left-to-right run. A first-strong isolate (FSI ... PDI)
 * keeps such an argument in its own run. LTR locales, numbers and arguments without
 * left-to-right letters are left as they are.
 */
private fun Context.bidiIsolated(formatArgs: Array<out Any>): Array<out Any> {
    if (resources.configuration.layoutDirection != View.LAYOUT_DIRECTION_RTL) return formatArgs
    return Array(formatArgs.size) { index ->
        val arg = formatArgs[index]
        if (arg is CharSequence && arg.any(::isStrongLeftToRight)) {
            buildString {
                append(FirstStrongIsolate)
                append(arg)
                append(PopDirectionalIsolate)
            }
        } else {
            arg
        }
    }
}

// Code points, not literals, so the source carries no invisible bidi controls.
private val FirstStrongIsolate = Char(0x2068)
private val PopDirectionalIsolate = Char(0x2069)

/** Removes bidi isolate controls (U+2066..U+2069) from text that leaves the screen (clipboard, share). */
fun String.withoutBidiIsolates(): String = filterNot { it.code in BidiIsolateRange }

private val BidiIsolateRange = 0x2066..0x2069

private fun isStrongLeftToRight(char: Char): Boolean =
    Character.getDirectionality(char) == Character.DIRECTIONALITY_LEFT_TO_RIGHT

interface PermissionPlatformBridge {
    fun prepareVpnPermissionIntent(): Intent?

    fun createAppSettingsIntent(): Intent

    fun createVpnSettingsIntent(): Intent

    fun createBatteryOptimizationIntent(): Intent
}

@Singleton
class AndroidPermissionPlatformBridge
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val automationController: Optional<AutomationController>,
    ) : PermissionPlatformBridge {
        override fun prepareVpnPermissionIntent(): Intent? {
            val systemIntent = VpnService.prepare(context)
            // Not Optional.map: a null answer from automation means consent is granted, and map would
            // turn it into an empty Optional that falls back to the system intent.
            return if (automationController.isPresent) {
                automationController.get().prepareVpnPermissionIntent(systemIntent)
            } else {
                systemIntent
            }
        }

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

        override fun createVpnSettingsIntent(): Intent =
            Intent(Settings.ACTION_VPN_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
}
