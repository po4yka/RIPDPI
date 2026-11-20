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
    /** The clipboard held something importable: a proxy URI, a subscription link, or a `.conf`. */
    data class Importable(
        val payload: ImportPayload,
    ) : ClipboardImportResult

    /**
     * The clipboard held content that is not a recognised proxy URI.
     *
     * @property scheme a known format label, or `""` if no known format was found.
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
 * Parsing reuses the shared [ImportPayloadClassifier]; the classifier
 * never throws and never echoes the payload.
 */
object ClipboardImportClassifier {
    /**
     * Classifies [clipboardText]. A `null` or blank value is [ClipboardImportResult.Empty];
     * importable text is [ClipboardImportResult.Importable]; anything else is
     * [ClipboardImportResult.UnknownContent] carrying at most a known format label.
     */
    fun classify(clipboardText: String?): ClipboardImportResult =
        when {
            clipboardText == null -> {
                ClipboardImportResult.Empty
            }

            // Classified on the main thread, so pasted bundle JSON keeps the import-text bound.
            !isWithinImportTextLimit(clipboardText) -> {
                ClipboardImportResult.UnknownContent("")
            }

            clipboardText.isBlank() -> {
                ClipboardImportResult.Empty
            }

            else -> {
                ImportPayloadClassifier.classify(clipboardText)?.let(ClipboardImportResult::Importable)
                    ?: ClipboardImportResult.UnknownContent(
                        ProxyUriShareDispatcher.schemeOf(clipboardText),
                    )
            }
        }
}
