package com.poyka.ripdpi.integration

import android.os.Build
import android.view.HapticFeedbackConstants
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.poyka.ripdpi.ui.components.LifecycleEventEffect
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.rememberRipDpiHapticPerformer
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.components.ripDpiSelectable
import com.poyka.ripdpi.ui.components.ripDpiToggleable
import com.poyka.ripdpi.ui.screens.settings.HapticFeedbackPreference
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Counts platform View calls. It does not certify the physical actuator or change Android settings. */
@RunWith(AndroidJUnit4::class)
class HapticFeedbackInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var previousStaticMotion: String? = null

    @Before
    fun enableFeedbackForThisTest() {
        previousStaticMotion = System.getProperty("ripdpi.staticMotion")
        System.setProperty("ripdpi.staticMotion", "false")
    }

    @After
    fun restoreMotionConfiguration() {
        val previous = previousStaticMotion
        if (previous == null) {
            System.clearProperty("ripdpi.staticMotion")
        } else {
            System.setProperty("ripdpi.staticMotion", previous)
        }
    }

    @Test
    fun feedbackUsesPlatformConstantsWithoutOverrideFlags() {
        val view = RecordingHapticView()
        lateinit var perform: (RipDpiHapticFeedback) -> Unit
        compose.setContent {
            CompositionLocalProvider(LocalView provides view) {
                RipDpiTheme { perform = rememberRipDpiHapticPerformer() }
            }
        }
        compose.runOnIdle {
            RipDpiHapticFeedback.entries.forEach(perform)
            val confirmation =
                if (Build.VERSION.SDK_INT >=
                    30
                ) {
                    HapticFeedbackConstants.CONFIRM
                } else {
                    HapticFeedbackConstants.VIRTUAL_KEY
                }
            val rejection =
                if (Build.VERSION.SDK_INT >=
                    30
                ) {
                    HapticFeedbackConstants.REJECT
                } else {
                    HapticFeedbackConstants.LONG_PRESS
                }
            assertEquals(
                listOf(
                    HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.CLOCK_TICK,
                    HapticFeedbackConstants.KEYBOARD_TAP,
                    confirmation,
                    confirmation,
                    rejection,
                    HapticFeedbackConstants.CONTEXT_CLICK,
                ),
                view.calls,
            )
            assertEquals(emptyList<Pair<Int, Int>>(), view.flaggedCalls)
        }
    }

    @Test
    fun offSuppressesEveryFeedbackAndLiveChangesTakeEffect() {
        val view = RecordingHapticView()
        var disabled by mutableStateOf(true)
        lateinit var perform: (RipDpiHapticFeedback) -> Unit
        compose.setContent {
            CompositionLocalProvider(LocalView provides view) {
                RipDpiTheme(hapticFeedbackDisabled = disabled) { perform = rememberRipDpiHapticPerformer() }
            }
        }
        compose.runOnIdle {
            RipDpiHapticFeedback.entries.forEach(perform)
            assertTrue(view.calls.isEmpty())
            disabled = false
        }
        compose.runOnIdle {
            perform(RipDpiHapticFeedback.Success)
            assertEquals(1, view.calls.size)
            disabled = true
        }
        compose.runOnIdle {
            perform(RipDpiHapticFeedback.Error)
            assertEquals(1, view.calls.size)
        }
    }

    @Test
    fun offSuppressesClickSelectionAndToggleWhileActionsStillWork() {
        val view = RecordingHapticView()
        var disabled by mutableStateOf(true)
        var actions = 0
        compose.setContent {
            CompositionLocalProvider(LocalView provides view) {
                RipDpiTheme(hapticFeedbackDisabled = disabled) {
                    Column {
                        Text("Click", Modifier.testTag("haptic-click").ripDpiClickable { actions++ })
                        Text(
                            "Select",
                            Modifier.testTag("haptic-select").ripDpiSelectable(selected = false) { actions++ },
                        )
                        Text("Toggle", Modifier.testTag("haptic-toggle").ripDpiToggleable(value = false) { actions++ })
                    }
                }
            }
        }
        listOf("haptic-click", "haptic-select", "haptic-toggle").forEach { compose.onNodeWithTag(it).performClick() }
        compose.runOnIdle {
            assertEquals(3, actions)
            assertTrue(view.calls.isEmpty())
            disabled = false
        }
        listOf("haptic-click", "haptic-select", "haptic-toggle").forEach { compose.onNodeWithTag(it).performClick() }
        compose.runOnIdle {
            assertEquals(6, actions)
            assertEquals(
                listOf(
                    HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.CLOCK_TICK,
                    HapticFeedbackConstants.KEYBOARD_TAP,
                ),
                view.calls,
            )
        }
    }

    @Test
    fun equalResultsConsumeOnceAcrossStoppedLifecycleAndHostReplacement() {
        val view = RecordingHapticView()
        val pending = Channel<RipDpiHapticFeedback>(Channel.BUFFERED)
        val events = pending.receiveAsFlow()
        lateinit var owner: HapticLifecycleOwner
        var mounted by mutableStateOf(true)
        val consumed = AtomicInteger()
        compose.runOnUiThread { owner = HapticLifecycleOwner(Lifecycle.State.CREATED) }
        compose.setContent {
            if (mounted) {
                CompositionLocalProvider(LocalView provides view, LocalLifecycleOwner provides owner) {
                    RipDpiTheme {
                        val perform = rememberRipDpiHapticPerformer()
                        LifecycleEventEffect(events) {
                            consumed.incrementAndGet()
                            perform(it)
                        }
                    }
                }
            }
        }
        compose.runOnIdle {
            assertTrue(pending.trySend(RipDpiHapticFeedback.Success).isSuccess)
            assertEquals(0, consumed.get())
            assertTrue(view.calls.isEmpty())
            owner.moveTo(Lifecycle.State.RESUMED)
        }
        compose.waitUntil { consumed.get() == 1 }
        compose.runOnIdle { mounted = false }
        compose.runOnIdle {
            owner.moveTo(Lifecycle.State.DESTROYED)
            owner = HapticLifecycleOwner(Lifecycle.State.RESUMED)
            mounted = true
        }
        compose.runOnIdle {
            assertEquals(1, consumed.get())
            assertEquals(1, view.calls.size)
            owner.moveTo(Lifecycle.State.CREATED)
            assertTrue(pending.trySend(RipDpiHapticFeedback.Success).isSuccess)
        }
        compose.runOnIdle {
            assertEquals(1, consumed.get())
            owner.moveTo(Lifecycle.State.RESUMED)
        }
        compose.waitUntil { consumed.get() == 2 }
        compose.runOnIdle {
            owner.moveTo(Lifecycle.State.CREATED)
            owner.moveTo(Lifecycle.State.RESUMED)
        }
        compose.runOnIdle {
            assertEquals(2, consumed.get())
            assertEquals(2, view.calls.size)
            pending.close()
        }
    }

    @Test
    fun resultsConsumedWhileOffDoNotReplayAfterEnablingOrReturning() {
        val view = RecordingHapticView()
        val pending = Channel<RipDpiHapticFeedback>(Channel.BUFFERED)
        val events = pending.receiveAsFlow()
        var disabled by mutableStateOf(true)
        var mounted by mutableStateOf(true)
        val consumed = AtomicInteger()
        compose.setContent {
            if (mounted) {
                CompositionLocalProvider(LocalView provides view) {
                    RipDpiTheme(hapticFeedbackDisabled = disabled) {
                        val perform = rememberRipDpiHapticPerformer()
                        LifecycleEventEffect(events) {
                            consumed.incrementAndGet()
                            perform(it)
                        }
                    }
                }
            }
        }
        compose.runOnIdle { assertTrue(pending.trySend(RipDpiHapticFeedback.Success).isSuccess) }
        compose.waitUntil { consumed.get() == 1 }
        compose.runOnIdle {
            assertTrue(view.calls.isEmpty())
            disabled = false
        }
        compose.runOnIdle { mounted = false }
        compose.runOnIdle { mounted = true }
        compose.runOnIdle {
            assertEquals(1, consumed.get())
            assertTrue(view.calls.isEmpty())
            assertTrue(pending.trySend(RipDpiHapticFeedback.Error).isSuccess)
        }
        compose.waitUntil { consumed.get() == 2 }
        compose.runOnIdle {
            assertEquals(1, view.calls.size)
            pending.close()
        }
    }

    @Test
    fun consumedResultDoesNotReplayAfterActualActivityRecreation() {
        val view = RecordingHapticView()
        val pending = Channel<RipDpiHapticFeedback>(Channel.BUFFERED)
        val events = pending.receiveAsFlow()
        val consumed = AtomicInteger()
        compose.setContent { ResultHost(events, view, consumed) }
        compose.runOnIdle { assertTrue(pending.trySend(RipDpiHapticFeedback.Success).isSuccess) }
        compose.waitUntil { consumed.get() == 1 }
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent { ResultHost(events, view, consumed) }
        }
        compose.onNodeWithTag("haptic-retained-result").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, consumed.get())
            assertEquals(1, view.calls.size)
            assertTrue(pending.trySend(RipDpiHapticFeedback.Success).isSuccess)
        }
        compose.waitUntil { consumed.get() == 2 }
        compose.runOnIdle {
            assertEquals(2, view.calls.size)
            pending.close()
        }
    }

    @Test
    fun offSuppressesBuiltInTextSelectionFeedbackWithoutBlockingSelection() {
        val calls = mutableListOf<HapticFeedbackType>()
        val platform =
            object : HapticFeedback {
                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                    calls += hapticFeedbackType
                }
            }
        var disabled by mutableStateOf(false)
        var text by mutableStateOf("Selectable words sample")
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides platform) {
                RipDpiTheme(hapticFeedbackDisabled = disabled) {
                    Column(Modifier.padding(24.dp)) {
                        RipDpiTextField(value = text, onValueChange = { text = it })
                    }
                }
            }
        }
        val field = compose.onNode(hasSetTextAction())
        field.performTouchInput { longClick() }
        compose.runOnIdle {
            assertTrue(calls.isNotEmpty())
            disabled = true
            calls.clear()
        }
        field.performTextReplacement("Another selectable sample")
        field.performTouchInput { longClick() }
        compose.runOnIdle {
            assertTrue(calls.isEmpty())
        }
        val selection = field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertTrue(!selection.collapsed)
    }

    @Test
    fun preferenceChoicesRemainReachableWithLargeFontAndRtl() {
        var disabled by mutableStateOf(false)
        compose.setContent {
            RipDpiTheme(hapticFeedbackDisabled = disabled) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, 2f),
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                ) {
                    Column(Modifier.padding(16.dp)) {
                        HapticFeedbackPreference(disabled = disabled, onDisabledChange = { disabled = it })
                    }
                }
            }
        }
        val tag = "settings-haptic-feedback"
        compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
        compose.onNodeWithTag(RipDpiTestTags.dropdownOption(tag, "off")).assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(disabled) }
        capture("haptic-off-font2-rtl")
        compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
        compose.onNodeWithTag(RipDpiTestTags.dropdownOption(tag, "system")).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(false, disabled) }
        capture("haptic-system-font2-rtl")
    }

    @Composable
    private fun ResultHost(
        events: Flow<RipDpiHapticFeedback>,
        view: RecordingHapticView,
        consumed: AtomicInteger,
    ) {
        CompositionLocalProvider(LocalView provides view) {
            RipDpiTheme {
                val perform = rememberRipDpiHapticPerformer()
                LifecycleEventEffect(events) {
                    consumed.incrementAndGet()
                    perform(it)
                }
                Text("Completed", Modifier.testTag("haptic-retained-result"))
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "haptic-feedback-evidence")
        check(directory.mkdirs() || directory.isDirectory)
        assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(directory, "$name.png")))
    }
}

// Platform ripple needs a ViewGroup for its container; keep the real ripple path active.
private class RecordingHapticView : FrameLayout(InstrumentationRegistry.getInstrumentation().targetContext) {
    val calls = mutableListOf<Int>()
    val flaggedCalls = mutableListOf<Pair<Int, Int>>()

    override fun performHapticFeedback(feedbackConstant: Int): Boolean {
        calls += feedbackConstant
        return true
    }

    override fun performHapticFeedback(
        feedbackConstant: Int,
        flags: Int,
    ): Boolean {
        flaggedCalls += feedbackConstant to flags
        return true
    }
}

private class HapticLifecycleOwner(
    initialState: Lifecycle.State,
) : LifecycleOwner {
    private val registry = LifecycleRegistry(this).apply { currentState = initialState }
    override val lifecycle: Lifecycle = registry

    fun moveTo(state: Lifecycle.State) {
        registry.currentState = state
    }
}
