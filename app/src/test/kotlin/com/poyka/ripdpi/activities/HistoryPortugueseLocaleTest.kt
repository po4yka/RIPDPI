package com.poyka.ripdpi.activities

import android.app.Application
import android.content.res.Configuration
import android.os.Bundle
import android.os.LocaleList
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionDetail
import com.poyka.ripdpi.diagnostics.RememberedPolicyApplicationAudit
import com.poyka.ripdpi.platform.AndroidStringResolver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "en", application = Application::class)
class HistoryPortugueseLocaleTest {
    private val activities = mutableListOf<ActivityController<HistoryPortugueseLocaleActivity>>()

    @Before
    fun resetLanguage() {
        resetAppCompatLocales()
    }

    @After
    fun clearLanguage() {
        activities.forEach { it.destroy() }
        resetAppCompatLocales()
    }

    @Test
    fun `fresh history factory uses app Portuguese on English system for every context caption`() {
        val application = RuntimeEnvironment.getApplication()
        val english =
            application.createConfigurationContext(
                Configuration(application.resources.configuration).apply {
                    setLocales(LocaleList(Locale.ENGLISH))
                },
            )
        val session =
            historyConnectionSession(
                rememberedPolicyAudit =
                    RememberedPolicyApplicationAudit(
                        matchedFingerprintHash = "abcdef0123456789fedcba9876543210",
                        source = RememberedNetworkPolicySource.AUTOMATIC_PROBING_BACKGROUND,
                        appliedByExactMatch = true,
                        previousSuccessCount = 4,
                        previousFailureCount = 1,
                        previousConsecutiveFailureCount = 0,
                    ),
            )
        val source =
            DiagnosticConnectionDetail(
                session,
                emptyList(),
                listOf(historyContext(connectionSessionId = session.id)),
                emptyList(),
                emptyList(),
            )
        listOf("en", "pt-BR", "en").forEach { language ->
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language))
            activities += Robolectric.buildActivity(HistoryPortugueseLocaleActivity::class.java).create()
            val strings = AndroidStringResolver(english)
            val factory =
                HistoryConnectionDetailUiFactory(
                    strings,
                    DiagnosticsUiCoreSupport(DiagnosticsUiFormatter(english), strings),
                )
            assertEquals(
                "en",
                english.resources.configuration.locales[0]
                    .language,
            )
            assertEquals(
                if (language == "pt-BR") "Política memorizada" else "Remembered policy",
                strings.getString(R.string.history_connection_remembered_policy_badge),
            )
            val detail = factory.toConnectionDetail(source)
            val policy = detail.contextGroups.last()
            val captions =
                listOfNotNull(factory.toConnectionRowUiModel(session).rememberedPolicyBadge) +
                    detail.contextGroups.take(3).map { it.title } +
                    detail.contextGroups.takeLast(2).map { it.title } +
                    policy.fields.map { it.label } +
                    listOf(policy.fields[1].value)
            assertEquals("All 13 captions must use $language", expectedCaptions(language), captions)
            assertEquals("Running", source.session.connectionState)
            assertEquals(
                "abcdef0123456789fedcba9876543210",
                source.session.rememberedPolicyAudit?.matchedFingerprintHash,
            )
        }
    }

    private fun expectedCaptions(language: String): List<String> =
        if (language == "pt-BR") {
            listOf(
                "Política memorizada",
                "Serviço",
                "Ambiente",
                "Dispositivo",
                "Telemetria de campo",
                "Política memorizada",
                "Fonte",
                "Motivo da correspondência",
                "Impressão digital correspondente",
                "Sucessos anteriores",
                "Falhas anteriores",
                "Falhas consecutivas anteriores",
                "Correspondência exata de rede",
            )
        } else {
            listOf(
                "Remembered policy",
                "Service",
                "Environment",
                "Device",
                "Field telemetry",
                "Remembered policy",
                "Source",
                "Match reason",
                "Matched fingerprint",
                "Previous successes",
                "Previous failures",
                "Previous consecutive failures",
                "Exact network match",
            )
        }

    private fun resetAppCompatLocales() {
        AppCompatDelegate::class.java
            .getDeclaredMethod("resetStaticRequestedAndStoredLocales")
            .apply { isAccessible = true }
            .invoke(null)
    }
}

class HistoryPortugueseLocaleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_RIPDPI)
        super.onCreate(savedInstanceState)
    }
}
