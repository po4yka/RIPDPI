package com.poyka.ripdpi.ui.screens.settings

import android.app.Application
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsEffect
import com.poyka.ripdpi.activities.SettingsNoticeTone
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
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
@Config(sdk = [35], qualifiers = "en", application = Application::class)
class AdvancedNoticeLocaleTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `saved security notice follows locale after restoration without replaying the action`() {
        val context = mutableStateOf(languageContext("en"))
        val restoration = StateRestorationTester(composeRule)
        lateinit var savedNotice: MutableState<AdvancedNotice?>
        val notice =
            mapNoticeEffect(
                SettingsEffect.Notice(
                    R.string.notice_pin_key_lost_title,
                    R.string.notice_pin_key_lost_message,
                    SettingsNoticeTone.Warning,
                ),
            )
        restoration.setContent {
            val current = context.value
            CompositionLocalProvider(
                LocalContext provides current,
                LocalConfiguration provides current.resources.configuration,
            ) {
                savedNotice = rememberAdvancedNoticeState()
                RipDpiTheme { savedNotice.value?.let { AdvancedSettingsNoticeBanner(it) } }
            }
        }
        composeRule.runOnIdle { savedNotice.value = notice }
        for (tag in listOf("en", "fr", "ar", "en")) {
            composeRule.runOnIdle { context.value = languageContext(tag) }
            restoration.emulateSavedInstanceStateRestore()
            composeRule.onNodeWithText(context.value.getString(notice.title)).assertExists()
            composeRule.onNodeWithText(context.value.getString(notice.message)).assertExists()
            composeRule.runOnIdle { assertEquals(notice, savedNotice.value) }
        }
        val raw = "Custom catalog response"
        composeRule.runOnIdle { savedNotice.value = notice.copy(messageOverride = raw) }
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText(raw).assertExists()
    }

    private fun languageContext(tag: String) =
        RuntimeEnvironment.getApplication().let { app ->
            app.createConfigurationContext(
                Configuration(app.resources.configuration).apply { setLocales(LocaleList(Locale.forLanguageTag(tag))) },
            )
        }
}
