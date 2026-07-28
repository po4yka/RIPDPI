package com.poyka.ripdpi

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Test

class RipDpiAppDeviceEvidenceTest {
    @Test
    fun `records actual trim callback before trimming background caches`() {
        val calls = mutableListOf<String>()

        handleTrimMemoryCallback(
            level = ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            recordEvidence = { calls += "evidence:$it" },
            trimBackgroundCaches = { calls += "trim" },
        )

        assertEquals(listOf("evidence:${ComponentCallbacks2.TRIM_MEMORY_BACKGROUND}", "trim"), calls)
    }

    @Test
    fun `records unsupported callback without trimming caches`() {
        val calls = mutableListOf<String>()

        handleTrimMemoryCallback(
            level = Int.MAX_VALUE,
            recordEvidence = { calls += "evidence" },
            trimBackgroundCaches = { calls += "trim" },
        )

        assertEquals(listOf("evidence"), calls)
    }
}
