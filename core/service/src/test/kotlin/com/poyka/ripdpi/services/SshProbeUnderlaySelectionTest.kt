package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SshProbeUnderlaySelectionTest {
    @Test
    fun `returning to a previous network never revives its captured lease`() {
        val selection = SshProbeUnderlaySelection<String>()
        selection.update("wifi", 1L)
        val original = checkNotNull(selection.snapshot())
        selection.update("cell", 1L)
        selection.update("wifi", null)
        selection.update("wifi", 1L)
        selection.update("cell", null)

        val returned = checkNotNull(selection.snapshot())
        assertEquals(original.network, returned.network)
        assertNotEquals(original, returned)
    }
}
