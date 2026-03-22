package com.poyka.ripdpi.activities

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DiagnosticsBoundarySourceRulesTest {
    @Test
    fun `app main sources do not import diagnostics data package`() {
        val appRoot = File("app/src/main/java")
        val offendingFiles =
            appRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { file ->
                    file.readText().contains("import com.poyka.ripdpi.data.diagnostics")
                }.map { it.relativeTo(File(".")).path }
                .toList()

        assertTrue(
            "App main sources must not import diagnostics-data directly: $offendingFiles",
            offendingFiles.isEmpty(),
        )
    }
}
