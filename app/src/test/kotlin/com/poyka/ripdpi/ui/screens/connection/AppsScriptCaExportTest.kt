package com.poyka.ripdpi.ui.screens.connection

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AppsScriptCaExportTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `export requires disclosure confirmation and reports an unavailable certificate`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val registry = ExportRegistry()
        val owner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry: ActivityResultRegistry = registry
            }
        val title = context.getString(R.string.config_relay_apps_script_export_ca)
        val disclosure = context.getString(R.string.config_relay_apps_script_ca_trust)
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                RipDpiTheme { AppsScriptCaExport() }
            }
        }
        composeRule.onNodeWithText(title).performClick()
        composeRule.onNodeWithText(disclosure).assertIsDisplayed()
        composeRule.runOnIdle { assertNull(registry.launchedIntent) }
        composeRule.onNodeWithText(context.getString(R.string.action_dismiss)).performClick()
        composeRule.runOnIdle { assertNull(registry.launchedIntent) }

        composeRule.onNodeWithText(title).performClick()
        composeRule.onNode(hasText(title) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        composeRule.runOnIdle {
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, registry.launchedIntent?.action)
            assertEquals("ripdpi-apps-script-ca.crt", registry.launchedIntent?.getStringExtra(Intent.EXTRA_TITLE))
            registry.dispatchResult(
                checkNotNull(registry.requestCode),
                Activity.RESULT_OK,
                Intent().setData(Uri.parse("content://rly15-test/unavailable")),
            )
        }
        composeRule.waitUntil(10_000) {
            ShadowToast.getTextOfLatestToast() == context.getString(R.string.config_relay_apps_script_ca_unavailable)
        }
    }
}

private class ExportRegistry : ActivityResultRegistry() {
    var launchedIntent: Intent? = null
    var requestCode: Int? = null

    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
        this.requestCode = requestCode
        launchedIntent = contract.createIntent(ApplicationProvider.getApplicationContext(), input)
    }
}
