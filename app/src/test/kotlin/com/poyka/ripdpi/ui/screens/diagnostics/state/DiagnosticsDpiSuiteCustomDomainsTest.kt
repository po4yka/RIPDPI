package com.poyka.ripdpi.ui.screens.diagnostics.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticsDpiSuiteCustomDomainsTest {
    @Test
    fun `custom domains typed as URLs reach the probes as host names`() {
        assertEquals(
            listOf("example.com", "foo.org"),
            requireNotNull(parseCustomTargets("https://Example.com/path?q=1,\nfoo.org; example.com/")).domains,
        )
    }

    @Test
    fun `non-blank input without a valid host name yields an empty list, not the defaults`() {
        val targets = requireNotNull(parseCustomTargets("https:// ,;/"))
        assertEquals(emptyList<String>(), targets.domains)
        assertEquals(emptyList<String>(), targets.quic)
    }

    @Test
    fun `custom IPv6 literals reach only the QUIC lane under their complete address`() {
        val targets = requireNotNull(parseCustomTargets("https://[2001:DB8::1]:8443/a, 2001:db8::1; Example.org"))
        assertEquals(listOf("example.org"), targets.domains)
        assertEquals(listOf("2001:db8::1", "example.org"), targets.quic)
    }

    @Test
    fun `blank input selects the bundled defaults`() {
        assertNull(parseCustomTargets("  \n "))
    }
}
