package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.backup.BackupExporter
import com.poyka.ripdpi.data.backup.BackupImporter
import com.poyka.ripdpi.data.backup.BackupSerializer
import com.poyka.ripdpi.data.backup.BackupVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Tests for [BackupSerializer] — the public JSON surface for [com.poyka.ripdpi.data.backup.BackupV1].
 */
class BackupSerializerTest {
    private fun sampleDocument(variant: BackupVariant = BackupVariant.FULL) =
        BackupExporter.export(
            variant = variant,
            profiles =
                listOf(
                    ProxyProfile.Vless(
                        id = "v-1",
                        displayName = "VLESS",
                        groupId = "g-1",
                        server = "vless.example.com",
                        serverPort = 443,
                        uuid = "secret-uuid",
                    ),
                ),
            groups = emptyList(),
            rules = emptyList(),
            settings = mapOf("k" to "v"),
            createdAtEpochMillis = 1_700_000_000_000L,
            appVersion = "1.2.3",
        )

    @Test
    fun `encodeToString round-trips through BackupImporter`() {
        val original = sampleDocument()
        val json = BackupSerializer.encodeToString(original)
        val restored = BackupImporter.import(json)

        assertEquals(original.schemaVersion, restored.schemaVersion)
        assertEquals(original.createdAtEpochMillis, restored.createdAtEpochMillis)
        assertEquals(original.appVersion, restored.appVersion)
        assertEquals(original.containsCredentials, restored.containsCredentials)
        assertEquals(original.settings, restored.settings)
        assertEquals(original.profiles, restored.profiles)
    }

    @Test
    fun `encodeToStream produces identical bytes to encodeToString`() {
        val document = sampleDocument()
        val expected = BackupSerializer.encodeToString(document).toByteArray(Charsets.UTF_8)

        val out = ByteArrayOutputStream()
        val written = BackupSerializer.encodeToStream(document, out)

        assertEquals("byte count must equal stream length", out.size().toLong(), written)
        assertEquals(expected.size.toLong(), written)
    }

    @Test
    fun `encodeToStream output round-trips through BackupImporter`() {
        val original = sampleDocument()
        val out = ByteArrayOutputStream()
        BackupSerializer.encodeToStream(original, out)

        val restored = BackupImporter.import(out.toString("UTF-8"))
        assertEquals(original.appVersion, restored.appVersion)
        assertEquals(original.profiles, restored.profiles)
    }

    @Test
    fun `encodeToStream does not close the destination stream`() {
        val document = sampleDocument()
        var closed = false
        val tracking =
            object : ByteArrayOutputStream() {
                override fun close() {
                    closed = true
                    super.close()
                }
            }
        BackupSerializer.encodeToStream(document, tracking)
        assertTrue("destination must remain open for the caller to close", !closed)
    }

    @Test
    fun `byte count is positive for a non-empty document`() {
        val out = ByteArrayOutputStream()
        val written = BackupSerializer.encodeToStream(sampleDocument(), out)
        assertTrue(written > 0L)
    }
}
