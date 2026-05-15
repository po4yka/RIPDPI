package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.backup.BACKUP_SCHEMA_VERSION
import com.poyka.ripdpi.data.backup.BackupExporter
import com.poyka.ripdpi.data.backup.BackupImporter
import com.poyka.ripdpi.data.backup.BackupVariant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Round-trip and top-level metadata tests for [BackupV1].
 */
class BackupV1Test {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private fun minimalExport() =
        BackupExporter.export(
            variant = BackupVariant.FULL,
            profiles = emptyList(),
            groups = emptyList(),
            rules = emptyList(),
            settings = emptyMap(),
            createdAtEpochMillis = 1_700_000_000_000L,
            appVersion = "1.2.3",
        )

    @Test
    fun `exported document contains schemaVersion createdAtEpochMillis and appVersion`() {
        val doc = minimalExport()
        assertEquals(BACKUP_SCHEMA_VERSION, doc.schemaVersion)
        assertEquals(1_700_000_000_000L, doc.createdAtEpochMillis)
        assertEquals("1.2.3", doc.appVersion)
    }

    @Test
    fun `round-trip through JSON preserves top-level metadata`() {
        val original = minimalExport()
        val serialized =
            json.encodeToString(
                com.poyka.ripdpi.data.backup.BackupV1
                    .serializer(),
                original,
            )
        val restored = BackupImporter.import(serialized)

        assertEquals(original.schemaVersion, restored.schemaVersion)
        assertEquals(original.createdAtEpochMillis, restored.createdAtEpochMillis)
        assertEquals(original.appVersion, restored.appVersion)
    }

    @Test
    fun `serialized JSON has schemaVersion as a top-level key`() {
        val doc = minimalExport()
        val serialized =
            json.encodeToString(
                com.poyka.ripdpi.data.backup.BackupV1
                    .serializer(),
                doc,
            )
        val parsed = json.parseToJsonElement(serialized).jsonObject
        assertEquals(
            BACKUP_SCHEMA_VERSION.toString(),
            parsed.getValue("schemaVersion").jsonPrimitive.content,
        )
    }

    @Test
    fun `SHARE export does not set containsCredentials`() {
        val doc =
            BackupExporter.export(
                variant = BackupVariant.SHARE,
                profiles = emptyList(),
                groups = emptyList(),
                rules = emptyList(),
                settings = emptyMap(),
                createdAtEpochMillis = 0L,
                appVersion = "1.0.0",
            )
        assertFalse(doc.containsCredentials)
    }
}
