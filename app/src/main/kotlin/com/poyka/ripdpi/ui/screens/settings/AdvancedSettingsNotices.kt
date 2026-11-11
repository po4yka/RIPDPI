package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import com.poyka.ripdpi.ui.components.LifecycleEventEffect
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer

@Composable
internal fun rememberAdvancedNoticeState() =
    rememberSaveable(
        stateSaver =
            mapSaver(
                save = { notice ->
                    if (notice != null) {
                        mapOf(
                            "t" to notice.title,
                            "m" to notice.message,
                            "raw" to notice.messageOverride,
                            "tone" to notice.tone.name,
                        )
                    } else {
                        emptyMap()
                    }
                },
                restore = { values ->
                    if (values.isNotEmpty()) {
                        AdvancedNotice(
                            title = values["t"] as Int,
                            message = values["m"] as Int,
                            messageOverride = values["raw"] as String?,
                            tone = WarningBannerTone.valueOf(values["tone"] as String),
                        )
                    } else {
                        null
                    }
                },
            ),
    ) { mutableStateOf<AdvancedNotice?>(null) }

@Composable
internal fun ObserveSettingsEffects(
    effects: kotlinx.coroutines.flow.Flow<SettingsEffect>,
    onNotice: (AdvancedNotice) -> Unit,
) {
    val performHaptic = rememberRipDpiHapticPerformer()

    LifecycleEventEffect(effects) { effect ->
        if (effect is SettingsEffect.Notice) {
            performHaptic(settingsNoticeHaptic(effect))
            onNotice(mapNoticeEffect(effect))
        }
    }
}

internal fun settingsNoticeHaptic(effect: SettingsEffect.Notice): RipDpiHapticFeedback =
    when (effect.tone) {
        SettingsNoticeTone.Error -> RipDpiHapticFeedback.Error
        SettingsNoticeTone.Info, SettingsNoticeTone.Warning -> RipDpiHapticFeedback.Acknowledge
    }
