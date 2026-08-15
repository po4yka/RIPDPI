package com.poyka.ripdpi.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupJournalTest {
    @Test
    fun `rotation is bounded and retains newest vpn startup evidence`() {
        val journal = BoundedStartupJournal(maxBytes = 128)

        repeat(8) { index ->
            journal.recordServiceStarted(mode = Mode.VPN, occurredAtMillis = index.toLong())
        }

        val snapshot = journal.snapshot()

        assertTrue(snapshot.truncated)
        assertTrue(snapshot.byteCount <= 128)
        assertTrue(snapshot.content.contains(StartupJournalTruncationMarker.trim()))
        assertTrue(snapshot.content.contains("7 service_started mode=vpn"))
        assertFalse(snapshot.content.contains("0 service_started mode=vpn"))
    }
}
