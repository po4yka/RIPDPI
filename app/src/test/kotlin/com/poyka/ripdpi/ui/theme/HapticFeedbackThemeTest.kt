package com.poyka.ripdpi.ui.theme

import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HapticFeedbackThemeTest {
    @get:Rule val compose = createComposeRule()
    private var previousStaticMotion: String? = null

    @Before
    fun enableMotion() {
        previousStaticMotion = System.getProperty("ripdpi.staticMotion")
        System.setProperty("ripdpi.staticMotion", "false")
    }

    @After
    fun restoreMotion() {
        previousStaticMotion?.let { System.setProperty("ripdpi.staticMotion", it) }
            ?: System.clearProperty("ripdpi.staticMotion")
    }

    @Test
    fun `off suppresses every feedback family and live changes apply`() {
        val calls = mutableListOf<Int>()
        val view = recordingView(calls)
        val disabled = mutableStateOf(true)
        lateinit var perform: (RipDpiHapticFeedback) -> Unit
        compose.setContent {
            RipDpiTheme(hapticFeedbackDisabled = disabled.value) {
                CompositionLocalProvider(LocalView provides view) {
                    perform = rememberRipDpiHapticPerformer()
                }
            }
        }
        compose.runOnIdle {
            RipDpiHapticFeedback.entries.forEach(perform)
            assertEquals(emptyList<Int>(), calls)
            disabled.value = false
        }
        compose.runOnIdle {
            perform(RipDpiHapticFeedback.Success)
            assertEquals(1, calls.size)
            disabled.value = true
        }
        compose.runOnIdle {
            perform(RipDpiHapticFeedback.Error)
            assertEquals(1, calls.size)
        }
    }

    @Test
    fun `system mode does not reenable feedback in static previews`() {
        System.setProperty("ripdpi.staticMotion", "true")
        val calls = mutableListOf<Int>()
        val view = recordingView(calls)
        lateinit var perform: (RipDpiHapticFeedback) -> Unit
        compose.setContent {
            RipDpiTheme(hapticFeedbackDisabled = false) {
                CompositionLocalProvider(LocalView provides view) {
                    perform = rememberRipDpiHapticPerformer()
                }
            }
        }
        compose.runOnIdle {
            RipDpiHapticFeedback.entries.forEach(perform)
            assertEquals(emptyList<Int>(), calls)
        }
    }

    @Test
    fun `off suppresses built in Compose feedback and system mode forwards it`() {
        val calls = mutableListOf<HapticFeedbackType>()
        val platform =
            object : HapticFeedback {
                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                    calls += hapticFeedbackType
                }
            }
        val disabled = mutableStateOf(true)
        val types = listOf(HapticFeedbackType.LongPress, HapticFeedbackType.TextHandleMove)
        lateinit var feedback: HapticFeedback
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides platform) {
                RipDpiTheme(hapticFeedbackDisabled = disabled.value) {
                    feedback = LocalHapticFeedback.current
                }
            }
        }
        compose.runOnIdle {
            types.forEach(feedback::performHapticFeedback)
            assertEquals(emptyList<HapticFeedbackType>(), calls)
            disabled.value = false
        }
        compose.runOnIdle {
            types.forEach(feedback::performHapticFeedback)
            assertEquals(types, calls)
            disabled.value = true
        }
        compose.runOnIdle {
            types.forEach(feedback::performHapticFeedback)
            assertEquals(types, calls)
        }
    }

    private fun recordingView(calls: MutableList<Int>): View =
        object : View(RuntimeEnvironment.getApplication()) {
            override fun performHapticFeedback(feedbackConstant: Int): Boolean {
                calls += feedbackConstant
                return true
            }
        }
}
