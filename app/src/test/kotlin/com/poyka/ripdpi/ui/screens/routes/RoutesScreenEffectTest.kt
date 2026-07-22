package com.poyka.ripdpi.ui.screens.routes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.poyka.ripdpi.data.rules.RuleEntity
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onStart
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RoutesScreenEffectTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `persisted row updates do not restart the reorder failure collector`() {
        val collectionCount = AtomicInteger()
        val reorderFailures =
            MutableSharedFlow<Unit>(extraBufferCapacity = 1)
                .onStart { collectionCount.incrementAndGet() }
        var state by
            mutableStateOf(
                RoutesUiState(
                    rows = persistentListOf(ruleRow(id = 1L, name = "First")),
                ),
            )

        composeRule.setContent {
            RipDpiTheme {
                RoutesScreen(
                    state = state,
                    onBack = {},
                    onAddRule = {},
                    onEditRule = {},
                    onToggleEnabled = {},
                    onDelete = {},
                    onReorder = {},
                    reorderFailures = reorderFailures,
                )
            }
        }
        composeRule.waitUntil { collectionCount.get() == 1 }

        composeRule.runOnIdle {
            state =
                RoutesUiState(
                    rows = persistentListOf(ruleRow(id = 2L, name = "Second")),
                )
        }
        composeRule.waitForIdle()

        assertEquals(1, collectionCount.get())
    }

    private fun ruleRow(
        id: Long,
        name: String,
    ): RuleRow =
        RuleRow(
            rule = RuleEntity(id = id, name = name),
            outboundLabel = "Proxy",
        )
}
