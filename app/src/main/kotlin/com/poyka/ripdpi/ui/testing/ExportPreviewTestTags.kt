package com.poyka.ripdpi.ui.testing

internal object ExportPreviewTestTags {
    const val Sheet = "export-preview-sheet"
    const val Content = "export-preview-content"
    const val Confirm = "export-preview-confirm"
    const val Cancel = "export-preview-cancel"
    const val Summary = "export-preview-summary"
    const val FileName = "export-preview-file-name"
    const val ByteCount = "export-preview-byte-count"
    const val Preparing = "export-preview-preparing"
    const val Error = "export-preview-error"

    fun entry(index: Int): String = "export-preview-entry-" + index
}
