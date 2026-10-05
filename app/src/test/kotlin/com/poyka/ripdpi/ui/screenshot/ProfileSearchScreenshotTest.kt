package com.poyka.ripdpi.ui.screenshot

import androidx.compose.ui.unit.LayoutDirection
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsProfileOptionUiModel
import com.poyka.ripdpi.activities.RelayProfileUiState
import com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.feedback.RipDpiBottomSheetCard
import com.poyka.ripdpi.ui.components.feedback.RipDpiSheetAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiSheetScrollPolicy
import com.poyka.ripdpi.ui.components.profiles.ProfileSearchState
import com.poyka.ripdpi.ui.screens.config.ConfigProfileActions
import com.poyka.ripdpi.ui.screens.config.VpnProfilePickerContent
import com.poyka.ripdpi.ui.screens.diagnostics.ProfilePickerContent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ProfileSearchScreenshotTest {
    @Test fun diagnosticsLight() = capture("diagnostics_light", diagnostic = true)

    @Test fun diagnosticsDark() = capture("diagnostics_dark", diagnostic = true, dark = true)

    @Test fun relayLight() = capture("relay_light")

    @Test fun relayDark() = capture("relay_dark", dark = true)

    @Test fun diagnosticsEmpty() = capture("diagnostics_empty", diagnostic = true, empty = true)

    @Test fun relayEmptyDark() = capture("relay_empty_dark", dark = true, empty = true)

    @Test
    @Config(qualifiers = "ar-rEG")
    fun diagnosticsRtlFont2() = capture("diagnostics_rtl_font2", diagnostic = true, rtl = true, font = 2f, empty = true)

    @Test
    @Config(qualifiers = "ar-rEG")
    fun relayRtlFont2() = capture("relay_rtl_font2", rtl = true, font = 2f)

    private fun capture(
        name: String,
        diagnostic: Boolean = false,
        dark: Boolean = false,
        rtl: Boolean = false,
        font: Float = 1f,
        empty: Boolean = false,
    ) {
        val context = RuntimeEnvironment.getApplication()
        captureSingle(
            name = name,
            widthDp = 411,
            heightDp = if (font > 1) 1400 else 900,
            darkMode = dark,
            fontScale = font,
            layoutDirection = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            testClassFqn = javaClass.name,
        ) {
            RipDpiBottomSheetCard(
                scrollPolicy = if (diagnostic) RipDpiSheetScrollPolicy.Container else RipDpiSheetScrollPolicy.Content,
                title =
                    context.getString(
                        if (diagnostic) R.string.diagnostics_profiles_title else R.string.config_vpn_profiles_title,
                    ),
                secondaryAction =
                    RipDpiSheetAction(context.getString(R.string.config_cancel), {
                    }, variant = RipDpiButtonVariant.Outline, wrapLabel = true),
            ) {
                val search = ProfileSearchState(if (empty) "no matching profile" else "")
                if (diagnostic) {
                    ProfilePickerContent(
                        profiles =
                            listOf(
                                DiagnosticsProfileOptionUiModel(
                                    "web",
                                    "Website check",
                                    "bundled",
                                    family = DiagnosticProfileFamily.WEB_CONNECTIVITY,
                                ),
                                DiagnosticsProfileOptionUiModel(
                                    "chat",
                                    "Messaging check",
                                    "bundled",
                                    family = DiagnosticProfileFamily.MESSAGING,
                                ),
                            ),
                        selectedProfileId = "web",
                        search = search,
                        onSelectProfile = {},
                    )
                } else {
                    VpnProfilePickerContent(
                        profiles =
                            listOf(
                                RelayProfileUiState("personal-vless", "vless", "VLESS", "", ""),
                                RelayProfileUiState("work-ssh", "ssh", "SSH", "", ""),
                            ),
                        selectedProfileId = "personal-vless",
                        search = search,
                        actions = ConfigProfileActions(),
                    )
                }
            }
        }
    }
}
