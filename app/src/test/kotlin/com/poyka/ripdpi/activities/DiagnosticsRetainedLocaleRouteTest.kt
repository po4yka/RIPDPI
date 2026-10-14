package com.poyka.ripdpi.activities

import android.app.Application
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.content.ContextCompat
import com.poyka.ripdpi.ui.screens.diagnostics.DiagnosticsRoute
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.util.MainDispatcherRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "en", application = Application::class)
class DiagnosticsRetainedLocaleRouteTest {
    @get:Rule val composeRule = createComposeRule()

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val locales = RetainedLocaleTestSupport()

    @After
    fun resetLocale() = locales.close()

    @Test
    fun `retained diagnostics route refreshes selected presentation without new data`() {
        locales.select("en")
        val context = locales.englishContext()
        val detail =
            historyDiagnosticsDetail().copy(
                results = listOf(historyProbeResult().copy(probeType = "dns_integrity")),
            )
        val manager = FakeDiagnosticsManager(detail = detail)
        val frozenSessions = listOf(detail.session)
        manager.sessionsState.value = frozenSessions
        var sessionLoads = 0
        val loadSession = requireNotNull(manager.detailLoader.onLoadSessionDetail)
        manager.detailLoader.onLoadSessionDetail = { id ->
            sessionLoads++
            loadSession(id)
        }
        var approachLoads = 0
        val loadApproach = requireNotNull(manager.detailLoader.onLoadApproachDetail)
        manager.detailLoader.onLoadApproachDetail = { kind, id ->
            approachLoads++
            loadApproach(kind, id)
        }
        val viewModel =
            createDiagnosticsViewModel(
                appContext = context,
                diagnosticsManager = manager,
                appSettingsRepository = FakeAppSettingsRepository(),
                initialize = false,
            )
        val configuration = mutableStateOf(Configuration(context.resources.configuration))
        composeRule.setContent {
            CompositionLocalProvider(
                LocalConfiguration provides configuration.value,
                LocalContext provides ContextCompat.getContextForLanguage(context),
            ) {
                RipDpiTheme { DiagnosticsRoute(viewModel = viewModel) }
            }
        }
        val selectedIds = selectDetails(viewModel, detail.session.id)

        listOf("pt-BR" to "Serviço", "en" to "Service").forEach { (tag, expected) ->
            composeRule.runOnUiThread {
                locales.select(tag)
                configuration.value =
                    Configuration(configuration.value).apply {
                        setLocales(LocaleList.forLanguageTags(tag))
                    }
            }
            composeRule.waitForIdle()
            val state = viewModel.uiState.value
            assertPresentation(state, tag, expected, selectedIds)
            assertSame(frozenSessions, manager.sessionsState.value)
            assertSame(detail, manager.detail)
            assertEquals(2, sessionLoads)
            assertEquals(1, approachLoads)
        }
    }

    private fun selectDetails(
        viewModel: DiagnosticsViewModel,
        sessionId: String,
    ): SelectedIds {
        composeRule.runOnUiThread { viewModel.selectSession(sessionId) }
        composeRule.waitUntil(5_000) { viewModel.uiState.value.selectedSessionDetail != null }
        composeRule.runOnUiThread { viewModel.toggleSensitiveSessionDetails() }
        composeRule.waitUntil(5_000) {
            viewModel.uiState.value.selectedSessionDetail
                ?.sensitiveDetailsVisible == true
        }
        composeRule.runOnUiThread {
            viewModel.selectProbe(
                requireNotNull(viewModel.uiState.value.selectedSessionDetail)
                    .probeGroups
                    .first()
                    .items
                    .first(),
            )
            viewModel.selectApproach("custom-profile")
            viewModel.setSessionPathModeFilter("RAW_PATH")
            viewModel.setSessionStatusFilter("completed")
            viewModel.setSessionSearch("Scan")
        }
        composeRule.waitUntil(5_000) { viewModel.uiState.value.selectedApproachDetail != null }
        assertEquals(
            "Service",
            requireNotNull(viewModel.uiState.value.selectedSessionDetail).contextGroups.first().title,
        )
        return SelectedIds(
            probeId = requireNotNull(viewModel.uiState.value.selectedProbe).id,
            approachId = requireNotNull(viewModel.uiState.value.selectedApproachDetail).approach.id,
        )
    }

    private fun assertPresentation(
        state: DiagnosticsUiState,
        tag: String,
        expected: String,
        selectedIds: SelectedIds,
    ) {
        assertEquals(expected, requireNotNull(state.selectedSessionDetail).contextGroups.first().title)
        assertTrue(requireNotNull(state.selectedSessionDetail).sensitiveDetailsVisible)
        assertEquals(selectedIds.probeId, requireNotNull(state.selectedProbe).id)
        assertEquals(
            if (tag ==
                "pt-BR"
            ) {
                "Resposta"
            } else {
                "Response"
            },
            requireNotNull(state.selectedProbe)
                .dnsResponses
                .first()
                .fields
                .first()
                .label,
        )
        assertEquals(selectedIds.approachId, requireNotNull(state.selectedApproachDetail).approach.id)
        assertEquals(
            if (tag ==
                "pt-BR"
            ) {
                "Validado"
            } else {
                "Validated"
            },
            requireNotNull(state.selectedApproachDetail)
                .approach.metrics
                .first()
                .label,
        )
        assertEquals("example.org", requireNotNull(state.selectedProbe).target)
        assertEquals("RAW_PATH", state.sessions.filters.pathMode)
        assertEquals("completed", state.sessions.filters.status)
        assertEquals("Scan", state.sessions.filters.query)
    }

    private data class SelectedIds(
        val probeId: String,
        val approachId: String,
    )
}
