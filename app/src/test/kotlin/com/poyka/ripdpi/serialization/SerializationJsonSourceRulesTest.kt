package com.poyka.ripdpi.serialization

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SerializationJsonSourceRulesTest {
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

    private fun productionKotlinSources(): Sequence<File> =
        repoRoot()
            .walkTopDown()
            .onEnter { dir ->
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
