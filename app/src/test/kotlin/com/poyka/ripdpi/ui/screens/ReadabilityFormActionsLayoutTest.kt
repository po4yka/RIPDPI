package com.poyka.ripdpi.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.screens.settings.BackupPinEditor
import com.poyka.ripdpi.ui.screens.settings.CommunityApiUrlEditor
import com.poyka.ripdpi.ui.screens.settings.DomainBypassEditorCard
import com.poyka.ripdpi.ui.screens.settings.DomainBypassListScreenState
import com.poyka.ripdpi.ui.screens.settings.WsTunnelWorkerSettingsUi
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-rRU-w420dp-h1800dp")
class ReadabilityFormActionsLayoutTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun domainListActionsKeepCompleteWordsAndDistinctCallbacks() {
        val clicks = IntArray(4)
        val labels =
            listOf(
                R.string.config_save,
                R.string.domain_bypass_paste,
                R.string.domain_bypass_copy,
                R.string.domain_bypass_move_to_editor,
            )
        assertMatrix(labels) {
            DomainBypassEditorCard(
                DomainBypassListScreenState("example.net", emptyList(), 1, true),
                {},
                { clicks[0]++ },
                { clicks[1]++ },
                { clicks[2]++ },
                { clicks[3]++ },
            )
        }
        labels.forEach { composeRule.onNodeWithText(text(it)).performScrollTo().performClick() }
        composeRule.runOnIdle { clicks.forEach { assertEquals(1, it) } }
    }

    @Test
    fun workerSettingsActionsDoNotCompressLabels() {
        var clears = 0
        assertMatrix(listOf(R.string.ws_tunnel_worker_transport_edit, R.string.ws_tunnel_worker_transport_clear)) {
            WsTunnelWorkerSettingsUi("https://fixture.example", "fixture-ref", true, { _, _, _ -> }, { clears++ })
        }
        composeRule.onNodeWithText(text(R.string.ws_tunnel_worker_transport_clear)).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, clears) }
    }

    @Test
    fun communityUrlActionsKeepCompleteLabelsAndCallbacks() {
        var saves = 0
        var resets = 0
        val labels = listOf(R.string.settings_community_api_url_save, R.string.settings_community_api_url_reset)
        assertMatrix(labels) { CommunityApiUrlEditor("https://fixture.example", {}, { saves++ }, { resets++ }) }
        labels.forEach { composeRule.onNodeWithText(text(it)).performScrollTo().performClick() }
        composeRule.runOnIdle {
            assertEquals(1, saves)
            assertEquals(1, resets)
        }
    }

    @Test
    fun backupPinActionsKeepCompleteLabelsAndCallbacks() {
        var saves = 0
        var clears = 0
        val labels = listOf(R.string.settings_backup_pin_save, R.string.settings_backup_pin_clear)
        assertMatrix(labels) { BackupPinEditor("1234", null, true, {}, { saves++ }, { clears++ }, true) }
        labels.forEach { composeRule.onNodeWithText(text(it)).performScrollTo().performClick() }
        // Clearing the PIN removes backup protection, so it waits for confirmation.
        composeRule.runOnIdle { assertEquals(0, clears) }
        composeRule.onNodeWithTag(RipDpiTestTags.SettingsBackupPinClearConfirm).performClick()
        composeRule.runOnIdle {
            assertEquals(1, saves)
            assertEquals(1, clears)
        }
    }

    private fun assertMatrix(
        labels: List<Int>,
        content: @Composable () -> Unit,
    ) {
        var width by mutableStateOf(320.dp)
        var scale by mutableStateOf(1f)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                RipDpiTheme {
                    Box(Modifier.requiredWidth(width)) {
                        Column(Modifier.verticalScroll(rememberScrollState())) { content() }
                    }
                }
            }
        }
        listOf(320.dp, 420.dp).forEach { testWidth ->
            listOf(1f, 2f).forEach { testScale ->
                composeRule.runOnIdle {
                    width = testWidth
                    scale = testScale
                }
                labels.forEach { label ->
                    val node = composeRule.onNodeWithText(text(label), useUnmergedTree = true).performScrollTo()
                    val layouts = mutableListOf<TextLayoutResult>()
                    node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    val layout = layouts.single()
                    assertFalse("'${text(label)}' overflows at $testWidth, font $testScale", layout.hasVisualOverflow)
                    assertTrue((0 until layout.lineCount).none(layout::isLineEllipsized))
                    assertTrue(
                        "'${text(label)}' is squeezed below its longest word",
                        layout.size.width + 1f >= layout.multiParagraph.intrinsics.minIntrinsicWidth,
                    )
                }
            }
        }
    }

    private fun text(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)
}
