package com.poyka.ripdpi.ui.components

import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.test.platform.app.InstrumentationRegistry
import org.robolectric.shadows.ShadowWindowManagerGlobal

/** Compose 1.12 test rules start in touch mode; keyboard tests must request keyboard mode. */
@Composable
internal fun EnableKeyboardInput() {
    val inputModeManager = LocalInputModeManager.current
    val view = LocalView.current
    SideEffect {
        // Set the platform mode before a popup creates another Compose owner.
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        check(!ShadowWindowManagerGlobal.getInTouchMode()) { "The platform must be in keyboard mode" }
        inputModeManager.requestInputMode(InputMode.Keyboard)
        // Robolectric does not deliver the platform touch-mode notification after requestFocusFromTouch.
        // Deliver that Android event to the real Compose owner; keep its actual input mode manager.
        (view as ViewTreeObserver.OnTouchModeChangeListener).onTouchModeChanged(false)
        check(inputModeManager.inputMode == InputMode.Keyboard) { "Keyboard input mode is required for this test" }
    }
}
