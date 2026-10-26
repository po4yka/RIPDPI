package com.poyka.ripdpi.services

import android.app.Application
import android.content.Intent
import android.os.Looper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreenStateObserverTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun `value follows screen broadcasts without any collector`() =
        runTest(UnconfinedTestDispatcher()) {
            val observer = DefaultScreenStateObserver(context, backgroundScope)
            assertTrue(observer.isInteractive.value)

            context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(observer.isInteractive.value)

            context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(observer.isInteractive.value)
        }
}
