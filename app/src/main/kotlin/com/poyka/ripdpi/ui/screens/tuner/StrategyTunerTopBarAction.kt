package com.poyka.ripdpi.ui.screens.tuner

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons

/**
 * Top-bar affordance that opens the Strategy Tuner screen.
 *
 * Owned by the tuner feature so the cross-screen launch wiring stays out of the
 * diagnostics screen's action contract; the diagnostics top bar renders it
 * through a composable slot supplied by the navigation host.
 */
@Composable
fun StrategyTunerTopBarAction(onOpen: () -> Unit) {
    RipDpiIconButton(
        icon = RipDpiIcons.Advanced,
        contentDescription = stringResource(R.string.strategy_tuner_open_action),
        onClick = onOpen,
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsStrategyTunerAction),
    )
}
