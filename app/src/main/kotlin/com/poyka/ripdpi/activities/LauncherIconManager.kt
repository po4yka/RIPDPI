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
    const val DefaultIconKey = "clean"
    const val CleanIconKey = "clean"
    const val CrackedIconKey = "cracked"
    const val DisintegrateIconKey = "disintegrate"
    const val GlitchIconKey = "glitch"
    const val RubbleIconKey = "rubble"
    const val StitchIconKey = "stitch"

    const val ThemedIconStyle = "themed"
    const val PlainIconStyle = "plain"

    val availableIcons: List<LauncherIconOption> =
        listOf(
            LauncherIconOption(
                key = CleanIconKey,
                labelRes = R.string.customization_icon_clean,
                previewRes = R.drawable.ic_launcher_foreground_ripdpi_clean,
                themedAliasClassName = "com.poyka.ripdpi.activities.MainActivityCleanThemed",
                plainAliasClassName = "com.poyka.ripdpi.activities.MainActivityCleanPlain",
            ),
            LauncherIconOption(
                key = CrackedIconKey,
                labelRes = R.string.customization_icon_cracked,
                previewRes = R.drawable.ic_launcher_foreground_ripdpi_cracked,
                themedAliasClassName = "com.poyka.ripdpi.activities.MainActivityCrackedThemed",
                plainAliasClassName = "com.poyka.ripdpi.activities.MainActivityCrackedPlain",
            ),
            LauncherIconOption(
                key = DisintegrateIconKey,
                labelRes = R.string.customization_icon_disintegrate,
                previewRes = R.drawable.ic_launcher_foreground_ripdpi_disintegrate,
                themedAliasClassName = "com.poyka.ripdpi.activities.MainActivityDisintegrateThemed",
                plainAliasClassName = "com.poyka.ripdpi.activities.MainActivityDisintegratePlain",
            ),
            LauncherIconOption(
                key = GlitchIconKey,
                labelRes = R.string.customization_icon_glitch,
                previewRes = R.drawable.ic_launcher_foreground_ripdpi_glitch,
                themedAliasClassName = "com.poyka.ripdpi.activities.MainActivityGlitchThemed",
                plainAliasClassName = "com.poyka.ripdpi.activities.MainActivityGlitchPlain",
            ),
            LauncherIconOption(
                key = RubbleIconKey,
                labelRes = R.string.customization_icon_rubble,
                previewRes = R.drawable.ic_launcher_foreground_ripdpi_rubble,
                themedAliasClassName = "com.poyka.ripdpi.activities.MainActivityRubbleThemed",
                plainAliasClassName = "com.poyka.ripdpi.activities.MainActivityRubblePlain",
            ),
            LauncherIconOption(
                key = StitchIconKey,
                labelRes = R.string.customization_icon_stitch,
                previewRes = R.drawable.ic_launcher_foreground_ripdpi_stitch,
                themedAliasClassName = "com.poyka.ripdpi.activities.MainActivityStitchThemed",
                plainAliasClassName = "com.poyka.ripdpi.activities.MainActivityStitchPlain",
            ),
        )

    fun normalizeIconKey(value: String): String = availableIcons.firstOrNull { it.key == value }?.key ?: DefaultIconKey

    fun normalizeIconStyle(value: String): String = if (value == PlainIconStyle) PlainIconStyle else ThemedIconStyle

    fun resolveOption(key: String): LauncherIconOption = availableIcons.first { it.key == normalizeIconKey(key) }

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
    ): String =
        if (iconStyle == PlainIconStyle) {
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
        val desiredState =
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }

        val currentState =
            try {
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
