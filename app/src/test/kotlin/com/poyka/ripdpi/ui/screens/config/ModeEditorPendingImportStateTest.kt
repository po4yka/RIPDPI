package com.poyka.ripdpi.ui.screens.config

import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ModeEditorPendingImportStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `pending document metadata survives recreation without retaining password`() {
        val restorationTester = StateRestorationTester(composeRule)
        lateinit var requestState: MutableState<MasqueImportRequest?>
        lateinit var resultState: MutableState<PendingMasqueDocumentResult?>
        lateinit var pkcs12State: MutableState<PendingMasquePkcs12Import?>
        lateinit var passwordState: MutableState<String>

        restorationTester.setContent {
            requestState = rememberMasqueImportRequestState()
            resultState = rememberPendingMasqueDocumentResultState()
            pkcs12State = rememberPendingMasquePkcs12ImportState()
            passwordState = remember { mutableStateOf("") }
        }

        composeRule.runOnIdle {
            requestState.value = MasqueImportRequest(MasqueImportAction.PrivateKey, 41L)
            resultState.value =
                PendingMasqueDocumentResult(
                    MasqueImportAction.CertificateChain,
                    Uri.parse("content://credential/client.pem"),
                )
            pkcs12State.value = PendingMasquePkcs12Import(Uri.parse("content://credential/client.p12"))
            passwordState.value = "never-save-this"
        }

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.runOnIdle {
            assertEquals(MasqueImportRequest(MasqueImportAction.PrivateKey, 41L), requestState.value)
            assertEquals(
                PendingMasqueDocumentResult(
                    MasqueImportAction.CertificateChain,
                    Uri.parse("content://credential/client.pem"),
                ),
                resultState.value,
            )
            assertEquals(
                PendingMasquePkcs12Import(Uri.parse("content://credential/client.p12")),
                pkcs12State.value,
            )
            assertEquals("", passwordState.value)
        }
    }

    @Test
    fun `selected document waits for a fresh ready editor session`() {
        var readySessionId: Long? by mutableStateOf(null)
        val pending =
            PendingMasqueDocumentResult(
                MasqueImportAction.PrivateKey,
                Uri.parse("content://credential/client.key"),
            )
        var consumed: Pair<PendingMasqueDocumentResult, Long>? = null

        composeRule.setContent {
            ModeEditorPendingDocumentResultEffect(pending, readySessionId) { result, sessionId ->
                consumed = result to sessionId
            }
        }

        composeRule.runOnIdle {
            assertNull(consumed)
            readySessionId = 77L
        }
        composeRule.runOnIdle {
            assertEquals(pending to 77L, consumed)
        }
    }

    @Test
    fun `permission result waits for a fresh ready editor session`() {
        var readySessionId: Long? by mutableStateOf(null)
        var consumedSessionId: Long? = null

        composeRule.setContent {
            ModeEditorPendingDraftUpdateEffect(pending = true, readySessionId) { sessionId ->
                consumedSessionId = sessionId
            }
        }

        composeRule.runOnIdle {
            assertNull(consumedSessionId)
            readySessionId = 88L
        }
        composeRule.runOnIdle {
            assertEquals(88L, consumedSessionId)
        }
    }

    @Test
    fun `hydration failure remains visible until the user dismisses it`() {
        var dismissClicks = 0
        composeRule.setContent {
            RipDpiTheme {
                ModeEditorHydrationFailureDialog(
                    visible = true,
                    onDismiss = { dismissClicks += 1 },
                )
            }
        }

        composeRule
            .onNodeWithText("Couldn't open this configuration. Your saved credentials were not changed.")
            .assertExists()
        composeRule.onNodeWithText("Dismiss").performClick()
        assertEquals(1, dismissClicks)
    }

    @Test
    fun `empty pending import state remains empty after recreation`() {
        val restorationTester = StateRestorationTester(composeRule)
        lateinit var requestState: MutableState<MasqueImportRequest?>
        lateinit var resultState: MutableState<PendingMasqueDocumentResult?>
        lateinit var pkcs12State: MutableState<PendingMasquePkcs12Import?>

        restorationTester.setContent {
            requestState = rememberMasqueImportRequestState()
            resultState = rememberPendingMasqueDocumentResultState()
            pkcs12State = rememberPendingMasquePkcs12ImportState()
        }

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.runOnIdle {
            assertNull(requestState.value)
            assertNull(resultState.value)
            assertNull(pkcs12State.value)
        }
    }
}
