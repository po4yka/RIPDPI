package com.poyka.ripdpi.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DesignSystemSourceRulesTest {
    private val repoRoot = File(".").canonicalFile
    private val mainUiRoot = File(repoRoot, "app/src/main/kotlin/com/poyka/ripdpi/ui")
    private val screenUiRoot = File(mainUiRoot, "screens")

    @Test
    fun `ui sources only import icons through RipDpiIcons`() {
        assertNoOffendingFiles(
            files = uiSourceFiles(),
            allowedPaths = setOf("app/src/main/kotlin/com/poyka/ripdpi/ui/theme/RipDpiIcons.kt"),
            message = "Raw Material icon imports are not allowed outside RipDpiIcons.kt",
        ) { source ->
            source.text.contains("import androidx.compose.material.icons")
        }
    }

    @Test
    fun `ui sources route interaction semantics through shared primitives`() {
        assertNoOffendingFiles(
            files = uiSourceFiles(),
            allowedPaths = setOf("app/src/main/kotlin/com/poyka/ripdpi/ui/components/RipDpiInteraction.kt"),
            message = "Direct clickable imports bypass shared RIPDPI interaction semantics",
        ) { source ->
            source.text.contains("import androidx.compose.foundation.clickable")
        }
    }

    @Test
    fun `screens use shared design-system wrappers for governed Material primitives`() {
        val governedMaterialImports =
            Regex(
                pattern =
                    "^import androidx\\.compose\\.material3\\." +
                        "(AlertDialog|AssistChip|Button|DropdownMenu|ElevatedButton|FilterChip|" +
                        "IconButton|InputChip|ModalBottomSheet|NavigationBar|NavigationBarItem|" +
                        "OutlinedButton|OutlinedTextField|Snackbar|SuggestionChip|Switch|" +
                        "TextButton|TextField)\\b",
                option = RegexOption.MULTILINE,
            )

        assertNoOffendingFiles(
            files = uiSourceFiles(screenUiRoot),
            message =
                "Screens must use shared RIPDPI wrappers for actions, inputs, modals, menus, " +
                    "and navigation chrome",
        ) { source ->
            governedMaterialImports.containsMatchIn(source.text)
        }
    }

    @Test
    fun `animation sources consume shared motion tokens`() {
        val animationImport = Regex("^import androidx\\.compose\\.animation", RegexOption.MULTILINE)

        assertNoOffendingFiles(
            files = uiSourceFiles(),
            allowedPaths = setOf("app/src/main/kotlin/com/poyka/ripdpi/ui/theme/RipDpiMotion.kt"),
            message = "Compose animation usage must consume RipDpiThemeTokens.motion",
        ) { source ->
            animationImport.containsMatchIn(source.text) &&
                !source.text.contains("RipDpiThemeTokens.motion")
        }
    }

    private fun uiSourceFiles(root: File = mainUiRoot): List<UiSource> =
        root
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { file ->
                UiSource(
                    path = file.relativeTo(repoRoot).path.replace(File.separatorChar, '/'),
                    text = file.readText(),
                )
            }.toList()

    private fun assertNoOffendingFiles(
        files: List<UiSource>,
        message: String,
        allowedPaths: Set<String> = emptySet(),
        isOffending: (UiSource) -> Boolean,
    ) {
        val offendingFiles =
            files
                .filterNot { it.path in allowedPaths }
                .filter(isOffending)
                .map { it.path }

        assertTrue(
            "$message: $offendingFiles",
            offendingFiles.isEmpty(),
        )
    }

    private data class UiSource(
        val path: String,
        val text: String,
    )
}
