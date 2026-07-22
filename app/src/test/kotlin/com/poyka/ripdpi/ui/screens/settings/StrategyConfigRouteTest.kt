package com.poyka.ripdpi.ui.screens.settings

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StrategyConfigRouteTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun luaStrategyConfigYamlPersistsTunnelReadableLuaStep() {
        val yaml =
            luaStrategyConfigYaml(
                function = "candidate",
                scriptPath = "/data/user/0/com.poyka.ripdpi/files/lua/candidate.lua",
            )

        assertEquals(
            """
            version: 1
            strategies:
              - id: "lua:candidate"
                steps:
                  - type: lua
                    function: "candidate"
                    script_paths:
                      - "/data/user/0/com.poyka.ripdpi/files/lua/candidate.lua"
            """.trimIndent(),
            yaml,
        )
    }

    @Test
    fun `save cleanup failure banner never exposes a raw internal path`() =
        runTest {
            val rawPath = "/data/user/0/com.poyka.ripdpi/no_backup/private.json"
            val context: Context = ApplicationProvider.getApplicationContext()
            val store =
                object : StrategyConfigDraftStore {
                    override suspend fun restore(sessionId: String): StrategyConfigDraftRestoreResult =
                        StrategyConfigDraftRestoreResult.MissingOrCorrupt

                    override suspend fun persist(
                        sessionId: String,
                        session: StrategyConfigEditorSession,
                    ) = Unit

                    override suspend fun delete(sessionId: String): Unit = throw IllegalStateException(rawPath)
                }
            val viewModel = StrategyConfigEditorViewModel(SavedStateHandle(), store)
            viewModel.syncBuiltIn("tcp: split")
            viewModel.update { copy(configText = "tcp: saved") }
            val request = requireNotNull(viewModel.beginSave())

            val banner =
                runStrategyConfigRouteSave(context, viewModel, request) {
                    StrategyConfigBanner(
                        title = "saved",
                        message = "saved",
                        tone = WarningBannerTone.Info,
                        saved = true,
                    )
                }

            assertEquals(context.getString(R.string.update_error_unknown), banner.message)
            assertFalse(banner.message.contains(rawPath))
            assertFalse(requireNotNull(viewModel.session).isSaving)
            assertFalse(banner.saved)
        }

    @Test
    fun `draft persistence error takes precedence over an older banner`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val previous =
            StrategyConfigBanner(
                title = "Saved",
                message = "Saved for the next connection",
                tone = WarningBannerTone.Info,
            )

        val failure = resolveStrategyConfigRouteBanner(context, previous, hasPersistenceError = true)

        assertEquals(context.getString(R.string.strategy_config_draft_save_failed_title), failure?.title)
        assertEquals(context.getString(R.string.strategy_config_draft_save_failed_body), failure?.message)
        assertEquals(WarningBannerTone.Error, failure?.tone)
        assertEquals(previous, resolveStrategyConfigRouteBanner(context, previous, hasPersistenceError = false))
    }
}
