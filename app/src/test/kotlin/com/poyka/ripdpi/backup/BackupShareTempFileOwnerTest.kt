package com.poyka.ripdpi.backup

import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class BackupShareTempFileOwnerTest {
    @Test
    fun `shared URI stays readable after owner closes`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.cacheDir, "backup-share").apply { mkdirs() }
        val file = File.createTempFile("delayed-", ".json", directory)
        try {
            file.writeText("redacted backup")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.backup.fileprovider", file)
            val owner = BackupShareTempFileOwner()
            owner.replace(file)
            owner.releaseForShare()
            owner.close()

            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            assertEquals("redacted backup", bytes?.decodeToString())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `new share prunes expired files but preserves recent shares`() {
        val cacheDir = Files.createTempDirectory("backup-share-cache-test").toFile()
        try {
            val directory = File(cacheDir, "backup-share").apply { mkdirs() }
            val nowMs = 1_000_000_000L
            val expired =
                File(directory, "expired.json").apply {
                    writeText("old")
                    setLastModified(nowMs - 25 * 60 * 60 * 1000L)
                }
            val recent =
                File(directory, "recent.json").apply {
                    writeText("recent")
                    setLastModified(nowMs - 60 * 60 * 1000L)
                }
            val owner = BackupShareTempFileOwner()

            val next = owner.createFile(cacheDir, "ripdpi-backup", nowMs)

            assertFalse(expired.exists())
            assertTrue(recent.exists())
            assertEquals(directory, next.parentFile)
            assertTrue(next != recent)
            next.writeText("new")
            owner.releaseForShare()
            val another = owner.createFile(cacheDir, "ripdpi-backup", nowMs)
            assertTrue(next.exists())
            assertTrue(another != next)
            owner.clear()
        } finally {
            cacheDir.deleteRecursively()
        }
    }

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
