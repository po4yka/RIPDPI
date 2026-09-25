package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.activities.DiagnosticsEventUiModel
import com.poyka.ripdpi.activities.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.activities.DiagnosticsSessionRowUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DiagnosticsRowActionTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `row click semantics match available actions`() {
        var clicks = 0
        val event = DiagnosticsEventUiModel("event", "network", "info", "message", "now", DiagnosticsTone.Info)
        val session =
            DiagnosticsSessionRowUiModel(
                id = "session",
                profileId = "default",
                title = "Session",
                subtitle = "Details",
                pathMode = "IN_PATH",
                serviceMode = "VPN",
                status = "completed",
                startedAtLabel = "now",
                summary = "Summary",
                metrics = persistentListOf(),
                tone = DiagnosticsTone.Info,
            )
        val probe =
            DiagnosticsProbeResultUiModel(
                id = "probe",
                probeType = "dns",
                target = "example.com",
                outcome = "ok",
                tone = DiagnosticsTone.Info,
                details = persistentListOf(),
            )
        composeRule.setContent {
            RipDpiTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    EventRow(event, onClick = null, modifier = Modifier.testTag("event-info"))
                    EventRow(event, onClick = { clicks++ }, modifier = Modifier.testTag("event-detail"))
                    SessionRow(session, onClick = null, modifier = Modifier.testTag("session-info"))
                    SessionRow(session, onClick = { clicks++ }, modifier = Modifier.testTag("session-detail"))
                    ProbeResultRow(probe, onClick = null, modifier = Modifier.testTag("probe-info"))
                    ProbeResultRow(probe, onClick = { clicks++ }, modifier = Modifier.testTag("probe-detail"))
                    com.poyka.ripdpi.ui.screens.history.EventRow(
                        event,
                        onClick = null,
                        modifier = Modifier.testTag("history-info"),
                    )
                    com.poyka.ripdpi.ui.screens.history.EventRow(
                        event,
                        onClick = { clicks++ },
                        modifier = Modifier.testTag("history-detail"),
                    )
                }
            }
        }
        composeRule.onNodeWithTag("event-info").assertHasNoClickAction()
        composeRule.onNodeWithTag("session-info").assertHasNoClickAction()
        composeRule.onNodeWithTag("probe-info").assertHasNoClickAction()
        composeRule.onNodeWithTag("history-info").assertHasNoClickAction()
        composeRule
            .onNodeWithTag("event-detail")
            .performScrollTo()
            .assertHasClickAction()
            .performClick()
        composeRule
            .onNodeWithTag("session-detail")
            .performScrollTo()
            .assertHasClickAction()
            .performClick()
        composeRule
            .onNodeWithTag("probe-detail")
            .performScrollTo()
            .assertHasClickAction()
            .performClick()
        composeRule
            .onNodeWithTag("history-detail")
            .performScrollTo()
            .assertHasClickAction()
            .performClick()
        assertEquals(4, clicks)
    }
}
