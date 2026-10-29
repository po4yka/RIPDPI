package com.poyka.ripdpi.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect

private const val MediumContrastThreshold = 0.25f
private const val HighContrastThreshold = 0.75f

/** Maps the platform contrast value (-1..1) to a level: below 0.25 Standard, below 0.75 Medium, else High. */
internal fun contrastLevelFor(systemContrast: Float): RipDpiContrastLevel =
    when {
        systemContrast < MediumContrastThreshold -> RipDpiContrastLevel.Standard
        systemContrast < HighContrastThreshold -> RipDpiContrastLevel.Medium
        else -> RipDpiContrastLevel.High
    }

/** Follows the system contrast on API 34+ while the host is started; Standard below that. */
@Composable
fun rememberSystemContrastLevel(): RipDpiContrastLevel {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return RipDpiContrastLevel.Standard
    return rememberSystemContrastLevelApi34()
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@Composable
private fun rememberSystemContrastLevelApi34(): RipDpiContrastLevel {
    val context = LocalContext.current
    val uiModeManager = remember(context) { context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager }
    var level by remember(uiModeManager) { mutableStateOf(contrastLevelFor(uiModeManager.contrast)) }
    LifecycleStartEffect(uiModeManager, context) {
        level = contrastLevelFor(uiModeManager.contrast)
        val listener = UiModeManager.ContrastChangeListener { level = contrastLevelFor(it) }
        uiModeManager.addContrastChangeListener(context.mainExecutor, listener)
        onStopOrDispose { uiModeManager.removeContrastChangeListener(listener) }
    }
    return level
}
