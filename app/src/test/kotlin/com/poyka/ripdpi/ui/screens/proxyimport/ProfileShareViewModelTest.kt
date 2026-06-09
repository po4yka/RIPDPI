package com.poyka.ripdpi.ui.screens.proxyimport

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests for [ProfileShareViewModel]: QR + plain-URI generation for a saved profile.
 *
 * Generation is offline. The first invocation must show a secrets-redaction warning that
 * cannot be skipped for 5 seconds and cannot be permanently suppressed — every share
 * session re-shows it. The share URI is the canonical per-protocol scheme produced by
 * [com.poyka.ripdpi.data.uri.ProxyUriCodec].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileShareViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val vlessProfile =
        ProxyProfile.Vless(
            id = "p1",
            displayName = "Tokyo",
            groupId = "g1",
            server = "example.com",
            serverPort = 443,
            uuid = "11111111-2222-3333-4444-555555555555",
        )

    @Test
    fun `requesting share on a profile first shows the secrets warning, not the payload`() =
        runTest {
            val viewModel = ProfileShareViewModel()

            viewModel.onShareProfileRequested(vlessProfile)

            assertTrue(viewModel.uiState.value.warningVisible)
            // The share content is not exposed until the warning is acknowledged.
            assertNull(viewModel.uiState.value.shareUri)
        }

    @Test
    fun `the warning cannot be acknowledged before the 5s hold elapses`() =
        runTest {
            val viewModel = ProfileShareViewModel()
            viewModel.onShareProfileRequested(vlessProfile)

            assertFalse(viewModel.uiState.value.warningAcknowledgeEnabled)
            // Attempting to acknowledge early is a no-op.
            viewModel.onWarningAcknowledged()
            assertTrue(viewModel.uiState.value.warningVisible)
        }

    @Test
    fun `the acknowledge action unlocks after the 5s hold`() =
        runTest {
            val viewModel = ProfileShareViewModel()
            viewModel.onShareProfileRequested(vlessProfile)

            viewModel.onWarningHoldElapsed()

            assertTrue(viewModel.uiState.value.warningAcknowledgeEnabled)
        }

    @Test
    fun `acknowledging the warning reveals the canonical share uri`() =
        runTest {
            val viewModel = ProfileShareViewModel()
            viewModel.onShareProfileRequested(vlessProfile)
            viewModel.onWarningHoldElapsed()

            viewModel.onWarningAcknowledged()

            assertFalse(viewModel.uiState.value.warningVisible)
            val uri = viewModel.uiState.value.shareUri
            assertNotNull(uri)
            assertTrue(uri!!.startsWith("vless://"))
            assertTrue(uri.contains("example.com:443"))
        }

    @Test
    fun `the warning re-shows on every share session and is never permanently suppressed`() =
        runTest {
            val viewModel = ProfileShareViewModel()

            // First session: acknowledge fully.
            viewModel.onShareProfileRequested(vlessProfile)
            viewModel.onWarningHoldElapsed()
            viewModel.onWarningAcknowledged()
            viewModel.onShareDismissed()

            // Second session must show the warning again.
            viewModel.onShareProfileRequested(vlessProfile)
            assertTrue(viewModel.uiState.value.warningVisible)
            assertFalse(viewModel.uiState.value.warningAcknowledgeEnabled)
            assertNull(viewModel.uiState.value.shareUri)
        }

    @Test
    fun `the hold-duration constant is five seconds`() {
        assertEquals(5_000L, ProfileShareViewModel.WARNING_HOLD_MILLIS)
    }

    @Test
    fun `a raw-config profile that is not a uri cannot be shared`() =
        runTest {
            val rawConfig =
                ProxyProfile.RawConfig(
                    id = "p1",
                    displayName = "Opaque",
                    groupId = "g1",
                    config = "{\"outbounds\":[]}",
                )
            val viewModel = ProfileShareViewModel()

            viewModel.onShareProfileRequested(rawConfig)
            viewModel.onWarningHoldElapsed()
            viewModel.onWarningAcknowledged()

            assertNull(viewModel.uiState.value.shareUri)
            assertTrue(viewModel.uiState.value.unshareable)
        }

    @Test
    fun `dismissing the share sheet resets the session`() =
        runTest {
            val viewModel = ProfileShareViewModel()
            viewModel.onShareProfileRequested(vlessProfile)
            viewModel.onWarningHoldElapsed()
            viewModel.onWarningAcknowledged()

            viewModel.onShareDismissed()

            assertNull(viewModel.uiState.value.shareUri)
            assertFalse(viewModel.uiState.value.warningVisible)
        }
}
