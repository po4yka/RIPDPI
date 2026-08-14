package com.poyka.ripdpi.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DesignSystemSourceRulesTest {
    private val repoRoot =
        generateSequence(File(".").canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
    private val mainAppRoot = File(repoRoot, "app/src/main/kotlin/com/poyka/ripdpi")
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
    fun `screens use shared design-system wrappers for loading indicators`() {
        val governedProgressImports =
            Regex(
                pattern =
                    "^import androidx\\.compose\\.material3\\." +
                        "(CircularProgressIndicator|LinearProgressIndicator)\\b",
                option = RegexOption.MULTILINE,
            )

        assertNoOffendingFiles(
            files = uiSourceFiles(screenUiRoot),
            // The stealth-score meter encodes its verdict in the fill color, so it is a meter rather
            // than a loading state; RipDpiProgressBar is fixed to the foreground/muted palette.
            allowedPaths =
                setOf("app/src/main/kotlin/com/poyka/ripdpi/ui/screens/detection/DetectionResultCards.kt"),
            message = "Screens must use RipDpiSpinner or RipDpiProgressBar for loading states",
        ) { source ->
            governedProgressImports.containsMatchIn(source.text)
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

    @Test
    fun `app theme does not opt into platform dynamic color palettes`() {
        val dynamicPaletteUsage =
            Regex("\\b(dynamicLightColorScheme|dynamicDarkColorScheme|DynamicColors)\\b")

        assertNoOffendingFiles(
            files = uiSourceFiles(mainAppRoot),
            message = "RIPDPI uses governed fixed theme palettes; dynamic color requires contrast coverage first",
        ) { source ->
            dynamicPaletteUsage.containsMatchIn(source.text)
        }
    }

    @Test
    fun `accent container token is not used as foreground content`() {
        val foregroundOnlyFiles =
            setOf(
                "app/src/main/kotlin/com/poyka/ripdpi/ui/components/feedback/RipDpiJsonTree.kt",
                "app/src/main/kotlin/com/poyka/ripdpi/ui/components/feedback/RipDpiLogStream.kt",
                "app/src/main/kotlin/com/poyka/ripdpi/ui/components/feedback/RipDpiTooltipRich.kt",
                "app/src/main/kotlin/com/poyka/ripdpi/ui/screens/blockcheck/BlockcheckRoute.kt",
                "app/src/main/kotlin/com/poyka/ripdpi/ui/screens/detection/DetectionHistoryCommunityCards.kt",
                "app/src/main/kotlin/com/poyka/ripdpi/ui/screens/detection/DetectionResultCards.kt",
                "app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/HandshakeTimelineScreen.kt",
                "app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/PortMatrixScreen.kt",
                "app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/QualityGraphsScreen.kt",
                "app/src/main/kotlin/com/poyka/ripdpi/ui/screens/history/HistoryFilters.kt",
                "app/src/main/kotlin/com/poyka/ripdpi/ui/screens/settings/DesyncSection.kt",
            )
        val directAccentContent =
            Regex("""\b(?:contentColor|tint)\s*=\s*colors\.accent\b""")
        val governedPaths =
            foregroundOnlyFiles +
                "app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/PcapViewerScreen.kt"

        assertNoOffendingFiles(
            files = governedPaths.map(::uiSource),
            message = "The accent token is a container role; use accentForeground, foreground, or info for content",
        ) { source ->
            (source.path in foregroundOnlyFiles && source.text.contains("colors.accent")) ||
                directAccentContent.containsMatchIn(source.text)
        }
    }

    private fun uiSource(path: String): UiSource {
        val file =
            sequenceOf(
                File(repoRoot, path),
                File(repoRoot, path.removePrefix("app/")),
            ).firstOrNull(File::isFile)

        checkNotNull(file) { "Unable to resolve UI source: $path" }
        return UiSource(path = path, text = file.readText())
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
