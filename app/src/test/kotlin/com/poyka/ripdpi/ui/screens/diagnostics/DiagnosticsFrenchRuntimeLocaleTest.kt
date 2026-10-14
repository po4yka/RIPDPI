package com.poyka.ripdpi.ui.screens.diagnostics

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.poyka.ripdpi.activities.DiagnosticsUiFactorySupport
import com.poyka.ripdpi.activities.buildCurrentServiceTelemetry
import com.poyka.ripdpi.activities.buildLiveUiModel
import com.poyka.ripdpi.activities.historyTelemetry
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "fr-w420dp-h1100dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagnosticsFrenchRuntimeLocaleTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `stopped retained sample shows French state in Live badge and headline`() {
        val context = languageContext("fr")
        val sample = historyTelemetry().copy(connectionState = "Stopped", createdAt = 0L)
        val live =
            DiagnosticsUiFactorySupport(context).buildLiveUiModel(
                activeConnectionSession = null,
                telemetry = listOf(sample),
                currentTelemetry = sample,
                nativeEvents = emptyList(),
                latestSnapshot = null,
                latestContext = null,
            )
        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
            ) {
                RipDpiTheme { LiveHeroCard(live) }
            }
        }
        val stopped = composeRule.onAllNodesWithText("arrêté")
        assertEquals("Both the status badge and headline must be translated", 2, stopped.fetchSemanticsNodes().size)
        stopped[0].assertIsDisplayed()
        stopped[1].assertIsDisplayed()
        assertEquals("Stopped", sample.connectionState)
    }

    @Test
    fun `generated unknown network follows French English French without changing raw sample`() {
        val context = mutableStateOf(languageContext("fr"))
        val sample =
            checkNotNull(
                buildCurrentServiceTelemetry(
                    status = AppStatus.Running,
                    mode = Mode.VPN,
                    telemetry = ServiceTelemetrySnapshot(mode = Mode.VPN, status = AppStatus.Running),
                    activeConnectionSession = null,
                ),
            )
        composeRule.setContent {
            val current = context.value
            val live =
                DiagnosticsUiFactorySupport(current).buildLiveUiModel(
                    activeConnectionSession = null,
                    telemetry = listOf(sample),
                    currentTelemetry = sample,
                    nativeEvents = emptyList(),
                    latestSnapshot = null,
                    latestContext = null,
                )
            CompositionLocalProvider(
                LocalContext provides current,
                LocalConfiguration provides current.resources.configuration,
            ) {
                RipDpiTheme { LiveHeroCard(live) }
            }
        }
        composeRule.onNodeWithText("Le trafic passe par Inconnu").assertIsDisplayed()
        composeRule.onAllNodesWithText("Inconnu")[0].assertIsDisplayed()
        composeRule.runOnIdle { context.value = languageContext("en") }
        composeRule.onNodeWithText("Traffic is moving through Unknown").assertIsDisplayed()
        composeRule.onAllNodesWithText("Unknown")[0].assertIsDisplayed()
        composeRule.runOnIdle { context.value = languageContext("fr") }
        composeRule.onNodeWithText("Le trafic passe par Inconnu").assertIsDisplayed()
        assertEquals("unknown", sample.networkType)
        assertEquals("Running", sample.connectionState)
    }

    private fun languageContext(language: String): Context {
        val application = RuntimeEnvironment.getApplication()
        val configuration = Configuration(application.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return application.createConfigurationContext(configuration)
    }
}
