package com.poyka.ripdpi.activities

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
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
import org.junit.Assert.assertNull
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
        showHistoryRoute(context, configuration, viewModel)
        selectConnectionAndFilters(viewModel, connectionDetail.session.id)
        assertEquals(
            "Service",
            requireNotNull(viewModel.uiState.value.selectedConnectionDetail).contextGroups.first().title,
        )

        // Switching section closes the open detail, so each section's detail is checked on its own.
        refreshLocales(context, configuration, locales) { expected ->
            assertEquals(
                expected,
                connectionFactory
                    .toConnectionDetail(connectionDetail)
                    .contextGroups
                    .first()
                    .title,
            )
            assertEquals(
                expected,
                requireNotNull(viewModel.uiState.value.selectedConnectionDetail).contextGroups.first().title,
            )
            assertSame(frozenConnections, source.connectionSessions.value)
            assertSame(connectionDetail, source.connectionDetails[connectionDetail.session.id])
            assertEquals(1, source.connectionDetailLoads)
        }

        selectDiagnosticsSession(viewModel, sessionDetail.session.id)
        refreshLocales(context, configuration, locales) { expected ->
            assertRetainedState(viewModel.uiState.value, expected, sessionDetail.session.id)
            assertSame(frozenSessions, source.diagnosticsSessions.value)
            assertSame(sessionDetail, rawSessionLoader.nextDetail)
            assertEquals(1, rawSessionLoader.sessionDetailLoads)
        }
    }

    private fun refreshLocales(
        context: Context,
        configuration: androidx.compose.runtime.MutableState<Configuration>,
        locales: RetainedLocaleTestSupport,
        assertLocale: (expected: String) -> Unit,
    ) {
        listOf("pt-BR" to "Serviço", "en" to "Service").forEach { (tag, expected) ->
            composeRule.runOnUiThread {
                locales.select(tag)
                configuration.value =
                    Configuration(configuration.value).apply {
                        setLocales(LocaleList.forLanguageTags(tag))
                    }
            }
            composeRule.waitForIdle()
            assertLocale(expected)
        }
    }

    private fun showHistoryRoute(
        context: Context,
        configuration: State<Configuration>,
        viewModel: HistoryViewModel,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalConfiguration provides configuration.value,
                LocalContext provides ContextCompat.getContextForLanguage(context),
            ) {
                RipDpiTheme {
                    HistoryRoute(
                        onBack = {},
                        onOpenConnection = {},
                        onOpenDiagnostics = {},
                        viewModel = viewModel,
                    )
                }
            }
        }
    }

    private fun selectConnectionAndFilters(
        viewModel: HistoryViewModel,
        connectionId: String,
    ) {
        composeRule.runOnUiThread {
            viewModel.selectConnection(connectionId)
            viewModel.setConnectionModeFilter("VPN")
            viewModel.setConnectionStatusFilter("Running")
            viewModel.setConnectionSearch("wifi")
            viewModel.setDiagnosticsPathModeFilter("RAW_PATH")
            viewModel.setDiagnosticsStatusFilter("completed")
            viewModel.setDiagnosticsSearch("Scan")
        }
        composeRule.waitUntil(5_000) { viewModel.uiState.value.selectedConnectionDetail != null }
    }

    private fun selectDiagnosticsSession(
        viewModel: HistoryViewModel,
        sessionId: String,
    ) {
        composeRule.runOnUiThread {
            viewModel.selectSection(HistorySection.Diagnostics)
            viewModel.selectDiagnosticsSession(sessionId)
        }
        composeRule.waitUntil(5_000) { viewModel.uiState.value.selectedDiagnosticsDetail != null }
    }

    private fun assertRetainedState(
        state: HistoryUiState,
        expected: String,
        sessionId: String,
    ) {
        assertNull(state.selectedConnectionDetail)
        assertEquals(expected, requireNotNull(state.selectedDiagnosticsDetail).contextGroups.first().title)
        assertNull(state.connections.focusedSessionId)
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
