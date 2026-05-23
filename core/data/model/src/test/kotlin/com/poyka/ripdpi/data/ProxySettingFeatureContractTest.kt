package com.poyka.ripdpi.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Kotlin half of the cross-layer feature-contract harness for proxy
 * settings. Mirrors `native/rust/crates/feature-contract-harness/tests/
 * proxy_setting.rs`: it loads the same JSON manifests and asserts each
 * declared layer file still contains its marker substring.
 *
 * Failure messages name the file the contributor forgot, plus the full
 * shotgun-surgery checklist from the manifest. The harness's authoring
 * workflow is documented in
 * `native/rust/crates/feature-contract-harness/README.md`.
 */
class ProxySettingFeatureContractTest {
    @Test
    fun `every proxy_setting manifest still pins its cross-layer markers`() {
        val repoRoot = locateRepoRoot()
        val manifests = loadFamilyManifests(repoRoot, family = "proxy_setting")
        assertTrue("at least one proxy_setting manifest is required", manifests.isNotEmpty())
        manifests.forEach { (path, manifest) ->
            assertEquals("manifest $path declares wrong family", "proxy_setting", manifest.family)
            assertMarkers(repoRoot, manifest, manifestPath = path)
        }
    }

    @Test
    fun `every proxy_setting manifest lists a proto layer and a rust layer`() {
        val repoRoot = locateRepoRoot()
        loadFamilyManifests(repoRoot, family = "proxy_setting").forEach { (path, manifest) ->
            val layerIds = manifest.layers.map { it.id }
            assertTrue(
                "proxy_setting manifest $path must list a `proto` layer — the AppSettings proto field is the wire root",
                layerIds.any { it.contains("proto") },
            )
            assertTrue(
                "proxy_setting manifest $path must list at least one `rust_*` layer — " +
                    "the Rust runtime model is the consuming end",
                layerIds.any { it.contains("rust") },
            )
        }
    }
}

@Serializable
internal data class FeatureManifest(
    val schemaVersion: Int,
    val family: String,
    val name: String,
    val wireId: String,
    val summary: String,
    val layers: List<ManifestLayer>,
    val checklist: List<String> = emptyList(),
)

@Serializable
internal data class ManifestLayer(
    val id: String,
    val path: String,
    val marker: String,
    val fixHint: String,
)

private val manifestJson: Json =
    Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

internal fun loadFamilyManifests(
    repoRoot: File,
    family: String,
): List<Pair<File, FeatureManifest>> {
    val dir = File(repoRoot, "native/rust/crates/feature-contract-harness/manifests/$family")
    assertTrue(
        "manifest directory missing: ${dir.absolutePath} — the Rust harness is the source of truth, " +
            "Kotlin reads the same files",
        dir.isDirectory,
    )
    return dir
        .listFiles { _, fileName -> fileName.endsWith(".json") }
        .orEmpty()
        .sortedBy { it.name }
        .map { file ->
            val manifest =
                runCatching { manifestJson.decodeFromString(FeatureManifest.serializer(), file.readText()) }
                    .getOrElse { error("failed to parse manifest ${file.absolutePath}: ${it.message}") }
            assertEquals(
                "manifest name ${manifest.name} must match file stem ${file.nameWithoutExtension}",
                file.nameWithoutExtension,
                manifest.name,
            )
            assertFalse("manifest ${file.name} has no layers", manifest.layers.isEmpty())
            file to manifest
        }
}

internal fun assertMarkers(
    repoRoot: File,
    manifest: FeatureManifest,
    manifestPath: File,
) {
    val misses = mutableListOf<String>()
    manifest.layers.forEach { layer ->
        val file = File(repoRoot, layer.path)
        if (!file.exists()) {
            misses += "[${layer.id}] file missing: ${layer.path}"
            return@forEach
        }
        if (!file.readText().contains(layer.marker)) {
            misses +=
                "[${layer.id}] ${layer.path} does not contain marker `${layer.marker}`\n" +
                "           fix: ${layer.fixHint}"
        }
    }
    if (misses.isEmpty()) return
    val checklist =
        manifest.checklist.mapIndexed { index, line -> "  ${index + 1}. $line" }.joinToString("\n")
    error(
        buildString {
            appendLine(
                "feature `${manifest.name}` (${manifest.family} / ${manifest.summary}) drift detected — " +
                    "wireId `${manifest.wireId}`:",
            )
            misses.forEach { appendLine("  - $it") }
            appendLine()
            appendLine("Shotgun-surgery checklist for `${manifest.family}`:")
            appendLine(checklist)
            appendLine()
            append(
                "Either update the layer file to restore the marker, or update the manifest at " +
                    "${manifestPath.relativeToOrSelf(repoRoot)} if the marker name has changed intentionally.",
            )
        },
    )
}

internal fun locateRepoRoot(): File {
    var current = File(System.getProperty("user.dir") ?: ".").absoluteFile
    while (!File(current, "settings.gradle.kts").exists()) {
        current = current.parentFile ?: error("unable to locate the repository root (settings.gradle.kts)")
    }
    return current
}
