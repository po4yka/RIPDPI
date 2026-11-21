import kotlin.test.Test
import kotlin.test.assertTrue

class DiagnosticsCatalogStrategyControlTest {
    @Test
    fun `the first strategy control returns a body that fits the active path transfer`() {
        val index = DiagnosticsCatalogIndex(DefaultDiagnosticsCatalogPackSource.load())
        val strategyProfiles =
            DefaultDiagnosticsCatalogProfileSource.load(index).filter { it.kind == CatalogScanKind.STRATEGY_PROBE }

        assertTrue(strategyProfiles.isNotEmpty())
        strategyProfiles.forEach { profile ->
            val control = profile.domainTargets.first { it.isControl }
            // The in-path transfer reads at most 16 KiB; a larger control page can never verify the active path.
            val bytes =
                Regex("""/__down\?bytes=(\d+)""")
                    .matchEntire(control.httpPath)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt()
            assertTrue(control.host == "speed.cloudflare.com" && bytes != null && bytes in 1..16_384, profile.id)
        }
    }
}
