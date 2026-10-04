import org.gradle.testfixtures.ProjectBuilder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Nfqws2BuildTaskTest {
    @Test
    fun `builder receives the configured native job budget`() {
        val projectDir = Files.createTempDirectory("nfqws2-jobs").toFile()
        try {
            val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
            val task = project.tasks.create("nfqws2", Nfqws2BuildTask::class.java)
            val builder = projectDir.resolve("builder.py")
            builder.writeText(
                """
                import pathlib, sys
                output = pathlib.Path(sys.argv[sys.argv.index("--output") + 1])
                output.mkdir(parents=True, exist_ok=True)
                (output / "jobs.txt").write_text(sys.argv[sys.argv.index("--jobs") + 1])
                """.trimIndent(),
            )
            task.builderScript.set(builder)
            task.sdkDir.set(projectDir.resolve("sdk"))
            task.ndkVersion.set("29.0.14206865")
            task.minSdk.set(27)
            task.abis.set(listOf("arm64-v8a"))
            task.jobs.set(2)
            task.workDir.set(projectDir.resolve("work"))
            task.outputDir.set(projectDir.resolve("output"))

            task.build()

            assertEquals("2", projectDir.resolve("output/jobs.txt").readText())
        } finally {
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun `invalid job budget fails before deleting previous output`() {
        val projectDir = Files.createTempDirectory("nfqws2-invalid-jobs").toFile()
        try {
            val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
            val task = project.tasks.create("nfqws2", Nfqws2BuildTask::class.java)
            val output = projectDir.resolve("output").apply { mkdirs() }
            val previousOutput = output.resolve("previous.txt").apply { writeText("previous") }
            task.outputDir.set(output)
            task.jobs.set(0)

            assertFailsWith<IllegalArgumentException> { task.build() }
            assertEquals("previous", previousOutput.readText())
        } finally {
            projectDir.deleteRecursively()
        }
    }
}
