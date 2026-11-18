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
    // Shared with BackupEncryptionTest in :core:data, which decrypts the same archive with the app's code.
    private val fixtures = File("../../core/data/src/test/resources/backup").absoluteFile
    private val plaintext = fixtures.resolve("preset-fixture.json").readText()

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

    @Test
    fun `encrypted FULL export becomes the plaintext preset`() =
        run(fixtures.resolve("preset-fixture.ripdpibackup")) { preset, prepare ->
            prepare()
            assertEquals(plaintext, preset.readText())
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
    fun `SHARE export is rejected for missing credentials`() =
        run(
            temp(plaintext.replace("\"containsCredentials\":true", "\"containsCredentials\":false")),
        ) { preset, prepare ->
            assertTrue(assertFailsWith<GradleException> { prepare() }.message!!.contains("no credentials"))
            assertFalse(preset.exists())
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
