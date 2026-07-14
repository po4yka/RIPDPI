package com.poyka.ripdpi.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class BackupShareTempFileOwnerTest {
    @Test
    fun `replacement and close delete every owned share file`() {
        val directory = Files.createTempDirectory("backup-share-owner-test").toFile()
        try {
            val first = directory.resolve("first.json").apply { writeText("first") }
            val second = directory.resolve("second.json").apply { writeText("second") }
            val owner = BackupShareTempFileOwner()

            owner.replace(first)
            assertEquals(first, owner.current())

            owner.replace(second)
            assertFalse(first.exists())
            assertTrue(second.exists())
            assertEquals(second, owner.current())

            owner.close()
            assertFalse(second.exists())
            assertNull(owner.current())
        } finally {
            directory.deleteRecursively()
        }
    }
}
