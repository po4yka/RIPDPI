package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
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

    @Test
    fun `collector waits for started lifecycle without losing buffered event`() {
        val owner = TestLifecycleOwner(Lifecycle.State.CREATED)
        val effects = Channel<String>(Channel.BUFFERED)
        val calls = mutableListOf<String>()

        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                BackupEffectCollector(flow = effects.receiveAsFlow(), onEffect = { calls += it })
            }
        }
        composeRule.waitForIdle()

        runBlocking { effects.send("buffered") }
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), calls)

        composeRule.runOnIdle { owner.moveTo(Lifecycle.State.STARTED) }
        composeRule.waitForIdle()

        assertEquals(listOf("buffered"), calls)
    }

    private class TestLifecycleOwner(
        initialState: Lifecycle.State,
    ) : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply { currentState = initialState }

        override val lifecycle: Lifecycle = registry

        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }
}
