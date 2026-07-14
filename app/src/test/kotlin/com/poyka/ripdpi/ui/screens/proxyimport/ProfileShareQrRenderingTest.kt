package com.poyka.ripdpi.ui.screens.proxyimport

import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileShareQrRenderingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `QR rendering runs outside composition on the worker dispatcher`() {
        val compositionThread = AtomicReference<Thread>()
        val renderingThread = AtomicReference<Thread>()
        val rendered = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        executor.asCoroutineDispatcher().use { dispatcher ->
            composeRule.setContent {
                compositionThread.compareAndSet(null, Thread.currentThread())
                rememberShareQrBitmap(
                    content = "vless://uuid@example.com:443",
                    dispatcher = dispatcher,
                    renderer = {
                        renderingThread.set(Thread.currentThread())
                        rendered.countDown()
                        null
                    },
                )
            }

            assertTrue(rendered.await(5, TimeUnit.SECONDS))
            assertNotSame(compositionThread.get(), renderingThread.get())
        }
    }
}
