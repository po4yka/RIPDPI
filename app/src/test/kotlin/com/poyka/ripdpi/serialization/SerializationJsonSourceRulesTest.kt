package com.poyka.ripdpi.serialization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SerializationJsonSourceRulesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val jsonBuilder = Regex("""(?:kotlinx\.serialization\.json\.)?Json\s*\{""")
    private val sharedJsonFile =
        normalizedPath("core/data/model/src/main/kotlin/com/poyka/ripdpi/serialization/RipDpiJson.kt")

    @Test
    fun `production Json builders stay centralized`() {
        val offenders =
            productionKotlinSources()
                .filter { file ->
                    file.normalizedPath() != sharedJsonFile && jsonBuilder.containsMatchIn(file.readText())
                }.map { it.relativeTo(repoRoot()).path }
                .sorted()
                .toList()

        assertTrue("Production Json builders must use shared serialization instances: $offenders", offenders.isEmpty())
    }

    @Test
    fun `production source scan excludes nested Git checkouts`() {
        for (rootIsWorktree in listOf(false, true)) {
            val root = temporaryFolder.newFolder()
            val gitMarker = File(root, ".git")
            if (rootIsWorktree) gitMarker.writeText("gitdir: elsewhere") else gitMarker.mkdir()

            fun source(path: String): File =
                File(root, path).apply {
                    requireNotNull(parentFile).mkdirs()
                    writeText("val local = Json { }")
                }

            val current = source("core/src/main/kotlin/CurrentViolation.kt")
            source("nested/app/src/main/kotlin/OtherViolation.kt")
            source(".worktrees/foreign/core/src/main/kotlin/ForeignViolation.kt")
            File(root, ".worktrees/foreign/.git").writeText("gitdir: elsewhere")
            source("other-copy/app/src/main/kotlin/ForeignViolation.kt")
            File(root, "other-copy/.git").mkdir()

            assertEquals(
                setOf("core/src/main/kotlin/CurrentViolation.kt", "nested/app/src/main/kotlin/OtherViolation.kt"),
                productionKotlinSources(root).map { it.relativeTo(root).invariantSeparatorsPath }.toSet(),
            )
            assertTrue(jsonBuilder.containsMatchIn(current.readText()))
        }
    }

    private fun productionKotlinSources(root: File = repoRoot()): Sequence<File> =
        root
            .walkTopDown()
            .onEnter { dir ->
                (dir == root || !File(dir, ".git").exists()) &&
                    dir.name !in setOf(".claude", ".git", ".gradle", "build") &&
                    !dir.path.contains("/build/")
            }.filter { file ->
                file.isFile &&
                    file.extension == "kt" &&
                    (file.path.contains("/app/") || file.path.contains("/core/")) &&
                    !file.path.contains("/src/test/") &&
                    !file.path.contains("/src/androidTest/")
            }

    private fun repoRoot(): File {
        var current = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (!File(current, "settings.gradle.kts").isFile) {
            current = requireNotNull(current.parentFile)
        }
        return current
    }

    private fun File.normalizedPath(): String = normalizedPath(relativeTo(repoRoot()).path)

    private fun normalizedPath(path: String): String = path.replace(File.separatorChar, '/')
}
