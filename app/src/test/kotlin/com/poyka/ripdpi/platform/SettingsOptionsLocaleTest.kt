package com.poyka.ripdpi.platform

import android.app.Application
import android.content.res.Configuration
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.screens.settings.settingsOptionValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SettingsOptionsLocaleTest {
    @Test
    fun `all locales retain exact setting values and technical labels`() {
        for (tag in listOf("en", "ru", "es", "de", "fr", "fa", "ar", "zh-CN", "hi", "pt-BR")) {
            val resources = localizedContext(tag).resources
            for (contract in contracts) {
                val labels = resources.getStringArray(contract.labels)
                assertEquals("$tag wire values", contract.values, settingsOptionValues(resources, contract.entries))
                assertEquals(contract.values.size, labels.size)
                labels.forEach { label ->
                    assertTrue("$tag empty display label", label.isNotBlank())
                    assertFalse("$tag unresolved reference", label.startsWith("@string/"))
                }
                contract.exactLabels.forEach { (index, label) -> assertEquals(label, labels[index]) }
            }
        }
    }

    @Test
    fun `Hindi settings arrays resolve all descriptive labels to Devanagari`() {
        val resources = localizedContext("hi").resources
        var translated = 0
        for (contract in contracts) {
            resources.getStringArray(contract.labels).forEachIndexed { index, label ->
                if (index !in contract.exactLabels) {
                    translated += 1
                    assertTrue("Untranslated display label: $label", label.any { it in '\u0900'..'\u097F' })
                }
            }
        }
        assertEquals(46, translated)
    }

    @Test
    fun `theme labels follow English Hindi English without changing entry values`() {
        val first = localizedContext("en").resources.getStringArray(R.array.themes).toList()
        val hindi = localizedContext("hi").resources.getStringArray(R.array.themes).toList()
        val last = localizedContext("en").resources.getStringArray(R.array.themes).toList()
        assertNotEquals(first, hindi)
        assertEquals(first, last)
    }

    private fun localizedContext(tag: String) =
        RuntimeEnvironment.getApplication().let { application ->
            application.createConfigurationContext(
                Configuration(application.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(tag))
                },
            )
        }

    private val contracts =
        listOf(
            OptionContract(
                R.array.themes,
                R.array.themes_entries,
                listOf("system", "light", "dark"),
                emptyMap(),
            ),
            OptionContract(
                R.array.ripdpi_hosts_modes,
                R.array.ripdpi_hosts_modes_entries,
                listOf("disable", "blacklist", "whitelist"),
                emptyMap(),
            ),
            OptionContract(
                R.array.warp_route_modes,
                R.array.warp_route_modes_entries,
                listOf("off", "rules"),
                emptyMap(),
            ),
            OptionContract(
                R.array.warp_endpoint_selection_modes,
                R.array.warp_endpoint_selection_modes_entries,
                listOf("automatic", "manual"),
                emptyMap(),
            ),
            OptionContract(
                R.array.warp_amnezia_presets,
                R.array.warp_amnezia_presets_entries,
                listOf("off", "random", "quic_imitation", "tls_imitation", "dns_imitation", "custom"),
                emptyMap(),
            ),
            OptionContract(
                R.array.quic_initial_modes,
                R.array.quic_initial_modes_entries,
                listOf("disabled", "route", "route_and_cache"),
                emptyMap(),
            ),
            OptionContract(
                R.array.tls_fingerprint_profiles,
                R.array.tls_fingerprint_profiles_entries,
                listOf(
                    "chrome_stable",
                    "chrome_desktop_stable",
                    "firefox_stable",
                    "firefox_ech_stable",
                    "safari_stable",
                    "edge_stable",
                ),
                emptyMap(),
            ),
            OptionContract(
                R.array.entropy_modes,
                R.array.entropy_modes_entries,
                listOf("disabled", "popcount", "shannon", "combined"),
                emptyMap(),
            ),
            OptionContract(
                R.array.fake_tls_base_modes,
                R.array.fake_tls_base_modes_entries,
                listOf("default", "original"),
                emptyMap(),
            ),
            OptionContract(
                R.array.http_fake_profiles,
                R.array.http_fake_profiles_entries,
                listOf("compat_default", "iana_get", "cloudflare_get"),
                mapOf(1 to "IANA GET", 2 to "Cloudflare GET"),
            ),
            OptionContract(
                R.array.fake_tls_sni_modes,
                R.array.fake_tls_sni_modes_entries,
                listOf("fixed", "randomized"),
                emptyMap(),
            ),
            OptionContract(
                R.array.ip_id_modes,
                R.array.ip_id_modes_entries,
                listOf("", "seq", "seqgroup", "rnd", "zero"),
                mapOf(2 to "Seqgroup"),
            ),
            OptionContract(
                R.array.fake_order_modes,
                R.array.fake_order_modes_entries,
                listOf("0", "1", "2", "3"),
                mapOf(0 to "Altorder 0", 1 to "Altorder 1", 2 to "Altorder 2", 3 to "Altorder 3"),
            ),
            OptionContract(
                R.array.tls_fake_profiles,
                R.array.tls_fake_profiles_entries,
                listOf(
                    "compat_default",
                    "iana_firefox",
                    "google_chrome",
                    "vk_chrome",
                    "sberbank_chrome",
                    "rutracker_kyber",
                    "bigsize_iana",
                ),
                mapOf(
                    1 to "IANA Firefox",
                    2 to "Google Chrome",
                    3 to "VK Chrome",
                    4 to "Sberbank Chrome",
                    5 to "Rutracker Kyber",
                ),
            ),
            OptionContract(
                R.array.udp_fake_profiles,
                R.array.udp_fake_profiles_entries,
                listOf(
                    "compat_default",
                    "zero_256",
                    "zero_512",
                    "dns_query",
                    "stun_binding",
                    "wireguard_initiation",
                    "dht_get_peers",
                ),
                mapOf(6 to "DHT get_peers"),
            ),
        )
}

private data class OptionContract(
    val labels: Int,
    val entries: Int,
    val values: List<String>,
    val exactLabels: Map<Int, String>,
)
