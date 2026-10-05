package com.poyka.ripdpi.ui.screenshot

import androidx.compose.ui.unit.LayoutDirection
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.HomeAppliedConfigurationUiState
import com.poyka.ripdpi.ui.screens.home.HomeAppliedConfigurationPanel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RuntimeConfigurationScreenshotTest {
    @Test fun appliedLight() = capture("applied_light", R.string.runtime_config_applied)

    @Test fun appliedDark() = capture("applied_dark", R.string.runtime_config_applied, dark = true)

    @Test fun permissionRecovery() =
        capture(
            "permission_recovery",
            R.string.runtime_config_failed,
            pending = true,
            previous = true,
            message = R.string.runtime_config_permission,
        )

    @Test fun timeoutRecoveryDark() =
        capture(
            "timeout_recovery_dark",
            R.string.runtime_config_failed,
            pending = true,
            previous = true,
            dark = true,
            message = R.string.runtime_config_stop_timeout,
        )

    @Test
    @Config(qualifiers = "ar-rEG")
    fun unknownRecoveryRtlFont2() =
        capture(
            "unknown_recovery_rtl_font2",
            R.string.runtime_config_failed,
            pending = true,
            previous = true,
            rtl = true,
            font = 2f,
            message = R.string.runtime_config_lockdown_unknown,
        )

    @Test fun pendingLtrMaximumFont() =
        capture("pending_ltr_font2", R.string.runtime_config_pending, pending = true, font = 2f)

    @Test fun pendingLight() = capture("pending_light", R.string.runtime_config_pending, pending = true)

    @Test fun pendingDark() = capture("pending_dark", R.string.runtime_config_pending, pending = true, dark = true)

    @Test
    @Config(qualifiers = "ar-rEG")
    fun pendingRtlMaximumFont() =
        capture("pending_rtl_font2", R.string.runtime_config_pending, pending = true, rtl = true, font = 2f)

    @Test fun applyingLight() =
        capture(
            "applying_light",
            R.string.runtime_config_applying,
            applying = true,
            previous = true,
        )

    @Test fun applyingDark() =
        capture("applying_dark", R.string.runtime_config_applying, applying = true, previous = true, dark = true)

    @Test fun failedLight() = capture("failed_light", R.string.runtime_config_failed, previous = true)

    @Test fun failedDark() = capture("failed_dark", R.string.runtime_config_failed, previous = true, dark = true)

    @Test
    @Config(qualifiers = "ar-rEG")
    fun failedRtlMaximumFont() =
        capture("failed_rtl_font2", R.string.runtime_config_failed, previous = true, rtl = true, font = 2f)

    @Test fun unknownLight() = capture("unknown_light", R.string.runtime_config_unknown, confirmed = false)

    @Test fun unknownDark() = capture("unknown_dark", R.string.runtime_config_unknown, confirmed = false, dark = true)

    private fun capture(
        name: String,
        status: Int,
        pending: Boolean = false,
        applying: Boolean = false,
        previous: Boolean = false,
        confirmed: Boolean = true,
        dark: Boolean = false,
        rtl: Boolean = false,
        font: Float = 1f,
        message: Int? = null,
    ) {
        val context = RuntimeEnvironment.getApplication()
        captureSingle(
            name = name,
            widthDp = 420,
            heightDp = if (font > 1) 1200 else 640,
            darkMode = dark,
            fontScale = font,
            layoutDirection = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            testClassFqn = javaClass.name,
        ) {
            HomeAppliedConfigurationPanel(
                HomeAppliedConfigurationUiState(
                    visible = true,
                    status = context.getString(status),
                    confirmedSummary =
                        if (confirmed) {
                            "Xray · WS · ${context.getString(
                                R.string.dns_mode_doh,
                            )} · DoH"
                        } else {
                            null
                        },
                    message = message?.let(context::getString),
                    previous = previous,
                    pending = pending,
                    confirmation =
                        com.poyka.ripdpi.services.RunningReconnectRequest.ConfirmedRuntime(
                            com.poyka.ripdpi.data.Mode.VPN,
                            "fixture-runtime",
                            1,
                        ),
                    reconnecting = applying,
                ),
                onReconnect = {},
                onCancelReconnect = {},
            )
        }
    }
}
