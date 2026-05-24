package com.poyka.ripdpi.diagnostics.replay

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Build-time parity gate: every `recommendationKey` referenced by the
 * JSON catalog at `assets/diagnostics/replay_recommendations.json`
 * MUST resolve to an `R.string.<key>` in the app's `values/strings.xml`.
 * Per-locale parity is already enforced at compile time by
 * `lint.xml` `MissingTranslation severity="error"`, so this test only
 * needs to verify the canonical `values/strings.xml`.
 *
 * Locating the strings file: this test walks up from the JVM working
 * directory until it finds the workspace root (the directory containing
 * `settings.gradle.kts`), then resolves the canonical paths from there.
 * If the test breaks due to a module re-layout, the file lookup is
 * the thing to update.
 */
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
            File(rootDir, "app/src/main/res/values/strings.xml").readText()
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
            "catalog keys missing from app strings.xml: $missing\n(declared keys: $declaredKeys)",
            missing.isEmpty(),
        )
    }
}
