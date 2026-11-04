package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.ui.screens.status.StatusConnectionActuatorStatus
import com.poyka.ripdpi.ui.screens.status.compileStrategyPassageScene
import com.poyka.ripdpi.ui.screens.status.durationMillis
import com.poyka.ripdpi.ui.theme.RipDpiMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RipDpiStrategyPassagePlaybackTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val scene =
        compileStrategyPassageScene(listOf(TcpChainStepModel(TcpChainStepKind.Split, "host+1")), emptyList())
    private val otherScene =
        compileStrategyPassageScene(listOf(TcpChainStepModel(TcpChainStepKind.Disorder, "host+1")), emptyList())
    private val atRest = Float.MAX_VALUE

    private var status by mutableStateOf(StatusConnectionActuatorStatus.Locked)
    private var currentScene by mutableStateOf(scene)
    private var sceneVisible by mutableStateOf(true)
    private val memory = PassagePlaybackMemory()
    private lateinit var clock: Animatable<Float, *>

    private fun play(
        initial: StatusConnectionActuatorStatus,
        motion: RipDpiMotion = RipDpiMotion(),
    ) {
        status = initial
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            if (sceneVisible) clock = rememberPassagePlayback(currentScene, status, memory, motion)
        }
        composeRule.mainClock.advanceTimeBy(FrameMillis)
    }

    private fun advance(millis: Long) {
        Snapshot.sendApplyNotifications()
        composeRule.mainClock.advanceTimeBy(FrameMillis)
        composeRule.mainClock.advanceTimeBy(millis)
    }

    @Test
    fun `opening a locked session stays at rest`() {
        play(StatusConnectionActuatorStatus.Locked)
        advance(MidFlightMillis)
        assertEquals(atRest, clock.value)
    }

    @Test
    fun `locking the session plays one flight and stops`() {
        play(StatusConnectionActuatorStatus.Open)
        status = StatusConnectionActuatorStatus.Locked
        advance(MidFlightMillis)
        assertTrue(clock.value > 0f && clock.value < scene.durationMillis)
        advance(scene.durationMillis + MidFlightMillis)
        assertEquals(scene.durationMillis.toFloat(), clock.value)
        assertTrue(!clock.isRunning)
    }

    @Test
    fun `a degraded session does not play`() {
        play(StatusConnectionActuatorStatus.Locked)
        status = StatusConnectionActuatorStatus.Degraded
        advance(MidFlightMillis)
        assertEquals(atRest, clock.value)
    }

    @Test
    fun `connecting loops until the status changes`() {
        play(StatusConnectionActuatorStatus.Engaging)
        advance(scene.durationMillis * 2L + MidFlightMillis)
        assertTrue(clock.isRunning)
        status = StatusConnectionActuatorStatus.Open
        advance(MidFlightMillis)
        assertEquals(atRest, clock.value)
    }

    @Test
    fun `a tap replays only a locked session`() {
        play(StatusConnectionActuatorStatus.Locked)
        memory.replayRequests += 1
        advance(MidFlightMillis)
        assertTrue(clock.value < scene.durationMillis)
        status = StatusConnectionActuatorStatus.Open
        advance(MidFlightMillis)
        memory.replayRequests += 1
        advance(MidFlightMillis)
        assertEquals(atRest, clock.value)
    }

    @Test
    fun `a strategy change while locked plays`() {
        play(StatusConnectionActuatorStatus.Locked)
        currentScene = otherScene
        advance(MidFlightMillis)
        assertTrue(clock.value < otherScene.durationMillis)
    }

    @Test
    fun `a scene that returns does not repeat an earlier tap`() {
        play(StatusConnectionActuatorStatus.Locked)
        memory.replayRequests += 1
        advance(otherScene.durationMillis + MidFlightMillis)
        sceneVisible = false
        advance(MidFlightMillis)
        sceneVisible = true
        advance(MidFlightMillis)
        assertEquals(atRest, clock.value)
    }

    @Test
    fun `a scene that returns with another strategy plays`() {
        play(StatusConnectionActuatorStatus.Locked)
        sceneVisible = false
        advance(MidFlightMillis)
        currentScene = otherScene
        sceneVisible = true
        advance(MidFlightMillis)
        assertTrue(clock.value < otherScene.durationMillis)
    }

    @Test
    fun `reduced motion stays at rest`() {
        play(StatusConnectionActuatorStatus.Engaging, RipDpiMotion(reducedMotion = true))
        advance(MidFlightMillis)
        assertEquals(atRest, clock.value)
        status = StatusConnectionActuatorStatus.Locked
        advance(MidFlightMillis)
        assertEquals(atRest, clock.value)
    }

    @Test
    fun `disabled animations stay at rest`() {
        play(StatusConnectionActuatorStatus.Open, RipDpiMotion(animationsEnabled = false))
        status = StatusConnectionActuatorStatus.Locked
        advance(MidFlightMillis)
        assertEquals(atRest, clock.value)
    }

    @Test
    fun `connecting does not stall a test that waits for idle`() {
        status = StatusConnectionActuatorStatus.Engaging
        composeRule.setContent { clock = rememberPassagePlayback(scene, status, memory, RipDpiMotion()) }
        composeRule.waitForIdle()
    }

    private companion object {
        const val FrameMillis = 16L
        const val MidFlightMillis = 300L
    }
}
