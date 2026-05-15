package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.backup.BACKUP_SCHEMA_VERSION
import com.poyka.ripdpi.data.backup.BackupImporter
import com.poyka.ripdpi.data.backup.UnsupportedBackupVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Forward-migration and version-gate tests for [BackupImporter].
 */
class BackupVersionMigrationTest {
    private fun minimalJson(schemaVersion: Int) =
        """
        {
          "schemaVersion": $schemaVersion,
          "createdAtEpochMillis": 0,
          "appVersion": "1.0.0",
          "profiles": [],
          "groups": [],
          "rules": [],
          "settings": {},
          "containsCredentials": false
        }
        """.trimIndent()

    @Test
    fun `deserializing schema version 1 succeeds`() {
        val doc = BackupImporter.import(minimalJson(BACKUP_SCHEMA_VERSION))
        assertEquals(BACKUP_SCHEMA_VERSION, doc.schemaVersion)
    }

    @Test
    fun `deserializing schema version 99 throws UnsupportedBackupVersion`() {
        val ex =
            assertThrows(UnsupportedBackupVersion::class.java) {
                BackupImporter.import(minimalJson(99))
            }
        assertEquals(99, ex.found)
        assertEquals(BACKUP_SCHEMA_VERSION, ex.supported)
    }

    @Test
    fun `deserializing schema version 0 throws UnsupportedBackupVersion`() {
        val ex =
            assertThrows(UnsupportedBackupVersion::class.java) {
                BackupImporter.import(minimalJson(0))
            }
        assertEquals(0, ex.found)
    }

    @Test
    fun `UnsupportedBackupVersion message contains both version numbers`() {
        val ex =
            assertThrows(UnsupportedBackupVersion::class.java) {
                BackupImporter.import(minimalJson(42))
            }
        assert("42" in ex.message.orEmpty()) { "message should contain found version 42" }
        assert(BACKUP_SCHEMA_VERSION.toString() in ex.message.orEmpty()) {
            "message should contain supported version $BACKUP_SCHEMA_VERSION"
        }
    }
}
