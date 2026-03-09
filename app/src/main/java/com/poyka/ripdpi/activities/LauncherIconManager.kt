package com.poyka.ripdpi.activities

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.poyka.ripdpi.R

data class LauncherIconOption(
    val key: String,
    @param:StringRes val labelRes: Int,
    @param:DrawableRes val previewRes: Int,
    val themedAliasClassName: String,
    val plainAliasClassName: String,
)

object LauncherIconManager {
    const val DefaultIconKey = "default"
    const val DarkIconKey = "dark"
    const val BlackIconKey = "black"
    const val RavenIconKey = "raven"
    const val RavenDarkIconKey = "raven_dark"
    const val RavenBlackIconKey = "raven_black"
    const val NeutralIconKey = "neutral"
    const val CharcoalIconKey = "charcoal"
    const val StealthIconKey = "stealth"

    const val ThemedIconStyle = "themed"
    const val PlainIconStyle = "plain"

    val availableIcons: List<LauncherIconOption> = listOf(
        LauncherIconOption(
            key = DefaultIconKey,
            labelRes = R.string.customization_icon_default,
            previewRes = R.drawable.ic_launcher,
            themedAliasClassName = "com.poyka.ripdpi.activities.MainActivityDefaultThemed",
            plainAliasClassName = "com.poyka.ripdpi.activities.MainActivityDefaultPlain",
        ),
        LauncherIconOption(
            key = DarkIconKey,
            labelRes = R.string.customization_icon_dark,
            previewRes = R.drawable.ic_launcher_dark,
            themedAliasClassName = "com.poyka.ripdpi.activities.MainActivityDarkThemed",
            plainAliasClassName = "com.poyka.ripdpi.activities.MainActivityDarkPlain",
        ),
        LauncherIconOption(
            key = BlackIconKey,
            labelRes = R.string.customization_icon_black,
            previewRes = R.drawable.ic_launcher_black,
            themedAliasClassName = "com.poyka.ripdpi.activities.MainActivityBlackThemed",
            plainAliasClassName = "com.poyka.ripdpi.activities.MainActivityBlackPlain",
        ),
        LauncherIconOption(
            key = RavenIconKey,
            labelRes = R.string.customization_icon_raven,
            previewRes = R.drawable.ic_launcher_raven,
            themedAliasClassName = "com.poyka.ripdpi.activities.MainActivityRavenThemed",
            plainAliasClassName = "com.poyka.ripdpi.activities.MainActivityRavenPlain",
        ),
        LauncherIconOption(
            key = RavenDarkIconKey,
            labelRes = R.string.customization_icon_raven_dark,
            previewRes = R.drawable.ic_launcher_raven_dark,
            themedAliasClassName = "com.poyka.ripdpi.activities.MainActivityRavenDarkThemed",
            plainAliasClassName = "com.poyka.ripdpi.activities.MainActivityRavenDarkPlain",
        ),
        LauncherIconOption(
            key = RavenBlackIconKey,
            labelRes = R.string.customization_icon_raven_black,
            previewRes = R.drawable.ic_launcher_raven_black,
            themedAliasClassName = "com.poyka.ripdpi.activities.MainActivityRavenBlackThemed",
            plainAliasClassName = "com.poyka.ripdpi.activities.MainActivityRavenBlackPlain",
        ),
        LauncherIconOption(
            key = NeutralIconKey,
            labelRes = R.string.customization_icon_neutral,
            previewRes = R.drawable.ic_launcher_neutral,
            themedAliasClassName = "com.poyka.ripdpi.activities.MainActivityNeutralThemed",
            plainAliasClassName = "com.poyka.ripdpi.activities.MainActivityNeutralPlain",
        ),
        LauncherIconOption(
            key = CharcoalIconKey,
            labelRes = R.string.customization_icon_charcoal,
            previewRes = R.drawable.ic_launcher_charcoal,
            themedAliasClassName = "com.poyka.ripdpi.activities.MainActivityCharcoalThemed",
            plainAliasClassName = "com.poyka.ripdpi.activities.MainActivityCharcoalPlain",
        ),
        LauncherIconOption(
            key = StealthIconKey,
            labelRes = R.string.customization_icon_stealth,
            previewRes = R.drawable.ic_launcher_stealth,
            themedAliasClassName = "com.poyka.ripdpi.activities.MainActivityStealthThemed",
            plainAliasClassName = "com.poyka.ripdpi.activities.MainActivityStealthPlain",
        ),
    )

    fun normalizeIconKey(value: String): String =
        availableIcons.firstOrNull { it.key == value }?.key ?: DefaultIconKey

    fun normalizeIconStyle(value: String): String =
        if (value == PlainIconStyle) PlainIconStyle else ThemedIconStyle

    fun resolveOption(key: String): LauncherIconOption =
        availableIcons.first { it.key == normalizeIconKey(key) }

    fun applySelection(
        context: Context,
        iconKey: String,
        iconStyle: String,
    ) {
        val packageManager = context.packageManager
        val selectedOption = resolveOption(iconKey)
        val selectedStyle = normalizeIconStyle(iconStyle)
        val selectedAlias = aliasClassName(selectedOption, selectedStyle)

        availableIcons.forEach { option ->
            updateAliasState(
                packageManager = packageManager,
                packageName = context.packageName,
                className = option.themedAliasClassName,
                enabled = option.themedAliasClassName == selectedAlias,
            )
            updateAliasState(
                packageManager = packageManager,
                packageName = context.packageName,
                className = option.plainAliasClassName,
                enabled = option.plainAliasClassName == selectedAlias,
            )
        }
    }

    private fun aliasClassName(
        option: LauncherIconOption,
        iconStyle: String,
    ): String = if (iconStyle == PlainIconStyle) {
        option.plainAliasClassName
    } else {
        option.themedAliasClassName
    }

    private fun updateAliasState(
        packageManager: PackageManager,
        packageName: String,
        className: String,
        enabled: Boolean,
    ) {
        val componentName = ComponentName(packageName, className)
        val desiredState = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }

        val currentState = try {
            packageManager.getComponentEnabledSetting(componentName)
        } catch (_: IllegalArgumentException) {
            return
        }

        val normalizedCurrentState =
            if (currentState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                if (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
            } else {
                currentState
            }

        if (normalizedCurrentState != desiredState) {
            packageManager.setComponentEnabledSetting(
                componentName,
                desiredState,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
