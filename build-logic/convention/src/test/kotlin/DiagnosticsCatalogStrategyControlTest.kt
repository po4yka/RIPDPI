import kotlin.test.Test
import kotlin.test.assertTrue

class DiagnosticsCatalogStrategyControlTest {
    @Test
    fun `strategy controls return bounded bodies from more than one provider`() {
        val index = DiagnosticsCatalogIndex(DefaultDiagnosticsCatalogPackSource.load())
        val strategyProfiles =
            DefaultDiagnosticsCatalogProfileSource.load(index).filter { it.kind == CatalogScanKind.STRATEGY_PROBE }

        assertTrue(strategyProfiles.isNotEmpty())
        strategyProfiles.forEach { profile ->
            val controls = profile.domainTargets.filter { it.isControl }
            // The in-path transfer reads at most 16 KiB; a site root page can never verify the active path.
            assertTrue(controls.all { it.httpPath != "/" }, profile.id)
            // Cloudflare is blocked in some regions, so it cannot be the only baseline.
            assertTrue(controls.any { !it.host.endsWith("cloudflare.com") }, profile.id)
            controls.filter { it.host == "speed.cloudflare.com" }.forEach { control ->
                val bytes = Regex("""/__down\?bytes=(\d+)""").matchEntire(control.httpPath)?.groupValues?.get(1)
                assertTrue(bytes?.toIntOrNull() in 1..16_384, profile.id)
            }
        }
    }
}
