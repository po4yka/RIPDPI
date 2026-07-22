package com.poyka.ripdpi.backup

import com.poyka.ripdpi.data.backup.BackupVariant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupPendingExportOwnerTest {
    @Test
    fun `full export secret survives handoff and is wiped after consumption`() {
        val owner = BackupPendingExportOwner()
        val callerSecret = "archive fixture".toCharArray()

        owner.stage(BackupVariant.FULL, callerSecret)

        assertArrayEquals(CharArray(callerSecret.size), callerSecret)
        val pending = requireNotNull(owner.consume())
        assertEquals(BackupVariant.FULL, pending.variant)
        assertArrayEquals("archive fixture".toCharArray(), pending.passphrase)

        pending.close()

        assertArrayEquals(CharArray("archive fixture".length), pending.passphrase)
        assertNull(owner.consume())
    }

    @Test
    fun `replacing request leaves the latest export pending`() {
        val owner = BackupPendingExportOwner()
        val callerSecret = "cancel fixture".toCharArray()
        owner.stage(BackupVariant.FULL, callerSecret)
        owner.stage(BackupVariant.SHARE)

        assertArrayEquals(CharArray(callerSecret.size), callerSecret)
        val pending = requireNotNull(owner.consume())
        assertEquals(BackupVariant.SHARE, pending.variant)
        assertNull(pending.passphrase)
    }
}
