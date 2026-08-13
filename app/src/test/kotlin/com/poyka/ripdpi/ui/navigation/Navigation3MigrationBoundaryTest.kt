package com.poyka.ripdpi.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class Navigation3MigrationBoundaryTest {
    @Test
    fun `app navigation uses Navigation 3 and keeps typed route keys complete`() {
        val versionCatalog = File("../gradle/libs.versions.toml").readText()
        val appBuild = File("build.gradle.kts").readText()
        val routeSource = File("src/main/kotlin/com/poyka/ripdpi/ui/navigation/Route.kt").readText()
        val navigationStateSource =
            File("src/main/kotlin/com/poyka/ripdpi/ui/navigation/RipDpiNavigationState.kt").readText()
        val productionSources =
            File("src/main/kotlin")
                .walkTopDown()
                .filter { file -> file.isFile && file.extension == "kt" }
                .map { file -> file.relativeTo(File("src/main/kotlin")).path to file.readText() }
                .toList()
        val routeDeclarations =
            Regex(
                """(?s)@Serializable\s+(?:data\s+)?(?:object|class)\s+([A-Za-z0-9_]+)""" +
                    """(?:(?!@Serializable).)*?:\s*Route\(""",
            ).findAll(routeSource)
                .map { match -> match.groupValues[1] }
                .toList()
        val routeAllBlock = routeSource.substringAfter("val all: List<Route>").substringBefore("fun fromStableRoute")
        val routeBaseImplementsNavKey =
            Regex("""sealed\s+class\s+Route\s*:\s*NavKey\b""").containsMatchIn(routeSource)
        val missingNavKeyRoutes = if (routeBaseImplementsNavKey) emptyList() else routeDeclarations
        val routesMissingFromRegistry =
            routeDeclarations.filterNot { routeName ->
                Regex("""\b$routeName(?:\(\))?\b""").containsMatchIn(routeAllBlock)
            }
        val legacyNavigationUsages =
            productionSources
                .flatMap { (path, source) ->
                    LegacyNavigationApi.findAll(source).map { match -> "$path: ${match.value}" }
                }.distinct()
                .sorted()

        assertEquals(
            emptyList<String>(),
            buildList {
                if (!versionCatalog.contains("""androidx.navigation3:navigation3-runtime""")) {
                    add("Missing Navigation 3 runtime dependency")
                }
                if (!versionCatalog.contains("""androidx.navigation3:navigation3-ui""")) {
                    add("Missing Navigation 3 UI dependency")
                }
                if (!productionSources.any { (_, source) -> source.contains("androidx.navigation3.ui.NavDisplay") }) {
                    add("App production must render the stack with Navigation 3 NavDisplay")
                }
                if (!navigationStateSource.contains("rememberNavBackStack")) {
                    add("Navigation state must use saveable Navigation 3 back stacks")
                }
                if (!navigationStateSource.contains("rememberSaveableStateHolderNavEntryDecorator")) {
                    add("Navigation entries must retain their saveable Compose state")
                }
                if (!navigationStateSource.contains("rememberRipDpiSharedViewModelStoreNavEntryDecorator")) {
                    add("Navigation entries must retain entry and graph scoped ViewModels")
                }
                if (!routeSource.contains("import androidx.navigation3.runtime.NavKey")) {
                    add("Route keys must import Navigation 3 NavKey")
                }
                if (versionCatalog.contains("""androidx.navigation:navigation-compose""")) {
                    add("Legacy navigation-compose dependency remains in version catalog")
                }
                if (appBuild.contains("androidx-navigation-compose")) {
                    add("Legacy navigation-compose alias remains on the app classpath")
                }
                addAll(
                    legacyNavigationUsages.map { usage ->
                        "Legacy Navigation API remains in app production: $usage"
                    },
                )
                addAll(missingNavKeyRoutes.map { route -> "Route.$route does not implement NavKey" })
                addAll(routesMissingFromRegistry.map { route -> "Route.$route is missing from Route.all" })
            },
        )
    }

    private companion object {
        val LegacyNavigationApi =
            Regex(
                listOf(
                    """\b(?:androidx\.navigation\.compose\.NavHost|""",
                    """androidx\.navigation\.compose\.rememberNavController|""",
                    """androidx\.navigation\.compose\.navigation|""",
                    """androidx\.navigation\.NavHostController|""",
                    """androidx\.navigation\.NavController|""",
                    """NavHostController|NavController|NavHost\()""",
                ).joinToString(separator = ""),
            )
    }
}
