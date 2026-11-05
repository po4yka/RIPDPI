import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsCatalogThroughputTest {
    @Test
    fun `throughput profiles measure download objects and retain YouTube reachability`() {
        val index = DiagnosticsCatalogIndex(DefaultDiagnosticsCatalogPackSource.load())
        val profiles = DefaultDiagnosticsCatalogProfileSource.load(index)

        listOf("ru-throttling", "ru-dpi-full").forEach { id ->
            val profile = profiles.single { it.id == id }
            assertTrue(profile.domainTargets.any { it.host == "www.youtube.com" }, id)
            assertEquals(2, profile.throughputTargets.size, id)
            assertTrue(profile.throughputTargets.all { it.windowBytes == 8_388_608 }, id)
            assertEquals(
                setOf("https://speed.cloudflare.com/__down?bytes=8388608", "https://proof.ovh.net/files/10Mb.dat"),
                profile.throughputTargets.map { it.url }.toSet(),
                id,
            )
        }
        assertFalse(profiles.flatMap { it.throughputTargets }.any { it.url == "https://www.youtube.com/" })
    }
}
