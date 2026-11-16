package com.poyka.ripdpi.ui.screens.status

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.StatusAppliedConfigurationUiState
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PausePhase
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.inputs.RipDpiConnectionActuator
import com.poyka.ripdpi.ui.components.inputs.RipDpiConnectionStrategyPanel
import kotlinx.collections.immutable.persistentListOf
import kotlin.time.Duration.Companion.minutes

@Preview(name = "Hero pause available", widthDp = 360)
@Composable
private fun HeroPauseAvailablePreview() {
    HeroSessionScene(pause = StatusPauseUiState(available = true))
}

@Preview(name = "Hero pause available dark", widthDp = 360)
@Composable
private fun HeroPauseAvailableDarkPreview() {
    HeroSessionScene(theme = "dark", pause = StatusPauseUiState(available = true))
}

@Preview(name = "Hero paused", widthDp = 360)
@Composable
private fun HeroPausedPreview() {
    HeroSessionScene(pause = PausedState)
}

@Preview(name = "Hero paused dark", widthDp = 360)
@Composable
private fun HeroPausedDarkPreview() {
    HeroSessionScene(theme = "dark", pause = PausedState)
}

@Preview(name = "Hero pending reconnect", widthDp = 360)
@Composable
private fun HeroPendingReconnectPreview() {
    HeroSessionScene(pause = StatusPauseUiState(available = true), pending = true)
}

@Preview(name = "Hero pending reconnect dark", widthDp = 360)
@Composable
private fun HeroPendingReconnectDarkPreview() {
    HeroSessionScene(theme = "dark", pause = StatusPauseUiState(available = true), pending = true)
}

@Preview(name = "Hero reconnecting", widthDp = 360)
@Composable
private fun HeroReconnectingPreview() {
    HeroSessionScene(reconnecting = true)
}

@Preview(name = "Hero reconnecting dark", widthDp = 360)
@Composable
private fun HeroReconnectingDarkPreview() {
    HeroSessionScene(theme = "dark", reconnecting = true)
}

@Preview(name = "Strategy row closed and open", widthDp = 360)
@Composable
private fun StrategyRowPreview() {
    StrategyRowScene(theme = "light")
}

@Preview(name = "Strategy row closed and open dark", widthDp = 360)
@Composable
private fun StrategyRowDarkPreview() {
    StrategyRowScene(theme = "dark")
}

private val PausedState =
    StatusPauseUiState(
        phase = PausePhase.Paused,
        mode = Mode.VPN,
        deadlineWallMillis = 0L,
        remainingMillis = 15.minutes.inWholeMilliseconds,
    )

@Composable
private fun HeroSessionScene(
    theme: String = "light",
    pause: StatusPauseUiState = StatusPauseUiState(),
    pending: Boolean = false,
    reconnecting: Boolean = false,
) {
    val applied =
        StatusAppliedConfigurationUiState(
            visible = pending || reconnecting,
            status =
                stringResource(
                    if (reconnecting) R.string.runtime_config_applying else R.string.runtime_config_pending,
                ),
            pending = pending,
            reconnecting = reconnecting,
        )
    RipDpiComponentPreview(themePreference = theme) {
        RipDpiConnectionActuator(
            state =
                StatusConnectionActuatorUiState(
                    status =
                        if (reconnecting) {
                            StatusConnectionActuatorStatus.Engaging
                        } else {
                            StatusConnectionActuatorStatus.Locked
                        },
                    sessionDuration = 15.minutes,
                ),
            onActivate = {},
            onDeactivate = {},
            sessionControls = {
                if (pause != StatusPauseUiState()) StatusPauseControls(pause, {}, {}, {})
                StatusAppliedConfigurationPanel(applied, onReconnect = {}, onCancelReconnect = {})
            },
        )
    }
}

@Composable
private fun StrategyRowScene(theme: String) {
    val passage = compileStrategyPassageScene(emptyList(), emptyList())
    RipDpiComponentPreview(themePreference = theme) {
        listOf(false, true).forEach { open ->
            RipDpiConnectionStrategyPanel(
                strategyLabel = "tcp: none",
                facets = persistentListOf(StatusModeSummaryFacet("DNS", "AdGuard DNS DoH")),
                status = StatusConnectionActuatorStatus.Locked,
                passage = passage,
                initiallyExpanded = open,
            )
        }
    }
}
