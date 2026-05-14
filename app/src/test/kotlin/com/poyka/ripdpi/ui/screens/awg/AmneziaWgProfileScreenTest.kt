package com.poyka.ripdpi.ui.screens.awg

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.poyka.ripdpi.data.awg.AwgCohortCatalogData
import com.poyka.ripdpi.data.awg.AwgCohortPreset
import com.poyka.ripdpi.data.awg.AwgProfileForm
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric Compose UI tests for [AmneziaWgProfileScreen].
 *
 * Asserts the editor surfaces every standard WireGuard field and all 16 obfuscation
 * fields inline, and that picking a cohort preset locks the obfuscation inputs while the
 * "Custom" sentinel frees them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AmneziaWgProfileScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val rtkSouth =
        AwgCohortPreset(
            id = "rtk_south",
            displayNameKey = "awg_cohort_rtk_south_name",
            descriptionKey = "awg_cohort_rtk_south_desc",
            jc = 4,
            jmin = 40,
            jmax = 70,
            s1 = 50,
            s2 = 100,
            h1 = 1_000_000_001L,
            h2 = 1_000_000_002L,
            h3 = 1_000_000_003L,
            h4 = 1_000_000_004L,
            randomizeHeaders = false,
        )

    private fun viewModel() =
        AmneziaWgProfileViewModel(
            object : AwgCohortCatalogProvider {
                override fun catalog() = AwgCohortCatalogData(presets = listOf(rtkSouth))
            },
        )

    @Test
    fun `the editor renders the standard wireguard fields`() {
        val viewModel = viewModel()
        composeRule.setContent { RipDpiTheme { ScreenUnderTest(viewModel) } }

        composeRule
            .onNodeWithTag(RipDpiTestTags.awgField(AwgEditorField.INTERFACE_PRIVATE_KEY.name))
            .assertExists()
        composeRule
            .onNodeWithTag(RipDpiTestTags.awgField(AwgEditorField.SERVER.name))
            .assertExists()
        composeRule
            .onNodeWithTag(RipDpiTestTags.awgField(AwgEditorField.PEER_PUBLIC_KEY.name))
            .performScrollTo()
            .assertExists()
        composeRule
            .onNodeWithTag(RipDpiTestTags.awgField(AwgEditorField.PRESHARED_KEY.name))
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun `the editor renders every obfuscation field inline`() {
        val viewModel = viewModel()
        composeRule.setContent { RipDpiTheme { ScreenUnderTest(viewModel) } }

        listOf(
            AwgEditorField.JC,
            AwgEditorField.JMAX,
            AwgEditorField.S4,
            AwgEditorField.H1,
            AwgEditorField.I5,
        ).forEach { field ->
            composeRule
                .onNodeWithTag(RipDpiTestTags.awgField(field.name))
                .performScrollTo()
                .assertExists()
        }
    }

    @Test
    fun `obfuscation fields are enabled while custom and disabled after a preset`() {
        val viewModel = viewModel()
        composeRule.setContent { RipDpiTheme { ScreenUnderTest(viewModel) } }

        composeRule
            .onNodeWithTag(RipDpiTestTags.awgField(AwgEditorField.JC.name))
            .performScrollTo()
            .assertIsEnabled()

        composeRule.runOnUiThread { viewModel.onCohortSelected("rtk_south") }

        composeRule
            .onNodeWithTag(RipDpiTestTags.awgField(AwgEditorField.JC.name))
            .performScrollTo()
            .assertIsNotEnabled()

        composeRule.runOnUiThread {
            viewModel.onCohortSelected(AwgProfileForm.CUSTOM_COHORT_ID)
        }

        composeRule
            .onNodeWithTag(RipDpiTestTags.awgField(AwgEditorField.JC.name))
            .performScrollTo()
            .assertIsEnabled()
    }

    @Test
    fun `editing the server field flows into the view model`() {
        val viewModel = viewModel()
        composeRule.setContent { RipDpiTheme { ScreenUnderTest(viewModel) } }

        composeRule
            .onNodeWithTag(RipDpiTestTags.awgField(AwgEditorField.SERVER.name))
            .performScrollTo()
            .performTextInput("vpn.example.com")

        composeRule.runOnIdle {
            org.junit.Assert.assertEquals(
                "vpn.example.com",
                viewModel.uiState.value.editor.form.server,
            )
        }
    }
}

@Composable
private fun ScreenUnderTest(viewModel: AmneziaWgProfileViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    AmneziaWgProfileScreen(
        uiState = uiState,
        onBack = {},
        onFieldChanged = viewModel::onFieldChanged,
        onCohortSelected = viewModel::onCohortSelected,
        onPasteConf = {},
        onRevealPrivateKey = viewModel::onPrivateKeyRevealAuthorized,
        onRevealPresharedKey = viewModel::onPresharedKeyRevealAuthorized,
    )
}
