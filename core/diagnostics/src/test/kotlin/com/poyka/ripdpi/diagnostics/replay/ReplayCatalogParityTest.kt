package com.poyka.ripdpi.diagnostics.replay

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReplayCatalogParityTest {
    private val rootDir: File =
        run {
            val startCwd = System.getProperty("user.dir") ?: error("user.dir system property unset")
            var dir: File = File(startCwd).absoluteFile
            while (!File(dir, "settings.gradle.kts").exists()) {
                dir = dir.parentFile ?: error("could not locate workspace root from $startCwd")
            }
            dir
        }

    @Test
    fun everyCatalogKeyExistsInAppStringsXml() {
        val catalogJson =
            File(
                rootDir,
                "core/diagnostics/src/main/assets/diagnostics/replay_recommendations.json",
            ).readText()
        val catalog = ReplayRecommendationCatalogParser.parse(catalogJson)
        val stringsXml =
            File(rootDir, "app/src/main/res/values")
                .listFiles { file -> file.name.startsWith("strings") && file.extension == "xml" }
                .orEmpty()
                .joinToString("\n") { it.readText() }
        val declaredKeys =
            Regex("""name="(vpn_replay_rec_[^"]+)"""")
                .findAll(stringsXml)
                .map { it.groupValues[1] }
                .toSet()
        val catalogKeys =
            (
                catalog.rules.map { it.recommendationKey } +
                    catalog.defaultRecommendationKey
            ).toSet()
        val missing = catalogKeys - declaredKeys
        assertTrue(
            "catalog keys missing from app string resources: $missing\n(declared keys: $declaredKeys)",
            missing.isEmpty(),
        )
    }
}
