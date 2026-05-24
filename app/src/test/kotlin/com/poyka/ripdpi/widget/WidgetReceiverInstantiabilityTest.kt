package com.poyka.ripdpi.widget

import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Validates the `tools:ignore="Instantiatable"` suppression on the four
 * Glance widget receivers in `AndroidManifest.xml`.
 *
 * Android lint's Instantiatable check fires false-positive on
 * GlanceAppWidgetReceiver subclasses because it can't reason through the
 * Kotlin override of `glanceAppWidget`. The receivers DO have a public no-arg
 * constructor and Android's AppWidgetHost instantiates them fine at runtime.
 *
 * This test exercises that runtime instantiation path directly via reflection.
 * If the test passes, the lint suppression is provably a false positive.
 * If a future refactor adds a non-default constructor, makes a receiver
 * abstract, or otherwise breaks instantiability, this test fails BEFORE the
 * manifest suppression masks a real bug at install time.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetReceiverInstantiabilityTest {
    @Test
    fun connectToggleReceiverInstantiable() {
        assertNotNull(
            ConnectToggleWidgetReceiver::class.java
                .getDeclaredConstructor()
                .newInstance(),
        )
    }

    @Test
    fun statusDisplayReceiverInstantiable() {
        assertNotNull(
            StatusDisplayWidgetReceiver::class.java
                .getDeclaredConstructor()
                .newInstance(),
        )
    }

    @Test
    fun telemetryReceiverInstantiable() {
        assertNotNull(
            TelemetryWidgetReceiver::class.java
                .getDeclaredConstructor()
                .newInstance(),
        )
    }

    @Test
    fun modePickerReceiverInstantiable() {
        assertNotNull(
            ModePickerWidgetReceiver::class.java
                .getDeclaredConstructor()
                .newInstance(),
        )
    }
}
