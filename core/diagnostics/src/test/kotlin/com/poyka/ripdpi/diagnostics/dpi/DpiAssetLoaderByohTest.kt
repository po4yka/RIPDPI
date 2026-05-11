package com.poyka.ripdpi.diagnostics.dpi

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.io.path.createTempDirectory

class DpiAssetLoaderByohTest {
    @Test
    fun syntheticByohFixtureLoadSkipsComments() {
        val loader =
            DpiAssetLoader(
                fileProvider =
                    FakeDpiAssetFileProvider(
                        assets =
                            mapOf(
                                "dpich/byoh_synthetic_domains.txt" to
                                    """
                                    # synthetic domains
                                    alpha.test

                                    beta.test
                                    """.trimIndent(),
                            ),
                    ),
            )

        assertEquals(listOf("alpha.test", "beta.test"), loader.loadByohSyntheticDomains())
    }

    private class FakeDpiAssetFileProvider(
        private val filesDir: File = createTempDirectory().toFile(),
        private val assets: Map<String, String>,
    ) : DpiAssetFileProvider {
        override fun overrideFile(relativePath: String): File = File(filesDir, relativePath)

        override fun openAsset(relativePath: String): InputStream =
            ByteArrayInputStream(requireNotNull(assets[relativePath]).toByteArray())
    }
}
