package com.poyka.ripdpi.diagnostics.dpi

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DiagnosticsOwnedTlsSourceRulesTest {
    private val repoRoot = findRepoRoot()

    @Test
    fun `diagnostic owned tls probes do not instantiate jsse socket contexts`() {
        val blockedPatterns =
            listOf(
                "import javax.net.ssl.SSLContext",
                "import javax.net.ssl.SSLSocket",
                "import javax.net.ssl.SSLSocketFactory",
                "SSLContext.getInstance",
                "SSLContext.getDefault",
                "SSLSocketFactory.getDefault",
                "sslSocketFactory(",
            )
        val guardedFiles =
            listOf(
                "core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpi/DomainReachabilityScanner.kt",
                "core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpi/Tcp16FatHeaderProbe.kt",
                "core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/rkn/RknLayeredProbePipeline.kt",
                "core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpich/WebhostFarm.kt",
            )

        val offenders =
            guardedFiles.flatMap { path ->
                val text = File(repoRoot, path).readText()
                blockedPatterns
                    .filter { pattern -> text.contains(pattern) }
                    .map { pattern -> "$path contains $pattern" }
            }

        assertTrue(
            "Diagnostic probes must route TLS through DiagnosticsHttpClientFactory or injected WebhostProbe: $offenders",
            offenders.isEmpty(),
        )
    }

    private fun findRepoRoot(): File {
        var current = File(".").canonicalFile
        while (!File(current, "settings.gradle.kts").isFile) {
            current = current.parentFile ?: error("Unable to find repo root")
        }
        return current
    }
}
