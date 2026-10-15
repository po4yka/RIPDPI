package com.poyka.ripdpi.activities

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeDnsAnswerGrammarRegressionTest {
    @Test
    fun `DNS quality has the noun form required by the full German sentence`() {
        val rationale =
            "UDP DNS showed dns_mismatch while Custom Resolver returned matching encrypted answers over " +
                "DOH on 1 probe(s) with 33 ms average latency."
        val raw = homeOutcome().copy(recommendationSummary = rationale)
        val projected = projectHome(raw, "de").analysisSheet!!.recommendationSummary!!

        assertTrue(projected, projected.contains("übereinstimmende verschlüsselte Antworten"))
        listOf("dns_mismatch", "Custom Resolver", "DOH").forEach { assertTrue(projected.contains(it)) }
        assertEquals(rationale, raw.recommendationSummary)
    }

    @Test
    fun `DNS answer objects remain grammatical and technical values remain exact in every locale`() {
        val expectedObjects =
            mapOf(
                "en" to listOf("matching encrypted answers", "usable encrypted answers"),
                "ru" to listOf("совпадающие зашифрованные ответы", "пригодные зашифрованные ответы"),
                "es" to listOf("respuestas cifradas coincidentes", "respuestas cifradas utilizables"),
                "de" to listOf("übereinstimmende verschlüsselte Antworten", "nutzbare verschlüsselte Antworten"),
                "fr" to listOf("des réponses chiffrées concordantes", "des réponses chiffrées utilisables"),
                "fa" to listOf("پاسخ‌های رمزگذاری‌شدهٔ منطبق", "پاسخ‌های رمزگذاری‌شدهٔ قابل استفاده"),
                "ar" to listOf("ردوداً مشفّرة متطابقة", "ردوداً مشفّرة قابلة للاستخدام"),
                "zh-CN" to listOf("匹配的加密应答", "可用的加密应答"),
                "hi" to listOf("मेल खाने वाले एन्क्रिप्टेड उत्तर", "उपयोग योग्य एन्क्रिप्टेड उत्तर"),
                "pt-BR" to listOf("respostas criptografadas correspondentes", "respostas criptografadas utilizáveis"),
            )
        expectedObjects.forEach { (tag, objects) ->
            listOf("matching", "usable").forEachIndexed { index, quality ->
                val rationale =
                    "UDP DNS showed dns_mismatch while Resolver Ω returned $quality encrypted answers over " +
                        "DOH on 1 probe(s) with 33 ms average latency. Avoided bootstrap IPs blocked by DPI: 192.0.2.8."
                val raw = homeOutcome().copy(recommendationSummary = rationale)
                val projected = projectHome(raw, tag).analysisSheet!!.recommendationSummary!!
                assertTrue("$tag: $projected", projected.contains(objects[index]))
                listOf("dns_mismatch", "Resolver Ω", "DOH", "192.0.2.8").forEach {
                    assertTrue("$tag: $it", projected.contains(it))
                }
                assertEquals(rationale, raw.recommendationSummary)
            }
        }
    }

    @Test
    fun `English count labels work at singular plural and zero boundaries`() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val context =
            application.createConfigurationContext(
                Configuration(application.resources.configuration).apply { setLocale(Locale.ENGLISH) },
            )
        listOf(0, 1, 2, 3, 11, 21, 100).forEach { count ->
            assertEquals("Entries: $count", context.getString(R.string.logs_entries_count, count))
            assertEquals("Packets: $count", context.getString(R.string.vpn_pcap_viewer_meta_format, count))
            assertEquals("Minutes remaining: $count", context.getString(R.string.pause_remaining, count.toString()))
            val raw =
                homeOutcome().copy(
                    recommendationSummary =
                        "Detected TCP RST injection on $count TCP probe(s) with $count blocked service(s). " +
                            "Recommend switching to fake packets with adaptive TTL.",
                )
            val projected = projectHome(raw, "en").analysisSheet!!.recommendationSummary!!
            assertTrue(projected, projected.contains(" (TCP probes: $count) (blocked services: $count)"))
        }
    }
}
