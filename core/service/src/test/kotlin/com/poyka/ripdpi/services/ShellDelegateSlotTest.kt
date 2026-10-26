package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShellDelegateSlotTest {
    private val revoked = mutableListOf<String>()
    private val slot = ShellDelegateSlot<String> { revoked += it }

    @Test
    fun `revoke during a re-attach reaches the new delegate and not the released one`() {
        slot.install("first attach")
        // Pause releases the first delegate; resume starts a new attach on the same instance.
        assertEquals("first attach", slot.release())
        assertNull(slot.current)
        slot.discardPendingRevoke()

        slot.onRevoke()
        assertEquals(emptyList<String>(), revoked)
        slot.install("second attach")

        assertEquals(listOf("second attach"), revoked)
    }

    @Test
    fun `revoke before the first delegate exists is delivered on install`() {
        slot.onRevoke()
        slot.install("delegate")

        assertEquals(listOf("delegate"), revoked)
    }

    @Test
    fun `revoke kept by a failed attach is dropped by the next attach`() {
        slot.onRevoke()
        slot.discardPendingRevoke()
        slot.install("delegate")

        assertEquals(emptyList<String>(), revoked)
    }

    @Test
    fun `revoke on an installed delegate is delivered at once`() {
        slot.install("delegate")
        slot.onRevoke()

        assertEquals(listOf("delegate"), revoked)
    }
}
