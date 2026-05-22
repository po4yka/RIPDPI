package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guardrail: the settings-section projections must stay pure data.
 *
 * The section models and their mapper sit at the lowest, most widely
 * depended-on layer. They must not reach an Android `Context`, a service or
 * JNI type, or a dependency-injection annotation — otherwise every consumer
 * that takes a section inherits that weight, defeating the projection. This
 * test reads the section sources and fails on any forbidden import.
 */
class SettingsSectionPurityTest {
    private val sectionSourceFileNames = setOf("SettingsSections.kt", "AppSettingsSectionMapper.kt")

    private val forbiddenImportPrefixes =
        listOf(
            "android.",
            "androidx.",
            "dagger.",
            "javax.inject.",
            "com.poyka.ripdpi.core.",
            "com.poyka.ripdpi.service",
        )

    @Test
    fun `section sources import no Android, service, JNI, or DI types`() {
        val sources = locateSectionSources()
        assertEquals(
            "expected to locate every section source file",
            sectionSourceFileNames,
            sources.map { it.name }.toSet(),
        )

        sources.forEach { file ->
            file
                .readLines()
                .map { it.trim() }
                .filter { it.startsWith("import ") }
                .forEach { importLine ->
                    val imported = importLine.removePrefix("import ").trim()
                    val forbidden = forbiddenImportPrefixes.firstOrNull { imported.startsWith(it) }
                    assertTrue("${file.name} has a forbidden import: $importLine", forbidden == null)
                }
        }
    }

    private fun locateSectionSources(): List<File> =
        File(System.getProperty("user.dir") ?: ".")
            .walkTopDown()
            .filter { it.isFile && it.name in sectionSourceFileNames && it.path.contains("/src/main/") }
            .toList()
}
