package com.poyka.ripdpi.ui.screens.proxyimport

import android.content.Context
import android.widget.Toast
import com.poyka.ripdpi.R

// The confirmation screen closes on both outcomes, so the result is said as it closes.

/** Names what an import added. */
internal fun Context.toastImported(name: String) {
    Toast.makeText(this, getString(R.string.import_done_added, name), Toast.LENGTH_SHORT).show()
}

/** The staged import is gone (the process restarted, or the link was opened twice). */
internal fun Context.toastImportUnavailable() {
    Toast.makeText(this, R.string.import_unavailable, Toast.LENGTH_LONG).show()
}
