package com.poyka.ripdpi.diagnostics.dpi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DpiTargetHostsTest {
    @Test
    fun quicTargetKeepsWholeHostsAndLiterals() {
        val cases =
            mapOf(
                " ExAmPlE.com./p?w=1#fragment " to "example.com",
                "https://Example.com:8443/path" to "example.com",
                "example.com/redirect?dest=https://other.example" to "example.com",
                "192.0.2.1:443" to "192.0.2.1",
                "2001:0DB8:0:0:0:0:0:1" to "2001:db8::1",
                "[::1]" to "::1",
                "https://[::1]:8443/a" to "::1",
            )

        for ((entry, host) in cases) {
            assertEquals(entry, host, quicTargetHostOrNull(entry))
        }
    }

    @Test
    fun malformedQuicTargetsCannotChangeTheProbedHost() {
        val invalid =
            listOf(
                "foo_bar.example",
                "-label.example",
                "label-.example",
                "example.com..",
                "example.com:0",
                "example.com:65536",
                "example.com:+443",
                "example.com:not-a-port",
                "user@host.example",
                "https://2001:db8::1/path",
                "2001:db8::badg",
                "[::1",
                "[::1]junk",
                "[fe80::1%en0]",
                "[192.0.2.1]",
                "192.0.2",
                "2001",
            )

        for (entry in invalid) {
            assertNull(entry, quicTargetHostOrNull(entry))
        }
        assertNull(dpiTargetHostOrNull("2001:db8::badg"))
    }
}
