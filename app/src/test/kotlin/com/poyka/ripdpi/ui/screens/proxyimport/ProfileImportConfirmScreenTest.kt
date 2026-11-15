package com.poyka.ripdpi.ui.screens.proxyimport

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileImportConfirmScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `unsupported imports explain why Add is unavailable`() {
        render(
            ProfileImportConfirmUiState(
                profile = ProxyProfile.RawConfig("id", "TUIC", "", "fixture"),
                admissionErrorRes = R.string.import_error_unsupported,
            ),
        )

        composeRule.onNodeWithText(context.getString(R.string.import_error_unsupported)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.import_confirm_add_action)).assertDoesNotExist()
    }

    @Test
    fun `Hysteria import discloses disabled certificate verification before Add`() {
        render(
            ProfileImportConfirmUiState(
                profile = ProxyProfile.Hysteria2("id", "Hysteria", "", "example.com", 443, "fixture", insecure = true),
            ),
        )

        composeRule
            .onNodeWithText(
                context.getString(R.string.import_profile_confirm_tls_unverified),
            ).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.import_confirm_add_action)).assertIsDisplayed()
    }

    private fun render(state: ProfileImportConfirmUiState) {
        composeRule.setContent {
            RipDpiTheme { ProfileImportConfirmScreen(state, onBack = {}, onConfirm = {}) }
        }
    }
}
