package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.R
import org.junit.Assert.assertEquals
import org.junit.Test

class UnsupportedImportMessageTest {
    @Test
    fun `naive quic link routes to the dedicated message`() {
        val request =
            ProxyUriShareDispatcher.dispatch("naive+quic://u:p@h.example:443") as ProxyImportRequest.UnsupportedScheme

        assertEquals(R.string.import_error_naive_quic, unsupportedImportMessage(request.scheme))
        assertEquals(R.string.import_error_unsupported, unsupportedImportMessage("foo"))
    }
}
