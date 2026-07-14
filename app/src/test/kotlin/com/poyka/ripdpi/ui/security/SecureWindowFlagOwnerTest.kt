package com.poyka.ripdpi.ui.security

import org.junit.Assert.assertEquals
import org.junit.Test

class SecureWindowFlagOwnerTest {
    @Test
    fun `overlapping leases keep window secure until final release`() {
        val transitions = mutableListOf<Boolean>()
        val owner = SecureWindowFlagOwner(transitions::add)

        val first = owner.acquire()
        val second = owner.acquire()
        first.release()

        assertEquals(listOf(true), transitions)

        second.release()

        assertEquals(listOf(true, false), transitions)
    }

    @Test
    fun `lease release is idempotent`() {
        val transitions = mutableListOf<Boolean>()
        val lease = SecureWindowFlagOwner(transitions::add).acquire()

        lease.release()
        lease.release()

        assertEquals(listOf(true, false), transitions)
    }
}
