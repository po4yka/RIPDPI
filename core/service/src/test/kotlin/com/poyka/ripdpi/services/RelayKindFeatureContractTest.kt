package com.poyka.ripdpi.services

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Kotlin half of the cross-layer feature-contract harness for relay kinds.
 * Mirrors `native/rust/crates/feature-contract-harness/tests/relay_kind.rs`:
 * it loads the same JSON manifests, asserts each declared layer file still
 * contains its marker substring, and asserts every manifest's `wireId`
 * resolves through `relayKindDescriptor(...)`.
 *
 * The `off` kind is the documented passthrough exception — it has a Kotlin
 * descriptor row but is intentionally absent from the Rust transport
 * descriptor table.
 *
 * Failure messages name the file the contributor forgot, plus the full
 * shotgun-surgery checklist from the manifest. The harness's authoring
 * workflow is documented in
 * `native/rust/crates/feature-contract-harness/README.md`.
 */
class RelayKindFeatureContractTest {
    @Test
    fun `every relay_kind manifest still pins its cross-layer markers`() {
        val repoRoot = locateRelayHarnessRoot()
        val manifests = loadRelayKindManifests(repoRoot)
        assertTrue("at least one relay_kind manifest is required", manifests.isNotEmpty())
        manifests.forEach { (path, manifest) ->
            assertEquals("manifest $path declares wrong family", "relay_kind", manifest.family)
            assertRelayLayerMarkers(repoRoot, manifest, manifestPath = path)
        }
    }

    @Test
    fun `every relay_kind manifest wireId resolves through relayKindDescriptor`() {
        loadRelayKindManifests(locateRelayHarnessRoot()).forEach { (path, manifest) ->
            val descriptor = relayKindDescriptor(manifest.wireId)
            assertTrue(
                "relay_kind manifest $path declares wireId `${manifest.wireId}` that does not resolve in " +
                    "RelayKindDescriptors (core/service/src/main/kotlin/com/poyka/ripdpi/services/" +
                    "RelayKindDescriptor.kt).\n" +
                    "If you just added the manifest, add a matching RelayKindDescriptor row with kindId equal " +
                    "to the wireId. The RelayKindDescriptorDriftTest will then enforce a resolver registration.",
                descriptor != null,
            )
            assertEquals(
                "RelayKindDescriptor.kindId must equal the manifest wireId",
                manifest.wireId,
                descriptor?.kindId,
            )
        }
    }
}

@Serializable
internal data class RelayFeatureManifest(
    val schemaVersion: Int,
    val family: String,
    val name: String,
    val wireId: String,
    val summary: String,
    val layers: List<RelayManifestLayer>,
    val checklist: List<String> = emptyList(),
)

@Serializable
internal data class RelayManifestLayer(
    val id: String,
    val path: String,
    val marker: String,
    val fixHint: String,
)

private val relayManifestJson: Json =
    Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

internal fun loadRelayKindManifests(repoRoot: File): List<Pair<File, RelayFeatureManifest>> {
    val dir = File(repoRoot, "native/rust/crates/feature-contract-harness/manifests/relay_kind")
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
                runCatching {
                    relayManifestJson.decodeFromString(RelayFeatureManifest.serializer(), file.readText())
                }.getOrElse { error("failed to parse manifest ${file.absolutePath}: ${it.message}") }
            assertEquals(
                "manifest name ${manifest.name} must match file stem ${file.nameWithoutExtension}",
                file.nameWithoutExtension,
                manifest.name,
            )
            assertFalse("manifest ${file.name} has no layers", manifest.layers.isEmpty())
            file to manifest
        }
}

internal fun assertRelayLayerMarkers(
    repoRoot: File,
    manifest: RelayFeatureManifest,
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

internal fun locateRelayHarnessRoot(): File {
    var current = File(System.getProperty("user.dir") ?: ".").absoluteFile
    while (!File(current, "settings.gradle.kts").exists()) {
        current = current.parentFile ?: error("unable to locate the repository root (settings.gradle.kts)")
    }
    return current
}
