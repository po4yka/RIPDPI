package com.poyka.ripdpi.proxyimport

/**
 * Outcome of classifying a single one-shot clipboard read for the explicit
 * "Import from clipboard" action.
 *
 * The clipboard is read exactly once, only after the user taps the menu entry — there is
 * no watcher, no polling, no auto-paste detection. The classification result deliberately
 * never carries the raw clipboard payload, only the scheme prefix, so a credential-bearing
 * URI cannot leak into logs or UI error text.
 */
sealed interface ClipboardImportResult {
    /** The clipboard held a recognised proxy share URI, ready for the confirm screen. */
    data class Importable(
        val request: ProxyImportRequest.Profile,
    ) : ClipboardImportResult

    /**
     * The clipboard held content that is not a recognised proxy URI.
     *
     * @property scheme the lower-cased `scheme` prefix that was found (`"http"`,
     *   `"vless"` for a structurally broken known scheme, …), or `""` when the content
     *   had no `scheme://` prefix at all. The payload itself is never retained.
     */
    data class UnknownContent(
        val scheme: String,
    ) : ClipboardImportResult

    /** The clipboard was empty (or held only whitespace). */
    data object Empty : ClipboardImportResult
}

/**
 * Classifies the text of a single, explicit clipboard read. This is pure logic: the
 * caller (the clipboard-import ViewModel) is responsible for performing the actual
 * one-shot `ClipboardManager` read only on user action, then handing the text here.
 *
 * Parsing reuses the shared [ProxyUriShareDispatcher] / `ProxyUriCodec`; the classifier
 * never throws and never echoes the payload.
 */
object ClipboardImportClassifier {
    /**
     * Classifies [clipboardText]. A `null` or blank value is [ClipboardImportResult.Empty];
     * a recognised proxy URI is [ClipboardImportResult.Importable]; anything else is
     * [ClipboardImportResult.UnknownContent] carrying only the scheme prefix.
     */
    fun classify(clipboardText: String?): ClipboardImportResult {
        val trimmed = clipboardText?.trim().orEmpty()
        if (trimmed.isEmpty()) return ClipboardImportResult.Empty
        return when (val request = ProxyUriShareDispatcher.dispatch(trimmed)) {
            is ProxyImportRequest.Profile -> ClipboardImportResult.Importable(request)
            is ProxyImportRequest.UnsupportedScheme -> ClipboardImportResult.UnknownContent(request.scheme)
        }
    }
}
