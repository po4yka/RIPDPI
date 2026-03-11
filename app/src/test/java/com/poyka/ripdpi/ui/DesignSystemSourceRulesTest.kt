package com.poyka.ripdpi.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignSystemSourceRulesTest {
    @Test
    fun `ui sources only import icons through RipDpiIcons`() {
        val uiRoot = File("app/src/main/java/com/poyka/ripdpi/ui")
        val offendingFiles =
            uiRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.name != "RipDpiIcons.kt" }
                .filter { file ->
                    file.readText().contains("import androidx.compose.material.icons")
                }.map { it.relativeTo(File(".")).path }
                .toList()

        assertTrue(
            "Raw Material icon imports are not allowed outside RipDpiIcons.kt: $offendingFiles",
            offendingFiles.isEmpty(),
        )
    }
}
