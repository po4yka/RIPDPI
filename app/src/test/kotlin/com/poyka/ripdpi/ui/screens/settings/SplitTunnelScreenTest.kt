package com.poyka.ripdpi.ui.screens.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.R
import com.poyka.ripdpi.services.SplitTunnelMode
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SplitTunnelScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `missing system settings activity retains local split tunnel editor`() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).checkActivities(true)

        setSystemManagedScreen { activityContext ->
            activityContext.startActivity(SplitTunnelSystemUiGate.createExclusionSettingsIntent())
        }

        composeRule
            .onNodeWithText(context.getString(R.string.split_tunnel_open_system_settings))
            .performClick()

        assertLocalEditorDisplayed()
    }

    @Test
    fun `system settings permission denial retains local split tunnel editor`() {
        val context = RuntimeEnvironment.getApplication()
        setSystemManagedScreen { activityContext ->
            activityContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.SET_TIME,
                "Settings access denied",
            )
        }

        composeRule
            .onNodeWithText(context.getString(R.string.split_tunnel_open_system_settings))
            .performClick()

        assertLocalEditorDisplayed()
    }

    @Test
    fun `successful settings launch keeps the system managed screen`() {
        val context = RuntimeEnvironment.getApplication()
        val intent = SplitTunnelSystemUiGate.createExclusionSettingsIntent()
        shadowOf(context).checkActivities(true)
        val settingsActivity = ComponentName("com.android.settings", "com.android.settings.VpnAppExclusionSettings")
        shadowOf(context.packageManager).addActivityIfNotPresent(settingsActivity).exported = true
        shadowOf(context.packageManager).addIntentFilterForActivity(
            settingsActivity,
            IntentFilter(intent.action).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
            },
        )
        setSystemManagedScreen { activityContext -> activityContext.startActivity(intent) }

        composeRule
            .onNodeWithText(context.getString(R.string.split_tunnel_open_system_settings))
            .performClick()
            .assertIsDisplayed()

        composeRule.onNodeWithText(context.getString(R.string.split_tunnel_mode_label)).assertDoesNotExist()
        assertEquals(intent.action, shadowOf(context).nextStartedActivity.action)
    }

    private fun setSystemManagedScreen(onOpenSystemSettings: (Context) -> Unit) {
        composeRule.setContent {
            val activityContext = LocalContext.current
            RipDpiTheme {
                SplitTunnelScreen(
                    state =
                        SplitTunnelScreenState(
                            mode = SplitTunnelMode.Exclude,
                            selectedCount = 2,
                            fullTunnelMode = false,
                            usesSystemExclusionScreen = true,
                        ),
                    installedApps = emptyList(),
                    selectedPackages = emptySet(),
                    onBack = {},
                    onModeSelected = {},
                    onSelectionConfirmed = {},
                    onOpenSystemSettings = {
                        onOpenSystemSettings(activityContext)
                    },
                )
            }
        }
    }

    private fun assertLocalEditorDisplayed() {
        val context = RuntimeEnvironment.getApplication()
        composeRule
            .onNodeWithText(context.getString(R.string.split_tunnel_mode_label))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.split_tunnel_edit_apps, 2))
            .performScrollTo()
            .assertIsDisplayed()
    }
}
