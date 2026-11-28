package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.LocalSocksCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSocksRouteTest {
    private val login = LocalSocksCredentials("user", "pass")
    private val path = "/data/user/0/app/files/proxy.sock"

    @Test
    fun `unix form is accepted`() {
        val route = LocalSocksRoute("", 0, login, unixPath = path)

        assertEquals(path, route.unixPath)
    }

    @Test
    fun `tcp form keeps working without credentials`() {
        assertEquals(1080, LocalSocksRoute("127.0.0.1", 1080).port)
    }

    @Test
    fun `invalid forms are rejected`() {
        val invalid =
            listOf<() -> LocalSocksRoute>(
                { LocalSocksRoute("127.0.0.1", 1080, login, unixPath = path) },
                { LocalSocksRoute("", 0, login) },
                { LocalSocksRoute("", 0, login, unixPath = "proxy.sock") },
                { LocalSocksRoute("", 0, login, unixPath = "/" + "a".repeat(107)) },
                { LocalSocksRoute("", 0, login, unixPath = "/tmp/a\u0000b") },
                { LocalSocksRoute("", 0, null, unixPath = path) },
            )

        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { it() } }
    }

    @Test
    fun `longest path under the sockaddr limit is accepted`() {
        LocalSocksRoute("", 0, login, unixPath = "/" + "a".repeat(106))
    }

    @Test
    fun `toString omits the path`() {
        val text = LocalSocksRoute("", 0, login, unixPath = path).toString()

        assertFalse(text.contains("proxy.sock"))
        assertTrue(text.contains("REDACTED"))
    }
}
