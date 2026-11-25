import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreparePresetTaskTest {
    private val fixtures = File("../../core/data/src/test/resources/backup").absoluteFile

    private fun run(
        archive: File,
        passphrase: String? = "fixture-passphrase",
        schema: String = "const val BackupSchemaVersion: Int = 3\nconst val OldestSupportedBackupVersion: Int = 1\n",
        assert: (preset: File, prepare: () -> Unit) -> Unit,
    ) {
        val projectDir = Files.createTempDirectory("prepare-preset").toFile()
        try {
            val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
            val task = project.tasks.create("preparePreset", PreparePresetTask::class.java)
            val preset = projectDir.resolve("assets/preset.json")
            task.archive.set(archive)
            passphrase?.let(task.passphrase::set)
            task.schemaSource.set(projectDir.resolve("BackupV1.kt").apply { writeText(schema) })
            task.presetFile.set(preset)
            assert(preset, task::prepare)
        } finally {
            projectDir.deleteRecursively()
        }
    }

    private fun temp(content: String): File =
        Files.createTempFile("archive", ".json").toFile().apply { writeText(content) }

    private val slotGroup =
        """{"id":"slot","name":"Preset relay","type":"SUBSCRIPTION","order":0,"isSelector":false,"members":[],""" +
            """"subscription":{"link":"https://relay.example/bootstrap/abc","token":"","kind":"BOOTSTRAP",""" +
            """"consumedAt":null,"mirrors":{"mirrors":[]}}}"""

    private fun export(
        groups: String = "[$slotGroup]",
        profiles: String = "[]",
    ): String =
        """{"schemaVersion":3,"createdAtEpochMillis":0,"appVersion":"fixture","profiles":$profiles,""" +
            """"groups":$groups,"rules":[],"settings":{},"containsCredentials":true,""" +
            """"privateData":{"relayCredentials":[{"profileId":"r","password":"relay-secret"}]}}"""

    private fun assertRejected(
        archive: String,
        message: String,
    ) = run(temp(archive)) { preset, prepare ->
        assertTrue(assertFailsWith<GradleException> { prepare() }.message!!.contains(message))
        assertFalse(preset.exists())
    }

    @Test
    fun `FULL export with a bootstrap slot becomes a preset without credentials`() =
        run(temp(export())) { preset, prepare ->
            prepare()
            val document = Json.parseToJsonElement(preset.readText()).jsonObject
            assertFalse("privateData" in document)
            assertFalse(document.getValue("containsCredentials").jsonPrimitive.boolean)
            assertEquals(Json.parseToJsonElement("[$slotGroup]"), document["groups"])
        }

    @Test
    fun `encrypted export is decrypted before the bootstrap slot check`() =
        run(fixtures.resolve("preset-fixture.ripdpibackup")) { preset, prepare ->
            assertTrue(assertFailsWith<GradleException> { prepare() }.message!!.contains("pending BOOTSTRAP"))
            assertFalse(preset.exists())
        }

    @Test
    fun `export without a pending bootstrap slot is rejected`() {
        assertRejected(export(groups = "[]"), "no group with a pending BOOTSTRAP")
        assertRejected(
            export(groups = "[${slotGroup.replace("https://relay.example/bootstrap/abc", "")}]"),
            "not a pending BOOTSTRAP slot",
        )
        assertRejected(
            export(groups = "[${slotGroup.replace("\"consumedAt\":null", "\"consumedAt\":1")}]"),
            "not a pending BOOTSTRAP slot",
        )
    }

    @Test
    fun `credentials outside privateData are rejected`() {
        assertRejected(export(profiles = """[{"type":"trojan","password":"p"}]"""), "carries profiles")
        assertRejected(
            export(groups = "[${slotGroup.replace("\"members\":[]", "\"members\":[{\"type\":\"trojan\"}]")}]"),
            "Group slot has members",
        )
        assertRejected(
            export(groups = "[${slotGroup.replace("\"kind\":\"BOOTSTRAP\"", "\"kind\":\"LONG_LIVED\"")}]"),
            "Group slot has a subscription that is not a pending BOOTSTRAP slot",
        )
        assertRejected(
            export(groups = "[${slotGroup.replace("\"token\":\"\"", "\"token\":\"t\"")}]"),
            "not a pending BOOTSTRAP slot",
        )
    }

    @Test
    fun `wrong or missing passphrase fails without writing`() {
        run(fixtures.resolve("preset-fixture.ripdpibackup"), passphrase = "wrong") { preset, prepare ->
            assertTrue(assertFailsWith<GradleException> { prepare() }.message!!.contains("Wrong passphrase"))
            assertFalse(preset.exists())
        }
        run(fixtures.resolve("preset-fixture.ripdpibackup"), passphrase = null) { preset, prepare ->
            assertTrue(assertFailsWith<GradleException> { prepare() }.message!!.contains("RIPDPI_PRESET_PASSPHRASE"))
            assertFalse(preset.exists())
        }
    }

    @Test
    fun `schema version outside the checkout range is rejected`() =
        run(
            fixtures.resolve("preset-fixture.ripdpibackup"),
            schema = "const val BackupSchemaVersion: Int = 2\nconst val OldestSupportedBackupVersion: Int = 1\n",
        ) { preset, prepare ->
            assertTrue(assertFailsWith<GradleException> { prepare() }.message!!.contains("version 3 is not supported"))
            assertFalse(preset.exists())
        }
}
