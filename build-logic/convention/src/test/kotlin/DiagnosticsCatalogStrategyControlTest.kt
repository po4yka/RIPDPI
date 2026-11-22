import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
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
            // A domestic site answers without any bypass, so it can never verify a strategy path.
            assertTrue(controls.none { it.controlTier == CatalogControlTier.DOMESTIC }, profile.id)
            controls.filter { it.host == "speed.cloudflare.com" }.forEach { control ->
                val bytes = Regex("""/__down\?bytes=(\d+)""").matchEntire(control.httpPath)?.groupValues?.get(1)
                assertTrue(bytes?.toIntOrNull() in 1..16_384, profile.id)
            }
        }
    }

    @Test
    fun `the raw path baseline has every control tier from independent providers`() {
        val index = DiagnosticsCatalogIndex(DefaultDiagnosticsCatalogPackSource.load())
        val throttling = DefaultDiagnosticsCatalogProfileSource.load(index).single { it.id == "ru-throttling" }
        val controlsByTier = throttling.domainTargets.filter { it.isControl }.groupBy { it.controlTier }

        assertTrue(controlsByTier.getValue(CatalogControlTier.DOMESTIC).size >= 2)
        assertTrue(CatalogControlTier.GLOBAL in controlsByTier)
        assertTrue(CatalogControlTier.CDN in controlsByTier)
    }

    @Test
    fun `the validator rejects a control without a tier and a domestic strategy control`() {
        val packs = DefaultDiagnosticsCatalogPackSource.load()
        val profiles = DefaultDiagnosticsCatalogProfileSource.load(DiagnosticsCatalogIndex(packs))
        val strategy = profiles.first { it.kind == CatalogScanKind.STRATEGY_PROBE }
        val untiered =
            strategy.copy(domainTargets = listOf(DomainTargetDefinition(host = "c.example", isControl = true)))
        val domestic =
            strategy.copy(
                domainTargets = domainTargets("d.example", control = CatalogControlTier.DOMESTIC, httpPath = "/r"),
            )

        listOf(
            untiered to "exactly one control tier",
            domestic to "must not use a domestic control",
        ).forEach { (invalid, reason) ->
            val error =
                assertFailsWith<IllegalArgumentException> {
                    DiagnosticsCatalogValidator().validate(DiagnosticsCatalog(packs, profiles - strategy + invalid))
                }
            assertContains(error.message.orEmpty(), reason)
        }
    }
}
