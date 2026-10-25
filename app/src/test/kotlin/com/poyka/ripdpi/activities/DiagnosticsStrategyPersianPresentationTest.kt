package com.poyka.ripdpi.activities

import android.app.Application
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.BypassApproachDetail
import com.poyka.ripdpi.diagnostics.BypassApproachId
import com.poyka.ripdpi.diagnostics.BypassApproachKind
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.BypassApproachVerificationState
import com.poyka.ripdpi.diagnostics.BypassRuntimeHealthSummary
import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.ProbeResult
import com.poyka.ripdpi.diagnostics.StrategyEmitterTier
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary
import com.poyka.ripdpi.diagnostics.StrategyProbeCompletionKind
import com.poyka.ripdpi.diagnostics.StrategyProbeRecommendation
import com.poyka.ripdpi.diagnostics.StrategyProbeReport
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.ui.diagnostics.toApproachDetailUiModel
import com.poyka.ripdpi.ui.diagnostics.toStrategyProbeReportUiModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "en", application = Application::class)
class DiagnosticsStrategyPersianPresentationTest {
    private val locale = RetainedLocaleTestSupport()

    @After
    fun tearDown() {
        locale.close()
    }

    @Test
    fun `retained factory rebuilds generated approach and strategy text English Persian English`() {
        val factory = DiagnosticsUiFactorySupport(locale.englishContext())
        val detail = approach()
        val raw = report()
        val initial = factory.toStrategyProbeReportUiModel(raw, results(), "VPN")
        assertEquals("Automatic audit", initial.suiteLabel)
        assertEquals("Usage", factory.toApproachDetailUiModel(detail).runtimeSummary[0].label)

        locale.select("fa")
        val resolver = factory.context
        val localized = factory.toStrategyProbeReportUiModel(raw, results(), "VPN")
        assertEquals(resolver.getString(R.string.diagnostics_scan_automatic_audit), localized.suiteLabel)
        assertEquals(
            listOf(
                resolver.getString(R.string.diagnostics_approach_metric_usage),
                resolver.getString(R.string.diagnostics_approach_metric_runtime),
                resolver.getString(R.string.diagnostics_metric_errors),
                resolver.getString(R.string.diagnostics_metric_route_changes),
            ),
            factory.toApproachDetailUiModel(detail).runtimeSummary.map { it.label },
        )
        assertEquals(
            listOf("2", "11", "13"),
            factory
                .toApproachDetailUiModel(detail)
                .runtimeSummary
                .filterIndexed { i, _ ->
                    i !=
                        1
                }.map { it.value },
        )
        assertEquals("ماتریس TCP / HTTP / HTTPS", localized.families[0].title)
        assertEquals("موفق", localized.summaryMetrics[0].label)
        val winner = requireNotNull(localized.candidateDetails["tcp-0012"])
        assertPersianWinner(winner, resolver)
        val recommendation = requireNotNull(localized.recommendation)
        assertEquals(
            listOf(
                "پیشنهاد TCP",
                resolver.getString(R.string.diagnostics_signature_tcp_tls_lane),
                "پیشنهاد QUIC",
                resolver.getString(R.string.diagnostics_signature_quic_lane),
                resolver.getString(R.string.diagnostics_audit_winning_dns_lane),
                "بازداری",
                "علت برنده شدن",
            ),
            recommendation.fields.map { it.label },
        )
        assertEquals("Raw DNS 0012", recommendation.fields[4].value)
        assertEquals("Raw suppression 0012", recommendation.fields[5].value)
        assertEquals("Raw recommendation 0012", recommendation.rationale)
        assertEquals("tcp-0012", requireNotNull(localized.winningPath).tcpWinner.id)

        locale.select("en")
        assertEquals(initial, factory.toStrategyProbeReportUiModel(raw, results(), "VPN"))
        assertEquals("Usage", factory.toApproachDetailUiModel(detail).runtimeSummary[0].label)
        assertEquals("baseline", raw.tcpCandidates[0].family)
        assertEquals("success", raw.tcpCandidates[0].outcome)
        assertEquals("full_matrix_v1", raw.suiteId)
    }

    @Test
    fun `unknown identifiers keep exact spelling and digits in Persian`() {
        locale.select("fa")
        val factory = DiagnosticsUiFactorySupport(locale.englishContext())
        val candidate = candidate().copy(family = "Future_Family_0012", outcome = "Future_Outcome_0012")
        val ui =
            factory.toStrategyProbeReportUiModel(
                StrategyProbeReport("Suite_0012", tcpCandidates = listOf(candidate)),
                emptyList(),
                null,
            )
        val detail = requireNotNull(ui.candidateDetails[candidate.id])
        assertEquals("Suite_0012", ui.suiteLabel)
        assertEquals("Future_Family_0012", detail.familyLabel)
        assertEquals("Future_Outcome_0012", detail.outcome)
        assertEquals(candidate.id, detail.id)
        assertEquals(candidate.label, detail.label)
        assertNull(ui.recommendation)
        assertNull(ui.winningPath)
    }

    @Test
    fun `skipped partial and absent data keep presentation boundaries in Persian`() {
        locale.select("fa")
        val factory = DiagnosticsUiFactorySupport(locale.englishContext())
        val skipped =
            candidate().copy(
                outcome = "skipped",
                skipped = true,
                averageLatencyMs = null,
                emitterDowngraded = true,
            )
        val raw =
            report().copy(
                tcpCandidates = listOf(skipped),
                completionKind = StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED,
            )
        val ui = factory.toStrategyProbeReportUiModel(raw, emptyList(), null)
        val resolver = factory.context
        val detail = requireNotNull(ui.candidateDetails[skipped.id])
        assertEquals(resolver.getString(R.string.diagnostics_probe_status_skipped), detail.outcome)
        assertEquals(resolver.getString(R.string.diagnostics_probe_status_fallback), detail.metrics.last().value)
        assertEquals("روش جایگزین تقریبی", detail.metrics[4].value)
        assertTrue(
            detail.notes.contains("ارسال دقیق در دسترس نبود؛ بنابراین این گزینه از مسیر جایگزین تقریبی اجرا شد."),
        )
        assertFalse(detail.notes.contains("ارسال دقیق در این مسیر به قابلیت‌های زمان اجرای روت‌شده نیاز داشت."))
        assertFalse(detail.metrics.any { it.label == resolver.getString(R.string.profile_variants_metric_latency) })
        assertNull(ui.winningPath)
        assertEquals("علت توقف آزمایش‌ها", requireNotNull(ui.recommendation).fields.last().label)
        assertEquals("روش جایگزین TCP", requireNotNull(ui.recommendation).fields.first().label)
        val partial =
            factory.toStrategyProbeReportUiModel(
                raw.copy(completionKind = StrategyProbeCompletionKind.PARTIAL_RESULTS, recommendation = null),
                emptyList(),
                null,
            )
        val partialPresentation = requireNotNull(partial.presentation)
        assertTrue(partialPresentation.isIncomplete)
        assertFalse(partialPresentation.supportsWinningPath)
        assertNull(partial.recommendation)
        assertTrue(partial.candidateDetails.values.all { it.resultGroups.isEmpty() })
        val absent =
            factory.toStrategyProbeReportUiModel(
                StrategyProbeReport(StrategyProbeSuiteQuickV1),
                emptyList(),
                null,
            )
        assertTrue(absent.candidateDetails.isEmpty())
        assertTrue(absent.families.all { it.candidates.isEmpty() })
        assertEquals(resolver.getString(R.string.diagnostics_scan_automatic_probing), absent.suiteLabel)
        assertEquals("گزینه‌های TCP", absent.families[0].title)
    }

    @Test
    fun `current families outcomes and emitter branches use Persian labels`() {
        locale.select("fa")
        val factory = DiagnosticsUiFactorySupport(locale.englishContext())
        val families = knownStrategyFamilies()
        val raw =
            StrategyProbeReport(
                StrategyProbeSuiteQuickV1,
                tcpCandidates = families.map { candidate().copy(id = it, family = it) },
            )
        val localized = factory.toStrategyProbeReportUiModel(raw, emptyList(), null)
        families.forEach { family ->
            assertFalse(
                "Known family must have a display label: $family",
                requireNotNull(localized.candidateDetails[family]).familyLabel == family,
            )
        }
        val outcomes =
            listOf("success", "partial", "failed", "not_applicable", "skipped", "eliminated", "unverified_execution")
        val statuses =
            factory.toStrategyProbeReportUiModel(
                raw.copy(
                    tcpCandidates =
                        outcomes.map {
                            candidate().copy(id = it, outcome = it)
                        },
                ),
                emptyList(),
                null,
            )
        outcomes.forEach { outcome ->
            assertFalse(
                "Known outcome must have a display label: $outcome",
                requireNotNull(statuses.candidateDetails[outcome]).outcome == outcome,
            )
        }
        val lab =
            candidate().copy(
                emitterTier = StrategyEmitterTier.LAB_DIAGNOSTICS_ONLY,
                exactEmitterRequiresRoot = false,
            )
        val direct =
            candidate().copy(
                id = "direct",
                emitterTier = StrategyEmitterTier.NON_ROOT_PRODUCTION,
                exactEmitterRequiresRoot = false,
            )
        val emitters =
            factory.toStrategyProbeReportUiModel(
                raw.copy(tcpCandidates = listOf(lab, direct)),
                emptyList(),
                null,
            )
        assertEquals("فقط عیب‌یابی آزمایشگاهی", requireNotNull(emitters.candidateDetails[lab.id]).metrics[4].value)
        assertEquals("مسیر آزمایشگاهی", requireNotNull(emitters.candidateDetails[lab.id]).metrics[5].value)
        assertTrue(
            requireNotNull(
                emitters.candidateDetails[lab.id],
            ).notes.contains("این گزینه فقط در محدودهٔ عیب‌یابی است و جزو پیش‌فرض‌های عملیاتی نیست."),
        )
        assertEquals("عملیاتی بدون روت", requireNotNull(emitters.candidateDetails[direct.id]).metrics[4].value)
        assertEquals(
            factory.context.getString(R.string.home_connection_actuator_direct),
            requireNotNull(emitters.candidateDetails[direct.id]).metrics[5].value,
        )
    }

    @Test
    fun `known DNS display labels localize in headline fields and winning path`() {
        locale.select("fa")
        val factory = DiagnosticsUiFactorySupport(locale.englishContext())
        val labels = listOf("Plain DNS" to R.string.dns_mode_plain, "Encrypted DNS" to R.string.dns_mode_doh)
        labels.forEach { (label, resource) ->
            val raw =
                report().copy(
                    recommendation = requireNotNull(report().recommendation).copy(dnsStrategyLabel = label),
                )
            val ui = factory.toStrategyProbeReportUiModel(raw, emptyList(), null)
            val expected = factory.context.getString(resource)
            assertEquals(expected, requireNotNull(ui.winningPath).dnsLaneLabel)
            assertEquals(expected, requireNotNull(ui.recommendation).fields[4].value)
            assertTrue(requireNotNull(ui.recommendation).headline.endsWith(expected))
            assertEquals(label, requireNotNull(raw.recommendation).dnsStrategyLabel)
        }
    }

    private fun assertPersianWinner(
        winner: DiagnosticsStrategyProbeCandidateDetailUiModel,
        resolver: StringResolver,
    ) {
        assertEquals("خط مبنا", winner.familyLabel)
        assertEquals(resolver.getString(R.string.diagnostics_approach_metric_success), winner.outcome)
        assertEquals("0012 custom label", winner.label)
        assertEquals("Raw rationale 0012", winner.rationale)
        assertEquals("User note 0012", winner.notes.first())
        assertTrue(winner.notes.contains("ارسال دقیق در این مسیر به قابلیت‌های زمان اجرای روت‌شده نیاز داشت."))
        assertEquals(
            listOf(
                "هدف‌ها",
                "وزن",
                resolver.getString(R.string.home_stat_quality),
                resolver.getString(R.string.profile_variants_metric_latency),
                "فرستنده",
                "نحوهٔ اجرا",
                resolver.getString(R.string.adaptive_split_preset_selected),
            ),
            winner.metrics.map { it.label },
        )
        assertEquals(listOf("1/2", "10/20", "7", "۱۷ ms"), winner.metrics.take(4).map { it.value })
        assertEquals("عملیاتی با روت", winner.metrics[4].value)
        assertEquals("اجرای دقیق با روت", winner.metrics[5].value)
        assertEquals("برنده", winner.metrics[6].value)
        // The resolver isolates the Latin argument so it cannot capture a neighbouring number.
        assertEquals("نتایج \u2068HTTPS\u2069", winner.resultGroups.single().title)
        assertEquals(
            listOf("one.example", "two.example"),
            winner.resultGroups
                .single()
                .items
                .map { it.target },
        )
    }

    private fun knownStrategyFamilies(): List<String> =
        listOf(
            "baseline",
            "baseline_current",
            "parser",
            "parser_aggressive",
            "split",
            "split_delayed",
            "split_tfo",
            "ech_split",
            "ech_tlsrec",
            "tlsrec_split",
            "tlsrec_seqovl",
            "tlsrec_fake",
            "tlsrec_oob",
            "tlsrec_disoob",
            "tlsrec_disorder",
            "tlsrec_split_tfo",
            "tlsrandrec_split",
            "tlsrandrec_disorder",
            "circular_tlsrec_split",
            "fake_flags",
            "fake_approx",
            "fake_rst",
            "hostfake",
            "seqovl",
            "activation_window",
            "adaptive_fake_ttl",
            "fake_payload_library",
            "multi_disorder",
            "disorder",
            "disoob",
            "oob",
            "ipfrag2",
            "ipfrag2_ipv6_ext",
            "quic_disabled",
            "quic_burst",
            "quic_crypto_split",
            "quic_dummy_prepend",
            "quic_fake_version",
            "quic_ipfrag2",
            "quic_ipfrag2_ipv6_ext",
            "quic_multi_initial_realistic",
            "quic_padding_ladder",
            "quic_sni_split",
            "quic_version_negotiation_decoy",
            "quic_compat_burst",
            "quic_realistic_burst",
        )

    private fun results(): List<ProbeResult> =
        listOf("one.example", "two.example").map { target ->
            ProbeResult(
                "strategy_probe",
                target,
                "success",
                listOf(ProbeDetail("candidateId", "tcp-0012"), ProbeDetail("protocol", "https")),
            )
        }

    private fun report(): StrategyProbeReport =
        StrategyProbeReport(
            suiteId = StrategyProbeSuiteFullMatrixV1,
            tcpCandidates = listOf(candidate()),
            quicCandidates = listOf(candidate().copy(id = "quic-0012", family = "quic_disabled")),
            recommendation =
                StrategyProbeRecommendation(
                    tcpCandidateId = "tcp-0012",
                    tcpCandidateLabel = "0012 custom label",
                    tcpCandidateFamily = "baseline",
                    quicCandidateId = "quic-0012",
                    quicCandidateLabel = "Raw QUIC 0012",
                    quicCandidateFamily = "quic_disabled",
                    dnsStrategyLabel = "Raw DNS 0012",
                    rationale = "Raw recommendation 0012",
                    recommendedProxyConfigJson = "{}",
                    tlsPathSuppressionSummary = "Raw suppression 0012",
                ),
        )

    private fun candidate(): StrategyProbeCandidateSummary =
        StrategyProbeCandidateSummary(
            id = "tcp-0012",
            label = "0012 custom label",
            family = "baseline",
            outcome = "success",
            rationale = "Raw rationale 0012",
            succeededTargets = 1,
            totalTargets = 2,
            weightedSuccessScore = 10,
            totalWeight = 20,
            qualityScore = 7,
            averageLatencyMs = 17,
            notes = listOf("User note 0012"),
            emitterTier = StrategyEmitterTier.ROOTED_PRODUCTION,
            exactEmitterRequiresRoot = true,
        )

    private fun approach(): BypassApproachDetail =
        BypassApproachDetail(
            BypassApproachSummary(
                approachId = BypassApproachId(BypassApproachKind.Strategy, "strategy-0012"),
                displayName = "User strategy 0012",
                secondaryLabel = "Raw subtitle",
                verificationState = BypassApproachVerificationState.NOT_EVALUATED,
                validatedScanCount = 0,
                validatedSuccessCount = 0,
                validatedSuccessRate = null,
                lastValidatedResult = null,
                usageCount = 2,
                totalRuntimeDurationMs = 60_000L,
                recentRuntimeHealth = BypassRuntimeHealthSummary(totalErrors = 11, routeChanges = 13),
                lastUsedAt = null,
            ),
        )
}
