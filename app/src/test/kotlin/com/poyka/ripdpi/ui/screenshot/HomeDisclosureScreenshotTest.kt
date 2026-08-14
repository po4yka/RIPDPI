package com.poyka.ripdpi.ui.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HomeConnectionActuatorStatus
import com.poyka.ripdpi.activities.HomeConnectionActuatorUiState
import com.poyka.ripdpi.activities.HomeMode
import com.poyka.ripdpi.activities.HomeModeCardUiState
import com.poyka.ripdpi.activities.HomeModeSummaryFacet
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.ui.screens.home.HomeScreen
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HomeDisclosureScreenshotTest {
    @Test
    fun expandedCompact() {
        captureHomeDisclosure(name = "expandedCompact", widthDp = 390, heightDp = 1280)
    }

    @Test
    fun expandedDefault() {
        captureHomeDisclosure(name = "expandedDefault", widthDp = 411, heightDp = 1400)
    }

    @Test
    fun expandedLargeFont() {
        captureHomeDisclosure(name = "expandedLargeFont", widthDp = 411, heightDp = 1800, fontScale = 1.5f)
    }

    @Test
    fun expandedMaximumFont() {
        captureHomeDisclosure(name = "expandedMaximumFont", widthDp = 411, heightDp = 2400, fontScale = 2f)
    }

    private fun captureHomeDisclosure(
        name: String,
        widthDp: Int,
        heightDp: Int,
        fontScale: Float = 1f,
    ) {
        captureExperienceRipDpiScreenshot(
            name = name,
            testClassFqn = FQN,
            widthDp = widthDp,
            heightDp = heightDp,
            fontScale = fontScale,
        ) {
            RipDpiTheme { HomeDisclosureScene() }
        }
    }

    private companion object {
        const val FQN = "com.poyka.ripdpi.ui.screenshot.HomeDisclosureScreenshotTest"
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-rRU")
class RussianHomeDisclosureScreenshotTest {
    @Test
    fun expandedLargeFont() {
        captureLocalizedHomeDisclosure(
            testClassFqn = "com.poyka.ripdpi.ui.screenshot.RussianHomeDisclosureScreenshotTest",
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "es-rES")
class SpanishHomeDisclosureScreenshotTest {
    @Test
    fun expandedLargeFont() {
        captureLocalizedHomeDisclosure(
            testClassFqn = "com.poyka.ripdpi.ui.screenshot.SpanishHomeDisclosureScreenshotTest",
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ar-rSA")
class ArabicHomeDisclosureScreenshotTest {
    @Test
    fun expandedLargeFont() {
        captureLocalizedHomeDisclosure(
            testClassFqn = "com.poyka.ripdpi.ui.screenshot.ArabicHomeDisclosureScreenshotTest",
            layoutDirection = LayoutDirection.Rtl,
        )
    }
}

private fun captureLocalizedHomeDisclosure(
    testClassFqn: String,
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
) {
    assumeFullExperienceScreenshot()
    captureExperienceRipDpiScreenshot(
        name = "expandedLargeFont",
        testClassFqn = testClassFqn,
        widthDp = 411,
        heightDp = 1800,
        fontScale = 1.5f,
    ) {
        RipDpiTheme {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                HomeDisclosureScene()
            }
        }
    }
}

@Composable
private fun HomeDisclosureScene() {
    val inactive = stringResource(R.string.home_mode_card_status_inactive)
    val configure = stringResource(R.string.home_mode_card_configure)
    val cards =
        persistentListOf(
            HomeModeCardUiState(
                mode = HomeMode.LocalDpiBypass,
                title = stringResource(R.string.home_mode_local_dpi_bypass),
                primaryLabel = "",
                summaryFacets =
                    persistentListOf(
                        HomeModeSummaryFacet(
                            label = stringResource(R.string.config_local_bypass_strategy_title),
                            value = stringResource(R.string.config_local_bypass_strategy_auto),
                        ),
                        HomeModeSummaryFacet(
                            label = stringResource(R.string.title_dns_settings),
                            value = "${stringResource(R.string.dns_mode_doh)} · AdGuard DNS (DoH)",
                        ),
                    ),
                statusLine = inactive,
                primaryActionLabel = stringResource(R.string.home_mode_card_enable),
                configureLabel = configure,
            ),
            HomeModeCardUiState(
                mode = HomeMode.RemoteVpn,
                title = stringResource(R.string.home_mode_remote_vpn),
                primaryLabel = "VLESS Reality · relay.example",
                statusLine = inactive,
                primaryActionLabel = stringResource(R.string.home_mode_card_enable),
                configureLabel = configure,
            ),
            HomeModeCardUiState(
                mode = HomeMode.Diagnostic,
                title = stringResource(R.string.home_mode_diagnostic_scan),
                primaryLabel = stringResource(R.string.home_mode_card_diagnostic_empty),
                statusLine = inactive,
                primaryActionLabel = stringResource(R.string.home_mode_card_run_scan),
                configureLabel = configure,
            ),
        )
    HomeScreen(
        uiState =
            MainUiState(
                connectionActuator =
                    HomeConnectionActuatorUiState(
                        status = HomeConnectionActuatorStatus.Locked,
                        trailingLabel = stringResource(R.string.home_connection_actuator_relay),
                        routeLabel = stringResource(R.string.home_mode_local_dpi_bypass),
                        statusDescription = stringResource(R.string.home_connection_actuator_state_locked),
                        actionLabel = stringResource(R.string.home_connection_actuator_action_deactivate),
                        carriageFraction = 1f,
                    ),
                modeCards = cards,
            ),
        onToggleConnection = {},
        onOpenDiagnostics = {},
        onOpenHistory = {},
        onRepairPermission = {},
        onOpenVpnPermissionDialog = {},
        initialModesExpanded = true,
    )
}
