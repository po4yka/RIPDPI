package com.poyka.ripdpi.activities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticsDpiSuiteCustomDomainsTest {
    @Test
    fun `custom domains typed as URLs reach the probes as host names`() {
        assertEquals(
            listOf("example.com", "foo.org"),
            parseCustomDomains("https://Example.com/path?q=1,\nfoo.org; example.com/"),
        )
    }

    @Test
    fun `input without a valid host name yields no custom list`() {
        assertNull(parseCustomDomains("https:// ,;/"))
    }
}
