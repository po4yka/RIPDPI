package com.poyka.ripdpi.ui.screens.proxyimport

import com.poyka.ripdpi.proxyimport.ClipboardReader
import com.poyka.ripdpi.proxyimport.ProxyImportRequest
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests for [ClipboardImportViewModel]: the explicit "Import from clipboard" action.
 *
 * The clipboard is read exactly once, only when [ClipboardImportViewModel.onImportFromClipboard]
 * is invoked from the user's menu tap — there is no watcher and nothing polls. A recognised
 * URI produces a navigation request to the profile-confirm destination; unknown content
 * produces a typed error carrying only the scheme. The optional "clear clipboard after
 * import" behaviour is opt-in and defaults to off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClipboardImportViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `importing a vless uri from the clipboard produces a navigation request`() =
        runTest {
            val clipboard = FakeClipboardReader("vless://uuid@example.com:443#Clip")
            val viewModel = ClipboardImportViewModel(clipboard)

            viewModel.onImportFromClipboard()

            val request = viewModel.uiState.value.navigateToConfirm
            assertTrue(request is ProxyImportRequest.Profile)
            assertEquals(1, clipboard.readCount)
        }

    @Test
    fun `the clipboard is read once and only on explicit action`() =
        runTest {
            val clipboard = FakeClipboardReader("vless://uuid@example.com:443#Clip")
            val viewModel = ClipboardImportViewModel(clipboard)

            // Constructing the ViewModel must not touch the clipboard.
            assertEquals(0, clipboard.readCount)

            viewModel.onImportFromClipboard()
            assertEquals(1, clipboard.readCount)
        }

    @Test
    fun `unknown clipboard content surfaces a typed error carrying the scheme`() =
        runTest {
            val clipboard = FakeClipboardReader("http://malicious.example.com/leak?token=abc")
            val viewModel = ClipboardImportViewModel(clipboard)

            viewModel.onImportFromClipboard()

            assertNull(viewModel.uiState.value.navigateToConfirm)
            assertEquals("http", viewModel.uiState.value.unknownContentScheme)
        }

    @Test
    fun `empty clipboard surfaces the empty-clipboard error`() =
        runTest {
            val clipboard = FakeClipboardReader(null)
            val viewModel = ClipboardImportViewModel(clipboard)

            viewModel.onImportFromClipboard()

            assertTrue(viewModel.uiState.value.clipboardEmpty)
            assertNull(viewModel.uiState.value.navigateToConfirm)
        }

    @Test
    fun `clear-after-import is off by default so the clipboard is not cleared`() =
        runTest {
            val clipboard = FakeClipboardReader("vless://uuid@example.com:443#Clip")
            val viewModel = ClipboardImportViewModel(clipboard)

            assertFalse(viewModel.uiState.value.clearClipboardAfterImport)
            viewModel.onImportFromClipboard()

            assertFalse(clipboard.cleared)
        }

    @Test
    fun `opting in to clear-after-import clears the clipboard on a successful import`() =
        runTest {
            val clipboard = FakeClipboardReader("vless://uuid@example.com:443#Clip")
            val viewModel = ClipboardImportViewModel(clipboard)

            viewModel.setClearClipboardAfterImport(true)
            viewModel.onImportFromClipboard()

            assertTrue(clipboard.cleared)
        }

    @Test
    fun `clear-after-import does not clear the clipboard when the content is unknown`() =
        runTest {
            val clipboard = FakeClipboardReader("not a proxy uri")
            val viewModel = ClipboardImportViewModel(clipboard)

            viewModel.setClearClipboardAfterImport(true)
            viewModel.onImportFromClipboard()

            assertFalse(clipboard.cleared)
        }

    @Test
    fun `consuming the navigation request clears it from ui state`() =
        runTest {
            val clipboard = FakeClipboardReader("vless://uuid@example.com:443#Clip")
            val viewModel = ClipboardImportViewModel(clipboard)

            viewModel.onImportFromClipboard()
            viewModel.consumeNavigation()

            assertNull(viewModel.uiState.value.navigateToConfirm)
        }

    @Test
    fun `dismissing the error clears the error fields`() =
        runTest {
            val clipboard = FakeClipboardReader("http://nope")
            val viewModel = ClipboardImportViewModel(clipboard)

            viewModel.onImportFromClipboard()
            viewModel.dismissError()

            assertNull(viewModel.uiState.value.unknownContentScheme)
            assertFalse(viewModel.uiState.value.clipboardEmpty)
        }
}

/** In-memory [ClipboardReader] double: never touches a real `ClipboardManager`. */
private class FakeClipboardReader(
    private var contents: String?,
) : ClipboardReader {
    var readCount: Int = 0
        private set
    var cleared: Boolean = false
        private set

    override fun readPrimaryClipText(): String? {
        readCount += 1
        return contents
    }

    override fun clearPrimaryClip() {
        cleared = true
        contents = null
    }
}
