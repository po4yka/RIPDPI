package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.backup.BackupDecryptionException
import com.poyka.ripdpi.data.backup.BackupEncryption
import com.poyka.ripdpi.data.backup.BackupExporter
import com.poyka.ripdpi.data.backup.BackupImporter
import com.poyka.ripdpi.data.backup.BackupVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class BackupEncryptionTest {
    private val document =
        BackupExporter.export(
            variant = BackupVariant.FULL,
            profiles =
                listOf(
                    ProxyProfile.Vless(
                        id = "profile",
                        displayName = "Private",
                        groupId = "group",
                        server = "example.com",
                        serverPort = 443,
                        uuid = SensitiveValue,
                    ),
                ),
            groups = emptyList(),
            rules = emptyList(),
            settings = emptyMap(),
            createdAtEpochMillis = 1L,
            appVersion = "test",
        )

    @Test
    fun `FULL archive is authenticated ciphertext and round trips`() {
        val output = ByteArrayOutputStream()
        val written = BackupEncryption.encryptToStream(document, ArchivePhrase, output)
        val archive = output.toByteArray()

        assertEquals(archive.size.toLong(), written)
        assertTrue(BackupEncryption.isEncrypted(archive))
        assertFalse(archive.toString(Charsets.UTF_8).contains(SensitiveValue))
        assertEquals(document, BackupImporter.import(BackupEncryption.decryptToString(archive, ArchivePhrase)))
    }

    @Test
    fun `wrong passphrase and tampering are rejected`() {
        val output = ByteArrayOutputStream()
        BackupEncryption.encryptToStream(document, ArchivePhrase, output)
        val archive = output.toByteArray()

        assertThrows(BackupDecryptionException::class.java) {
            BackupEncryption.decryptToString(archive, "wrong".toCharArray())
        }
        archive[archive.lastIndex] = (archive.last().toInt() xor 1).toByte()
        assertThrows(BackupDecryptionException::class.java) {
            BackupEncryption.decryptToString(archive, ArchivePhrase)
        }
    }

    @Test
    fun `each archive uses fresh randomness and truncated envelopes are rejected`() {
        val first = ByteArrayOutputStream()
        val second = ByteArrayOutputStream()
        BackupEncryption.encryptToStream(document, ArchivePhrase, first)
        BackupEncryption.encryptToStream(document, ArchivePhrase, second)

        assertFalse(first.toByteArray().contentEquals(second.toByteArray()))
        assertThrows(BackupDecryptionException::class.java) {
            BackupEncryption.decryptToString(first.toByteArray().copyOf(20), ArchivePhrase)
        }
    }

    private companion object {
        const val SensitiveValue = "sensitive-uuid-that-must-not-appear"
        val ArchivePhrase = "correct horse battery staple".toCharArray()
    }
}
