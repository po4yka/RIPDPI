package com.poyka.ripdpi.activities

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.BypassApproachId
import com.poyka.ripdpi.diagnostics.BypassApproachKind
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.BypassApproachVerificationState
import com.poyka.ripdpi.diagnostics.BypassRuntimeHealthSummary
import com.poyka.ripdpi.diagnostics.Diagnosis
import com.poyka.ripdpi.diagnostics.DiagnosticProfile
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.ScanProgress
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
class DiagnosticsGeneratedLocaleTest {
    @Test
    fun `all bundled catalog titles resolve in current locale and renamed profiles stay exact`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalog =
            org.json
                .JSONObject(
                    context.assets
                        .open("diagnostics/default_profiles.json")
                        .bufferedReader()
                        .use { it.readText() },
                ).getJSONArray("profiles")
        val strings = LocaleStrings(context)
        for (tag in TranslatedLocales) {
            strings.select(tag)
            for (index in 0 until catalog.length()) {
                val item = catalog.getJSONObject(index)
                val profile =
                    DiagnosticProfile(item.getString("id"), item.getString("name"), "bundled", 1, updatedAt = 0L)
                assertNotEquals("$tag ${profile.id}", profile.name, profile.localizedBundledName(strings))
                assertEquals(profile.name, profile.copy(source = "user").localizedBundledName(strings))
                assertEquals("Custom Ω", profile.copy(name = "Custom Ω").localizedBundledName(strings))
                assertEquals(profile.name, profile.copy(id = "future-profile").localizedBundledName(strings))
            }
        }
    }

    @Test
    fun `home approach summary for the bundled default profile shows the localized title`() {
        val strings = LocaleStrings(ApplicationProvider.getApplicationContext())

        fun summary(
            kind: BypassApproachKind,
            name: String,
        ) = BypassApproachSummary(
            approachId = BypassApproachId(kind, "default"),
            displayName = name,
            secondaryLabel = "Profile",
            verificationState = BypassApproachVerificationState.NOT_EVALUATED,
            validatedScanCount = 0,
            validatedSuccessCount = 0,
            validatedSuccessRate = null,
            lastValidatedResult = null,
            usageCount = 0,
            totalRuntimeDurationMs = 0L,
            recentRuntimeHealth = BypassRuntimeHealthSummary(),
            lastUsedAt = null,
        )
        for (tag in listOf("ar", "ru")) {
            strings.select(tag)
            val expected = strings.getString(R.string.diagnostics_profile_connectivity_title)
            assertNotEquals(tag, "Default diagnostics", expected)
            assertEquals(
                tag,
                expected,
                summary(BypassApproachKind.Profile, "Default diagnostics").localizedDisplayName(strings),
            )
            assertEquals("My Ω", summary(BypassApproachKind.Profile, "My Ω").localizedDisplayName(strings))
            assertEquals(
                "Default diagnostics",
                summary(BypassApproachKind.Strategy, "Default diagnostics").localizedDisplayName(strings),
            )
        }
    }

    @Test
    fun `active offline and scope diagnoses resolve while raw evidence and unknown text stay intact`() {
        val strings = LocaleStrings(ApplicationProvider.getApplicationContext())
        val support =
            DiagnosticsUiFactorySupport(
                strings,
                DiagnosticsUiCoreSupport(
                    DiagnosticsUiFormatter(
                        ApplicationProvider.getApplicationContext(),
                    ),
                    strings,
                ),
            )
        val offline =
            Diagnosis(
                code = "network_unavailable",
                summary = "The operating system reports no available network",
                severity = "error",
                target = "endpoint.example",
                evidence = listOf("network transport is unavailable", "transport=wifi"),
                recommendation = "Connect to a network and run diagnostics again",
            )
        for (tag in TranslatedLocales) {
            strings.select(tag)
            val projected = support.toDiagnosisUiModel(offline)
            assertEquals(strings.getString(R.string.diagnostics_network_unavailable_summary), projected.summary)
            assertEquals(
                strings.getString(R.string.diagnostics_network_unavailable_evidence),
                projected.evidence.first(),
            )
            assertEquals("transport=wifi", projected.evidence.last())
            assertEquals(offline.target, projected.target)
            assertEquals(offline.code, projected.code)
            assertEquals(
                strings.getString(R.string.diagnostics_network_unavailable_recommendation),
                projected.recommendation,
            )
            val scoped = offline.copy(code = "network_scope_unverified")
            assertEquals(
                strings.getString(R.string.diagnostics_network_scope_unverified_summary),
                support.toDiagnosisUiModel(scoped).summary,
            )
            val recommendation =
                "A confirmed interaction applies the learned owned-stack profile and cap on the next service start; " +
                    "browser-originated TLS in proxy mode may bypass this control."
            val concurrency =
                offline.copy(
                    code = "connection_concurrency_interaction",
                    severity = "info",
                    recommendation = recommendation,
                )
            assertEquals(
                strings.getString(R.string.diagnostics_connection_concurrency_info_recommendation),
                support.toDiagnosisUiModel(concurrency).recommendation,
            )
            assertEquals(recommendation, concurrency.recommendation)
            assertEquals(
                "Custom recommendation",
                support.toDiagnosisUiModel(concurrency.copy(recommendation = "Custom recommendation")).recommendation,
            )
            val unknown = offline.copy(code = "future_code")
            assertEquals(unknown.summary, support.toDiagnosisUiModel(unknown).summary)
            assertEquals(unknown.recommendation, support.toDiagnosisUiModel(unknown).recommendation)
        }
    }

    @Test
    fun `known progress phases templates handovers and unavailable sentinel resolve on each projection`() {
        val strings = LocaleStrings(ApplicationProvider.getApplicationContext())
        val support =
            DiagnosticsUiFactorySupport(
                strings,
                DiagnosticsUiCoreSupport(
                    DiagnosticsUiFormatter(
                        ApplicationProvider.getApplicationContext(),
                    ),
                    strings,
                ),
            )
        for (tag in TranslatedLocales) {
            strings.select(tag)
            val progress = ScanProgress("scan", "starting", 0, 1, "Preparing My Ω profile")
            val projected = support.toProgressUiModel(progress, ScanKind.CONNECTIVITY, false, 0L)
            assertEquals(
                strings.getString(R.string.home_generated_progress_preparing, "My Ω profile"),
                projected.summary,
            )
            assertEquals(strings.getString(R.string.diagnostics_phase_starting), projected.phaseSteps.single().label)
            assertEquals(progress.phase, projected.phase)
            val environment =
                support.toProgressUiModel(
                    progress.copy(phase = "environment", message = "Collected network environment"),
                    ScanKind.CONNECTIVITY,
                    false,
                    0L,
                )
            assertEquals(
                strings.getString(R.string.history_detail_section_environment),
                environment.phaseSteps.single().label,
            )
            assertEquals(strings.getString(R.string.diagnostics_progress_environment_collected), environment.summary)
            assertEquals(
                "custom raw Ω",
                support
                    .toProgressUiModel(
                        progress.copy(message = "custom raw Ω"),
                        ScanKind.CONNECTIVITY,
                        false,
                        0L,
                    ).summary,
            )
            assertEquals(
                strings.getString(R.string.diagnostics_handover_transport_switch),
                "transport_switch".displayTriggerClassification(strings),
            )
            assertEquals(
                strings.getString(R.string.diagnostics_handover_link_refresh),
                "link_refresh".displayTriggerClassification(strings),
            )
            assertEquals(
                strings.getString(R.string.diagnostics_handover_strategy_failure),
                "strategy_failure".displayTriggerClassification(strings),
            )
            assertEquals("future_Custom-ID", "future_Custom-ID".displayTriggerClassification(strings))
            val unavailable = strings.getString(R.string.diagnostics_value_unavailable)
            assertEquals(unavailable, support.formatAutolearnHost("unavailable"))
            assertEquals(unavailable, support.formatAutolearnGroup("unavailable"))
            assertEquals(unavailable, support.formatAutolearnAction("unavailable"))
            assertEquals(unavailable, support.formatBlockSignal("unavailable"))
            assertEquals(unavailable, support.formatBlockProvider("unavailable"))
            assertEquals("host.example", support.formatAutolearnHost("host.example"))
        }
    }
}

private val TranslatedLocales = listOf("ru", "es", "de", "fr", "fa", "ar", "zh-CN", "hi", "pt-BR")

private class LocaleStrings(
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
