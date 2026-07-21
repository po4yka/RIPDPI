package com.poyka.ripdpi.ui.screens.config

import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
                    42L,
                )
            pkcs12State.value = PendingMasquePkcs12Import(Uri.parse("content://credential/client.p12"), 43L)
            passwordState.value = "never-save-this"
        }

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.runOnIdle {
            assertEquals(MasqueImportRequest(MasqueImportAction.PrivateKey, 41L), requestState.value)
            assertEquals(
                PendingMasqueDocumentResult(
                    MasqueImportAction.CertificateChain,
                    Uri.parse("content://credential/client.pem"),
                    42L,
                ),
                resultState.value,
            )
            assertEquals(
                PendingMasquePkcs12Import(Uri.parse("content://credential/client.p12"), 43L),
                pkcs12State.value,
            )
            assertEquals("", passwordState.value)
        }
    }

    @Test
    fun `selected document is consumed for its original editor session`() {
        val pending =
            PendingMasqueDocumentResult(
                MasqueImportAction.PrivateKey,
                Uri.parse("content://credential/client.key"),
                77L,
            )
        var consumed: PendingMasqueDocumentResult? = null

        composeRule.setContent {
            ModeEditorPendingDocumentResultEffect(
                pendingResult = pending,
                editorSessionId = 77L,
                onReady = { consumed = it },
                onDiscard = {},
            )
        }

        composeRule.runOnIdle {
            assertEquals(pending, consumed)
        }
    }

    @Test
    fun `selected document is discarded for a replacement editor session`() {
        val pending =
            PendingMasqueDocumentResult(
                MasqueImportAction.PrivateKey,
                Uri.parse("content://credential/client.key"),
                77L,
            )
        var consumed: PendingMasqueDocumentResult? = null
        var discarded = false

        composeRule.setContent {
            ModeEditorPendingDocumentResultEffect(
                pendingResult = pending,
                editorSessionId = 88L,
                onReady = { consumed = it },
                onDiscard = { discarded = true },
            )
        }

        composeRule.runOnIdle {
            assertNull(consumed)
            assertEquals(true, discarded)
        }
    }

    @Test
    fun `selected document is discarded while editor session is absent`() {
        val pending =
            PendingMasqueDocumentResult(
                MasqueImportAction.PrivateKey,
                Uri.parse("content://credential/client.key"),
                77L,
            )
        var consumed: PendingMasqueDocumentResult? = null
        var discarded = false

        composeRule.setContent {
            ModeEditorPendingDocumentResultEffect(
                pendingResult = pending,
                editorSessionId = null,
                onReady = { consumed = it },
                onDiscard = { discarded = true },
            )
        }

        composeRule.runOnIdle {
            assertNull(consumed)
            assertEquals(true, discarded)
        }
    }

    @Test
    fun `permission result is consumed for its original editor session`() {
        var consumedSessionId: Long? = null

        composeRule.setContent {
            ModeEditorPendingDraftUpdateEffect(
                pendingSessionId = 88L,
                editorSessionId = 88L,
                onReady = { consumedSessionId = it },
                onDiscard = {},
            )
        }

        composeRule.runOnIdle {
            assertEquals(88L, consumedSessionId)
        }
    }

    @Test
    fun `permission result is discarded while editor session is absent`() {
        var consumedSessionId: Long? = null
        var discarded = false

        composeRule.setContent {
            ModeEditorPendingDraftUpdateEffect(
                pendingSessionId = 88L,
                editorSessionId = null,
                onReady = { consumedSessionId = it },
                onDiscard = { discarded = true },
            )
        }

        composeRule.runOnIdle {
            assertNull(consumedSessionId)
            assertEquals(true, discarded)
        }
    }

    @Test
    fun `permission result is discarded for a replacement editor session`() {
        var consumedSessionId: Long? = null
        var discarded = false

        composeRule.setContent {
            ModeEditorPendingDraftUpdateEffect(
                pendingSessionId = 88L,
                editorSessionId = 99L,
                onReady = { consumedSessionId = it },
                onDiscard = { discarded = true },
            )
        }

        composeRule.runOnIdle {
            assertNull(consumedSessionId)
            assertEquals(true, discarded)
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
