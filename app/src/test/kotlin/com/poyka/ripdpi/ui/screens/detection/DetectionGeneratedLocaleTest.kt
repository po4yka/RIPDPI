package com.poyka.ripdpi.ui.screens.detection

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.R
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.DetectionRecommendations
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.ExposureVerdict
import com.poyka.ripdpi.core.detection.ExposureVerdictBuilder
import com.poyka.ripdpi.core.detection.MatchedVpnApp
import com.poyka.ripdpi.core.detection.Recommendation
import com.poyka.ripdpi.core.detection.StealthScore
import com.poyka.ripdpi.core.detection.Verdict
import com.poyka.ripdpi.core.detection.VpnAppKind
import com.poyka.ripdpi.platform.StringResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DetectionGeneratedLocaleTest {
    @Test
    fun `retained clean verdict score labels and advice resolve from current locale without changing records`() {
        val result = detectionResult()
        val raw = ExposureVerdictBuilder.build(result, 100)
        val advice = DetectionRecommendations.generate(result).single()
        val strings = CurrentLocaleStrings(ApplicationProvider.getApplicationContext())
        for (tag in TranslatedLocales) {
            strings.select(tag)
            val localized = raw.localized(strings, 100)
            assertEquals(strings.getString(R.string.detection_headline_ordinary_https), localized.headline)
            assertNotEquals(raw.reason, localized.reason)
            assertNotEquals(raw.adjustmentHint, localized.adjustmentHint)
            val scoreLabels =
                listOf(
                    100 to R.string.home_quality_excellent,
                    89 to R.string.home_quality_good,
                    69 to R.string.detection_stealth_fair,
                    49 to R.string.detection_stealth_poor,
                    29 to R.string.detection_stealth_critical,
                )
            for ((score, resource) in scoreLabels) {
                assertEquals(strings.getString(resource), strings.detectionGeneratedText(StealthScore.label(score)))
                val low = ExposureVerdictBuilder.build(result, score).localized(strings, score)
                assertNotEquals(ExposureVerdictBuilder.build(result, score).reason, low.reason)
            }
            assertNotEquals(advice.title, advice.localized(strings).title)
            assertNotEquals(advice.description, advice.localized(strings).description)
        }
        assertEquals("You look like ordinary HTTPS traffic", raw.headline)
        assertEquals("No issues detected", advice.title)
    }

    @Test
    fun `all evidence signals produce translated headline reason and hint without rewriting technical evidence`() {
        val strings = CurrentLocaleStrings(ApplicationProvider.getApplicationContext())
        for (source in EvidenceSource.entries) {
            val evidence = EvidenceItem(source, true, EvidenceConfidence.HIGH, "raw endpoint.example Ω")
            val result =
                detectionResult(Verdict.DETECTED).copy(
                    directSigns = CategoryResult("Direct", true, emptyList(), evidence = listOf(evidence)),
                )
            val raw = ExposureVerdictBuilder.build(result, 50)
            for (tag in TranslatedLocales) {
                strings.select(tag)
                val localized = raw.localized(strings, 50)
                assertNotEquals("$tag $source headline", raw.headline, localized.headline)
                assertNotEquals("$tag $source reason", raw.reason, localized.reason)
                assertNotEquals("$tag $source hint", raw.adjustmentHint, localized.adjustmentHint)
            }
            assertEquals(
                "raw endpoint.example Ω",
                result.directSigns.evidence
                    .single()
                    .description,
            )
        }
    }

    @Test
    fun `category fallbacks and generic review verdicts resolve without evidence signals`() {
        val base = detectionResult(Verdict.DETECTED)
        val flagged = CategoryResult("raw Ω", true, emptyList())
        val cases =
            listOf(
                base.copy(tlsFingerprint = flagged),
                base.copy(timingAnalysis = flagged),
                base.copy(webRtcLeak = flagged),
                base.copy(directSigns = flagged),
                base.copy(indirectSigns = flagged),
                base.copy(locationSignals = flagged),
                base.copy(bypassResult = base.bypassResult.copy(detected = true)),
                base,
                detectionResult(Verdict.NEEDS_REVIEW),
                detectionResult(Verdict.NOT_DETECTED).copy(
                    tlsFingerprint = flagged.copy(detected = false, needsReview = true),
                ),
            )
        val strings = CurrentLocaleStrings(ApplicationProvider.getApplicationContext())
        cases.forEach { result ->
            val raw = ExposureVerdictBuilder.build(result, 50)
            TranslatedLocales.forEach { tag ->
                strings.select(tag)
                val localized = raw.localized(strings, 50)
                assertNotEquals("$tag headline", raw.headline, localized.headline)
                assertNotEquals("$tag reason", raw.reason, localized.reason)
                assertNotEquals("$tag hint", raw.adjustmentHint, localized.adjustmentHint)
                assertNotEquals("$tag signal", raw.dominantSignal, localized.dominantSignal)
            }
        }
    }

    @Test
    fun `active generated remediation advice resolves but custom recommendation and verdict remain exact`() {
        val strings = CurrentLocaleStrings(ApplicationProvider.getApplicationContext())
        val evidence =
            listOf(
                EvidenceSource.LOCAL_PROXY,
                EvidenceSource.XRAY_API,
                EvidenceSource.DNS,
                EvidenceSource.NETWORK_CAPABILITIES,
            ).map { EvidenceItem(it, true, EvidenceConfidence.HIGH, "raw") }
        val result =
            detectionResult(Verdict.DETECTED).copy(
                directSigns =
                    CategoryResult(
                        "Direct",
                        true,
                        emptyList(),
                        evidence = evidence,
                        matchedApps =
                            listOf(
                                MatchedVpnApp(
                                    "custom.package",
                                    "Custom Ω",
                                    null,
                                    VpnAppKind.TARGETED_BYPASS,
                                    EvidenceSource.INSTALLED_APP,
                                    false,
                                    EvidenceConfidence.HIGH,
                                ),
                            ),
                    ),
                indirectSigns = CategoryResult("Indirect", true, emptyList(), evidence = evidence),
                bypassResult = detectionResult().bypassResult.copy(evidence = evidence),
            )
        val generated = DetectionRecommendations.generate(result)
        assertEquals(5, generated.size)
        for (tag in TranslatedLocales) {
            strings.select(tag)
            for (raw in generated) {
                assertNotEquals(raw.title, raw.localized(strings).title)
                assertNotEquals(raw.description, raw.localized(strings).description)
                assertEquals(raw.destination, raw.localized(strings).destination)
            }
            val custom = Recommendation("Custom Ω", "endpoint.example:443 raw")
            assertEquals(custom, custom.localized(strings))
            val customVerdict = ExposureVerdict("Custom Ω", "raw reason", "raw hint", "future_signal")
            assertEquals(customVerdict, customVerdict.localized(strings, 100))
        }
    }
}

private val TranslatedLocales = listOf("ru", "es", "de", "fr", "fa", "ar", "zh-CN", "hi", "pt-BR")

private class CurrentLocaleStrings(
    private val base: Context,
) : StringResolver {
    private var context = base

    fun select(tag: String) {
        val configuration = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        context = base.createConfigurationContext(configuration)
    }

    override fun getString(
        resId: Int,
        vararg formatArgs: Any,
    ): String = context.getString(resId, *formatArgs)

    override fun getQuantityString(
        resId: Int,
        quantity: Int,
        vararg formatArgs: Any,
    ): String = context.resources.getQuantityString(resId, quantity, *formatArgs)
}
