package com.poyka.ripdpi.ui.screens.proxyimport

import android.app.Application
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-w411dp-h839dp-mdpi", application = Application::class)
class ProfileShareLocaleTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `retained profile updates displayed copied and shared sheet with the locale`() {
        val state = previewProfileShareUiState()
        val context = mutableStateOf(languageContext("en"))
        var copied = ""
        var shared = ""
        composeRule.setContent {
            val current = context.value
            CompositionLocalProvider(
                LocalContext provides current,
                LocalConfiguration provides current.resources.configuration,
            ) {
                RipDpiTheme {
                    ProfileShareScreen(state, null, false, {}, {}, {}, { copied = it }, { shared = it })
                }
            }
        }
        for (tag in listOf("en", "fr", "fa", "en")) {
            composeRule.runOnIdle { context.value = languageContext(tag) }
            val profile = requireNotNull(state.profile)
            val uri = requireNotNull(state.shareUri)
            val expected =
                context.value.getString(
                    R.string.profile_share_setup_sheet_template,
                    profile.displayName,
                    profileKindLabel(profile),
                    uri,
                )
            composeRule.onNodeWithTag(RipDpiTestTags.ProfileShareSheetText).assertTextEquals(expected)
            composeRule.onNodeWithTag(RipDpiTestTags.ProfileShareCopySheet).performScrollTo().performClick()
            composeRule.onNodeWithTag(RipDpiTestTags.ProfileShareShareSheet).performScrollTo().performClick()
            composeRule.runOnIdle {
                assertEquals(expected, copied)
                assertEquals(expected, shared)
                assertTrue(copied.contains(uri))
                assertTrue(copied.contains(profile.displayName))
            }
        }
    }

    private fun languageContext(tag: String) =
        RuntimeEnvironment.getApplication().let { app ->
            app.createConfigurationContext(
                Configuration(app.resources.configuration).apply { setLocales(LocaleList(Locale.forLanguageTag(tag))) },
            )
        }
}
