import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class FrameTimingScrollBenchmarkContractTest {
    @Test
    fun `scroll frame timing benchmarks cover logs diagnostics live and history`() {
        val sourcePath =
            repoRoot()
                .resolve(
                    "baselineprofile/src/main/kotlin/com/poyka/ripdpi/baselineprofile/ScrollFrameTimingBenchmark.kt",
                )
        assertTrue(Files.exists(sourcePath), "Missing benchmark source: $sourcePath")

        val source = Files.readString(sourcePath)
        assertContains(source, "class ScrollFrameTimingBenchmark")
        assertContains(source, "FrameTimingMetric")

        val scenarios =
            listOf(
                Scenario(
                    functionName = "logsScroll",
                    route = "logs",
                    dataPreset = "diagnostics_demo",
                    screenTag = "logs-screen",
                    scrollContainerTag = "logs-stream-list",
                ),
                Scenario(
                    functionName = "diagnosticsLiveScroll",
                    route = "diagnostics",
                    dataPreset = "diagnostics_demo",
                    screenTag = "diagnostics-screen",
                    scrollContainerTag = "diagnostics-live-list",
                    navigationMarkerTag = "diagnostics-section-live",
                ),
                Scenario(
                    functionName = "historyScroll",
                    route = "history",
                    dataPreset = "diagnostics_report_demo",
                    screenTag = "history-screen",
                    scrollContainerTag = "history-events-list",
                    navigationMarkerTag = "history-section-events",
                ),
            )

        scenarios.forEach { scenario ->
            assertTrue(
                Regex("@Test\\s+fun\\s+${scenario.functionName}\\s*\\(").containsMatchIn(source),
                "Missing @Test scenario: ${scenario.functionName}",
            )
            val function = source.functionBody(scenario.functionName)
            assertContains(function, "measureRepeated", message = scenario.functionName)
            assertContains(function, "setupBlock", message = scenario.functionName)
            assertContains(function, "measureBlock", message = scenario.functionName)
            assertTrue(
                Regex("metrics\\s*=\\s*listOf\\s*\\(\\s*FrameTimingMetric\\(\\)\\s*\\)")
                    .containsMatchIn(function),
                "${scenario.functionName} must collect FrameTimingMetric",
            )
            assertTrue(
                Regex("(?:startAutomationActivity|startActivityAndWait)").containsMatchIn(function),
                "${scenario.functionName} must open the automation route",
            )
            assertContains(function, "\"${scenario.route}\"", message = scenario.functionName)
            assertContains(function, "\"${scenario.dataPreset}\"", message = scenario.functionName)
            assertContains(function, "\"${scenario.screenTag}\"", message = scenario.functionName)
            assertContains(function, "\"${scenario.scrollContainerTag}\"", message = scenario.functionName)
            scenario.navigationMarkerTag?.let { markerTag ->
                assertContains(function, "\"$markerTag\"", message = scenario.functionName)
            }
            assertTrue(
                Regex(
                    "(?:requireObject|waitForObject|waitForIdle|findObject)[^\\n]*\"" +
                        Regex.escape(scenario.screenTag) +
                        "\"",
                ).containsMatchIn(function),
                "${scenario.functionName} must wait for its screen",
            )
            assertTrue(
                Regex(
                    "(?:requireObject|waitForObject|waitForIdle|findObject)[^\\n]*\"" +
                        Regex.escape(scenario.scrollContainerTag) +
                        "\"",
                ).containsMatchIn(function),
                "${scenario.functionName} must wait for its scroll container",
            )
            assertTrue(
                Regex("(?:scrollDown|swipe)\\s*\\(").containsMatchIn(function),
                "${scenario.functionName} must perform a deterministic downward scroll",
            )
        }
    }

    private fun repoRoot(): Path = Path.of(System.getProperty("user.dir")).resolve("../..").normalize()

    private fun String.functionBody(functionName: String): String {
        val declaration = Regex("fun\\s+$functionName\\s*\\(").find(this)
        requireNotNull(declaration) { "Missing benchmark test function: $functionName" }

        val bodyStart = indexOf('{', declaration.range.last + 1)
        require(bodyStart >= 0) { "Missing body for benchmark test function: $functionName" }

        var depth = 0
        for (index in bodyStart until length) {
            when (this[index]) {
                '{' -> {
                    depth += 1
                }

                '}' -> {
                    depth -= 1
                    if (depth == 0) return substring(bodyStart, index + 1)
                }
            }
        }
        error("Unclosed body for benchmark test function: $functionName")
    }

    private data class Scenario(
        val functionName: String,
        val route: String,
        val dataPreset: String,
        val screenTag: String,
        val scrollContainerTag: String,
        val navigationMarkerTag: String? = null,
    )
}
