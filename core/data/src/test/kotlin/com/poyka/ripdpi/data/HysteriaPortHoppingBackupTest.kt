package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.backup.BackupExporter
import com.poyka.ripdpi.data.backup.BackupImporter
import com.poyka.ripdpi.data.backup.BackupRestoreDecoder
import com.poyka.ripdpi.data.backup.BackupSerializer
import com.poyka.ripdpi.data.backup.BackupVariant
import com.poyka.ripdpi.data.backup.ProfileDecodeResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HysteriaPortHoppingBackupTest {
    private val profile =
        ProxyProfile.Hysteria2(
            id = "hopping",
            displayName = "Hopping",
            groupId = "group",
            server = "hopping.example",
            serverPort = 443,
            password = listOf("fixture", "password").joinToString("-"),
            portHopping = Hysteria2PortHopping("20000-21000", 45_000L),
        )
    private val group =
        ProxyGroup(
            id = "group",
            name = "Group",
            type = ProxyGroupType.BASIC,
            order = 0,
            isSelector = true,
            members = listOf(profile),
            ownershipGeneration = "owner",
        )

    private fun document(variant: BackupVariant = BackupVariant.FULL) =
        BackupExporter.export(
            variant,
            listOf(profile),
            listOf(group),
            emptyList(),
            emptyMap(),
            0L,
            "test",
        )

    @Test
    fun `full backup preserves hopping in profiles and group members`() {
        val restored = BackupImporter.import(BackupSerializer.encodeToString(document()))
        assertEquals(
            profile,
            (BackupRestoreDecoder.decodeProfiles(restored).single() as ProfileDecodeResult.Decoded).profile,
        )
        assertEquals(group, restored.groups.single())
    }

    @Test
    fun `share retains public hopping without credentials`() {
        val shared = document(BackupVariant.SHARE)
        val encoded = shared.profiles.single()
        assertTrue("portHopping" in encoded)
        assertFalse("password" in encoded)
        assertTrue(
            shared.groups
                .single()
                .members
                .isEmpty(),
        )
    }

    @Test
    fun `old backup migrates top level and group hopping before typed decode`() {
        val restored = BackupImporter.import(legacyBackup("45s"))
        assertEquals(
            profile,
            (BackupRestoreDecoder.decodeProfiles(restored).single() as ProfileDecodeResult.Decoded).profile,
        )
        assertEquals(group, restored.groups.single())
    }

    @Test
    fun `malformed old group hopping rejects the backup`() {
        assertTrue(runCatching { BackupImporter.import(legacyBackup("4s")) }.isFailure)
    }

    private fun legacyBackup(interval: String): String {
        val raw = Json.parseToJsonElement(BackupSerializer.encodeToString(document())).jsonObject

        fun old(value: JsonObject): JsonObject =
            JsonObject(
                (value - "portHopping") +
                    mapOf(
                        "portHopPorts" to JsonPrimitive("20000:21000"),
                        "portHopInterval" to JsonPrimitive(interval),
                    ),
            )
        val legacyProfile =
            old(
                raw
                    .getValue("profiles")
                    .jsonArray
                    .single()
                    .jsonObject,
            )
        val rawGroup =
            raw
                .getValue("groups")
                .jsonArray
                .single()
                .jsonObject
        val legacyGroup = JsonObject(rawGroup + ("members" to JsonArray(listOf(legacyProfile))))
        return JsonObject(
            raw + mapOf("profiles" to JsonArray(listOf(legacyProfile)), "groups" to JsonArray(listOf(legacyGroup))),
        ).toString()
    }
}
