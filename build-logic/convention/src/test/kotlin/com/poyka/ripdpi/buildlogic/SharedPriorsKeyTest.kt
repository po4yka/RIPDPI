package com.poyka.ripdpi.buildlogic

import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SharedPriorsKeyTest {
    @Test
    fun `valid key is trimmed and returned`() {
        val key = "ab".repeat(32)

        assertEquals(key, SharedPriorsKey.validated("  $key\n"))
    }

    @Test
    fun `malformed key fails the build`() {
        assertThrows(GradleException::class.java) { SharedPriorsKey.validated("abcd") }
        assertThrows(GradleException::class.java) { SharedPriorsKey.validated("g".repeat(64)) }
    }

    @Test
    fun `all-zero key fails the build`() {
        assertThrows(GradleException::class.java) { SharedPriorsKey.validated("0".repeat(64)) }
    }
}
