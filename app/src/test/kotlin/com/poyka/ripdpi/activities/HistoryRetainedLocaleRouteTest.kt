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
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionDetail
import com.poyka.ripdpi.platform.AndroidStringResolver
import com.poyka.ripdpi.ui.screens.history.HistoryRoute
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.util.MainDispatcherRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "en", application = Application::class)
class HistoryRetainedLocaleRouteTest {
    @get:Rule val composeRule = createComposeRule()

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val locales = RetainedLocaleTestSupport()

    @After
    fun resetLocale() = locales.close()

    @Test
    fun `retained history route refreshes loaded details without repository reloads`() {
        locales.select("en")
        val context = locales.englishContext()
        val strings = AndroidStringResolver(context)
        val core = DiagnosticsUiCoreSupport(DiagnosticsUiFormatter(context), strings)
        val connectionFactory = HistoryConnectionDetailUiFactory(stringResolver = strings, coreSupport = core)
        val sessionMapper = DiagnosticsSessionDetailUiFactory(DiagnosticsUiFactorySupport(strings, core))
        val connectionDetail =
            DiagnosticConnectionDetail(
                session = historyConnectionSession(),
                snapshots = listOf(historySnapshot(connectionSessionId = "connection-1")),
                contexts = listOf(historyContext(connectionSessionId = "connection-1")),
                telemetry = listOf(historyTelemetry(connectionSessionId = "connection-1")),
                events = listOf(historyEvent(connectionSessionId = "connection-1")),
            )
        val sessionDetail = historyDiagnosticsDetail()
        val frozenConnections = listOf(connectionDetail.session)
        val frozenSessions = listOf(sessionDetail.session)
        val source =
            FakeDiagnosticsHistorySource().apply {
                connectionSessions.value = frozenConnections
                diagnosticsSessions.value = frozenSessions
                connectionDetails[connectionDetail.session.id] = connectionDetail
            }
        val rawSessionLoader = FakeHistoryDiagnosticsDetailLoader().apply { nextDetail = sessionDetail }
        val viewModel =
            HistoryViewModel(
                diagnosticsHistorySource = source,
                historyDetailLoader = DefaultHistoryDetailLoader(source, rawSessionLoader),
                diagnosticsBootstrapper = FakeHistoryDiagnosticsBootstrapper(),
                historyUiStateFactory = HistoryUiStateFactory(core, connectionFactory, sessionMapper),
            )
        val configuration = mutableStateOf(Configuration(context.resources.configuration))
        composeRule.setContent {
            CompositionLocalProvider(
                LocalConfiguration provides configuration.value,
                LocalContext provides ContextCompat.getContextForLanguage(context),
            ) {
                RipDpiTheme { HistoryRoute(onBack = {}, viewModel = viewModel) }
            }
        }
        selectDetailsAndFilters(viewModel, connectionDetail.session.id, sessionDetail.session.id)
        assertEquals(
            "Service",
            requireNotNull(viewModel.uiState.value.selectedConnectionDetail).contextGroups.first().title,
        )

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
            assertEquals(
                expected,
                connectionFactory
                    .toConnectionDetail(connectionDetail)
                    .contextGroups
                    .first()
                    .title,
            )
            assertRetainedState(state, expected, connectionDetail.session.id, sessionDetail.session.id)
            assertSame(frozenConnections, source.connectionSessions.value)
            assertSame(frozenSessions, source.diagnosticsSessions.value)
            assertSame(connectionDetail, source.connectionDetails[connectionDetail.session.id])
            assertSame(sessionDetail, rawSessionLoader.nextDetail)
            assertEquals(1, source.connectionDetailLoads)
            assertEquals(1, rawSessionLoader.sessionDetailLoads)
        }
    }

    private fun selectDetailsAndFilters(
        viewModel: HistoryViewModel,
        connectionId: String,
        sessionId: String,
    ) {
        composeRule.runOnUiThread {
            viewModel.selectConnection(connectionId)
            viewModel.selectDiagnosticsSession(sessionId)
            viewModel.setConnectionModeFilter("VPN")
            viewModel.setConnectionStatusFilter("Running")
            viewModel.setConnectionSearch("wifi")
            viewModel.setDiagnosticsPathModeFilter("RAW_PATH")
            viewModel.setDiagnosticsStatusFilter("completed")
            viewModel.setDiagnosticsSearch("Scan")
            viewModel.selectSection(HistorySection.Diagnostics)
        }
        composeRule.waitUntil(5_000) {
            viewModel.uiState.value.selectedConnectionDetail != null &&
                viewModel.uiState.value.selectedDiagnosticsDetail != null
        }
    }

    private fun assertRetainedState(
        state: HistoryUiState,
        expected: String,
        connectionId: String,
        sessionId: String,
    ) {
        assertEquals(expected, requireNotNull(state.selectedConnectionDetail).contextGroups.first().title)
        assertEquals(expected, requireNotNull(state.selectedDiagnosticsDetail).contextGroups.first().title)
        assertEquals(connectionId, state.connections.focusedSessionId)
        assertEquals(sessionId, state.diagnostics.focusedSessionId)
        assertEquals(HistorySection.Diagnostics, state.selectedSection)
        assertEquals("VPN", state.connections.filters.mode)
        assertEquals("Running", state.connections.filters.status)
        assertEquals("wifi", state.connections.filters.query)
        assertEquals("RAW_PATH", state.diagnostics.filters.pathMode)
        assertEquals("completed", state.diagnostics.filters.status)
        assertEquals("Scan", state.diagnostics.filters.query)
    }
}
