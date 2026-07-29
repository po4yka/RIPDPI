package com.poyka.ripdpi.diagnostics

import android.app.ActivityManager
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class AndroidDeviceStateProviderTest {
    @Test
    fun `process memory state publishes importance and trim from one successful read`() {
        val state =
            captureProcessMemoryState { processInfo ->
                processInfo.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
                processInfo.lastTrimLevel = TestTrimLevel
            }

        assertEquals(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE, state.importance)
        assertEquals(TestTrimLevel, state.lastTrimLevel)
    }

    @Test
    fun `failed process memory read publishes both values as unknown`() {
        val state =
            captureProcessMemoryState { processInfo ->
                processInfo.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                processInfo.lastTrimLevel = TestTrimLevel
                error("platform memory-state read failed")
            }

        assertNull(state.importance)
        assertNull(state.lastTrimLevel)
    }

    private companion object {
        const val TestTrimLevel = 42
    }
}
