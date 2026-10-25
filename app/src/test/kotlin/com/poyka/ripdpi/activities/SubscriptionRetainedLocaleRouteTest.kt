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
import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.platform.AndroidStringResolver
import com.poyka.ripdpi.platform.LocalizedTimestampFormatter
import com.poyka.ripdpi.testsupport.FakeServiceStateStore
import com.poyka.ripdpi.ui.screens.subscription.SubscriptionFailoverRoute
import com.poyka.ripdpi.ui.screens.subscription.SubscriptionFailoverUiState
import com.poyka.ripdpi.ui.screens.subscription.SubscriptionFailoverViewModel
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
class SubscriptionRetainedLocaleRouteTest {
    @get:Rule val composeRule = createComposeRule()

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val locales = RetainedLocaleTestSupport()

    @After
    fun resetLocale() = locales.close()

    @Test
    fun `retained subscription route refreshes captions without telemetry or profile reloads`() {
        locales.select("en")
        val context = locales.englishContext()
        val profiles =
            listOf(
                RelayProfileRecord(
                    id = "01-primary",
                    server = "primary.example.net",
                    serverName = "Minha rede — Primary",
                ),
                RelayProfileRecord(id = "02-backup", server = "backup.example.net", serverName = "Minha rede — Backup"),
            )
        val profileStore = FakeLocaleRelayProfileStore(profiles)
        val frozenGroups = emptyList<ProxyGroup>()
        val groupRepository = FakeLocaleProxyGroupRepository(frozenGroups)
        val telemetry = telemetryFixture()
        val service = FakeServiceStateStore().apply { updateTelemetry(telemetry) }
        val viewModel =
            SubscriptionFailoverViewModel(
                service,
                groupRepository,
                profileStore,
                AndroidStringResolver(context),
                LocalizedTimestampFormatter(context),
            )
        val configuration = mutableStateOf(Configuration(context.resources.configuration))
        composeRule.setContent {
            CompositionLocalProvider(
                LocalConfiguration provides configuration.value,
                LocalContext provides ContextCompat.getContextForLanguage(context),
            ) {
                RipDpiTheme { SubscriptionFailoverRoute(onBack = {}, onAddSubscription = {}, viewModel = viewModel) }
            }
        }
        composeRule.waitUntil(5_000) { viewModel.uiState.value.servers.size == 2 }
        assertEquals(
            "current server",
            viewModel.uiState.value.servers
                .last()
                .detail,
        )

        listOf("pt-BR", "en").forEach { tag ->
            composeRule.runOnUiThread {
                locales.select(tag)
                configuration.value =
                    Configuration(configuration.value).apply {
                        setLocales(LocaleList.forLanguageTags(tag))
                    }
            }
            composeRule.waitForIdle()
            val state = viewModel.uiState.value
            assertPresentation(state, tag)
            assertSame(telemetry, service.telemetry.value)
            assertSame(profiles, profileStore.profiles)
            assertSame(frozenGroups, groupRepository.state.value)
            assertEquals(1, profileStore.listCalls)
        }
    }

    private fun telemetryFixture(): ServiceTelemetrySnapshot =
        ServiceTelemetrySnapshot(
            relayTelemetry =
                NativeRuntimeSnapshot(
                    source = "relay",
                    state = "running",
                    health = "healthy",
                    profileId = "02-backup",
                    nativeEvents =
                        listOf(
                            NativeRuntimeEvent(
                                source = "relay",
                                level = "info",
                                kind = "failover",
                                message = "primary server stopped answering",
                                createdAt = 0L,
                            ),
                        ),
                ),
        )

    private fun assertPresentation(
        state: SubscriptionFailoverUiState,
        tag: String,
    ) {
        assertEquals(if (tag == "pt-BR") "servidor atual" else "current server", state.servers.last().detail)
        assertEquals(
            if (tag ==
                "pt-BR"
            ) {
                "trocou para o backup"
            } else {
                "switched to backup"
            },
            state.events.first().message,
        )
        assertEquals(if (tag == "pt-BR") "horário desconhecido" else "time unknown", state.events.first().timeLabel)
        assertEquals("primary server stopped answering", state.events.last().message)
        assertEquals("Minha rede — Backup", state.servers.last().name)
        assertEquals("backup.example.net:443", state.servers.last().endpoint)
        assertTrue(state.activeServerLabel.contains("Minha rede — Backup"))
    }
}
