package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupEffectCollectorTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `collector uses latest callback without restarting collection`() {
        val effects = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val calls = mutableListOf<String>()
        var onEffect by mutableStateOf<suspend (String) -> Unit>({ calls += "initial:$it" })

        composeRule.setContent {
            BackupEffectCollector(flow = effects, onEffect = onEffect)
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            onEffect = { calls += "updated:$it" }
        }
        composeRule.waitForIdle()
        runBlocking { effects.emit("event") }
        composeRule.waitForIdle()

        assertEquals(listOf("updated:event"), calls)
    }

    @Test
    fun `collector switches subscriptions when flow changes`() {
        val initialEffects = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val updatedEffects = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val calls = mutableListOf<String>()
        var effects by mutableStateOf<SharedFlow<String>>(initialEffects)

        composeRule.setContent {
            BackupEffectCollector(flow = effects, onEffect = { calls += it })
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            effects = updatedEffects
        }
        composeRule.waitForIdle()
        runBlocking {
            initialEffects.emit("stale")
            updatedEffects.emit("current")
        }
        composeRule.waitForIdle()

        assertEquals(listOf("current"), calls)
    }
}
