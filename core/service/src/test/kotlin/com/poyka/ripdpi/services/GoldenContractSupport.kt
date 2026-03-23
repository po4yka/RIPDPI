package com.poyka.ripdpi.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonElement.Companion.serializer
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.fail
import java.io.File

internal object GoldenContractSupport {
    private const val ModulePath = "core/service"

    private val snapshotJson =
        Json {
            prettyPrint = true
            explicitNulls = true
        }

    fun assertJsonGolden(
        relativePath: String,
        actualJson: String,
        scrub: (JsonElement) -> JsonElement = { it },
    ) {
        val canonical = canonicalJson(scrub(Json.parseToJsonElement(actualJson)))
        assertTextGolden(relativePath, canonical)
    }

    private fun assertTextGolden(
        relativePath: String,
        actualText: String,
    ) {
        val goldenFile = resolveRepoPath("$ModulePath/src/test/resources/golden/$relativePath")
        val actual = normalizeText(actualText)

        if (System.getenv("RIPDPI_BLESS_GOLDENS") != null) {
            goldenFile.parentFile.mkdirs()
            goldenFile.writeText(actual)
            return
        }

        val expected = normalizeText(goldenFile.readText())
        if (expected == actual) {
            return
        }

        writeArtifacts(relativePath, expected, actual)
        fail("Golden mismatch for ${goldenFile.path}")
    }

    private fun canonicalJson(value: JsonElement): String =
        snapshotJson.encodeToString(serializer(), value.sortedKeys())

    private fun JsonElement.sortedKeys(): JsonElement =
        when (this) {
            is JsonArray -> JsonArray(map { it.sortedKeys() })
            is JsonObject -> JsonObject(entries.sortedBy { it.key }.associate { it.key to it.value.sortedKeys() })
            else -> this
        }

    private fun normalizeText(value: String): String {
        val normalized = value.replace("\r\n", "\n")
        return if (normalized.endsWith('\n')) normalized else "$normalized\n"
    }

    private fun writeArtifacts(
        relativePath: String,
        expected: String,
        actual: String,
    ) {
        val artifactDir = resolveRepoPath("$ModulePath/build/golden-diffs")
        artifactDir.mkdirs()
        val safeName = relativePath.replace('/', '_')
        File(artifactDir, "$safeName.expected").writeText(expected)
        File(artifactDir, "$safeName.actual").writeText(actual)
        File(artifactDir, "$safeName.diff").writeText(
            buildString {
                appendLine("--- expected")
                appendLine("+++ actual")
                appendLine(expected)
                appendLine("=== actual ===")
                appendLine(actual)
            },
        )
    }

    private fun resolveRepoPath(relativePath: String): File {
        var current = File(System.getProperty("user.dir")).absoluteFile
        while (!File(current, "settings.gradle.kts").exists()) {
            current =
                current.parentFile ?: error("Unable to locate repository root from ${System.getProperty("user.dir")}")
        }
        return File(current, relativePath)
    }
}
