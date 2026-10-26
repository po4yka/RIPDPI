package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.FileDescriptor
import java.io.IOException

class ProtectSocketAcceptLoopTest {
    @Test
    fun `persistent accept failures back off instead of spinning and reset after a client`() {
        val pauses = mutableListOf<Long>()
        val script = ArrayDeque(List(7) { false } + true + false + false)
        var dispatched = 0

        runProtectAcceptLoop(
            isRunning = { script.isNotEmpty() },
            accept = {
                if (!script.removeFirst()) throw IOException("EMFILE")
                IdleSession
            },
            dispatch = {
                dispatched += 1
                true
            },
            pause = { pauses += it },
        )

        assertEquals(listOf(50L, 100L, 200L, 400L, 800L, 1_000L, 1_000L, 50L), pauses)
        assertEquals(1, dispatched)
    }

    private object IdleSession : ProtectSocketClientSession {
        override val ancillaryFileDescriptors: Array<FileDescriptor>? = null

        override fun readHandshake(): Int = 0

        override fun writeAck(success: Boolean) = Unit

        override fun close() = Unit
    }
}
